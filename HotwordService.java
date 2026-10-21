package com.partner.diary;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/**
 * Listens for "Hey Partner" while the phone is locked or in your pocket. The speech model runs on the
 * phone, no audio is sent anywhere. When the phrase is heard it opens Partner in talk mode.
 */
public class HotwordService extends Service {

    static final String PREF = "partner_hotword";
    static final String ASSET_DIR = "vosk-model";
    static final String MODEL_VER = "small-en-0.15-1";
    static final String GRAMMAR = "[\"hey partner\", \"hi partner\", \"[unk]\"]";
    static final String CH_FG = "partner_hotword_fg";
    static final String CH_GO = "partner_hotword_go";
    static final int FG_ID = 7101;
    static final int GO_ID = 7102;

    static volatile boolean running = false;
    static volatile boolean appVisible = false;
    static volatile String state = "";
    static volatile String err = "";
    private static volatile long pauseUntil = 0;
    private static volatile long lastFire = 0;

    private volatile boolean stopFlag = false;
    private Thread worker;
    private final Handler h = new Handler(Looper.getMainLooper());

    /* ---------- switching it on and off ---------- */

    static boolean wanted(Context c) {
        return c.getSharedPreferences(PREF, 0).getBoolean("on", false);
    }

    static void setWanted(Context c, boolean on) {
        c.getSharedPreferences(PREF, 0).edit().putBoolean("on", on).apply();
    }

