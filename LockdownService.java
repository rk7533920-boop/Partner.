package com.partner.diary;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
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
import java.util.HashMap;
import java.util.HashSet;
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

  private final Runnable tick = new Runnable() {
    @Override
    public void run() {
      try {
        onTick();
      } catch (Exception e) {
        // keep ticking
      }
      h.postDelayed(this, shown ? 1000 : 5000);
    }
  };

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    inst = this;
    sp = LockdownPlugin.sp(this);
    wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    buildSets();
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
    JSONObject c = cfg();
    return c == null || c.optBoolean("exit", true);
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent e) {
    if (e == null || e.getPackageName() == null || sp == null) return;
    if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
    String p = e.getPackageName().toString();
    String c = e.getClassName() == null ? "" : e.getClassName().toString().toLowerCase(Locale.ROOT);
    if (isLocked() && protect() && (c.contains("globalactions") || c.contains("powermenu") || c.contains("shutdown") || c.contains("reboot"))) {
      performGlobalAction(GLOBAL_ACTION_BACK);
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

  private void onTick() {
    long now = System.currentTimeMillis();
    applyPending(now);
    long until = sp.getLong("until", 0);
    long exitAt = sp.getLong("exitAt", 0);
    if (until > 0 && (now >= until || (exitAt > 0 && now >= exitAt))) {
      sp.edit().putLong("until", 0).putLong("exitAt", 0).apply();
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
    lastTick = now;
  }

  private void trackUsage(long now) {
    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
    if (pm != null && !pm.isInteractive()) return;
    long dt = lastTick == 0 ? 0 : Math.min(now - lastTick, 10000L);
    JSONObject c = cfg();
    if (c == null || !c.optBoolean("on", false)) return;
    JSONArray rs = c.optJSONArray("rules");
    if (rs == null) return;
    for (int i = 0; i < rs.length(); i++) {
      JSONObject r = rs.optJSONObject(i);
      if (r == null) continue;
      String id = r.optString("id"), pkg = r.optString("pkg");
      int min = r.optInt("min", 30), lock = r.optInt("lock", 20);
      if (pkg.equals(curPkg)) {
        long u = (used.containsKey(id) ? used.get(id) : 0L) + dt;
        used.put(id, u);
        seen.put(id, now);
        if (u >= min * 60000L) {
          used.put(id, 0L);
          sp.edit()
            .putLong("until", now + lock * 60000L)
            .putString("reason", r.optString("name", "App") + " for " + min + " min")
            .putLong("exitAt", 0)
            .apply();
          fgAllowed = false;
          return;
        }
      } else if (seen.containsKey(id) && now - seen.get(id) > 180000L) {
        used.put(id, 0L);
        seen.remove(id);
      }
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
    tMsg.setText((reason.isEmpty() ? "Your phone is resting." : reason + ". Your phone is resting.") + " Calls still work.");
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
