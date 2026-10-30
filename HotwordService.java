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
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
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
    // US English model (required) and Indian English model (optional, hears Indian accents better).
    // Both listen at the same time and either one can wake Partner.
    static final String[] ASSETS = { "vosk-model", "vosk-model-in" };
    static final String MODEL_VER = "v2";
    // One spelling per sound. Spellings that sound the same (hey/hay, partner/pardner) make the speech
    // engine split its certainty between them, so a perfect "Hey Partner" only ever scored about 50%.
    static final String[] PHRASES = { "hey partner", "hi partner" };
    static final String[] LONE = { "hey", "hi" };
    static final Set<String> WAKE = new HashSet<>(Arrays.asList(PHRASES));
    static final Set<String> LONE_WAKE = new HashSet<>(Arrays.asList(LONE));
    static final String CH_FG = "partner_hotword_fg";
    static final String CH_GO = "partner_hotword_go";
    static final int FG_ID = 7101;
    static final int GO_ID = 7102;

    static volatile boolean running = false;
    static volatile boolean appVisible = false;
    static volatile String state = "";
    static volatile String err = "";
    static volatile String heard = "";
    static volatile int modelCount = 0;
    private static volatile long pauseUntil = 0;
    private static volatile long lastFire = 0;
    private long lastWeak = 0; // when the last half-heard "Hey Partner" was, a repeat within a few seconds counts

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

    /** 0 = careful, 1 = balanced (default), 2 = easy */
    static int sensitivity(Context c) {
        return c.getSharedPreferences(PREF, 0).getInt("sens", 1);
    }

    static void setSensitivity(Context c, int level) {
        c.getSharedPreferences(PREF, 0).edit().putInt("sens", Math.max(0, Math.min(2, level))).apply();
    }

    /** Called when the app goes to the background: wait a moment so Partner's own voice is not heard. */
    static void afterApp() {
        pauseUntil = Math.max(pauseUntil, SystemClock.elapsedRealtime() + 3000);
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

    private File prepareModel(String asset) throws IOException {
        File dst = new File(getFilesDir(), asset);
        File mark = new File(getFilesDir(), asset + ".ok-" + MODEL_VER);
        if (mark.exists() && dst.isDirectory()) return dst;
        String[] top = getAssets().list(asset);
        if (top == null || top.length == 0) return null;
        note("Preparing Hey Partner (one time)");
        deleteTree(dst);
        copyAsset(asset, dst);
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

    private AcousticEchoCanceler aec;

    private AudioRecord openRecorder() {
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, 32000);
        int[] sources = { MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC };
        for (int src : sources) {
            try {
                AudioRecord r = new AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    // cancels the phone's own speaker (reels, music) out of what the mic hears
                    try {
                        if (AcousticEchoCanceler.isAvailable()) {
                            aec = AcousticEchoCanceler.create(r.getAudioSessionId());
                            if (aec != null) aec.setEnabled(true);
                        }
                    } catch (Throwable t) {
                        aec = null;
                    }
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

    private void closeRecorder(AudioRecord r) {
        try {
            if (aec != null) aec.release();
        } catch (Throwable t) {
            // ignore
        }
        aec = null;
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
        List<Model> models = new ArrayList<>();
        List<Recognizer> recs = new ArrayList<>();
        AudioRecord ar = null;
        try {
            state = "loading";
            for (String a : ASSETS) {
                File dir = prepareModel(a);
                if (dir == null) continue;
                try {
                    Model m = new Model(dir.getAbsolutePath());
                    models.add(m);
                    Recognizer r = new Recognizer(m, 16000.0f, grammar());
                    try {
                        // word scores and timing, used to reject weak matches (called by name so a missing method cannot break the build)
                        Recognizer.class.getMethod("setWords", boolean.class).invoke(r, true);
                    } catch (Throwable t) {
                        // scores unavailable, the other checks still apply
                    }
                    recs.add(r);
                } catch (Throwable t) {
                    if (recs.isEmpty()) throw new IllegalStateException(String.valueOf(t));
                }
            }
            if (recs.isEmpty()) {
                err = "This build has no wake-word model. Run the build again.";
                note("Hey Partner needs a new build");
                return;
            }
            modelCount = recs.size();
            note("Listening for Hey Partner");

            final int CH = 1600; // 100 ms of audio
            final int PRE = 5;   // keep the last 0.5 s so the start of the phrase is not lost
            short[] buf = new short[CH];
            short[][] pre = new short[PRE][CH];
            int[] preLen = new int[PRE];
            int preHead = 0, preN = 0;
            boolean active = false, noisy = false;
            int hangLeft = 0, activeChunks = 0, quietRun = 0;
            double floor = 40;
            long nextCheck = 0;
            boolean mediaOn = false;
            boolean onBreak = false;
            int level = 0;
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            while (!stopFlag) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextCheck) {
                    nextCheck = now + 1000;
                    level = sensitivity(this);
                    // a planned break: the microphone stays closed until it ends
                    boolean b = NightRule.breakNow(this, System.currentTimeMillis());
                    if (b != onBreak) {
                        onBreak = b;
                        note(b ? "Break mode: not listening" : "Listening for Hey Partner");
                    }
                    try {
                        mediaOn = am != null && am.isMusicActive();
                    } catch (Exception e) {
                        mediaOn = false;
                    }
                }
                boolean appBusy = appVisible || now < pauseUntil || onBreak;
                // On "careful" it also stays quiet while a video or music is playing
                if (appBusy || (level == 0 && mediaOn)) {
                    if (ar != null) {
                        closeRecorder(ar);
                        ar = null;
                    }
                    active = false;
                    noisy = false;
                    preN = 0;
                    activeChunks = 0;
                    state = onBreak ? "break" : appBusy ? "paused" : "media";
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
                    noisy = false;
                    preN = 0;
                    activeChunks = 0;
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

                double lv = rms(buf, n);
                // The bar for "someone is talking" follows the room's background level, but never climbs
                // so high that normal speech cannot pass it.
                boolean voiced = lv > Math.max(90.0, Math.min(floor * 2.5, 1200.0));
                if (!voiced && !active) floor = Math.min(500, Math.max(20, floor * 0.97 + lv * 0.03));
                // Steady loud sound (fan, AC, TV, traffic): learn it as background, slowly, so Partner
                // does not stay deaf for as long as it lasts.
                else if (noisy) floor = Math.min(500, floor * 0.995 + lv * 0.005);

                // Non-stop sound (a video, music, a TV, a crowd) is not someone saying the phrase.
                // Ignore it completely until it goes quiet for a second.
                if (noisy) {
                    if (voiced) quietRun = 0;
                    else if (++quietRun >= 10) {
                        noisy = false;
                        quietRun = 0;
                        preN = 0;
                    }
                    continue;
                }

                if (voiced) hangLeft = 8; // wait 0.8 s of quiet after the voice stops

                if (!active) {
                    if (!voiced) {
                        System.arraycopy(buf, 0, pre[preHead], 0, n);
                        preLen[preHead] = n;
                        preHead = (preHead + 1) % PRE;
                        if (preN < PRE) preN++;
                        continue;
                    }
                    active = true;
                    activeChunks = 0;
                    boolean hit = false;
                    for (int k = 0; k < preN && !hit; k++) {
                        int idx = ((preHead - preN + k) % PRE + PRE) % PRE;
                        hit = feed(recs, pre[idx], preLen[idx], level);
                    }
                    if (!hit) hit = feed(recs, buf, n, level);
                    preN = 0;
                    if (hit) {
                        active = false;
                        flush(recs);
                    }
                } else {
                    activeChunks++;
                    if (activeChunks > 50) { // more than 5 s of sound in a row
                        active = false;
                        noisy = true;
                        quietRun = 0;
                        preN = 0;
                        flush(recs);
                        continue;
                    }
                    boolean hit = feed(recs, buf, n, level);
                    if (hit) {
                        active = false;
                        flush(recs);
                    } else if (!voiced && --hangLeft <= 0) {
                        active = false;
                        if (finish(recs, level)) flush(recs);
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
            for (Recognizer r : recs) { try { r.close(); } catch (Throwable t) { } }
            for (Model m : models) { try { m.close(); } catch (Throwable t) { } }
            running = false;
            if (!stopFlag) {
                state = "stopped";
                h.post(() -> stopSelf());
            }
        }
    }

    private static String grammar() {
        StringBuilder g = new StringBuilder("[");
        for (String p : PHRASES) g.append('"').append(p).append("\", ");
        for (String p : LONE) g.append('"').append(p).append("\", ");
        g.append("\"[unk]\"]");
        return g.toString();
    }

    private static void flush(List<Recognizer> recs) {
        for (Recognizer r : recs) {
            try { r.getFinalResult(); } catch (Throwable t) { }
        }
    }

    static final int NO = 0, WEAK = 1, STRONG = 2;

    /** How sure the phone must be before it wakes at once (careful, balanced, easy). */
    private static double needStrong(int level) {
        return level == 0 ? 0.80 : (level == 1 ? 0.60 : 0.45);
    }

    /** Below this a hit is ignored. Between this and the line above it only counts if you say it twice. */
    private static double needWeak(int level) {
        return level == 0 ? 2.0 : (level == 1 ? 0.35 : 0.25); // careful: never
    }

    /**
     * Looks at one finished piece of speech. STRONG wakes Partner. WEAK means it probably heard
     * "Hey Partner" but is not sure (an accent, a noisy room): two of those within a few seconds
     * also wake it, because people repeat themselves when nothing happens, and noise does not.
     */
    private int judge(String json, int level) {
        try {
            JSONObject o = new JSONObject(json);
            String text = o.optString("text", "").trim();
            if (text.isEmpty()) return NO;
            String core = text;
            if (level == 2) {
                // Easy also accepts the phrase with other speech after it
                while (core.endsWith(" [unk]")) core = core.substring(0, core.length() - 6).trim();
            }
            boolean full = WAKE.contains(core);
            boolean lone = level == 2 && LONE_WAKE.contains(core);
            if (!full && !lone) {
                if (text.contains("partner")) heard = "partner, with other sounds around it";
                else if (LONE_WAKE.contains(text)) heard = text + " (say the full Hey Partner)";
                else heard = text.contains("[unk]") ? "something else" : text;
                return NO;
            }
            double min = 1.0, sum = 0, start = -1, end = 0;
            int cnt = 0;
            JSONArray w = o.optJSONArray("result");
            if (w != null) {
                for (int k = 0; k < w.length(); k++) {
                    JSONObject x = w.getJSONObject(k);
                    double c = x.optDouble("conf", 1.0);
                    min = Math.min(min, c);
                    sum += c;
                    cnt++;
                    if (k == 0) start = x.optDouble("start", 0);
                    end = x.optDouble("end", 0);
                }
            }
            // one weak word should not sink the phrase on its own: blend the worst word with the average
            double score = cnt == 0 ? 1.0 : 0.5 * min + 0.5 * (sum / cnt);
            double dur = end - start;
            boolean durOk = w == null || cnt == 0 || (full ? (dur >= 0.3 && dur <= 2.6) : (dur >= 0.1 && dur <= 0.9));
            int pct = (int) Math.round(score * 100);
            String name = full ? "hey partner " : text + " ";
            if (!durOk) {
                heard = name + pct + "% (" + (dur < 0.3 ? "too short" : "too slow") + ")";
                return NO;
            }
            double strong = lone ? 0.75 : needStrong(level);
            if (score >= strong) {
                heard = name + pct + "%";
                return STRONG;
            }
            if (full && score >= needWeak(level)) {
                heard = name + pct + "% (say it once more)";
                return WEAK;
            }
            heard = name + pct + "% (too low, needs " + (int) Math.round(strong * 100) + "%)";
            return NO;
        } catch (Exception e) {
            return NO;
        }
    }

    /** Feeds one chunk to every speech model. Returns true when a model finished hearing "Hey Partner". */
    private boolean feed(List<Recognizer> recs, short[] b, int n, int level) {
        int best = NO;
        for (Recognizer r : recs) {
            if (r.acceptWaveForm(b, n)) best = Math.max(best, judge(r.getResult(), level));
        }
        return decide(best);
    }

    /** The speech ended without the models closing the phrase themselves: ask for what they have. */
    private boolean finish(List<Recognizer> recs, int level) {
        int best = NO;
        for (Recognizer r : recs) {
            best = Math.max(best, judge(r.getFinalResult(), level));
        }
        return decide(best);
    }

    private boolean decide(int best) {
        if (best == STRONG) return fire();
        if (best == WEAK) {
            long now = SystemClock.elapsedRealtime();
            if (lastWeak != 0 && now - lastWeak >= 1200 && now - lastWeak <= 10000) {
                lastWeak = 0;
                heard = heard.replace(" (say it once more)", "") + " (said twice, so it woke)";
                return fire();
            }
            lastWeak = now;
        }
        return false;
    }

    private boolean fire() {
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
