package com.partner.diary;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

public class SpeakService extends Service implements TextToSpeech.OnInitListener {
    private static final String CH = "partner_voice";
    private TextToSpeech tts;
    private String text = "", lang = "hi-IN";
    private boolean done = false;
    private PowerManager.WakeLock wl;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String title = "Partner", body = "";
        int id = 1;
        if (i != null) {
            if (i.getStringExtra("title") != null) title = i.getStringExtra("title");
            if (i.getStringExtra("body") != null) body = i.getStringExtra("body");
            if (i.getStringExtra("text") != null) text = i.getStringExtra("text");
            if (i.getStringExtra("lang") != null) lang = i.getStringExtra("lang");
            id = i.getIntExtra("id", 1);
        }
        if (text.isEmpty()) text = body;
        done = false;

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Partner reminders", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null); // only the voice, no ringtone
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | SpeakerPlugin.immutable());
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setContentTitle(title).setContentText(body).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentIntent(pi).setAutoCancel(true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1000 + id, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(1000 + id, b.build());
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (wl == null) wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "partner:speak");
        if (!wl.isHeld()) wl.acquire(30000);

        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
        }
        tts = new TextToSpeech(this, this);
        h.removeCallbacksAndMessages(null);
        h.postDelayed(this::finishUp, 25000); // safety stop
        return START_NOT_STICKY;
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            finishUp();
            return;
        }
        Locale l = lang.startsWith("hi") ? new Locale("hi", "IN") : new Locale("en", "IN");
        int r = tts.setLanguage(l);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(new Locale("en", "IN"));
        }
        tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION) // change to USAGE_ALARM if you want it on alarm volume
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String s) { }
            @Override public void onDone(String s) { h.post(SpeakService.this::finishUp); }
            @Override public void onError(String s) { h.post(SpeakService.this::finishUp); }
        });
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "partner");
    }

    private void finishUp() {
        if (done) return;
        done = true;
        try { if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } } catch (Exception ignored) { }
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        // keep the notification on screen after the voice ends, so he can tap it
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_DETACH);
        else stopForeground(false);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
