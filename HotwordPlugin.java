package com.partner.diary;

import android.Manifest;
import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Hotword")
public class HotwordPlugin extends Plugin {

    @PluginMethod
    public void setEnabled(PluginCall call) {
        Context c = getContext();
        boolean on = Boolean.TRUE.equals(call.getBoolean("enabled"));
        if (on) {
            if (Build.VERSION.SDK_INT >= 23 && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                call.reject("Microphone permission is off");
                return;
            }
            HotwordService.setWanted(c, true);
            if (!HotwordService.running && !HotwordService.start(c)) {
                call.reject(HotwordService.err.isEmpty() ? "Android did not allow the listener to start" : HotwordService.err);
                return;
            }
        } else {
            HotwordService.setWanted(c, false);
            HotwordService.stop(c);
        }
        call.resolve();
    }

    @PluginMethod
    public void setSensitivity(PluginCall call) {
        Integer v = call.getInt("level");
        HotwordService.setSensitivity(getContext(), v == null ? 0 : v);
        call.resolve();
    }

    /** Test mode: for 30 seconds it listens even while Partner is open and only shows what it heard. */
    @PluginMethod
    public void setTesting(PluginCall call) {
        HotwordService.setTesting(Boolean.TRUE.equals(call.getBoolean("on")));
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        Context c = getContext();
        JSObject r = new JSObject();
        r.put("wanted", HotwordService.wanted(c));
        r.put("running", HotwordService.running);
        r.put("state", HotwordService.state);
        r.put("error", HotwordService.err);
        r.put("model", HotwordService.hasModel(c));
        r.put("heard", HotwordService.heard);
        r.put("models", HotwordService.modelCount);
        r.put("sens", HotwordService.sensitivity(c));
        r.put("testing", HotwordService.testing());
        r.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c));
        boolean battery = true;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                battery = ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(c.getPackageName());
            }
        } catch (Exception e) {
            // unknown, do not nag
        }
        r.put("battery", battery);
        boolean fsi = true;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                fsi = ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).canUseFullScreenIntent();
            }
        } catch (Exception e) {
            // unknown, do not nag
        }
        r.put("fsi", fsi);
        boolean assistant = false;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = (RoleManager) c.getSystemService(Context.ROLE_SERVICE);
                assistant = rm != null && rm.isRoleHeld("android.app.role.ASSISTANT");
            }
        } catch (Exception e) {
            // unknown
        }
        r.put("assistant", assistant);
        call.resolve(r);
    }

    private void open(PluginCall call, Intent i, Intent fallback) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(i);
            call.resolve();
            return;
        } catch (Exception e) {
            // try the fallback
        }
        if (fallback != null) {
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                getContext().startActivity(fallback);
                call.resolve();
                return;
            } catch (Exception e) {
                // give up below
            }
        }
        call.reject("This Android page could not be opened");
    }

    @PluginMethod
    public void requestOverlay(PluginCall call) {
        Uri u = Uri.parse("package:" + getContext().getPackageName());
        open(call, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, u), new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, u));
    }

    @PluginMethod
    public void requestBattery(PluginCall call) {
        Uri u = Uri.parse("package:" + getContext().getPackageName());
        open(call, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, u), new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    @PluginMethod
    public void requestFullScreen(PluginCall call) {
        Uri u = Uri.parse("package:" + getContext().getPackageName());
        open(call, new Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", u), new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, u));
    }

    /** Opens Android's "Digital assistant app" page, where Partner should now be in the list. */
    @PluginMethod
    public void openAssistantSettings(PluginCall call) {
        open(call, new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
    }
}
