package com.partner.diary;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Bridge between the web page and the wake-up alarm (see WakeAlarm). Plugin name: Wake. */
@CapacitorPlugin(name = "Wake")
public class WakePlugin extends Plugin {

  private static WakePlugin inst;

  @Override
  public void load() {
    inst = this;
  }

  /** Tells the web page the mission screen was asked for (the page also checks the ring when it comes to the front). */
  static void ping() {
    if (inst != null) {
      try {
        inst.notifyListeners("wake", new JSObject());
      } catch (Exception e) {
        // the page checks again when it is shown
      }
    }
  }

  @PluginMethod
  public void status(PluginCall call) {
    Context c = getContext();
    long now = System.currentTimeMillis();
    WakeAlarm.applyPending(c, now);
    SharedPreferences p = WakeAlarm.sp(c);
    JSObject o = new JSObject();
    o.put("cfg", p.getString("wake", ""));
    o.put("pend", p.getString("wakePend", ""));
    o.put("pendAt", p.getLong("wakePendAt", 0));
    o.put("ring", WakeAlarm.ringStart(c));
    o.put("kind", WakeAlarm.kind(c));
    o.put("checkAt", p.getLong("wakeCheckAt", 0));
    o.put("done", p.getLong("wakeDone", 0));
    o.put("miss", p.getLong("wakeMiss", 0));
    o.put("next", WakeAlarm.nextFire(c, now));
    o.put("guard", WakeAlarm.guard(c, now));
    o.put("max", WakeAlarm.RING_MAX);
    boolean fsi = true;
    try {
      NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
      if (Build.VERSION.SDK_INT >= 34 && nm != null) fsi = nm.canUseFullScreenIntent();
      if (nm != null && !nm.areNotificationsEnabled()) fsi = false;
    } catch (Exception e) {
      // unknown
    }
    o.put("fsi", fsi);
    boolean ex = true;
    try {
      AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
      if (Build.VERSION.SDK_INT >= 31 && am != null) ex = am.canScheduleExactAlarms();
    } catch (Exception e) {
      // unknown
    }
    o.put("exact", ex);
    call.resolve(o);
  }

  /** Saves the config and a waiting weaker change. Refused while the guard window or a ring is running. */
  @PluginMethod
  public void set(PluginCall call) {
    Context c = getContext();
    long now = System.currentTimeMillis();
    if (WakeAlarm.guard(c, now) || WakeAlarm.ringStart(c) > 0) {
      call.reject("Wake-up is active");
      return;
    }
    String cfg = call.getString("cfg", "");
    String pend = call.getString("pend", "");
    long pendAt = call.getLong("pendAt", 0L);
    WakeAlarm.sp(c).edit().putString("wake", cfg == null ? "" : cfg).putString("wakePend", pend == null ? "" : pend).putLong("wakePendAt", pendAt).apply();
    WakeAlarm.schedule(c);
    call.resolve();
  }

  /** The mission was done. Only counts while a ring is running. */
  @PluginMethod
  public void done(PluginCall call) {
    Context c = getContext();
    JSObject o = new JSObject();
    boolean was = WakeAlarm.ringStart(c) > 0;
    String k = WakeAlarm.kind(c);
    if (was) WakeAlarm.done(c);
    o.put("was", was);
    o.put("kind", k);
    call.resolve(o);
  }

  /** The ring started by "Try it": the same ring and mission as the real alarm, but it does not log a wake-up. */
  @PluginMethod
  public void test(PluginCall call) {
    Context c = getContext();
    if (WakeAlarm.ringStart(c) > 0) {
      call.resolve();
      return;
    }
    WakeAlarm.startRing(c, "test");
    call.resolve();
  }

  /** Clears the "missed" note after he has seen it. */
  @PluginMethod
  public void seenMiss(PluginCall call) {
    WakeAlarm.sp(getContext()).edit().putLong("wakeMiss", 0).apply();
    call.resolve();
  }

  @PluginMethod
  public void openFullScreenSettings(PluginCall call) {
    try {
      Intent i = Build.VERSION.SDK_INT >= 34
        ? new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getContext().getPackageName()))
        : new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      getContext().startActivity(i);
      call.resolve();
    } catch (Exception e) {
      call.reject("Could not open the page");
    }
  }
}
