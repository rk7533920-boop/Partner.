package com.partner.diary;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.Locale;

/** Bridge for the adult-content block and the accountability messages (see Guard). Plugin name: Guard. */
@CapacitorPlugin(
  name = "Guard",
  permissions = {
    @Permission(alias = "sendsms", strings = { Manifest.permission.SEND_SMS }),
    @Permission(alias = "steps", strings = { Manifest.permission.ACTIVITY_RECOGNITION })
  }
)
public class GuardPlugin extends Plugin {

  private boolean accEnabled() {
    if (LockdownService.inst != null) return true;
    String en = Settings.Secure.getString(getContext().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    if (en == null) return false;
    ComponentName cn = new ComponentName(getContext(), LockdownService.class);
    String e = en.toLowerCase(Locale.ROOT);
    return e.contains(cn.flattenToString().toLowerCase(Locale.ROOT)) || e.contains(cn.flattenToShortString().toLowerCase(Locale.ROOT));
  }

  @PluginMethod
  public void status(PluginCall call) {
    Context c = getContext();
    long now = System.currentTimeMillis();
    Guard.applyPending(c, now);
    SharedPreferences p = Guard.sp(c);
    JSObject o = new JSObject();
    o.put("cfg", p.getString(Guard.KEY, ""));
    o.put("pend", p.getString(Guard.PEND, ""));
    o.put("pendAt", p.getLong(Guard.PENDAT, 0));
    o.put("dnsMode", Guard.dnsMode(c));
    o.put("dnsSpec", Guard.dnsSpec(c));
    o.put("dnsWant", Guard.wantHost(c));
    o.put("dnsOk", Guard.dnsOk(c));
    o.put("secure", LockdownAcc.granted(c));
    o.put("sms", Guard.smsPerm(c));
    o.put("steps", WalkSteps.permission(c));
    o.put("accOn", accEnabled());
    o.put("running", LockdownService.inst != null);
    o.put("queue", Guard.queueLen(c));
    o.put("hits", p.getString("gdHits", "[]"));
    o.put("lastOk", p.getLong("gdLastOk", 0));
    o.put("lastFail", p.getLong("gdLastFail", 0));
    o.put("dnsOffAt", p.getLong("gdDnsOffAt", 0));
    o.put("used", p.getInt("gUsedDay", 0) == Guard.dayKey(now) ? p.getLong("gUsed", 0) : 0);
    call.resolve(o);
  }

  /** Saves the settings and a waiting weaker change (the page decides what is weaker). */
  @PluginMethod
  public void set(PluginCall call) {
    Context c = getContext();
    String cfg = call.getString("cfg", "");
    String pend = call.getString("pend", "");
    Double pa = call.getDouble("pendAt");
    Guard.sp(c).edit().putString(Guard.KEY, cfg == null ? "" : cfg).putString(Guard.PEND, pend == null ? "" : pend).putLong(Guard.PENDAT, pa == null ? 0 : pa.longValue()).apply();
    if (Guard.keepOn(c) && !Guard.dnsOk(c)) Guard.dnsApply(c);
    if (LockdownService.inst != null) LockdownService.inst.poke();
    call.resolve();
  }

  /** Sets the filter DNS by itself (needs the one-time adb permission). Answers whether it is now in place. */
  @PluginMethod
  public void applyDns(PluginCall call) {
    Context c = getContext();
    boolean ok = Guard.dnsApply(c);
    JSObject o = new JSObject();
    o.put("ok", ok);
    o.put("secure", LockdownAcc.granted(c));
    o.put("dnsOk", Guard.dnsOk(c));
    call.resolve(o);
  }

  @PluginMethod
  public void openDns(PluginCall call) {
    try {
      Intent i = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      getContext().startActivity(i);
      call.resolve();
    } catch (Exception e) {
      call.reject("Could not open the page");
    }
  }

  /** Sends one test message right now to a contact, the same way real messages go. */
  @PluginMethod
  public void test(PluginCall call) {
    Context c = getContext();
    String num = call.getString("num", ""), via = call.getString("via", "wa"), text = call.getString("text", "Test message from Partner.");
    if (Guard.digits(num).length() < 8) {
      call.reject("That number looks wrong");
      return;
    }
    if ("sms".equals(via) && !Guard.smsPerm(c)) {
      call.reject("Allow sending SMS first");
      return;
    }
    Guard.enqueue(c, call.getString("n", ""), num, via, Boolean.TRUE.equals(call.getBoolean("fb", true)), text, "test");
    Guard.pump(c);
    if (LockdownService.inst != null) LockdownService.inst.poke();
    else if (!"sms".equals(via)) {
      call.reject("Turn on the accessibility service first, WhatsApp messages are sent through it");
      return;
    }
    call.resolve();
  }

  @PluginMethod
  public void requestSms(PluginCall call) {
    if (getPermissionState("sendsms") == PermissionState.GRANTED) {
      call.resolve();
      return;
    }
    requestPermissionForAlias("sendsms", call, "permDone");
  }

  @PluginMethod
  public void requestSteps(PluginCall call) {
    if (getPermissionState("steps") == PermissionState.GRANTED) {
      call.resolve();
      return;
    }
    requestPermissionForAlias("steps", call, "permDone");
  }

  @PermissionCallback
  private void permDone(PluginCall call) {
    call.resolve();
  }
}
