package com.partner.diary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives the wake-up alarm, the "are you awake" check and the one-minute watch while a ring runs. */
public class WakeReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    String a = i == null ? null : i.getAction();
    long now = System.currentTimeMillis();
    try {
      WakeAlarm.applyPending(c, now);
      if (WakeAlarm.ACT_FIRE.equals(a)) {
        // set tomorrow's alarm first, so it keeps coming even if something below fails
        WakeAlarm.schedule(c);
        if (WakeAlarm.on(c) && !NightRule.breakNow(c, now)) WakeAlarm.startRing(c, "wake");
      } else if (WakeAlarm.ACT_CHECK.equals(a)) {
        if (WakeAlarm.sp(c).getLong("wakeCheckAt", 0) > 0) {
          if (WakeAlarm.on(c) && !NightRule.breakNow(c, now)) WakeAlarm.startRing(c, "check");
          else WakeAlarm.sp(c).edit().putLong("wakeCheckAt", 0).apply();
        }
      } else if (WakeAlarm.ACT_WATCH.equals(a)) {
        WakeAlarm.watch(c);
      } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
        // an app update clears the alarms of the old version: put the next wake-up back
        WakeAlarm.restore(c);
      }
    } catch (Exception e) {
      // the next alarm and the watch try again
    }
  }
}
