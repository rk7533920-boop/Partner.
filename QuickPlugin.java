package com.partner.diary;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Quick")
public class QuickPlugin extends Plugin {

    private static final String CHANNEL = "partner_quick";
    private static final int NOTE_ID = 7001;
    static volatile boolean pending = false;
    private static QuickPlugin inst;

    @Override
    public void load() {
        inst = this;
    }

    static void ping() {
        if (inst != null) {
            inst.notifyListeners("quick", new JSObject());
        }
    }

    @PluginMethod
    public void consume(PluginCall call) {
        JSObject r = new JSObject();
        r.put("pending", pending);
        pending = false;
        call.resolve(r);
    }

    @PluginMethod
    public void setEnabled(PluginCall call) {
        Context ctx = getContext();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean on = Boolean.TRUE.equals(call.getBoolean("enabled"));
        if (!on) {
            nm.cancel(NOTE_ID);
            call.resolve();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Partner quick talk", NotificationManager.IMPORTANCE_LOW);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(ctx, MainActivity.class);
        i.putExtra("quick", true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, NOTE_ID, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Partner")
            .setContentText("Tap to talk")
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pi);
        nm.notify(NOTE_ID, b.build());
        call.resolve();
    }

    @PluginMethod
    public void close(PluginCall call) {
        if (getActivity() != null) {
            getActivity().moveTaskToBack(true);
        }
        call.resolve();
    }
}
