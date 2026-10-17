package com.partner.diary;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;
import android.provider.Settings;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@CapacitorPlugin(name = "UsageStats")
public class UsageStatsPlugin extends Plugin {

    private boolean granted() {
        AppOpsManager ops = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getContext().getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @PluginMethod
    public void hasAccess(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", granted());
        call.resolve(r);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    private String label(PackageManager pm, Map<String, String> cache, String pkg) {
        String l = cache.get(pkg);
        if (l != null) return l;
        try {
            l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            String[] p = pkg.split("\\.");
            l = p[p.length - 1];
        }
        cache.put(pkg, l);
        return l;
    }

    private void add(JSArray out, PackageManager pm, Map<String, String> cache, String p, long s, long e) {
        JSObject o = new JSObject();
        o.put("p", p);
        o.put("a", label(pm, cache, p));
        o.put("s", s);
        o.put("e", e);
        out.put(o);
    }

    @PluginMethod
    public void getSessions(PluginCall call) {
        if (!granted()) {
            call.reject("no_access");
            return;
        }
        Long s = call.getLong("start");
        Long e = call.getLong("end");
        if (s == null || e == null) {
            call.reject("bad_range");
            return;
        }
        UsageStatsManager u = (UsageStatsManager) getContext().getSystemService(Context.USAGE_STATS_SERVICE);
        UsageEvents ev = u.queryEvents(s, e);
        UsageEvents.Event x = new UsageEvents.Event();
        PackageManager pm = getContext().getPackageManager();
        Map<String, Long> open = new HashMap<>();
        Map<String, String> cache = new HashMap<>();
        JSArray out = new JSArray();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(x);
            String p = x.getPackageName();
            int t = x.getEventType();
            long ts = x.getTimeStamp();
            if (t == 1) {
                open.put(p, ts);
            } else if (t == 2 || t == 23) {
                Long st = open.remove(p);
                if (st != null && ts > st) add(out, pm, cache, p, st, ts);
            } else if (t == 16) {
                for (String k : new ArrayList<>(open.keySet())) {
                    Long st = open.get(k);
                    if (st != null && ts > st) add(out, pm, cache, k, st, ts);
                }
                open.clear();
            }
        }
        for (Map.Entry<String, Long> en : open.entrySet()) {
            if (e > en.getValue()) add(out, pm, cache, en.getKey(), en.getValue(), e);
        }
        JSObject r = new JSObject();
        r.put("sessions", out);
        call.resolve(r);
    }
}
