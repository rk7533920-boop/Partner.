package com.partner.diary;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The wake-up alarm. It does not use the Clock app: the alarm, the ringing and the "is he really up" state all live here,
 * in the lockdown preferences, so a restored backup or a bug in the web page cannot weaken them.
 *
 * Config (saved by the app, key "wake"): {on, time "HH:mm", days [0..6, 0 = Sunday], mission, diff, count, text, vol,
 * guard, guardFrom "HH:mm", check, checkMin}. A weaker change waits 24 hours ("wakePend", "wakePendAt"), like the night rule.
 *
 * Ring state: "wakeRing" = when the ring started (0 = quiet), "wakeKind" = "wake" or "check", "wakeCheckAt" = when the
 * second "are you awake" ring is due, "wakeDone" and "wakeMiss" = last finished and last given-up time.
 *
 * Safety net: a ring that nobody finished stops after RING_MAX, so a bug in a mission can never lock him out for ever.
 * That time is counted as a missed wake-up and he is told.
 */
final class WakeAlarm {

  private WakeAlarm() {}

  static final String ACT_FIRE = "com.partner.diary.WAKE_FIRE";
  static final String ACT_CHECK = "com.partner.diary.WAKE_CHECK";
  static final String ACT_WATCH = "com.partner.diary.WAKE_WATCH";
  static final long RING_MAX = 60 * 60000L;
  static final long WATCH_EVERY = 60000L;

  private static final int REQ_FIRE = 7101, REQ_CHECK = 7102, REQ_WATCH = 7103, REQ_SHOW = 7104;

  static SharedPreferences sp(Context c) {
    return LockdownPlugin.sp(c);
  }

  static JSONObject cfg(Context c) {
    try {
      String s = sp(c).getString("wake", "");
      return s == null || s.isEmpty() ? null : new JSONObject(s);
    } catch (Exception e) {
      return null;
    }
  }

  static boolean on(Context c) {
    JSONObject o = cfg(c);
    return o != null && o.optBoolean("on", false);
  }

  static int hm(String t, int d) {
    try {
      String[] p = t.split(":");
      return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
    } catch (Exception e) {
      return d;
    }
  }

  private static boolean dayOn(JSONObject o, int dow) {
    JSONArray a = o.optJSONArray("days");
    if (a == null || a.length() == 0) return true;
    for (int i = 0; i < a.length(); i++) {
      if (a.optInt(i, -1) == dow) return true;
    }
    return false;
  }

  /** The next time the alarm must ring after `after`, skipping days that are off and times inside a planned break. 0 = none. */
  static long nextFire(Context c, long after) {
    JSONObject o = cfg(c);
    if (o == null || !o.optBoolean("on", false)) return 0;
    int t = hm(o.optString("time", "07:00"), 420);
    for (int add = 0; add <= 21; add++) {
      Calendar cal = Calendar.getInstance();
      cal.setTimeInMillis(after);
      cal.add(Calendar.DAY_OF_YEAR, add);
      cal.set(Calendar.HOUR_OF_DAY, t / 60);
      cal.set(Calendar.MINUTE, t % 60);
      cal.set(Calendar.SECOND, 0);
      cal.set(Calendar.MILLISECOND, 0);
      long at = cal.getTimeInMillis();
      if (at <= after + 1000L) continue;
      if (!dayOn(o, cal.get(Calendar.DAY_OF_WEEK) - 1)) continue;
      if (NightRule.breakNow(c, at)) continue;
      return at;
    }
    return 0;
  }

  /** Start of the guard window that leads to the fire at `fire` (the wind-down time the evening before). */
  private static long guardStart(JSONObject o, long fire) {
    int g = hm(o.optString("guardFrom", "22:15"), 1335);
    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(fire);
    cal.set(Calendar.HOUR_OF_DAY, g / 60);
    cal.set(Calendar.MINUTE, g % 60);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    if (cal.getTimeInMillis() > fire) cal.add(Calendar.DAY_OF_YEAR, -1);
    return cal.getTimeInMillis();
  }

  /**
   * The wake guard: from the evening wind-down until the wake-up mission is done, the pages that could remove the alarm
   * (device admin, accessibility, Partner's own app page) are closed and the power menu and the notification shade stay shut.
   */
  static boolean guard(Context c, long now) {
    JSONObject o = cfg(c);
    if (o == null || !o.optBoolean("on", false) || !o.optBoolean("guard", true)) return false;
    if (NightRule.breakNow(c, now)) return false;
    SharedPreferences p = sp(c);
    if (p.getLong("wakeRing", 0) > 0 || p.getLong("wakeCheckAt", 0) > 0) return true;
    long nf = nextFire(c, now);
    if (nf <= 0) return false;
    long gs = guardStart(o, nf);
    return now >= gs && now < nf;
  }

  /** True while a ring is waiting for its mission. */
  static boolean ringing(Context c) {
    return ringStart(c) > 0;
  }

  /** When the current ring started, or 0 when nothing rings. A ring older than RING_MAX is closed as missed. */
  static long ringStart(Context c) {
    SharedPreferences p = sp(c);
    long r = p.getLong("wakeRing", 0);
    if (r <= 0) return 0;
    if (System.currentTimeMillis() - r > RING_MAX) {
      giveUp(c);
      return 0;
    }
    return r;
  }

  static String kind(Context c) {
    return sp(c).getString("wakeKind", "wake");
  }

  /** A weaker change waits 24 hours and never lands while a ring or the guard window is running. */
  static void applyPending(Context c, long now) {
    SharedPreferences p = sp(c);
    long pa = p.getLong("wakePendAt", 0);
    String pend = p.getString("wakePend", "");
    if (pa > 0 && now >= pa && pend != null && pend.length() > 2 && !guard(c, now) && p.getLong("wakeRing", 0) == 0) {
      p.edit().putString("wake", pend).putString("wakePend", "").putLong("wakePendAt", 0).apply();
      schedule(c);
    }
  }

