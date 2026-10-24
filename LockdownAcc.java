package com.partner.diary;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns Partner's own accessibility service on and off. This needs the one-time permission
 * WRITE_SECURE_SETTINGS, which only adb can grant:
 * adb shell pm grant com.partner.diary android.permission.WRITE_SECURE_SETTINGS
 * Used for "banking mode": many bank and payment apps refuse to run while an accessibility service is on.
 */
final class LockdownAcc {

  private LockdownAcc() {}

  static boolean granted(Context c) {
    return ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
  }

  static boolean locked(Context c) {
    return LockdownPlugin.sp(c).getLong("until", 0) > System.currentTimeMillis();
  }

  private static boolean same(String flat, ComponentName ours) {
    ComponentName cn = ComponentName.unflattenFromString(flat);
    return cn != null && cn.equals(ours);
  }

  static boolean isOn(Context c) {
    String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    if (cur == null || cur.isEmpty()) return false;
    ComponentName ours = new ComponentName(c, LockdownService.class);
    for (String p : cur.split(":")) {
      if (!p.isEmpty() && same(p, ours)) return true;
    }
    return false;
  }

  /** Adds or removes only Partner's service, other accessibility services stay as they are. */
  static boolean setOn(Context c, boolean on) {
    if (!granted(c)) return false;
    try {
      ContentResolver r = c.getContentResolver();
      ComponentName ours = new ComponentName(c, LockdownService.class);
      String cur = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      List<String> parts = new ArrayList<>();
      if (cur != null && !cur.isEmpty()) {
        for (String p : cur.split(":")) {
          if (!p.isEmpty() && !same(p, ours)) parts.add(p);
        }
      }
      if (on) parts.add(ours.flattenToString());
      Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, TextUtils.join(":", parts));
      Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, parts.isEmpty() ? 0 : 1);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static PendingIntent pending(Context c) {
    int f = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
    return PendingIntent.getBroadcast(c, 7731, new Intent(c, BankModeReceiver.class), f);
  }

  /** Pauses the service for a while. Never during a lockdown. */
  static boolean startBank(Context c, int minutes) {
    if (!granted(c) || locked(c) || !isOn(c)) return false;
    int m = Math.max(1, Math.min(60, minutes));
    long end = System.currentTimeMillis() + m * 60000L;
    SharedPreferences p = LockdownPlugin.sp(c);
    p.edit().putLong("bankUntil", end).apply();
    try {
      AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
      if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending(c));
      else am.set(AlarmManager.RTC_WAKEUP, end, pending(c));
    } catch (Exception e) {
      // the app also turns it back on whenever it is opened
    }
    if (!setOn(c, false)) {
      p.edit().putLong("bankUntil", 0).apply();
      return false;
    }
    return true;
  }

  /** Turns the service back on and cancels the timer. */
  static void endBank(Context c) {
    try {
      AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
      am.cancel(pending(c));
    } catch (Exception e) {
      // ignore
    }
    LockdownPlugin.sp(c).edit().putLong("bankUntil", 0).apply();
    setOn(c, true);
  }

  /** Called whenever Partner opens: if the pause is over, switch the service back on. */
  static void checkBank(Context c) {
    long u = LockdownPlugin.sp(c).getLong("bankUntil", 0);
    if (u > 0 && System.currentTimeMillis() >= u) endBank(c);
  }
}
