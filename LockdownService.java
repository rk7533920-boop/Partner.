package com.partner.diary;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
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
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
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
  private long nzOff = 0, nzAway = 0, nzLast = 0, shadeAt = 0, usedSave = 0;
  // the late-night call rule is running (see NightRule)
  private boolean nightRun = false;

  private final Runnable tick = new Runnable() {
    @Override
    public void run() {
      try {
        onTick();
      } catch (Exception e) {
        // keep ticking
      }
      h.postDelayed(this, shown ? 1000 : (nightRun ? 2000 : 5000));
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
    lastTick = System.currentTimeMillis();
    h.removeCallbacks(tick);
    h.post(tick);
  }

  @Override
  public boolean onUnbind(Intent intent) {
    inst = null;
    h.removeCallbacks(tick);
    hideOverlay();
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
      "com.android.contacts", "com.google.android.contacts"
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
    if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
    String p = e.getPackageName().toString();
    String c = e.getClassName() == null ? "" : e.getClassName().toString().toLowerCase(Locale.ROOT);
    if (!ignore.contains(p) || p.equals(getPackageName())) nzPkg = p;
    if (isLocked() && protect() && (c.contains("globalactions") || c.contains("powermenu") || c.contains("shutdown") || c.contains("reboot"))) {
      performGlobalAction(GLOBAL_ACTION_BACK);
      return;
    }
    // during a lockdown the notification shade stays shut: notifications (even inline replies) are a way around the lock
    if (isLocked() && "com.android.systemui".equals(p) && isShade(c, e)) {
      closeShade();
      return;
    }
    // smart banking mode: a listed bank or payment app opened, so pause this service for a while
    if (!isLocked() && sp.getBoolean("smartbank", false) && isBankPkg(p)) {
      if (LockdownAcc.startBank(this, sp.getInt("bankmin", 10))) return;
    }
    if (ignore.contains(p)) return;
    curPkg = p;
    fgAllowed = allow.contains(p);
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
      hideOverlay();
      lastTick = now;
      return;
    }
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
    if (until > now) {
      if (!fgAllowed) showOverlay(now, until, exitAt);
      else hideOverlay();
    } else {
      hideOverlay();
      trackUsage(now);
    }
    nudgeTick(now);
    lastTick = now;
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
    if (rs == null) return;
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
          return;
        }
      } else if (session && seen.containsKey(id) && now - seen.get(id) > 180000L) {
        used.put(id, 0L);
        seen.remove(id);
      }
    }
    if (changed && now - usedSave > 15000L) saveUsed(now);
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
