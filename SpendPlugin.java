package com.partner.diary;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.Settings;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Spending and the night call rule need a few things from Android: bank and UPI text messages, payment notifications,
 * the contact list, and the right to end calls. Nothing here sends anything anywhere: the app parses the amount and the shop
 * name on the phone and throws the message text away.
 */
@CapacitorPlugin(
  name = "Spend",
  permissions = {
    @Permission(alias = "sms", strings = { Manifest.permission.READ_SMS }),
    @Permission(
      alias = "calls",
      strings = { Manifest.permission.READ_CONTACTS, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE }
    )
  }
)
public class SpendPlugin extends Plugin {

  private boolean smsOk() {
    return getPermissionState("sms") == PermissionState.GRANTED;
  }

  private boolean callsOk() {
    return getPermissionState("calls") == PermissionState.GRANTED;
  }

  private boolean listenerOn() {
    try {
      String s = Settings.Secure.getString(getContext().getContentResolver(), "enabled_notification_listeners");
      return s != null && s.contains(getContext().getPackageName());
    } catch (Exception e) {
      return false;
    }
  }

  private boolean screeningOn() {
    if (Build.VERSION.SDK_INT < 29) return false;
    try {
      RoleManager rm = (RoleManager) getContext().getSystemService(Context.ROLE_SERVICE);
      return rm != null && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
    } catch (Exception e) {
      return false;
    }
  }

  @PluginMethod
  public void status(PluginCall call) {
    JSObject o = new JSObject();
    o.put("sms", smsOk());
    o.put("calls", callsOk());
    o.put("listener", listenerOn());
    o.put("screening", screeningOn());
    o.put("sdk", Build.VERSION.SDK_INT);
    call.resolve(o);
  }

  @PluginMethod
  public void requestSms(PluginCall call) {
    if (smsOk()) {
      status(call);
      return;
    }
    requestPermissionForAlias("sms", call, "permDone");
  }

  @PluginMethod
  public void requestCalls(PluginCall call) {
    if (callsOk()) {
      status(call);
      return;
    }
    requestPermissionForAlias("calls", call, "permDone");
  }

  @PermissionCallback
  private void permDone(PluginCall call) {
    status(call);
  }

  @PluginMethod
  public void openListener(PluginCall call) {
    try {
      Intent i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      getContext().startActivity(i);
      call.resolve();
    } catch (Exception e) {
      call.reject("Could not open the notification access page");
    }
  }

  /** Asks Android to make Partner the call screening app (a system dialog). Needed so calls to and from other people can be rejected at night. */
  @PluginMethod
  public void requestScreening(PluginCall call) {
    if (Build.VERSION.SDK_INT < 29) {
      call.reject("Call screening needs Android 10 or newer");
      return;
    }
    try {
      RoleManager rm = (RoleManager) getContext().getSystemService(Context.ROLE_SERVICE);
      if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
        call.reject("This phone has no call screening option");
        return;
      }
      if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
        call.resolve();
        return;
      }
      getActivity().startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 7711);
      call.resolve();
    } catch (Exception e) {
      call.reject("Could not ask for call screening: " + e.getMessage());
    }
  }

  /** Bank and UPI debit messages since a time. Only messages that look like a payment are returned, OTP messages never. */
  @PluginMethod
  public void smsList(PluginCall call) {
    if (!smsOk()) {
      call.reject("Messages permission is off");
      return;
    }
    Double sd = call.getDouble("since");
    long since = sd == null ? System.currentTimeMillis() - 30L * 86400000L : sd.longValue();
    JSArray out = new JSArray();
    Cursor c = null;
    try {
      c = getContext().getContentResolver().query(
        Uri.parse("content://sms/inbox"),
        new String[] { "_id", "address", "body", "date" },
        "date>?",
        new String[] { String.valueOf(since) },
        "date DESC"
      );
      int seen = 0;
      while (c != null && c.moveToNext() && seen < 800) {
        seen++;
        String body = c.getString(2);
        if (body == null) continue;
        String b = body.toLowerCase(Locale.ROOT);
        if (b.contains("otp") || b.contains("one time password") || b.contains("one-time password")) continue;
        boolean amt = b.contains("rs.") || b.contains("rs ") || b.contains("inr") || b.contains("\u20b9");
        boolean pay = b.contains("debited") || b.contains("spent") || b.contains("paid") || b.contains("sent") || b.contains("withdrawn") || b.contains("purchase") || b.contains("txn") || b.contains("upi");
        if (!amt || !pay) continue;
        JSObject o = new JSObject();
        o.put("id", c.getLong(0));
        o.put("from", c.getString(1));
        o.put("body", body.length() > 500 ? body.substring(0, 500) : body);
        o.put("ts", c.getLong(3));
        out.put(o);
      }
    } catch (Exception e) {
      call.reject("Could not read messages: " + e.getMessage());
      return;
    } finally {
      if (c != null) c.close();
    }
    JSObject r = new JSObject();
    r.put("items", out);
    call.resolve(r);
  }

  /** Payment notifications kept by the notification listener since the app last collected them. */
  @PluginMethod
  public void notifList(PluginCall call) {
    JSObject r = new JSObject();
    synchronized (PartnerNotifListener.LOCK) {
      SharedPreferences p = getContext().getSharedPreferences("partner_pay", Context.MODE_PRIVATE);
      try {
        r.put("items", new JSArray(p.getString("q", "[]")));
      } catch (Exception e) {
        r.put("items", new JSArray());
      }
    }
    call.resolve(r);
  }

  /** The app has read these notifications and saved the payments, so they leave the queue. */
  @PluginMethod
  public void notifClear(PluginCall call) {
    JSArray keys = call.getArray("keys");
    Set<String> gone = new HashSet<>();
    try {
      if (keys != null) for (int i = 0; i < keys.length(); i++) gone.add(keys.getString(i));
    } catch (Exception e) {
      // ignore
    }
    synchronized (PartnerNotifListener.LOCK) {
      SharedPreferences p = getContext().getSharedPreferences("partner_pay", Context.MODE_PRIVATE);
      try {
        JSONArray q = new JSONArray(p.getString("q", "[]"));
        JSONArray keep = new JSONArray();
        for (int i = 0; i < q.length(); i++) {
          JSONObject o = q.getJSONObject(i);
          if (!gone.contains(o.optString("k"))) keep.put(o);
        }
        p.edit().putString("q", keep.toString()).apply();
      } catch (Exception e) {
        // ignore
      }
    }
    call.resolve();
  }

  /** The phone's contacts (name and number), for choosing who may be called at night. */
  @PluginMethod
  public void contacts(PluginCall call) {
    if (!callsOk()) {
      call.reject("Contacts permission is off");
      return;
    }
    JSArray out = new JSArray();
    Cursor c = null;
    try {
      c = getContext().getContentResolver().query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        new String[] { ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER },
        null,
        null,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
      );
      Set<String> seen = new HashSet<>();
      while (c != null && c.moveToNext() && out.length() < 4000) {
        String n = c.getString(0), num = c.getString(1);
        if (n == null || num == null) continue;
        String key = NightRule.last10(num) + "|" + n.toLowerCase(Locale.ROOT);
        if (!seen.add(key)) continue;
        JSObject o = new JSObject();
        o.put("n", n);
        o.put("num", num);
        out.put(o);
      }
    } catch (Exception e) {
      call.reject("Could not read contacts: " + e.getMessage());
      return;
    } finally {
      if (c != null) c.close();
    }
    JSObject r = new JSObject();
    r.put("items", out);
    call.resolve(r);
  }
}
