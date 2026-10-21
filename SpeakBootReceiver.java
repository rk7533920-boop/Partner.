package com.partner.diary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class SpeakBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            SpeakerPlugin.restore(c);
            QuickPlugin.restore(c);
            try { HotwordService.restore(c); } catch (Exception e) { /* Android 14+ may refuse, opening Partner turns it back on */ }
        }
        else if (QuickPlugin.REPOST.equals(i.getAction())) QuickPlugin.restore(c);
    }
}
