package com.partner.diary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
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

    private void handle(Intent intent) {
        boolean quick = intent != null && intent.getBooleanExtra("quick", false);
        showOverLock(quick);
        if (quick) {
            intent.removeExtra("quick");
            QuickPlugin.pending = true;
            QuickPlugin.ping();
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(UsageStatsPlugin.class);
        registerPlugin(QuickPlugin.class);
        registerPlugin(SpeakerPlugin.class);
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
    public void onDestroy() {
        try {
            unregisterReceiver(screenOff);
        } catch (Exception e) {
            // ignore
        }
        super.onDestroy();
    }
}
