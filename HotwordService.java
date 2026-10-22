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
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/**
 * Listens for "Hey Partner" while the phone is locked or in your pocket. The speech model runs on the
 * phone, no audio is sent anywhere. When the phrase is heard it opens Partner in talk mode.
 *
 * How a wake-up is decided (every step must pass):
 *  1. The microphone must carry speech-like sound (not silence, not a non-stop video or crowd).
 *  2. A listener that only knows "hey partner" and a few look-alike phrases must hear exactly "hey partner".
 *  3. A second listener that knows every English word replays the same sound and must also hear
 *     something that sounds like "hey partner". Noise, "hey" on its own, and other phrases fail here.
 */
public class HotwordService extends Service {

    static final String PREF = "partner_hotword";
    static final String ASSET_DIR = "vosk-model";
    // US English model (required) and Indian English model (optional, hears Indian accents better).
    // Both listen at the same time and either one can wake Partner.
    static final String[] ASSETS = { "vosk-model", "vosk-model-in" };
    static final String MODEL_VER = "v2";
    // Only ONE spelling per sound. "hey" and "hay" sound exactly the same, so listing both made the model
    // split its score in half and every real "Hey Partner" showed up as 50% (not sure enough).
    static final String[] PHRASES = { "hey partner", "hi partner", "hey pardner", "hi pardner" };
    // Look-alikes that must NOT wake Partner. They give the model something else to choose, so noise,
    // "hey" on its own and ordinary phrases are no longer forced into "hey partner".
    static final String[] DECOYS = {
        "hey", "hi", "partner", "hey party", "hey part", "hey parker", "hey park", "hey pardon",
        "hey there", "hey google", "hey siri", "ok google", "hello", "okay", "a partner", "my partner",
        "hey buddy", "hey man", "hey bro", "hey dude"
    };
    static final Set<String> WAKE = new HashSet<>(Arrays.asList(PHRASES));
    // Word scores from the phrase-only listener are not reliable, so this is only a floor against junk.
    static final double CONF_FLOOR = 0.30;
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
    private static volatile long testUntil = 0;
    private static volatile int lastPct = 0;

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

    /** 0 = careful (default), 1 = balanced, 2 = easy */
    static int sensitivity(Context c) {
        return c.getSharedPreferences(PREF, 0).getInt("sens", 0);
    }

    static void setSensitivity(Context c, int level) {
        c.getSharedPreferences(PREF, 0).edit().putInt("sens", Math.max(0, Math.min(2, level))).apply();
    }

    /** Test mode: for 30 seconds it listens even while Partner is open, and shows what it heard instead of opening the app. */
    static boolean testing() {
        return SystemClock.elapsedRealtime() < testUntil;
    }

