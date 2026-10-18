package com.partner.diary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class SpeakBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) SpeakerPlugin.restore(c);
    }
}
