package com.partner.diary;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.telecom.TelecomManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Watches which app is in front. While a lockdown is running it covers everything except calls
 * with a full-screen overlay, closes the power menu and keeps the lock after a restart.
 */
public class LockdownService extends AccessibilityService {

  static LockdownService inst;

  private SharedPreferences sp;
  private final Handler h = new Handler(Looper.getMainLooper());
  private WindowManager wm;
  private LinearLayout overlay;
  private TextView tTime, tMsg;
  private Button bExit;
  private boolean shown = false;
  // 2-minute warning card with OK, and the small timer over an app that has a limit
  private LinearLayout warnView;
  private TextView wTitle, wMsg, timerView;
  private Button wClose;
  private boolean warnShown = false, timerShown = false;
  private long warnAt = 0;
  private final Set<String> warned = new HashSet<>();
  private String curPkg = "";
  private boolean fgAllowed = false;
  private long lastTick = 0;
  private final Map<String, Long> used = new HashMap<>();
  private final Map<String, Long> seen = new HashMap<>();
  private final Set<String> allow = new HashSet<>();
  private final Set<String> ignore = new HashSet<>();
  // focus nudges: front app (the app's own window counts, system windows do not), home screen app, time spent on apps that are not allowed
  private String nzPkg = "";
  private String launcherPkg = "";
  private long nzOff = 0, nzAway = 0, nzLast = 0, shadeAt = 0, usedSave = 0, lsAt = 0;
  // the late-night call rule is running (see NightRule)
  private boolean nightRun = false;
  // app detox: an app he blocked for hours or days is sent back to the home screen (see Detox)
  private boolean dxRun = false;
  private long dxHome = 0, dxToast = 0;
  // wake-up alarm: while it rings (or the evening guard runs) the mission is kept in front and the pages that could remove it are closed (see WakeAlarm)
  private boolean wkRun = false;
  private long wkOpen = 0;
  // adult-content block and accountability messages (see Guard)
  private boolean gdScan = false, gdApps = false;
  private long gdScanAt = 0, gdHitAt = 0, gdToast = 0, gdDnsAt = 0, waTry = 0;
  private String waCur = "";

