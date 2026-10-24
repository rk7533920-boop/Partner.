package com.partner.diary;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            showOverLock(false);
        }
    };

    private void showOverLock(boolean on) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on);
            setTurnScreenOn(on);
        } else if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    private static long lastQuick = 0;

    private void handle(Intent intent) {
        boolean quick = intent != null && intent.getBooleanExtra("quick", false);
        showOverLock(quick);
        if (quick) {
            intent.removeExtra("quick");
            try {
                ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(HotwordService.GO_ID);
            } catch (Exception e) {
                // ignore
            }
            // "Hey Partner" opens the app two ways at once, only start talking once
            long now = SystemClock.elapsedRealtime();
            if (now - lastQuick < 2500) return;
            lastQuick = now;
            QuickPlugin.pending = true;
            QuickPlugin.ping();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(UsageStatsPlugin.class);
        registerPlugin(QuickPlugin.class);
        registerPlugin(SpeakerPlugin.class);
        registerPlugin(PhonePlugin.class);
        registerPlugin(LockdownPlugin.class);
        registerPlugin(HotwordPlugin.class);
        try {
            // only present when the workflow could bundle the offline voice
            registerPlugin((Class<? extends com.getcapacitor.Plugin>) Class.forName("com.partner.diary.OfflineVoicePlugin"));
        } catch (Throwable t) {
            // this build has no offline voice
        }
        super.onCreate(savedInstanceState);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        HotwordService.appVisible = true; // the app is using the microphone now, "Hey Partner" waits
    }

    @Override
    public void onPause() {
        HotwordService.appVisible = false;
        HotwordService.afterApp();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(screenOff);
        } catch (Exception e) {
            // ignore
        }
        super.onDestroy();
    }
}
