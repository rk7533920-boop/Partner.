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
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.util.Locale;

public class SpeakService extends Service implements TextToSpeech.OnInitListener {
    private static final String CH = "partner_voice";
    private static final String CH_T = "partner_timer";
    static final String ACTION_STOP = "com.partner.diary.STOP_RING";
    private Vibrator vib;
    private boolean ringing = false;
    private int ringNid = 2000;
    private TextToSpeech tts;
    private String text = "", lang = "hi-IN", ak = "";
    private MediaPlayer mp;
    private boolean done = false;
    private PowerManager.WakeLock wl;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && ACTION_STOP.equals(i.getAction())) {
            stopRing();
            return START_NOT_STICKY;
        }
        String title = "Partner", body = "";
        int id = 1;
        ak = "";
        if (i != null) {
            if (i.getStringExtra("title") != null) title = i.getStringExtra("title");
            if (i.getStringExtra("body") != null) body = i.getStringExtra("body");
            if (i.getStringExtra("text") != null) text = i.getStringExtra("text");
            if (i.getStringExtra("lang") != null) lang = i.getStringExtra("lang");
            if (i.getStringExtra("ak") != null) ak = i.getStringExtra("ak");
            id = i.getIntExtra("id", 1);
        }
        if (i != null && i.getBooleanExtra("ring", false)) {
            startRing(id, title, body);
            return START_NOT_STICKY;
        }
        if (ringing) { // a reminder arrived while a timer rings: the voice wins, the ring goes quiet
            quietRing();
            try { ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ringNid); } catch (Exception ignored) { }
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
        h.removeCallbacksAndMessages(null);
        h.postDelayed(this::finishUp, 25000); // safety stop
        File af = ak.isEmpty() ? null : new File(getCacheDir(), "spk_" + ak + ".mp3");
        if (af != null && af.exists() && playFile(af)) return START_NOT_STICKY; // natural voice prepared by the app
        tts = new TextToSpeech(this, this);
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

    private boolean playFile(File f) {
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(x -> h.post(this::finishUp));
            mp.setOnErrorListener((x, a, b) -> { h.post(this::finishUp); return true; });
            mp.setOnPreparedListener(x -> x.start());
            mp.prepareAsync();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void finishUp() {
        if (done) return;
        done = true;
        try { if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } } catch (Exception ignored) { }
        try { if (mp != null) { mp.release(); mp = null; } } catch (Exception ignored) { }
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        // keep the notification on screen after the voice ends, so he can tap it
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_DETACH);
        else stopForeground(false);
        stopSelf();
    }

    private void startRing(int id, String title, String body) {
        ringing = true;
        done = false;
        h.removeCallbacksAndMessages(null);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_T) == null) {
            NotificationChannel ch = new NotificationChannel(CH_T, "Partner timer", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null); // the alarm sound is played by the service itself
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent open = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | SpeakerPlugin.immutable());
        Intent stop = new Intent(this, SpeakService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | SpeakerPlugin.immutable());
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_T) : new Notification.Builder(this);
        b.setContentTitle(title).setContentText(body).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(open).setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi);
        ringNid = 2000 + (id % 1000);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ringNid, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ringNid, b.build());
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (wl == null) wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "partner:speak");
        if (!wl.isHeld()) wl.acquire(95000);
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (mp != null) { try { mp.release(); } catch (Exception ignored) { } }
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(this, u);
            mp.setLooping(true);
            mp.setOnPreparedListener(x -> x.start());
            mp.prepareAsync();
        } catch (Exception ignored) {
        }
        try {
            vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null) vib.vibrate(new long[]{0, 700, 500}, 0);
        } catch (Exception ignored) {
        }
        h.postDelayed(this::stopRing, 90000); // rings for at most 90 seconds
    }

    private void quietRing() {
        ringing = false;
        try { if (mp != null) mp.stop(); } catch (Exception ignored) { }
        try { if (mp != null) mp.release(); } catch (Exception ignored) { }
        mp = null;
        try { if (vib != null) vib.cancel(); } catch (Exception ignored) { }
    }

    private void stopRing() {
        quietRing();
        done = true;
        h.removeCallbacksAndMessages(null);
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        try { if (vib != null) vib.cancel(); } catch (Exception ignored) { }
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
