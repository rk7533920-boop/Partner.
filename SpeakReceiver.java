package com.partner.diary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class SpeakReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Intent s = new Intent(c, SpeakService.class);
        if (i.getExtras() != null) s.putExtras(i.getExtras());
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
        else c.startService(s);

        // daily review: set tomorrow's alarm so it keeps coming even if the app is never opened
        if (i.getBooleanExtra("repeat", false)) {
            SpeakerPlugin.set(c, i.getIntExtra("id", 1), i.getLongExtra("at", System.currentTimeMillis()) + 86400000L,
                    i.getStringExtra("title"), i.getStringExtra("body"), i.getStringExtra("text"),
                    i.getStringExtra("lang"), true, i.getStringExtra("ak"));
        }
    }
}
