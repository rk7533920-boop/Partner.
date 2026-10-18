package com.partner.diary;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
    static void set(Context c, int id, long at, String title, String body, String text, String lang, boolean repeat) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, SpeakReceiver.class);
        i.putExtra("id", id);
        i.putExtra("at", at);
        i.putExtra("title", title);
        i.putExtra("body", body);
        i.putExtra("text", text);
        i.putExtra("lang", lang);
        i.putExtra("repeat", repeat);
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
                        o.optString("text", ""), o.optString("lang", "hi-IN"), rep);
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
        JSArray items = call.getArray("items");
        try {
            if (items != null) {
                for (int k = 0; k < items.length(); k++) {
                    JSONObject o = items.getJSONObject(k);
                    int id = o.getInt("id");
                    set(c, id, o.getLong("at"), o.optString("title", "Partner"), o.optString("body", ""),
                            o.optString("text", ""), o.optString("lang", "hi-IN"), o.optBoolean("repeat", false));
                    ids.add(String.valueOf(id));
                }
            }
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
            return;
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY, ids)
                .putString("items", items == null ? "[]" : items.toString())
                .apply();
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