    static void setTesting(boolean on) {
        if (on) {
            heard = "";
            pauseUntil = 0;
            lastFire = 0;
            testUntil = SystemClock.elapsedRealtime() + 30000;
        } else {
            testUntil = 0;
        }
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

    /* ---------- the two listeners ---------- */

    /** One speech model with its two listeners. */
    private static final class Ear {
        final Model model;
        final Recognizer wake; // only knows the phrase, the look-alikes and [unk]
        final Recognizer open; // knows every word, used as the second opinion (may be null)

        Ear(Model m, Recognizer w, Recognizer o) {
            model = m;
            wake = w;
            open = o;
        }

        void close() {
            try { if (wake != null) wake.close(); } catch (Throwable t) { }
            try { if (open != null) open.close(); } catch (Throwable t) { }
            try { if (model != null) model.close(); } catch (Throwable t) { }
        }
    }

    private static final int NONE = 0, ENDED = 1, CAND = 2;
    private final List<Ear> ears = new ArrayList<>();
    // the sound of the current burst of speech, kept so the second listener can replay it
    private final short[] burst = new short[16000 * 7];
    private int burstLen = 0, utterFrom = 0;

    private void loop() {
        AudioRecord ar = null;
        try {
            state = "loading";
            String loadErr = "";
            for (String a : ASSETS) {
                File dir = prepareModel(a);
                if (dir == null) continue;
                Model m = null;
                Recognizer w = null, o = null;
                try {
                    m = new Model(dir.getAbsolutePath());
                    w = new Recognizer(m, 16000.0f, grammar());
                    try {
                        // word scores and timing (called by name so a missing method cannot break the build)
                        Recognizer.class.getMethod("setWords", boolean.class).invoke(w, true);
                    } catch (Throwable t) {
                        // timing unavailable, the other checks still apply
                    }
                    try {
                        o = new Recognizer(m, 16000.0f);
                    } catch (Throwable t) {
                        o = null; // no second opinion from this model
                    }
                    ears.add(new Ear(m, w, o));
                } catch (Throwable t) {
                    loadErr = String.valueOf(t);
                    try { if (w != null) w.close(); } catch (Throwable x) { }
                    try { if (o != null) o.close(); } catch (Throwable x) { }
                    try { if (m != null) m.close(); } catch (Throwable x) { }
                }
            }
            if (ears.isEmpty()) {
                err = loadErr.isEmpty() ? "This build has no wake-word model. Run the build again." : "Wake-word model failed to load: " + loadErr;
                note("Hey Partner needs a new build");
                return;
            }
            modelCount = ears.size();
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
            int level = 0;
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            while (!stopFlag) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextCheck) {
                    nextCheck = now + 1000;
                    level = sensitivity(this);
                    try {
                        mediaOn = am != null && am.isMusicActive();
                    } catch (Exception e) {
                        mediaOn = false;
                    }
                }
                boolean appBusy = (appVisible && !testing()) || now < pauseUntil;
                // On "careful" it also stays quiet while a video or music is playing
                if (appBusy || (level == 0 && mediaOn)) {
                    if (ar != null) {
                        closeRecorder(ar);
                        ar = null;
                        flushWake();
                    }
                    active = false;
                    noisy = false;
                    preN = 0;
                    activeChunks = 0;
                    state = appBusy ? "paused" : "media";
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
                        flushWake();
                        Thread.sleep(500);
                    }
                    continue;
                }

                double lv = rms(buf, n);
                boolean voiced = lv > Math.max(90.0, floor * 2.5);
                if (!voiced && !active && !noisy) floor = Math.min(1500, Math.max(20, floor * 0.97 + lv * 0.03));

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
                    burstLen = 0;
                    utterFrom = 0;
                    boolean hit = false;
                    for (int k = 0; k < preN && !hit; k++) {
                        int idx = ((preHead - preN + k) % PRE + PRE) % PRE;
                        hit = take(pre[idx], preLen[idx], level);
                    }
                    if (!hit) hit = take(buf, n, level);
                    preN = 0;
                    if (hit) {
                        active = false;
                        flushWake();
                    }
                } else {
                    activeChunks++;
                    if (activeChunks > 50) { // more than 5 s of sound in a row
                        active = false;
                        noisy = true;
                        quietRun = 0;
                        preN = 0;
                        flushWake();
                        continue;
                    }
                    boolean hit = take(buf, n, level);
                    if (hit) {
                        active = false;
                        flushWake();
                    } else if (!voiced && --hangLeft <= 0) {
                        active = false;
                        finishBurst(level);
                        flushWake();
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
            for (Ear e : ears) e.close();
            ears.clear();
            running = false;
            if (!stopFlag) {
                state = "stopped";
                h.post(() -> stopSelf());
            }
        }
    }

    /** The phrase, then the look-alikes, then [unk] for anything else. Only the phrases wake Partner. */
    private static String grammar() {
        StringBuilder g = new StringBuilder("[");
        for (String p : PHRASES) g.append('"').append(p).append("\", ");
        for (String p : DECOYS) g.append('"').append(p).append("\", ");
        g.append("\"[unk]\"]");
        return g.toString();
    }

    private void flushWake() {
        for (Ear e : ears) {
            try { e.wake.getFinalResult(); } catch (Throwable t) { }
        }
    }

    /* ---------- judging ---------- */

    private static String describe(String text) {
        String t = text.replace("[unk]", "...").trim();
        if (t.equals("...")) return "other sounds, not the phrase";
        return "\"" + t + "\", not the phrase";
    }

