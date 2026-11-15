package com.partner.diary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class SpeakReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        // a planned break: spoken reminders stay silent (a timer he started himself still rings)
        boolean quiet = NightRule.breakNow(c, System.currentTimeMillis()) && !i.getBooleanExtra("ring", false);
        // a reminder that only makes sense in a situation (for example "wrap up the call") stays silent when it is not that situation
        if (!condOk(c, i.getStringExtra("cond"))) quiet = true;
        if (!quiet) {
            Intent s = new Intent(c, SpeakService.class);
            if (i.getExtras() != null) s.putExtras(i.getExtras());
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        }

        // daily review: set tomorrow's alarm so it keeps coming even if the app is never opened
        if (i.getBooleanExtra("repeat", false)) {
            SpeakerPlugin.set(c, i.getIntExtra("id", 1), i.getLongExtra("at", System.currentTimeMillis()) + 86400000L,
                    i.getStringExtra("title"), i.getStringExtra("body"), i.getStringExtra("text"),
                    i.getStringExtra("lang"), true, i.getStringExtra("ak"), i.getBooleanExtra("ring", false), i.getStringExtra("cond"));
        }
    }

    static boolean condOk(Context c, String cond) {
        if (cond == null || cond.isEmpty()) return true;
        if ("call".equals(cond)) {
            try {
                android.telecom.TelecomManager tm = (android.telecom.TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null && tm.isInCall()) return true;
            } catch (Exception e) {
                // no phone permission: fall through to the app-call check
            }
            return PartnerNotifListener.callActive(c);
        }
        return true;
    }
}
