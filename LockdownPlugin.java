package com.partner.diary;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@CapacitorPlugin(name = "Lockdown")
public class LockdownPlugin extends Plugin {

  static SharedPreferences sp(Context c) {
    return c.getSharedPreferences("partner_lock", Context.MODE_PRIVATE);
  }

  private boolean locked() {
    return sp(getContext()).getLong("until", 0) > System.currentTimeMillis();
  }

  private boolean accOn() {
    if (LockdownService.inst != null) return true;
    String en = Settings.Secure.getString(getContext().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    if (en == null) return false;
    ComponentName cn = new ComponentName(getContext(), LockdownService.class);
    String a = cn.flattenToString().toLowerCase(Locale.ROOT), b = cn.flattenToShortString().toLowerCase(Locale.ROOT);
    String e = en.toLowerCase(Locale.ROOT);
    return e.contains(a) || e.contains(b);
  }

  private ComponentName admin() {
    return new ComponentName(getContext(), PartnerAdminReceiver.class);
  }

  @PluginMethod
  public void status(PluginCall call) {
    LockdownAcc.checkBank(getContext());
    SharedPreferences p = sp(getContext());
    DevicePolicyManager dpm = (DevicePolicyManager) getContext().getSystemService(Context.DEVICE_POLICY_SERVICE);
    JSObject o = new JSObject();
    o.put("accessibility", accOn());
    o.put("granted", LockdownAcc.granted(getContext()));
    o.put("bankUntil", p.getLong("bankUntil", 0));
    o.put("admin", dpm != null && dpm.isAdminActive(admin()));
    o.put("locked", locked());
    o.put("until", p.getLong("until", 0));
    o.put("reason", p.getString("reason", ""));
    o.put("exitAt", p.getLong("exitAt", 0));
    o.put("cfg", p.getString("cfg", ""));
    o.put("pend", p.getString("pend", ""));
    o.put("pendAt", p.getLong("pendAt", 0));
    call.resolve(o);
  }

  @PluginMethod
  public void setConfig(PluginCall call) {
    if (locked()) {
      call.reject("Lockdown is active");
      return;
    }
    Double pa = call.getDouble("pendAt");
    sp(getContext())
      .edit()
      .putString("cfg", call.getString("cfg", ""))
      .putString("pend", call.getString("pend", ""))
      .putLong("pendAt", pa == null ? 0 : pa.longValue())
      .apply();
    call.resolve();
  }

  @PluginMethod
  public void start(PluginCall call) {
    Integer m = call.getInt("minutes", 0);
    if (m == null || m < 1 || m > 600) {
      call.reject("Bad length");
      return;
    }
    if (!accOn()) {
      if (LockdownAcc.granted(getContext())) {
        LockdownAcc.endBank(getContext()); // switches the service back on, it connects within a second
      } else {
        call.reject("Turn on the accessibility service first");
        return;
      }
    }
    SharedPreferences p = sp(getContext());
    long now = System.currentTimeMillis();
    long until = Math.max(now + m * 60000L, p.getLong("until", 0));
    p.edit().putLong("until", until).putString("reason", call.getString("reason", "Lockdown")).putLong("exitAt", 0).apply();
    if (LockdownService.inst != null) LockdownService.inst.poke();
    call.resolve();
  }

  @PluginMethod
  public void secureStatus(PluginCall call) {
    LockdownAcc.checkBank(getContext());
    SharedPreferences p = sp(getContext());
    JSObject o = new JSObject();
    o.put("granted", LockdownAcc.granted(getContext()));
    o.put("accessibility", accOn());
    o.put("bankUntil", p.getLong("bankUntil", 0));
    o.put("smart", p.getBoolean("smartbank", false));
    o.put("bankMin", p.getInt("bankmin", 10));
    o.put("bankPkgs", p.getString("bankpkgs", ""));
    o.put("locked", locked());
    call.resolve(o);
  }

  @PluginMethod
  public void setBankConfig(PluginCall call) {
    if (locked()) {
      call.reject("Lockdown is active");
      return;
    }
    Integer m = call.getInt("minutes", 10);
    int mm = m == null ? 10 : Math.max(1, Math.min(60, m));
    Boolean smart = call.getBoolean("smart", false);
    sp(getContext())
      .edit()
      .putBoolean("smartbank", smart != null && smart)
      .putInt("bankmin", mm)
      .putString("bankpkgs", call.getString("pkgs", ""))
      .apply();
    call.resolve();
  }

  @PluginMethod
  public void bankMode(PluginCall call) {
    if (locked()) {
      call.reject("Lockdown is active, so the accessibility service cannot be paused");
      return;
    }
    if (!LockdownAcc.granted(getContext())) {
      call.reject("The one-time adb permission is missing");
      return;
    }
    if (!accOn()) {
      call.reject("The accessibility service is already off");
      return;
    }
    Integer m = call.getInt("minutes", 15);
    if (!LockdownAcc.startBank(getContext(), m == null ? 15 : m)) {
      call.reject("Could not pause the accessibility service");
      return;
    }
    JSObject o = new JSObject();
    o.put("bankUntil", sp(getContext()).getLong("bankUntil", 0));
    call.resolve(o);
  }

  @PluginMethod
  public void bankEnd(PluginCall call) {
    if (!LockdownAcc.granted(getContext())) {
      call.reject("The one-time adb permission is missing");
      return;
    }
    LockdownAcc.endBank(getContext());
    call.resolve();
  }

  @PluginMethod
  public void apps(PluginCall call) {
    PackageManager pm = getContext().getPackageManager();
    Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    List<ResolveInfo> l = pm.queryIntentActivities(main, 0);
    Set<String> seen = new HashSet<>();
    List<String[]> rows = new ArrayList<>();
    for (ResolveInfo r : l) {
      String pkg = r.activityInfo.packageName;
      if (pkg.equals(getContext().getPackageName()) || !seen.add(pkg)) continue;
      rows.add(new String[] { String.valueOf(r.loadLabel(pm)), pkg });
    }
    Collections.sort(rows, (a, b) -> a[0].compareToIgnoreCase(b[0]));
    JSArray arr = new JSArray();
    for (String[] r : rows) {
      JSObject o = new JSObject();
      o.put("name", r[0]);
      o.put("pkg", r[1]);
      arr.put(o);
    }
    JSObject res = new JSObject();
    res.put("apps", arr);
    call.resolve(res);
  }

  @PluginMethod
  public void openAccessibility(PluginCall call) {
    getContext().startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    call.resolve();
  }

  @PluginMethod
  public void requestAdmin(PluginCall call) {
    Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
      .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin())
      .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Keeps Partner installed while a lockdown is running.")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    getContext().startActivity(i);
    call.resolve();
  }
}