    /**
     * Looks at one result of the phrase-only listener. True only for a clean "hey partner" (or on Easy,
     * "hey partner" followed by other speech) of a believable length.
     */
    private boolean judge(String json, int level) {
        try {
            JSONObject o = new JSONObject(json);
            String text = o.optString("text", "").trim();
            if (text.isEmpty()) return false;
            String core = text;
            if (level == 2) {
                while (core.endsWith(" [unk]")) core = core.substring(0, core.length() - 6).trim();
            }
            if (!WAKE.contains(core)) {
                heard = describe(text);
                return false;
            }
            double min = 1.0, start = -1, end = 0;
            int words = 0;
            JSONArray w = o.optJSONArray("result");
            if (w != null) {
                for (int k = 0; k < w.length(); k++) {
                    JSONObject x = w.getJSONObject(k);
                    if ("[unk]".equals(x.optString("word", ""))) continue;
                    min = Math.min(min, x.optDouble("conf", 1.0));
                    if (words == 0) start = x.optDouble("start", 0);
                    end = x.optDouble("end", 0);
                    words++;
                }
            }
            int pct = (int) Math.round(min * 100);
            double dur = end - start;
            if (min < CONF_FLOOR) {
                heard = "hey partner " + pct + "%, too unclear";
                return false;
            }
            if (words > 0 && (dur < 0.25 || dur > 2.2)) {
                heard = "hey partner, but too " + (dur < 0.25 ? "short" : "long");
                return false;
            }
            lastPct = pct;
            heard = "hey partner " + pct + "%";
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String field(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** Edit distance between two short strings. */
    static int lev(String a, String b) {
        int[] p = new int[b.length() + 1], c = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) p[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            c[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                c[j] = Math.min(Math.min(c[j - 1] + 1, p[j] + 1), p[j - 1] + cost);
            }
            int[] t = p;
            p = c;
            c = t;
        }
        return p[b.length()];
    }

    /**
     * Does what the all-words listener heard sound like "hey partner"? Spacing and small spelling
     * differences are forgiven ("hey pardner", "hey part her"). Empty text (noise) or other words fail.
     * Careful and Balanced need the whole sound to be the phrase, Easy also accepts more words after it.
     */
    static boolean soundsRight(String said, int level) {
        String clean = said == null ? "" : said.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").trim();
        if (clean.isEmpty()) return false;
        String[] w = clean.split("\\s+");
        if (w.length > 4 && level != 2) return false;
        int tol = level == 2 ? 3 : 2;
        int first = level == 2 ? 1 : w.length;
        StringBuilder j = new StringBuilder();
        for (int k = 0; k < Math.min(w.length, 4); k++) {
            j.append(w[k]);
            if (k + 1 < first) continue;
            String s = j.toString();
            if (lev(s, "heypartner") <= tol || lev(s, "hipartner") <= tol) return true;
        }
        return false;
    }

    /** Replays a piece of the recorded sound through the all-words listener and returns what it heard. */
    private static String replay(Recognizer r, short[] a, int from, int to) {
        StringBuilder sb = new StringBuilder();
        short[] tmp = new short[3200];
        for (int p = from; p < to; p += 3200) {
            int n = Math.min(3200, to - p);
            System.arraycopy(a, p, tmp, 0, n);
            if (r.acceptWaveForm(tmp, n)) sb.append(' ').append(field(r.getResult(), "text"));
        }
        sb.append(' ').append(field(r.getFinalResult(), "text"));
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    /** Second opinion. True when an all-words listener also hears something like "hey partner". */
    private boolean confirm(int level) {
        int to = burstLen;
        int from = Math.max(utterFrom, to - 16000 * 3);
        if (to - from < 4800) from = Math.max(0, to - 16000); // never replay less than a fraction of a second
        String last = "";
        boolean asked = false;
        for (Ear e : ears) {
            if (e.open == null) continue;
            asked = true;
            String said;
            try {
                said = replay(e.open, burst, from, to);
            } catch (Throwable t) {
                continue;
            }
            if (soundsRight(said, level)) {
                heard = "hey partner " + lastPct + "%, confirmed";
                return true;
            }
            last = said;
        }
        if (!asked) return true; // no second listener in this build, the other checks still applied
        heard = "hey partner " + lastPct + "%, but the second check heard " + (last.isEmpty() ? "only noise" : "\"" + last + "\"");
        return false;
    }

    /** Feeds one chunk to the phrase-only listeners. Returns true when Partner should wake now. */
    private boolean take(short[] b, int n, int level) {
        if (burstLen + n <= burst.length) {
            System.arraycopy(b, 0, burst, burstLen, n);
            burstLen += n;
        }
        int res = NONE;
        for (Ear e : ears) {
            if (e.wake.acceptWaveForm(b, n)) {
                if (judge(e.wake.getResult(), level)) res = CAND;
                else if (res == NONE) res = ENDED;
            }
        }
        if (res == NONE) return false;
        boolean go = false;
        if (res == CAND) {
            go = confirm(level);
            flushWake();
        }
        utterFrom = burstLen; // whatever came before this point is a finished phrase
        return go && fire();
    }

    /** The speech ended without the listeners closing the phrase themselves: ask for what they have. */
    private boolean finishBurst(int level) {
        boolean cand = false;
        for (Ear e : ears) {
            if (judge(e.wake.getFinalResult(), level)) cand = true;
        }
        return cand && confirm(level) && fire();
    }

    private boolean fire() {
        long now = SystemClock.elapsedRealtime();
        boolean test = testing();
        if (now - lastFire < (test ? 1500 : 6000)) return false;
        lastFire = now;
        if (test) {
            // test mode: do not open the app, just show that it worked
            heard = "hey partner " + lastPct + "%, WORKS. It would open Partner now";
            h.post(() -> buzz(this));
            return true;
        }
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