    static boolean hasModel(Context c) {
        try {
            String[] a = c.getAssets().list(ASSET_DIR);
            return a != null && a.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean start(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 23 && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                err = "Microphone permission is off";
                return false;
            }
            Intent i = new Intent(c, HotwordService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            return true;
        } catch (Exception e) {
            err = "Android did not allow the listener to start: " + e.getClass().getSimpleName();
            return false;
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, HotwordService.class));
        } catch (Exception e) {
            // ignore
        }
    }

    /** After a restart or a kill: puts the listener back if the person had it on. */
    static void restore(Context c) {
        if (wanted(c) && !running) start(c);
    }

    /* ---------- service ---------- */

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (!goForeground("Starting Hey Partner")) {
            running = false;
            state = "blocked";
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        err = "";
        if (worker == null || !worker.isAlive()) {
            stopFlag = false;
            worker = new Thread(this::loop, "partner-hotword");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopFlag = true;
        running = false;
        state = "";
        try {
            if (worker != null) worker.interrupt();
        } catch (Exception e) {
            // ignore
        }
        super.onDestroy();
    }

    private Notification fgNote(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_FG) == null) {
            NotificationChannel ch = new NotificationChannel(CH_FG, "Hey Partner listening", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | SpeakerPlugin.immutable());
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_FG) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Partner")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi);
        return b.build();
    }

    private boolean goForeground(String text) {
        try {
            Notification n = fgNote(text);
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(FG_ID, n);
            }
            return true;
        } catch (Exception e) {
            err = "Android blocked the listener from starting in the background. Open Partner once.";
            return false;
        }
    }

    private void note(String text) {
        try {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(FG_ID, fgNote(text));
        } catch (Exception e) {
            // ignore
        }
    }

    /* ---------- speech model ---------- */

    private File prepareModel() throws IOException {
        File dst = new File(getFilesDir(), ASSET_DIR);
        File mark = new File(getFilesDir(), ASSET_DIR + ".ok-" + MODEL_VER);
        if (mark.exists() && dst.isDirectory()) return dst;
        String[] top = getAssets().list(ASSET_DIR);
        if (top == null || top.length == 0) return null;
        note("Preparing Hey Partner (one time)");
        deleteTree(dst);
        copyAsset(ASSET_DIR, dst);
        new FileOutputStream(mark).close();
        return dst;
    }

    private void copyAsset(String from, File to) throws IOException {
        String[] kids = getAssets().list(from);
        if (kids != null && kids.length > 0) {
            to.mkdirs();
            for (String k : kids) copyAsset(from + "/" + k, new File(to, k));
        } else {
            InputStream in = getAssets().open(from);
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] b = new byte[65536];
                int r;
                while ((r = in.read(b)) > 0) out.write(b, 0, r);
            } finally {
                out.close();
                in.close();
            }
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /* ---------- listening ---------- */

    private AudioRecord openRecorder() {
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, 32000);
        int[] sources = { MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC };
        for (int src : sources) {
            try {
                AudioRecord r = new AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    r.startRecording();
                    if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) return r;
                }
                r.release();
            } catch (Exception e) {
                // try the next source
            }
        }
        return null;
    }

    private static void closeRecorder(AudioRecord r) {
        if (r == null) return;
        try {
            r.stop();
        } catch (Exception e) {
            // ignore
        }
        try {
            r.release();
        } catch (Exception e) {
            // ignore
        }
    }

    private static double rms(short[] b, int n) {
        if (n <= 0) return 0;
        double s = 0;
        for (int k = 0; k < n; k++) s += (double) b[k] * b[k];
        return Math.sqrt(s / n);
    }

    private static String field(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private void loop() {
        Model model = null;
        Recognizer rec = null;
        AudioRecord ar = null;
        try {
            state = "loading";
            File dir = prepareModel();
            if (dir == null) {
                err = "This build has no wake-word model. Run the build again.";
                note("Hey Partner needs a new build");
                return;
            }
            model = new Model(dir.getAbsolutePath());
            rec = new Recognizer(model, 16000.0f, GRAMMAR);
            note("Listening for Hey Partner");

            final int CH = 1600; // 100 ms of audio
            final int PRE = 5;   // keep the last 0.5 s so the start of the phrase is not lost
            short[] buf = new short[CH];
            short[][] pre = new short[PRE][CH];
            int[] preLen = new int[PRE];
            int preHead = 0, preN = 0;
            boolean active = false;
            int hangLeft = 0;
            double floor = 40;

            while (!stopFlag) {
                boolean hold = appVisible || SystemClock.elapsedRealtime() < pauseUntil;
                if (hold) {
                    if (ar != null) {
                        closeRecorder(ar);
                        ar = null;
                    }
                    active = false;
                    preN = 0;
                    state = "paused";
                    Thread.sleep(150);
                    continue;
                }
                if (ar == null) {
                    ar = openRecorder();
                    if (ar == null) {
                        err = "The microphone is busy or blocked";
                        state = "no mic";
                        Thread.sleep(2000);
                        continue;
                    }
                    err = "";
                    state = "listening";
                    active = false;
                    preN = 0;
                }
                int n = ar.read(buf, 0, CH);
                if (n <= 0) {
                    if (n < 0) {
                        closeRecorder(ar);
                        ar = null;
                        Thread.sleep(500);
                    }
                    continue;
                }

                // Skip silence so the speech model is only busy while someone is talking (saves battery)
                double level = rms(buf, n);
                boolean voiced = level > Math.max(90.0, floor * 2.5);
                if (!voiced && !active) floor = Math.min(1500, Math.max(20, floor * 0.97 + level * 0.03));
                if (voiced) hangLeft = 15; // keep listening 1.5 s after the voice stops

                if (!active) {
                    if (!voiced) {
                        System.arraycopy(buf, 0, pre[preHead], 0, n);
                        preLen[preHead] = n;
                        preHead = (preHead + 1) % PRE;
                        if (preN < PRE) preN++;
                        continue;
                    }
                    active = true;
                    boolean hit = false;
                    for (int k = 0; k < preN && !hit; k++) {
                        int idx = ((preHead - preN + k) % PRE + PRE) % PRE;
                        hit = feed(rec, pre[idx], preLen[idx]);
                    }
                    if (!hit) hit = feed(rec, buf, n);
                    preN = 0;
                    if (hit) {
                        active = false;
                        rec.getFinalResult();
                    }
                } else {
                    boolean hit = feed(rec, buf, n);
                    if (hit) {
                        active = false;
                        rec.getFinalResult();
                    } else if (!voiced && --hangLeft <= 0) {
                        active = false;
                        rec.getFinalResult(); // flush, ready for the next phrase
                    }
                }
            }
        } catch (InterruptedException e) {
            // service is stopping
        } catch (Throwable t) {
            err = "Listener stopped: " + t.getClass().getSimpleName() + (t.getMessage() != null ? " " + t.getMessage() : "");
            note("Hey Partner stopped. Open Partner to start it again.");
        } finally {
            closeRecorder(ar);
            try { if (rec != null) rec.close(); } catch (Throwable t) { }
            try { if (model != null) model.close(); } catch (Throwable t) { }
            running = false;
            if (!stopFlag) {
                state = "stopped";
                h.post(() -> stopSelf());
            }
        }
    }

    /** Feeds one chunk to the speech model. Returns true when "Hey Partner" was heard. */
    private boolean feed(Recognizer rec, short[] b, int n) {
        String text;
        if (rec.acceptWaveForm(b, n)) text = field(rec.getResult(), "text");
        else text = field(rec.getPartialResult(), "partial");
        if (text.contains("partner")) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastFire < 6000) return false;
            lastFire = now;
            pauseUntil = now + 6000; // let go of the microphone so the app can use it
            h.post(() -> {
                buzz(this);
                launchPartner(this);
            });
            return true;
        }
        return false;
    }

    /* ---------- waking the app ---------- */

    private static void buzz(Context c) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(60);
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Opens Partner in talk mode. A full-screen notification is what lets Android wake a locked phone
     * and show the app (the same way an alarm does). If the phone is in use, the direct start is tried too.
     */
    static void launchPartner(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.putExtra("quick", true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_GO) == null) {
                NotificationChannel ch = new NotificationChannel(CH_GO, "Hey Partner opens the app", NotificationManager.IMPORTANCE_HIGH);
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            PendingIntent pi = PendingIntent.getActivity(c, GO_ID, i, PendingIntent.FLAG_UPDATE_CURRENT | SpeakerPlugin.immutable());
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_GO) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Partner")
                .setContentText("Listening...")
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true);
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(10000);
            nm.notify(GO_ID, b.build());
        } catch (Exception e) {
            // ignore
        }
        try {
            c.startActivity(i);
        } catch (Exception e) {
            // Android blocks this when the phone is locked, the notification above covers that case
        }
    }
}