  private final Runnable tick = new Runnable() {
    @Override
    public void run() {
      try {
        onTick();
      } catch (Exception e) {
        // keep ticking
      }
      h.postDelayed(this, (shown || timerShown || warnShown) ? 1000 : ((nightRun || dxRun || wkRun) ? 2000 : 5000));
    }
  };

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    inst = this;
    sp = LockdownPlugin.sp(this);
    wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    buildSets();
    loadUsed();
    loadWarned();
    lastTick = System.currentTimeMillis();
    h.removeCallbacks(tick);
    h.post(tick);
  }

  @Override
  public boolean onUnbind(Intent intent) {
    inst = null;
    h.removeCallbacks(tick);
    hideOverlay();
    hideTimer();
    hideWarn();
    return super.onUnbind(intent);
  }

  @Override
  public void onInterrupt() {}

  public void poke() {
    h.post(() -> {
      try {
        buildSets();
        onTick();
      } catch (Exception e) {
        // ignore
      }
    });
  }

  private void buildSets() {
    allow.clear();
    ignore.clear();
    allow.addAll(Arrays.asList(
      "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
      "com.samsung.android.incallui", "com.android.incallui", "com.android.phone",
      "com.android.server.telecom", "com.android.emergency",
      "com.android.contacts", "com.google.android.contacts",
      // the Clock app: an alarm or timer that rings during a lock has to be stoppable
      "com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock"
    ));
    try {
      TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
      String d = tm == null ? null : tm.getDefaultDialerPackage();
      if (d != null) allow.add(d);
    } catch (Exception e) {
      // ignore
    }
    ignore.addAll(Arrays.asList(getPackageName(), "com.android.systemui", "android"));
    try {
      InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
      for (InputMethodInfo i : im.getEnabledInputMethodList()) ignore.add(i.getPackageName());
    } catch (Exception e) {
      // ignore
    }
    try {
      Intent hi = new Intent(Intent.ACTION_MAIN);
      hi.addCategory(Intent.CATEGORY_HOME);
      ResolveInfo ri = getPackageManager().resolveActivity(hi, PackageManager.MATCH_DEFAULT_ONLY);
      if (ri != null && ri.activityInfo != null) launcherPkg = ri.activityInfo.packageName;
    } catch (Exception e) {
      // ignore
    }
  }

  private JSONObject cfg() {
    try {
      String s = sp.getString("cfg", "");
      return s.isEmpty() ? null : new JSONObject(s);
    } catch (Exception e) {
      return null;
    }
  }

  private boolean isLocked() {
    return sp.getLong("until", 0) > System.currentTimeMillis();
  }

  private boolean protect() {
    JSONObject c = cfg();
    return c != null && c.optBoolean("protect", true);
  }

  private boolean exitOn() {
    // the night rule has no exit until its end time: that was his own choice
    if (sp.getLong("nightUntil", 0) > System.currentTimeMillis()) return false;
    JSONObject c = cfg();
    return c == null || c.optBoolean("exit", true);
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent e) {
    if (e == null || e.getPackageName() == null || sp == null) return;
    int type = e.getEventType();
    String p = e.getPackageName().toString();
    if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      // typing and content changes: only windows that something is watching, and only when something needs them
      if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) guardEvent(e, p, type);
      return;
    }
    String c = e.getClassName() == null ? "" : e.getClassName().toString().toLowerCase(Locale.ROOT);
    if (!ignore.contains(p) || p.equals(getPackageName())) nzPkg = p;
    if ((c.contains("globalactions") || c.contains("powermenu") || c.contains("shutdown") || c.contains("reboot"))
      && ((isLocked() && protect()) || WakeAlarm.guard(this, System.currentTimeMillis()))) {
      performGlobalAction(GLOBAL_ACTION_BACK);
      return;
    }
    // during a lockdown the notification shade stays shut: notifications (even inline replies) are a way around the lock
    if ((isLocked() || WakeAlarm.ringing(this)) && "com.android.systemui".equals(p) && isShade(c, e)) {
      closeShade();
      return;
    }
    // smart banking mode: a listed bank or payment app opened, so pause this service for a while
    if (!isLocked() && !WakeAlarm.ringing(this) && sp.getBoolean("smartbank", false) && isBankPkg(p)) {
      if (LockdownAcc.startBank(this, sp.getInt("bankmin", 10))) return;
    }
    // app detox: a blocked app (or a Settings page that could switch the detox off) goes straight back to the home screen
    if (!ignore.contains(p) && !p.equals(getPackageName()) && detoxHit(p, c, textOf(e))) {
      curPkg = p;
      fgAllowed = false;
      return;
    }
    if (ignore.contains(p)) return;
    curPkg = p;
    fgAllowed = allow.contains(p) || (!waCur.isEmpty() && Guard.isWa(p));
    if (gdScan && Guard.watchPkg(p)) guardEvent(e, p, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    if (isLocked()) {
      try {
        onTick();
      } catch (Exception ex) {
        // ignore
      }
    }
  }

  private boolean isBankPkg(String pkg) {
    String list = sp.getString("bankpkgs", "");
    if (list == null || list.isEmpty()) return false;
    for (String x : list.split("[,\\s]+")) {
      if (!x.isEmpty() && x.equals(pkg)) return true;
    }
    return false;
  }

  private void applyPending(long now) {
    long pa = sp.getLong("pendAt", 0);
    String pend = sp.getString("pend", "");
    if (pa > 0 && now >= pa && pend != null && pend.length() > 2) {
      sp.edit().putString("cfg", pend).putString("pend", "").putLong("pendAt", 0).apply();
    }
  }

  /**
   * Scheduled lockdown. While a schedule window is running, the phone stays locked until the window ends,
   * and it unlocks by itself afterwards. Needs lockdown mode on in the config (cfg.on) and this service running.
   */
  private void schedTick(long now) {
    JSONObject c = cfg();
    if (c == null || !c.optBoolean("on", false)) return;
    JSONArray sc = c.optJSONArray("sched");
    if (sc == null || sc.length() == 0) return;
    long best = 0;
    for (int i = 0; i < sc.length(); i++) {
      JSONObject s = sc.optJSONObject(i);
      if (s == null || !s.optBoolean("on", true)) continue;
      long end = windowEnd(s, now);
      if (end > best) best = end;
    }
    if (best > now && best > sp.getLong("schedSkip", 0) && sp.getLong("until", 0) < best) {
      sp
        .edit()
        .putLong("until", best)
        .putLong("schedEnd", best)
        .putString("reason", "Scheduled lockdown")
        .putLong("exitAt", 0)
        .apply();
    }
  }

  /**
   * The late-night call rule. While its window runs the phone is locked until the window ends (no emergency exit), a normal
   * call with somebody who is not on his list is ended, and WhatsApp calls are hung up by the notification listener.
   */
  private void nightTick(long now) {
    NightRule.applyPending(this, now);
    long end = NightRule.activeEnd(this, now);
    nightRun = end > 0;
    if (end > 0) {
      long until = sp.getLong("until", 0);
      SharedPreferences.Editor ed = sp.edit().putLong("nightUntil", end).putLong("exitAt", 0);
      if (until < end) ed.putLong("until", end).putString("reason", "Night rule");
      ed.apply();
      nightCalls(now);
      PartnerNotifListener.sweep();
    } else if (sp.getLong("nightUntil", 0) != 0) {
      sp.edit().putLong("nightUntil", 0).apply();
    }
  }

  /**
   * Ends a running normal call when it is with somebody he did not allow. It only ends a call the call screener has seen
   * and marked as not allowed. A call nobody wrote down (an emergency call, or the call screening app is not set) is never touched.
   */
  private void nightCalls(long now) {
    if (Build.VERSION.SDK_INT < 28) return;
    try {
      TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
      if (tm == null) return;
      boolean inCall;
      try {
        inCall = tm.isInCall();
      } catch (Exception e) {
        return; // the phone permission is off
      }
      if (!inCall) {
        if (sp.contains("curCallTs")) sp.edit().remove("curCallOk").remove("curCallTs").apply();
        return;
      }
      long ts = sp.getLong("curCallTs", 0);
      if (ts == 0 || now - ts > 6 * 3600000L || sp.getBoolean("curCallOk", false)) return;
      if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return;
      if (tm.endCall()) sp.edit().putLong("nightLastCut", now).apply();
    } catch (Exception e) {
      // try again on the next tick
    }
  }

  /** End time of the schedule window that is running right now, or 0 when none is. Windows can run past midnight. */
  private long windowEnd(JSONObject s, long now) {
    JSONArray days = s.optJSONArray("days");
    int f = hm(s.optString("from", ""), -1);
    int t = hm(s.optString("to", ""), -1);
    if (days == null || f < 0 || t < 0 || f == t) return 0;
    int len = t > f ? t - f : t + 1440 - f;
    for (int back = 0; back <= 1; back++) {
      Calendar cal = Calendar.getInstance();
      cal.setTimeInMillis(now);
      cal.add(Calendar.DAY_OF_YEAR, -back);
      cal.set(Calendar.HOUR_OF_DAY, f / 60);
      cal.set(Calendar.MINUTE, f % 60);
      cal.set(Calendar.SECOND, 0);
      cal.set(Calendar.MILLISECOND, 0);
      long start = cal.getTimeInMillis();
      long end = start + len * 60000L;
      int dow = cal.get(Calendar.DAY_OF_WEEK) - 1; // 0 = Sunday, like JavaScript
      boolean on = false;
      for (int i = 0; i < days.length(); i++) {
        if (days.optInt(i, -1) == dow) on = true;
      }
      if (on && now >= start && now < end) return end;
    }
    return 0;
  }

  private void onTick() {
    long now = System.currentTimeMillis();
    applyPending(now);
    // a planned break (set in advance): every lock, the night rule, app limits and nudges are off until it ends
    if (NightRule.breakNow(this, now)) {
      if (sp.getLong("until", 0) != 0 || sp.getLong("nightUntil", 0) != 0 || sp.getLong("exitAt", 0) != 0) {
        sp.edit().putLong("until", 0).putLong("nightUntil", 0).putLong("exitAt", 0).apply();
      }
      nightRun = false;
      wkRun = false;
      hideOverlay();
      hideTimer();
      hideWarn();
      detoxTick(now); // a break never pauses a detox: that would be a way around it
      lastTick = now;
      return;
    }
    Guard.applyPending(this, now);
    gdScan = Guard.scanOn(this);
    schedTick(now);
    nightTick(now);
    long until = sp.getLong("until", 0);
    long exitAt = sp.getLong("exitAt", 0);
    if (until > 0 && (now >= until || (exitAt > 0 && now >= exitAt))) {
      SharedPreferences.Editor ed = sp.edit().putLong("until", 0).putLong("exitAt", 0);
      // left early through the emergency exit: do not lock again for the rest of this scheduled window
      if (exitAt > 0 && now >= exitAt && now < until && sp.getLong("schedEnd", 0) == until) {
        ed.putLong("schedSkip", until);
      }
      ed.apply();
      until = 0;
      exitAt = 0;
    }
    boolean ringNow = WakeAlarm.ringing(this);
    if (until > now) {
      // notifications cannot be tapped during a lock: they are snoozed until it ends (a tap is a way into another app)
      if (now - lsAt > 1500L) {
        lsAt = now;
        PartnerNotifListener.lockSweep();
      }
      // while the wake-up alarm rings the night lock steps aside, so the mission can be done; everything else stays out
      if (!fgAllowed && !ringNow) showOverlay(now, until, exitAt);
      else hideOverlay();
      hideTimer();
      hideWarn();
    } else {
      hideOverlay();
      trackUsage(now);
      limitTick(now);
    }
    nudgeTick(now);
    detoxTick(now);
    wakeTick(now);
    guardTick(now);
    lastTick = now;
  }

  /**
   * Wake-up alarm. While it rings, anything but the mission (and calls) is sent back to the mission. Outside the ring the
   * service only remembers that the evening guard is running, so it ticks every 2 seconds and closes the removal pages.
   */
  private void wakeTick(long now) {
    WakeAlarm.applyPending(this, now);
    boolean ring = WakeAlarm.ringing(this);
    wkRun = ring || WakeAlarm.guard(this, now);
    if (!ring) return;
    if (nzPkg.equals(getPackageName()) || allow.contains(nzPkg)) return;
    if (now - wkOpen < 2500L) return;
    wkOpen = now;
    WakeAlarm.openMission(this);
  }

  // ---- adult-content block, accountability messages ----

  /** The app that came to the front most recently, from the phone's usage log (null when nothing changed in the last seconds). */
  private String usageFg(long now) {
    try {
      UsageStatsManager um = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
      if (um == null) return null;
      UsageEvents ev = um.queryEvents(now - 20000L, now + 1000L);
      UsageEvents.Event x = new UsageEvents.Event();
      String last = null;
      while (ev != null && ev.hasNextEvent()) {
        ev.getNextEvent(x);
        if (x.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) last = x.getPackageName();
      }
      return last;
    } catch (Exception e) {
      return null;
    }
  }

  private void guardTick(long now) {
    try {
      // time on the apps he picked, for the "long on the phone" message
      PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
      if (pm == null || pm.isInteractive()) Guard.usageTick(this, curPkg, lastTick == 0 ? 0 : now - lastTick, now);
      dnsTick(now);
      waPump(now);
    } catch (Exception e) {
      // next tick
    }
  }

  /** Keeps the filter DNS in place. When it cannot be set by itself and stays off for 10 minutes, he and his people are told. */
  private void dnsTick(long now) {
    if (now - gdDnsAt < 20000L) return;
    gdDnsAt = now;
    if (!Guard.keepOn(this)) {
      sp.edit().remove("gdDnsOffAt").remove("gdDnsNote").apply();
      return;
    }
    if (Guard.dnsOk(this) || Guard.dnsApply(this)) {
      if (sp.getLong("gdDnsOffAt", 0) != 0) sp.edit().remove("gdDnsOffAt").remove("gdDnsNote").apply();
      return;
    }
    long off = sp.getLong("gdDnsOffAt", 0);
    if (off == 0) {
      sp.edit().putLong("gdDnsOffAt", now).apply();
    } else if (now - off > 10 * 60000L && !sp.getBoolean("gdDnsNote", false)) {
      sp.edit().putBoolean("gdDnsNote", true).apply();
      Guard.note(this, "Adult filter is off", "Private DNS is not the filter any more. Open Partner, Settings, Adult content block and switch it back on.");
      Guard.fire(this, "filter", "");
    }
  }

  /** Typing or content in a watched window (a browser, a search box). */
  private void guardEvent(AccessibilityEvent e, String p, int type) {
    long now = System.currentTimeMillis();
    if (!waCur.isEmpty() && Guard.isWa(p)) {
      if (now - waTry > 350L) {
        waTry = now;
        waTryClick();
      }
      return;
    }
    if (!gdScan || !Guard.watchPkg(p)) return;
    if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
      if (e.isPassword() || e.getText() == null) return;
      StringBuilder b = new StringBuilder();
      for (CharSequence c : e.getText()) if (c != null) b.append(c).append(' ');
      if (AdultMatch.adult(b.toString())) adultHit(p);
      return;
    }
    if (now - gdScanAt < 700L) return;
    gdScanAt = now;
    AccessibilityNodeInfo root = null;
    try {
      root = getRootInActiveWindow();
      if (root == null || root.getPackageName() == null || !p.equals(root.getPackageName().toString())) return;
      if (scanNode(root, 0, new int[] { 260 })) adultHit(p);
    } catch (Exception ex) {
      // next event
    } finally {
      try {
        if (root != null) root.recycle();
      } catch (Exception ex) {
        // ignore
      }
    }
  }

  /** Looks at address bars and search boxes only (never at page text or messages). */
  private boolean scanNode(AccessibilityNodeInfo n, int depth, int[] budget) {
    if (n == null || depth > 14 || budget[0]-- <= 0) return false;
    CharSequence cn = n.getClassName(), t = n.getText();
    String id = n.getViewIdResourceName();
    if (t != null && t.length() > 2 && !n.isPassword()) {
      boolean field = (cn != null && cn.toString().contains("EditText"))
        || (id != null && (id.contains("url") || id.contains("search") || id.contains("omnibox") || id.contains("location_bar") || id.contains("address") || id.contains("query")));
      if (field && AdultMatch.adult(t.toString())) return true;
    }
    for (int i = 0; i < n.getChildCount(); i++) {
      AccessibilityNodeInfo ch = n.getChild(i);
      if (ch == null) continue;
      boolean hit = scanNode(ch, depth + 1, budget);
      try {
        ch.recycle();
      } catch (Exception ex) {
        // ignore
      }
      if (hit) return true;
    }
    return false;
  }

  /** Something adult was opened or typed: leave it, say why, count it, and tell his people (no more than every 20 minutes). */
  private void adultHit(String p) {
    long now = System.currentTimeMillis();
    if (now - gdHitAt < 2500L) return;
    gdHitAt = now;
    try {
      performGlobalAction(GLOBAL_ACTION_HOME);
    } catch (Exception ex) {
      // ignore
    }
    if (now - gdToast > 3000L) {
      gdToast = now;
      try {
        Toast.makeText(this, "Blocked. Your adult-content block is on.", Toast.LENGTH_LONG).show();
      } catch (Exception ex) {
        // ignore
      }
    }
    Guard.recordHit(this, now);
    Guard.fire(this, "adult", "");
  }

  /** Sends the next WhatsApp message of the queue: opens the chat with the text filled in and taps Send. */
  private void waPump(long now) {
    waCur = sp.getString("waId", "");
    if (Guard.queueLen(this) == 0) {
      if (!waCur.isEmpty()) sp.edit().remove("waId").apply();
      return;
    }
    Guard.pump(this); // SMS ones and messages that waited too long
    if (!waCur.isEmpty()) {
      if (now - sp.getLong("waStart", 0) > 25000L) {
        Guard.failed(this, waCur);
        sp.edit().remove("waId").putLong("waEnd", now).apply();
        waCur = "";
      } else {
        waTryClick();
      }
      return;
    }
    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
    KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
    if ((pm != null && !pm.isInteractive()) || (km != null && km.isKeyguardLocked())) return; // waits until the phone is in use
    if (now - sp.getLong("waEnd", 0) < 5000L) return;
    org.json.JSONObject it = Guard.nextWa(this);
    if (it == null) return;
    String id = it.optString("id");
    Intent i = Guard.waIntent(it.optString("num"), it.optString("text"), "com.whatsapp");
    if (i == null) {
      Guard.failed(this, id);
      Guard.failed(this, id);
      return;
    }
    sp.edit().putString("waId", id).putLong("waStart", now).apply();
    waCur = id;
    try {
      startActivity(i);
    } catch (Exception e1) {
      try {
        i.setPackage("com.whatsapp.w4b");
        startActivity(i);
      } catch (Exception e2) {
        sp.edit().remove("waId").putLong("waEnd", now).apply();
        waCur = "";
        Guard.failed(this, id);
      }
    }
  }

  private void waTryClick() {
    if (waCur.isEmpty()) return;
    AccessibilityNodeInfo root = null;
    try {
      root = getRootInActiveWindow();
      if (root == null || root.getPackageName() == null || !Guard.isWa(root.getPackageName().toString())) return;
      AccessibilityNodeInfo b = findSend(root, 0, new int[] { 300 });
      if (b == null) return;
      AccessibilityNodeInfo c = b;
      int up = 0;
      while (c != null && !c.isClickable() && up++ < 3) c = c.getParent();
      if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        final String id = waCur;
        Guard.sent(this, id);
        sp.edit().remove("waId").putLong("waEnd", System.currentTimeMillis()).apply();
        waCur = "";
        // back to what he was doing
        h.postDelayed(() -> {
          try {
            performGlobalAction(GLOBAL_ACTION_BACK);
          } catch (Exception ex) {
            // ignore
          }
        }, 1300L);
        h.postDelayed(() -> {
          try {
            if (Guard.isWa(nzPkg) || Guard.isWa(curPkg)) performGlobalAction(GLOBAL_ACTION_BACK);
          } catch (Exception ex) {
            // ignore
          }
        }, 2600L);
      }
    } catch (Exception ex) {
      // the next tick tries again
    } finally {
      try {
        if (root != null) root.recycle();
      } catch (Exception ex) {
        // ignore
      }
    }
  }

  private AccessibilityNodeInfo findSend(AccessibilityNodeInfo n, int depth, int[] budget) {
    if (n == null || depth > 16 || budget[0]-- <= 0) return null;
    String id = n.getViewIdResourceName();
    CharSequence d = n.getContentDescription();
    if ((id != null && id.endsWith(":id/send")) || (d != null && (d.toString().equalsIgnoreCase("send") || d.toString().equals("भेजें")))) return n;
    for (int i = 0; i < n.getChildCount(); i++) {
      AccessibilityNodeInfo ch = n.getChild(i);
      if (ch == null) continue;
      AccessibilityNodeInfo r = findSend(ch, depth + 1, budget);
      if (r != null) return r;
    }
    return null;
  }

  private String textOf(AccessibilityEvent e) {
    try {
      return e.getText() == null ? "" : e.getText().toString();
    } catch (Exception ex) {
      return "";
    }
  }

  /**
   * App detox. When the app in front is blocked, press Home and say why. Returns true when it was blocked.
   * The same goes for the Settings pages that could switch the service off while a detox runs.
   */
  private boolean detoxHit(String p, String c, String t) {
    long now = System.currentTimeMillis();
    String msg = null;
    long until = Detox.until(this, p);
    if (until > now) msg = Detox.name(this, p) + " is blocked for " + Detox.left(until - now) + " more.";
    else if (Guard.appBlocked(this, p)) msg = Guard.appName(this, p) + " is blocked by your adult-content block.";
    else if (Guard.dnsPage(this, p, c, t)) msg = "Private DNS is locked while the adult-content block is on.";
    else if (Detox.settingsGuard(this, p, c, t)) msg = Detox.any(this) ? "This setting is closed until your detox ends." : "This is closed until your wake-up task is done.";
    if (msg == null) return false;
    if (now - dxHome > 300L) {
      dxHome = now;
      try {
        performGlobalAction(GLOBAL_ACTION_HOME);
      } catch (Exception ex) {
        // the tick tries again
      }
    }
    if (now - dxToast > 3000L) {
      dxToast = now;
      try {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
      } catch (Exception ex) {
        // ignore
      }
    }
    return true;
  }

  /** Backup for the event: if a blocked app is still in front (the home press was missed), press Home again. */
  private void detoxTick(long now) {
    gdApps = false;
    try {
      org.json.JSONObject ad = Guard.adult(this);
      gdApps = ad != null && ad.optBoolean("on", false) && ad.optJSONArray("apps") != null && ad.optJSONArray("apps").length() > 0;
    } catch (Exception e) {
      gdApps = false;
    }
    dxRun = Detox.any(this) || gdApps;
    if (!dxRun) return;
    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
    if (pm != null && !pm.isInteractive()) return;
    // accessibility events can be missed (an app resumed from Recents), so the phone's usage log says which app is in front too
    String fg = usageFg(now);
    if (fg != null && !ignore.contains(fg) && !fg.equals(getPackageName())) {
      curPkg = fg;
      fgAllowed = allow.contains(fg);
    }
    if (curPkg.isEmpty()) return;
    if (Detox.until(this, curPkg) > now || Guard.appBlocked(this, curPkg)) detoxHit(curPkg, "", "");
  }

  /** The limit day changes at 5 AM, so late-night use still counts for the day that is ending. */
  private int dayKey(long t) {
    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(t - 5L * 3600000L);
    return cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH);
  }

  private void loadUsed() {
    used.clear();
    try {
      if (sp.getInt("usedDay", 0) == dayKey(System.currentTimeMillis())) {
        JSONObject o = new JSONObject(sp.getString("usedJson", "{}"));
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
          String k = it.next();
          used.put(k, o.optLong(k, 0L));
        }
      }
    } catch (Exception e) {
      // start from zero
    }
  }

  private void saveUsed(long now) {
    try {
      JSONObject o = new JSONObject();
      for (Map.Entry<String, Long> en : used.entrySet()) o.put(en.getKey(), en.getValue().longValue());
      sp.edit().putInt("usedDay", dayKey(now)).putString("usedJson", o.toString()).apply();
      usedSave = now;
    } catch (Exception e) {
      // ignore
    }
  }

  private void trackUsage(long now) {
    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
    if (pm != null && !pm.isInteractive()) return;
    long dt = lastTick == 0 ? 0 : Math.min(now - lastTick, 10000L);
    JSONObject c = cfg();
    if (c == null || !c.optBoolean("on", false)) return;
    JSONArray rs = c.optJSONArray("rules");
    if (rs == null) rs = new JSONArray();
    if (sp.getInt("usedDay", 0) != dayKey(now)) {
      used.clear();
      seen.clear();
      saveUsed(now);
    }
    boolean changed = false;
    for (int i = 0; i < rs.length(); i++) {
      JSONObject r = rs.optJSONObject(i);
      if (r == null) continue;
      String id = r.optString("id"), pkg = r.optString("pkg");
      int min = r.optInt("min", 30), lock = r.optInt("lock", 20);
      boolean session = r.optBoolean("session", false); // old behaviour, only if a rule asks for it
      if (pkg.equals(curPkg)) {
        long u = (used.containsKey(id) ? used.get(id) : 0L) + dt;
        used.put(id, u);
        seen.put(id, now);
        changed = true;
        if (u >= min * 60000L) {
          if (session) used.put(id, 0L);
          sp.edit()
            .putLong("until", now + lock * 60000L)
            .putString("reason", r.optString("name", "App") + " for " + min + " min" + (session ? "" : " today"))
            .putLong("exitAt", 0)
            .apply();
          fgAllowed = false;
          saveUsed(now);
          goHome();
          return;
        }
      } else if (session && seen.containsKey(id) && now - seen.get(id) > 180000L) {
        used.put(id, 0L);
        seen.remove(id);
      }
    }
    // allowed social media: the apps in the pool share one daily limit
    JSONObject pl = c.optJSONObject("pool");
    if (pl != null && pl.optBoolean("on", false) && inPool(pl, curPkg)) {
      long u = (used.containsKey("pool") ? used.get("pool") : 0L) + dt;
      used.put("pool", u);
      changed = true;
      int pmin = pl.optInt("min", 60), plock = pl.optInt("lock", 20);
      if (u >= pmin * 60000L) {
        sp.edit()
          .putLong("until", now + plock * 60000L)
          .putString("reason", "Social media for " + pmin + " min today")
          .putLong("exitAt", 0)
          .apply();
        fgAllowed = false;
        saveUsed(now);
        goHome();
        return;
      }
    }
    if (changed && now - usedSave > 15000L) saveUsed(now);
  }

  /** The app is closed at once when its time is over: back to the home screen, then the lock covers the phone. */
  private void goHome() {
    try {
      performGlobalAction(GLOBAL_ACTION_HOME);
    } catch (Exception e) {
      // the lock screen still covers the app
    }
  }

  private boolean inPool(JSONObject pl, String pkg) {
    if (pkg == null || pkg.isEmpty() || pl == null) return false;
    JSONArray a = pl.optJSONArray("apps");
    if (a == null) return false;
    for (int i = 0; i < a.length(); i++) {
      Object o = a.opt(i);
      String p = o instanceof JSONObject ? ((JSONObject) o).optString("pkg") : String.valueOf(o);
      if (pkg.equals(p)) return true;
    }
    return false;
  }

  // ---- timer over limited apps, and the warning 2 minutes before a lock ----

  private static final long WARN_MS = 120000L;

  private String clock(long t) {
    Calendar c = Calendar.getInstance();
    c.setTimeInMillis(t);
    int hh = c.get(Calendar.HOUR), ap = c.get(Calendar.AM_PM);
    return String.format(Locale.US, "%d:%02d %s", hh == 0 ? 12 : hh, c.get(Calendar.MINUTE), ap == Calendar.AM ? "AM" : "PM");
  }

  private void loadWarned() {
    warned.clear();
    try {
      if (sp.getInt("warnDay", 0) == dayKey(System.currentTimeMillis())) {
        String s = sp.getString("warnSet", "");
        if (!s.isEmpty()) warned.addAll(Arrays.asList(s.split("\\|")));
      }
    } catch (Exception e) {
      // start again
    }
  }

  private void saveWarned(long now) {
    StringBuilder b = new StringBuilder();
    for (String k : warned) {
      if (b.length() > 0) b.append('|');
      b.append(k);
    }
    sp.edit().putInt("warnDay", dayKey(now)).putString("warnSet", b.toString()).apply();
  }

  /**
   * While an app with a limit (a rule or the social pool) is in front, a small timer shows how long is left today. Two
   * minutes before the limit ends a card says so; OK dismisses it, Close app goes to the home screen. Clock locks (the night
   * rule and scheduled lockdowns) get the same card two minutes before they start.
   */
  private void limitTick(long now) {
    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
    JSONObject c = cfg();
    if ((pm != null && !pm.isInteractive()) || c == null || !c.optBoolean("on", false)) {
      hideTimer();
      hideWarn();
      return;
    }
    if (sp.getInt("warnDay", 0) != dayKey(now)) {
      warned.clear();
      saveWarned(now);
    }
    boolean timerOn = c.optBoolean("timer", true), warnOn = c.optBoolean("warn", true);
    long best = -1;
    String label = "", key = "";
    JSONArray rs = c.optJSONArray("rules");
    if (rs != null && !curPkg.isEmpty()) {
      for (int i = 0; i < rs.length(); i++) {
        JSONObject r = rs.optJSONObject(i);
        if (r == null || !curPkg.equals(r.optString("pkg"))) continue;
        String id = r.optString("id");
        long rem = r.optInt("min", 30) * 60000L - (used.containsKey(id) ? used.get(id) : 0L);
        if (best < 0 || rem < best) {
          best = rem;
          label = r.optString("name", "App");
          key = "r:" + id;
        }
      }
    }
    JSONObject pl = c.optJSONObject("pool");
    if (pl != null && pl.optBoolean("on", false) && inPool(pl, curPkg)) {
      long rem = pl.optInt("min", 60) * 60000L - (used.containsKey("pool") ? used.get("pool") : 0L);
      if (best < 0 || rem < best) {
        best = rem;
        label = "Social media";
        key = "pool";
      }
    }
    boolean ownApp = curPkg.equals(getPackageName()) || ignore.contains(curPkg);
    if (best > 0 && !ownApp) {
      if (timerOn) showTimer(label, best);
      else hideTimer();
      if (warnOn && best <= WARN_MS && !warned.contains(key)) {
        warned.add(key);
        saveWarned(now);
        showWarn("Lockdown in 2 minutes", label + " ends at " + clock(now + best) + ". Then your phone rests for a while. Finish what you are doing.", true, now);
      }
    } else {
      hideTimer();
    }
    if (warnOn) clockWarn(now, c);
    if (warnShown && now - warnAt > 90000L) hideWarn();
  }

  private long startToday(int minutes, long now) {
    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(now);
    cal.set(Calendar.HOUR_OF_DAY, minutes / 60);
    cal.set(Calendar.MINUTE, minutes % 60);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    return cal.getTimeInMillis();
  }

  private void clockWarn(long now, JSONObject c) {
    JSONObject n = NightRule.cfg(this);
    if (n != null && n.optBoolean("on", false) && NightRule.activeEnd(this, now) == 0) {
      long st = startToday(hm(n.optString("from", "23:00"), 1380), now);
      if (st > now && st - now <= WARN_MS && !warned.contains("n:" + st)) {
        warned.add("n:" + st);
        saveWarned(now);
        showWarn("Night rule in 2 minutes", "Your phone rests from " + clock(st) + ". Only the people you allowed can be called.", false, now);
      }
    }
    JSONArray sc = c.optJSONArray("sched");
    if (sc == null) return;
    int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1;
    for (int i = 0; i < sc.length(); i++) {
      JSONObject s = sc.optJSONObject(i);
      if (s == null || !s.optBoolean("on", true)) continue;
      JSONArray days = s.optJSONArray("days");
      boolean today = false;
      for (int k = 0; days != null && k < days.length(); k++) if (days.optInt(k, -1) == dow) today = true;
      int f = hm(s.optString("from", ""), -1);
      if (!today || f < 0) continue;
      long st = startToday(f, now);
      if (st > now && st - now <= WARN_MS && !warned.contains("s:" + st)) {
        warned.add("s:" + st);
        saveWarned(now);
        showWarn("Lockdown in 2 minutes", "Your scheduled lockdown starts at " + clock(st) + ".", false, now);
      }
    }
  }

  private void showTimer(String label, long rem) {
    if (timerView == null) {
      TextView t = new TextView(this);
      t.setTextColor(Color.WHITE);
      t.setTextSize(14);
      t.setTypeface(Typeface.DEFAULT_BOLD);
      t.setPadding(dp(14), dp(8), dp(14), dp(8));
      GradientDrawable g = new GradientDrawable();
      g.setColor(Color.parseColor("#E6111318"));
      g.setCornerRadius(dp(20));
      t.setBackground(g);
      timerView = t;
    }
    long sec = Math.max(0, rem) / 1000;
    timerView.setText(label + "  \u00b7  " + String.format(Locale.US, "%d:%02d", sec / 60, sec % 60) + " left today");
    if (!timerShown) {
      WindowManager.LayoutParams p = new WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
      );
      p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
      p.y = dp(44);
      try {
        wm.addView(timerView, p);
        timerShown = true;
      } catch (Exception e) {
        // try again on the next tick
      }
    }
  }

  private void hideTimer() {
    if (timerShown && timerView != null) {
      try {
        wm.removeView(timerView);
      } catch (Exception e) {
        // already gone
      }
      timerShown = false;
    }
  }

  private void showWarn(String title, String msg, boolean closeApp, long now) {
    if (warnView == null) {
      LinearLayout l = new LinearLayout(this);
      l.setOrientation(LinearLayout.VERTICAL);
      l.setPadding(dp(22), dp(20), dp(22), dp(16));
      GradientDrawable g = new GradientDrawable();
      g.setColor(Color.parseColor("#111318"));
      g.setCornerRadius(dp(24));
      l.setBackground(g);
      wTitle = tv("", 20, "#FFFFFF");
      wTitle.setTypeface(Typeface.DEFAULT_BOLD);
      wMsg = tv("", 15, "#C9CCD3");
      LinearLayout row = new LinearLayout(this);
      row.setGravity(Gravity.CENTER);
      Button ok = btn("OK");
      ok.setOnClickListener(v -> hideWarn());
      wClose = btn("Close app");
      wClose.setOnClickListener(v -> {
        goHome();
        hideWarn();
      });
      row.addView(ok, lp(0));
      row.addView(wClose, lp(0));
      l.addView(wTitle, lp(0));
      l.addView(wMsg, lp(10));
      l.addView(row, lp(18));
      warnView = l;
    }
    wTitle.setText(title);
    wMsg.setText(msg);
    wClose.setVisibility(closeApp ? View.VISIBLE : View.GONE);
    warnAt = now;
    if (!warnShown) {
      WindowManager.LayoutParams p = new WindowManager.LayoutParams(
        dp(330),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
      );
      p.gravity = Gravity.CENTER;
      try {
        wm.addView(warnView, p);
        warnShown = true;
      } catch (Exception e) {
        // try again on the next tick
      }
    }
  }

  private void hideWarn() {
    if (warnShown && warnView != null) {
      try {
        wm.removeView(warnView);
      } catch (Exception e) {
        // already gone
      }
      warnShown = false;
    }
  }

  // ---- focus nudges: an app that is not allowed, used for long, makes the phone speak his message ----

  private int hm(String t, int d) {
    try {
      String[] p = t.split(":");
      return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
    } catch (Exception e) {
      return d;
    }
  }

  private boolean inWindow(JSONObject n, long now) {
    int a = hm(n.optString("from", "00:00"), 0), b = hm(n.optString("to", "23:59"), 1439);
    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(now);
    int t = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    if (a == b) return true;
    return a < b ? (t >= a && t < b) : (t >= a || t < b);
  }

  private boolean nzAllowed(JSONObject n, String p) {
    if (p.equals(getPackageName()) || p.equals(launcherPkg) || allow.contains(p)) return true;
    JSONArray a = n.optJSONArray("allow");
    if (a != null) {
      for (int i = 0; i < a.length(); i++) {
        if (p.equals(a.optString(i))) return true;
      }
    }
    return false;
  }

  private void nudgeTick(long now) {
    JSONObject n = null;
    try {
      String raw = sp.getString("nudge", "");
      if (raw != null && !raw.isEmpty()) n = new JSONObject(raw);
    } catch (Exception e) {
      n = null;
    }
    long dt = lastTick == 0 ? 0 : Math.min(now - lastTick, 10000L);
    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
    boolean screenOn = pm == null || pm.isInteractive();
    if (n == null || !n.optBoolean("on", false) || isLocked() || !inWindow(n, now)) {
      nzOff = 0;
      nzAway = 0;
      return;
    }
    if (!screenOn || nzPkg.isEmpty() || nzAllowed(n, nzPkg)) {
      nzAway += dt;
      if (nzAway >= (screenOn ? 90000L : 120000L)) nzOff = 0;
      return;
    }
    nzAway = 0;
    nzOff += dt;
    long after = Math.max(1, n.optInt("after", 10)) * 60000L;
    long every = Math.max(1, n.optInt("every", 10)) * 60000L;
    if (nzOff >= after && now - nzLast >= every) {
      nzLast = now;
      String m = n.optString("msg", "");
      if (m == null || m.trim().isEmpty()) m = "You have been on your phone for a while.";
      speakNudge(this, m);
    }
  }

  /** Speaks a message through the same alarm path the reminders use, so it also works with the screen off. */
  static void speakNudge(Context ctx, String msg) {
    boolean hindi = false;
    for (int i = 0; i < msg.length(); i++) {
      char ch = msg.charAt(i);
      if (ch >= 0x0900 && ch <= 0x097F) {
        hindi = true;
        break;
      }
    }
    SpeakerPlugin.set(ctx, 9100, System.currentTimeMillis() + 700L, "Partner", msg, msg, hindi ? "hi-IN" : "en-IN", false, "");
  }

  // ---- notification shade ----

  private boolean isShade(String c, AccessibilityEvent e) {
    String t = "";
    try {
      if (e.getText() != null) t = e.getText().toString().toLowerCase(Locale.ROOT);
    } catch (Exception ex) {
      t = "";
    }
    return c.contains("notification") || c.contains("shade") || c.contains("quicksetting") || c.contains("qspanel")
      || t.contains("notification shade") || t.contains("quick settings") || t.contains("notification panel");
  }

  private void closeShade() {
    long now = System.currentTimeMillis();
    if (now - shadeAt < 400L) return;
    shadeAt = now;
    try {
      // 15 = GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE (Android 12 and newer); older versions: Back closes the shade
      if (Build.VERSION.SDK_INT >= 31) performGlobalAction(15);
      else performGlobalAction(GLOBAL_ACTION_BACK);
    } catch (Exception ex) {
      // ignore
    }
  }

  private int dp(int v) {
    return (int) (v * getResources().getDisplayMetrics().density);
  }

  private TextView tv(String t, int sp, String col) {
    TextView v = new TextView(this);
    v.setText(t);
    v.setTextSize(sp);
    v.setTextColor(Color.parseColor(col));
    v.setGravity(Gravity.CENTER);
    return v;
  }

  private Button btn(String t) {
    Button b = new Button(this);
    b.setText(t);
    b.setAllCaps(false);
    b.setTextColor(Color.parseColor("#F1EFE9"));
    b.setBackgroundColor(Color.parseColor("#2A2A27"));
    b.setMinHeight(dp(48));
    b.setMinimumHeight(dp(48));
    b.setPadding(dp(20), 0, dp(20), 0);
    return b;
  }

  private LinearLayout.LayoutParams lp(int top) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    p.topMargin = dp(top);
    p.leftMargin = dp(6);
    p.rightMargin = dp(6);
    return p;
  }

  private void ensureOverlay() {
    if (overlay != null) return;
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setGravity(Gravity.CENTER);
    l.setBackgroundColor(Color.parseColor("#0E0E0D"));
    l.setPadding(dp(32), dp(32), dp(32), dp(32));
    l.setClickable(true);
    TextView a = tv("LOCKDOWN", 12, "#8F8C84");
    a.setLetterSpacing(0.18f);
    tTime = tv("", 64, "#F1EFE9");
    tTime.setTypeface(Typeface.SERIF);
    tMsg = tv("", 15, "#8F8C84");
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER);
    Button bp = btn("Phone");
    bp.setOnClickListener(v -> openDial(null));
    Button be = btn("Emergency");
    be.setOnClickListener(v -> openDial("tel:112"));
    row.addView(bp, lp(0));
    row.addView(be, lp(0));
    bExit = btn("Emergency exit");
    bExit.setOnClickListener(v -> toggleExit());
    TextView note = tv("Partner stays installed during this time.", 13, "#8F8C84");
    l.addView(a, lp(0));
    l.addView(tTime, lp(16));
    l.addView(tMsg, lp(8));
    l.addView(row, lp(48));
    l.addView(note, lp(48));
    l.addView(bExit, lp(24));
    overlay = l;
  }

  private void showOverlay(long now, long until, long exitAt) {
    ensureOverlay();
    long left = Math.max(0, until - now) / 1000;
    tTime.setText(String.format(Locale.US, "%d:%02d", left / 60, left % 60));
    String reason = sp.getString("reason", "");
    boolean nightNow = sp.getLong("nightUntil", 0) > now;
    tMsg.setText(
      nightNow
        ? "Night rule. Your phone is resting. Only the contacts you allowed can be called."
        : (reason.isEmpty() ? "Your phone is resting." : reason + ". Your phone is resting.") + " Calls still work."
    );
    if (exitOn()) {
      bExit.setVisibility(View.VISIBLE);
      if (exitAt > 0) {
        long w = Math.max(0, exitAt - now) / 1000;
        bExit.setText(String.format(Locale.US, "Cancel exit  -  unlocks in %d:%02d", w / 60, w % 60));
      } else {
        bExit.setText("Emergency exit (10 min wait)");
      }
    } else {
      bExit.setVisibility(View.GONE);
    }
    if (!shown) {
      WindowManager.LayoutParams p = new WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE
      );
      try {
        wm.addView(overlay, p);
        shown = true;
      } catch (Exception e) {
        // try again on the next tick
      }
    }
  }

  private void hideOverlay() {
    if (shown && overlay != null) {
      try {
        wm.removeView(overlay);
      } catch (Exception e) {
        // already gone
      }
      shown = false;
    }
  }

  private void toggleExit() {
    if (!exitOn()) return;
    long ex = sp.getLong("exitAt", 0);
    sp.edit().putLong("exitAt", ex > 0 ? 0 : System.currentTimeMillis() + 10 * 60000L).apply();
    try {
      onTick();
    } catch (Exception e) {
      // ignore
    }
  }

  private void openDial(String uri) {
    try {
      Intent i = new Intent(Intent.ACTION_DIAL, uri == null ? null : Uri.parse(uri));
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(i);
      fgAllowed = true;
      hideOverlay();
    } catch (Exception e) {
      fgAllowed = false;
    }
  }
}