  private static PendingIntent fire(Context c, String act, int req) {
    Intent i = new Intent(c, WakeReceiver.class).setAction(act);
    return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | SpeakerPlugin.immutable());
  }

  private static PendingIntent show(Context c) {
    Intent i = missionIntent(c);
    return PendingIntent.getActivity(c, REQ_SHOW, i, PendingIntent.FLAG_UPDATE_CURRENT | SpeakerPlugin.immutable());
  }

  /** The intent that opens Partner on the wake-up mission. */
  static Intent missionIntent(Context c) {
    Intent i = new Intent(c, MainActivity.class);
    i.putExtra("wake", true);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    return i;
  }

  static void openMission(Context c) {
    try {
      c.startActivity(missionIntent(c));
    } catch (Exception e) {
      // the full-screen notification and the accessibility service try again
    }
  }

  /** Puts the next alarm in the system clock. Called after every change, after a restart and after each ring. */
  static void schedule(Context c) {
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    if (am == null) return;
    try {
      am.cancel(fire(c, ACT_FIRE, REQ_FIRE));
    } catch (Exception e) {
      // nothing to cancel
    }
    long at = nextFire(c, System.currentTimeMillis());
    if (at <= 0) return;
    try {
      // an alarm-clock alarm: exact, works in Doze, and needs no special permission
      am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show(c)), fire(c, ACT_FIRE, REQ_FIRE));
    } catch (Exception e) {
      try {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire(c, ACT_FIRE, REQ_FIRE));
      } catch (Exception ex) {
        am.set(AlarmManager.RTC_WAKEUP, at, fire(c, ACT_FIRE, REQ_FIRE));
      }
    }
  }

  private static void exact(Context c, long at, PendingIntent pi) {
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    if (am == null) return;
    try {
      if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
      else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    } catch (Exception e) {
      am.set(AlarmManager.RTC_WAKEUP, at, pi);
    }
  }

  private static void cancelAlarm(Context c, String act, int req) {
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    if (am == null) return;
    try {
      am.cancel(fire(c, act, req));
    } catch (Exception e) {
      // ignore
    }
  }

  /** Starts a ring (kind "wake" or "check"). The service plays the sound; the app shows the mission. */
  static void startRing(Context c, String kind) {
    long now = System.currentTimeMillis();
    sp(c).edit().putLong("wakeRing", now).putString("wakeKind", kind).putLong("wakeCheckAt", 0).apply();
    startService(c);
    exact(c, now + WATCH_EVERY, fire(c, ACT_WATCH, REQ_WATCH));
  }

  static void startService(Context c) {
    Intent s = new Intent(c, WakeService.class);
    try {
      if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
      else c.startService(s);
    } catch (Exception e) {
      // Android may refuse after a restart; opening Partner starts it again
    }
  }

  /** Called every minute while a ring runs: brings the sound back if the system stopped the service. */
  static void watch(Context c) {
    if (ringStart(c) > 0) {
      startService(c);
      exact(c, System.currentTimeMillis() + WATCH_EVERY, fire(c, ACT_WATCH, REQ_WATCH));
    } else {
      cancelAlarm(c, ACT_WATCH, REQ_WATCH);
    }
  }

  private static void quiet(Context c) {
    cancelAlarm(c, ACT_WATCH, REQ_WATCH);
    try {
      c.stopService(new Intent(c, WakeService.class));
    } catch (Exception e) {
      // already stopped
    }
    try {
      NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm != null) nm.cancel(WakeService.NID);
    } catch (Exception e) {
      // ignore
    }
  }

  /** The mission was done: the ring stops. A first wake-up mission may start the "are you awake" check a few minutes later. */
  static void done(Context c) {
    SharedPreferences p = sp(c);
    long now = System.currentTimeMillis();
    String k = p.getString("wakeKind", "wake");
    SharedPreferences.Editor ed = p.edit().putLong("wakeRing", 0).putLong("wakeDone", now);
    JSONObject o = cfg(c);
    long chk = 0;
    if ("wake".equals(k) && o != null && o.optBoolean("check", true)) {
      int m = Math.max(2, Math.min(30, o.optInt("checkMin", 8)));
      chk = now + m * 60000L;
    }
    ed.putLong("wakeCheckAt", chk).apply();
    quiet(c);
    cancelAlarm(c, ACT_CHECK, REQ_CHECK);
    if (chk > 0) exact(c, chk, fire(c, ACT_CHECK, REQ_CHECK));
    schedule(c);
  }

  /** Nobody finished the mission within RING_MAX: the ring stops by itself and the miss is written down. */
  static void giveUp(Context c) {
    sp(c).edit().putLong("wakeRing", 0).putLong("wakeCheckAt", 0).putLong("wakeMiss", System.currentTimeMillis()).apply();
    quiet(c);
    schedule(c);
  }

  /** After a restart: the next alarm goes back into the clock, and a ring that was running starts again. */
  static void restore(Context c) {
    applyPending(c, System.currentTimeMillis());
    schedule(c);
    SharedPreferences p = sp(c);
    if (ringStart(c) > 0) {
      startService(c);
      exact(c, System.currentTimeMillis() + WATCH_EVERY, fire(c, ACT_WATCH, REQ_WATCH));
    } else {
      long chk = p.getLong("wakeCheckAt", 0);
      if (chk > 0) {
        if (chk <= System.currentTimeMillis()) startRing(c, "check");
        else exact(c, chk, fire(c, ACT_CHECK, REQ_CHECK));
      }
    }
  }
}
