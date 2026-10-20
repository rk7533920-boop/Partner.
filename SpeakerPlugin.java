package com.partner.diary;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import android.os.Build;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

@CapacitorPlugin(name = "Speaker")
public class SpeakerPlugin extends Plugin {
    private static final String PREFS = "partner_speaker";
    private static final String KEY = "ids";

    static int immutable() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    /** Sets one alarm that will make SpeakService talk at time `at` (epoch ms). */
    static void set(Context c, int id, long at, String title, String body, String text, String lang, boolean repeat, String ak) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, SpeakReceiver.class);
        i.putExtra("id", id);
        i.putExtra("at", at);
        i.putExtra("title", title);
        i.putExtra("body", body);
        i.putExtra("text", text);
        i.putExtra("lang", lang);
        i.putExtra("repeat", repeat);
        i.putExtra("ak", ak == null ? "" : ak);
        PendingIntent pi = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancelAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Set<String> ids = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, new HashSet<String>());
        for (String s : ids) {
            try {
                PendingIntent pi = PendingIntent.getBroadcast(c, Integer.parseInt(s),
                        new Intent(c, SpeakReceiver.class), PendingIntent.FLAG_NO_CREATE | immutable());
                if (pi != null) {
                    am.cancel(pi);
                    pi.cancel();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Called after phone restart: puts the saved alarms back. */
    static void restore(Context c) {
        try {
            String raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", "[]");
            JSONArray a = new JSONArray(raw);
            long now = System.currentTimeMillis();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.getJSONObject(k);
                long at = o.getLong("at");
                boolean rep = o.optBoolean("repeat", false);
                if (at <= now) {
                    if (!rep) continue;
                    while (at <= now) at += 86400000L;
                }
                set(c, o.getInt("id"), at, o.optString("title", "Partner"), o.optString("body", ""),
                        o.optString("text", ""), o.optString("lang", "hi-IN"), rep, o.optString("ak", ""));
            }
        } catch (Exception ignored) {
        }
    }

    /** items: [{id, at, title, body, text, lang, repeat}]. Replaces every earlier alarm. Empty list = cancel all. */
    @PluginMethod
    public void schedule(PluginCall call) {
        Context c = getContext();
        cancelAll(c);
        Set<String> ids = new HashSet<>();
        Set<String> keep = new HashSet<>();
        JSArray items = call.getArray("items");
        try {
            if (items != null) {
                for (int k = 0; k < items.length(); k++) {
                    JSONObject o = items.getJSONObject(k);
                    String ak0 = o.optString("ak", ""), au = o.optString("audio", "");
                    if (!ak0.isEmpty() && !au.isEmpty()) writeAudio(c, ak0, au);
                    o.remove("audio");
                    if (!ak0.isEmpty()) keep.add("spk_" + ak0 + ".mp3");
                    int id = o.getInt("id");
                    set(c, id, o.getLong("at"), o.optString("title", "Partner"), o.optString("body", ""),
                            o.optString("text", ""), o.optString("lang", "hi-IN"), o.optBoolean("repeat", false), o.optString("ak", ""));
                    ids.add(String.valueOf(id));
                }
            }
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
            return;
        }
        File[] old = c.getCacheDir().listFiles();
        if (old != null) for (File f : old) if (f.getName().startsWith("spk_") && !keep.contains(f.getName())) f.delete();
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY, ids)
                .putString("items", items == null ? "[]" : items.toString())
                .apply();
        call.resolve();
    }

    static void writeAudio(Context c, String ak, String b64) {
        try {
            FileOutputStream f = new FileOutputStream(new File(c.getCacheDir(), "spk_" + ak + ".mp3"));
            f.write(Base64.decode(b64, Base64.DEFAULT));
            f.close();
        } catch (Exception ignored) {
        }
    }

    /** keys: [ak]. Tells JS which prepared voice files are already on the phone. */
    @PluginMethod
    public void hasAudio(PluginCall call) {
        JSArray out = new JSArray();
        try {
            JSArray keys = call.getArray("keys");
            if (keys != null) {
                for (int k = 0; k < keys.length(); k++) {
                    String ak = keys.getString(k);
                    if (new File(getContext().getCacheDir(), "spk_" + ak + ".mp3").exists()) out.put(ak);
                }
            }
        } catch (Exception ignored) {
        }
        JSObject r = new JSObject();
        r.put("have", out);
        call.resolve(r);
    }

    private static MediaPlayer mp;

    static void stopNow() {
        try { if (mp != null) { mp.stop(); mp.release(); } } catch (Exception ignored) { }
        mp = null;
    }

    /** Plays one mp3 (base64) and resolves when it ends. Works with the screen locked. */
    @PluginMethod
    public void play(PluginCall call) {
        try {
            stopNow();
            File f = new File(getContext().getCacheDir(), "play.mp3");
            FileOutputStream o = new FileOutputStream(f);
            o.write(Base64.decode(call.getString("audio", ""), Base64.DEFAULT));
            o.close();
            MediaPlayer m = new MediaPlayer();
            mp = m;
            m.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            m.setDataSource(f.getAbsolutePath());
            m.setOnCompletionListener(x -> {
                try { x.release(); } catch (Exception ignored) { }
                if (mp == x) mp = null;
                call.resolve();
            });
            m.setOnErrorListener((x, a, b) -> {
                try { x.release(); } catch (Exception ignored) { }
                if (mp == x) mp = null;
                call.reject("play error");
                return true;
            });
            m.setOnPreparedListener(x -> x.start());
            m.prepareAsync();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void stopPlay(PluginCall call) {
        stopNow();
        call.resolve();
    }

    @PluginMethod
    public void canExact(PluginCall call) {
        AlarmManager am = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        JSObject r = new JSObject();
        r.put("value", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms());
        call.resolve(r);
    }

    @PluginMethod
    public void requestExact(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 31) {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        }
        call.resolve();
    }
}
