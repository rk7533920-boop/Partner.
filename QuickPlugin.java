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

    static final String PREF = "partner_quick_pref";
    static final String REPOST = "com.partner.diary.QUICK_REPOST";

    static void post(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        String ch = "partner_quick_top";
        if (Build.VERSION.SDK_INT >= 26) {
            nm.deleteNotificationChannel(CHANNEL);
            NotificationChannel c = new NotificationChannel(ch, "Partner quick talk", NotificationManager.IMPORTANCE_HIGH);
            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            c.setShowBadge(false);
            c.setSound(null, null);
            c.enableVibration(false);
            nm.createNotificationChannel(c);
        }
        Intent i = new Intent(ctx, MainActivity.class);
        i.putExtra("quick", true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, NOTE_ID, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent d = new Intent(ctx, SpeakBootReceiver.class).setAction(REPOST);
        PendingIntent del = PendingIntent.getBroadcast(ctx, NOTE_ID, d, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, ch) : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Partner")
            .setContentText("Tap to talk")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSortKey("0")
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .setDeleteIntent(del);
        nm.notify(NOTE_ID, b.build());
    }

    static void restore(Context ctx) {
        if (ctx.getSharedPreferences(PREF, 0).getBoolean("on", false)) post(ctx);
    }

    @PluginMethod
    public void setEnabled(PluginCall call) {
        Context ctx = getContext();
        boolean on = Boolean.TRUE.equals(call.getBoolean("enabled"));
        ctx.getSharedPreferences(PREF, 0).edit().putBoolean("on", on).apply();
        if (on) {
            post(ctx);
        } else {
            ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTE_ID);
        }
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
