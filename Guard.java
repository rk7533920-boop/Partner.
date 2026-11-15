package com.partner.diary;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.telephony.SmsManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Two things he asked for, both chosen by him in advance and both hard to switch off in a weak moment:
 *
 * 1. Adult-content block. A family-filter Private DNS for the whole phone (the real work is done by that DNS), a check of
 *    browser address bars and search boxes (LockdownService + AdultMatch), and apps that stay closed (for example Webtoon).
 *    Turning any part of it off waits 18 hours, and the Settings pages for Private DNS stay closed while it is on.
 *
 * 2. Accountability messages. People he picked get a text (WhatsApp, or SMS) when something happens: he is awake, he tried
 *    an adult site, he used chosen apps for an hour, the filter was turned off. Only contacts he added himself, only the
 *    events he ticked. Removing a contact or an event waits 18 hours.
 *
 * Config (key "guard", saved by the web page): {adult:{on, dns, keep, scan, apps:[{pkg,name}]},
 *   msg:{on, name, usage:{on, min, apps:[{pkg,name}]}, contacts:[{n, num, via:"wa"|"sms", fb, ev:{awake, adult, usage, filter}}]}}.
 * A weaker change waits ("guardPend", "guardPendAt") and is applied here when its time has come.
 */
final class Guard {

  private Guard() {}

  static final String KEY = "guard", PEND = "guardPend", PENDAT = "guardPendAt", QUEUE = "gq";
  static final String CH = "partner_guard";
  static final int MAX_TRIES = 2;

  static SharedPreferences sp(Context c) {
    return LockdownPlugin.sp(c);
  }

  // ---- config ----

  static JSONObject cfg(Context c) {
    try {
      String s = sp(c).getString(KEY, "");
      return s == null || s.isEmpty() ? null : new JSONObject(s);
    } catch (Exception e) {
      return null;
    }
  }

  static JSONObject adult(Context c) {
    JSONObject o = cfg(c);
    return o == null ? null : o.optJSONObject("adult");
  }

  static JSONObject msg(Context c) {
    JSONObject o = cfg(c);
    return o == null ? null : o.optJSONObject("msg");
  }

  static boolean adultOn(Context c) {
    JSONObject a = adult(c);
    return a != null && a.optBoolean("on", false);
  }

  static boolean scanOn(Context c) {
    JSONObject a = adult(c);
    return a != null && a.optBoolean("on", false) && a.optBoolean("scan", true);
  }

  static boolean keepOn(Context c) {
    JSONObject a = adult(c);
    return a != null && a.optBoolean("on", false) && a.optBoolean("keep", true);
  }

  /** A weaker change waits until its time; then it becomes the real setting. */
  static void applyPending(Context c, long now) {
    SharedPreferences p = sp(c);
    long pa = p.getLong(PENDAT, 0);
    String pend = p.getString(PEND, "");
    if (pa > 0 && now >= pa && pend != null && pend.length() > 2) {
      p.edit().putString(KEY, pend).putString(PEND, "").putLong(PENDAT, 0).apply();
    }
  }

  /** Apps that stay closed while the adult block is on. */
  static boolean appBlocked(Context c, String pkg) {
    if (pkg == null || pkg.isEmpty() || pkg.equals(c.getPackageName())) return false;
    JSONObject a = adult(c);
    if (a == null || !a.optBoolean("on", false)) return false;
    JSONArray ap = a.optJSONArray("apps");
    if (ap == null) return false;
    for (int i = 0; i < ap.length(); i++) {
      JSONObject o = ap.optJSONObject(i);
      if (o != null && pkg.equals(o.optString("pkg"))) return true;
    }
    return false;
  }

  static String appName(Context c, String pkg) {
    JSONObject a = adult(c);
    JSONArray ap = a == null ? null : a.optJSONArray("apps");
    if (ap != null) {
      for (int i = 0; i < ap.length(); i++) {
        JSONObject o = ap.optJSONObject(i);
        if (o != null && pkg.equals(o.optString("pkg"))) return o.optString("name", pkg);
      }
    }
    return pkg;
  }

  // ---- which windows are watched for adult addresses and searches ----

  private static final Set<String> WATCH = new HashSet<>(
    Arrays.asList(
      "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "com.sec.android.app.sbrowser",
      "com.sec.android.app.sbrowser.beta", "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
      "org.mozilla.focus", "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
      "com.opera.gx", "com.duckduckgo.mobile.android", "com.UCMobile.intl", "com.uc.browser.en", "com.kiwibrowser.browser",
      "com.vivaldi.browser", "com.android.browser", "com.mi.globalbrowser", "com.google.android.googlequicksearchbox",
      "com.google.android.apps.searchlite", "com.google.android.youtube", "com.reddit.frontpage", "com.twitter.android"
    )
  );

  static boolean watchPkg(String pkg) {
    return pkg != null && WATCH.contains(pkg);
  }

  static boolean isWa(String pkg) {
    return "com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg);
  }

  // ---- Private DNS ----

  static String dnsHost(String kind) {
    if ("standard".equals(kind)) return "adult-filter-dns.cleanbrowsing.org";
    if ("cf".equals(kind)) return "family.cloudflare-dns.com";
    return "family-filter-dns.cleanbrowsing.org"; // strict
  }

  static String wantHost(Context c) {
    JSONObject a = adult(c);
    return dnsHost(a == null ? "strict" : a.optString("dns", "strict"));
  }

  static String dnsMode(Context c) {
    try {
      String m = Settings.Global.getString(c.getContentResolver(), "private_dns_mode");
      return m == null ? "" : m;
    } catch (Exception e) {
      return "";
    }
  }

  static String dnsSpec(Context c) {
    try {
      String m = Settings.Global.getString(c.getContentResolver(), "private_dns_specifier");
      return m == null ? "" : m;
    } catch (Exception e) {
      return "";
    }
  }

  /** True when the phone uses exactly the filter DNS wanted. */
  static boolean dnsOk(Context c) {
    return "hostname".equals(dnsMode(c)) && wantHost(c).equalsIgnoreCase(dnsSpec(c));
  }

  /** Sets Private DNS by itself. Only possible when the one-time adb permission WRITE_SECURE_SETTINGS was given. */
  static boolean dnsApply(Context c) {
    if (!LockdownAcc.granted(c)) return false;
    try {
      ContentResolver r = c.getContentResolver();
      Settings.Global.putString(r, "private_dns_specifier", wantHost(c));
      Settings.Global.putString(r, "private_dns_mode", "hostname");
      return dnsOk(c);
    } catch (Exception e) {
      return false;
    }
  }

  /** The Android page for Private DNS (or the nearest page) is closed while the block is on. */
  static boolean dnsPage(Context c, String pkg, String cls, String text) {
    if (pkg == null || !(pkg.equals("com.android.settings") || pkg.equals("com.samsung.android.settings"))) return false;
    if (!keepOn(c)) return false;
    String s = ((cls == null ? "" : cls) + " " + (text == null ? "" : text)).toLowerCase(Locale.ROOT);
    return s.contains("private dns") || s.contains("private_dns") || s.contains("privatedns");
  }

  // ---- events that are counted (status page) ----

  static int dayKey(long t) {
    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(t - 5L * 3600000L);
    return cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH);
  }

  /** Remembers that something adult was stopped (time only, never what it was). */
  static void recordHit(Context c, long now) {
    SharedPreferences p = sp(c);
    try {
      JSONArray a = new JSONArray(p.getString("gdHits", "[]"));
      JSONArray out = new JSONArray();
      long cut = now - 14L * 86400000L;
      for (int i = 0; i < a.length(); i++) if (a.optLong(i, 0) > cut) out.put(a.optLong(i));
      out.put(now);
      while (out.length() > 300) out.remove(0);
      p.edit().putString("gdHits", out.toString()).apply();
    } catch (Exception e) {
      // not important
    }
  }

  // ---- accountability messages ----

  private static boolean evCooldown(Context c, String ev, long now) {
    SharedPreferences p = sp(c);
    long last = p.getLong("gl_" + ev, 0);
    if ("awake".equals(ev)) {
      if (last > 0 && dayKey(last) == dayKey(now)) return true;
    } else if ("adult".equals(ev)) {
      if (now - last < 20 * 60000L) return true;
    } else if ("filter".equals(ev)) {
      if (now - last < 30 * 60000L) return true;
    }
    p.edit().putLong("gl_" + ev, now).apply();
    return false;
  }

  /** Something happened: put the message for every contact who wants it in the queue. `arg` replaces {m} in the text. */
  static void fire(Context c, String ev, String arg) {
    try {
      JSONObject m = msg(c);
      if (m == null || !m.optBoolean("on", false)) return;
      JSONArray cs = m.optJSONArray("contacts");
      if (cs == null || cs.length() == 0) return;
      long now = System.currentTimeMillis();
      if (evCooldown(c, ev, now)) return;
      for (int i = 0; i < cs.length(); i++) {
        JSONObject k = cs.optJSONObject(i);
        if (k == null) continue;
        JSONObject e = k.optJSONObject("ev");
        String t = e == null ? "" : e.optString(ev, "");
        if (t == null || t.trim().isEmpty()) continue;
        enqueue(c, k.optString("n", ""), k.optString("num", ""), k.optString("via", "wa"), k.optBoolean("fb", true), t.replace("{m}", arg == null ? "" : arg), ev);
      }
      pump(c);
      if (LockdownService.inst != null) LockdownService.inst.poke();
    } catch (Exception e) {
      // the next event tries again
    }
  }

  static synchronized void enqueue(Context c, String name, String num, String via, boolean fb, String text, String ev) {
    try {
      JSONArray q = queue(c);
      JSONObject o = new JSONObject();
      o.put("id", "m" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(q.length()));
      o.put("n", name);
      o.put("num", num);
      o.put("via", "sms".equals(via) ? "sms" : "wa");
      o.put("fb", fb);
      o.put("text", text);
      o.put("ev", ev);
      o.put("ts", System.currentTimeMillis());
      o.put("tries", 0);
      q.put(o);
      sp(c).edit().putString(QUEUE, q.toString()).apply();
    } catch (Exception e) {
      // ignore
    }
  }

  static synchronized JSONArray queue(Context c) {
    try {
      return new JSONArray(sp(c).getString(QUEUE, "[]"));
    } catch (Exception e) {
      return new JSONArray();
    }
  }

  static int queueLen(Context c) {
    return queue(c).length();
  }

  /** Sends every SMS in the queue and drops messages that waited too long. WhatsApp ones are sent by LockdownService. */
  static synchronized void pump(Context c) {
    try {
      JSONArray q = queue(c), keep = new JSONArray();
      long now = System.currentTimeMillis();
      for (int i = 0; i < q.length(); i++) {
        JSONObject o = q.getJSONObject(i);
        if (now - o.optLong("ts", now) > 6 * 3600000L) {
          note(c, "Message not sent", "The message to " + o.optString("n") + " waited too long and was dropped.");
          continue;
        }
        if ("sms".equals(o.optString("via"))) {
          if (sendSms(c, o.optString("num"), o.optString("text"))) {
            sp(c).edit().putLong("gdLastOk", now).apply();
          } else {
            note(c, "SMS not sent", "Could not text " + o.optString("n") + ". Check the SMS permission.");
            sp(c).edit().putLong("gdLastFail", now).apply();
          }
          continue;
        }
        keep.put(o);
      }
      sp(c).edit().putString(QUEUE, keep.toString()).apply();
    } catch (Exception e) {
      // try again later
    }
  }

  /** The first message that needs WhatsApp, or null. */
  static synchronized JSONObject nextWa(Context c) {
    JSONArray q = queue(c);
    for (int i = 0; i < q.length(); i++) {
      JSONObject o = q.optJSONObject(i);
      if (o != null && !"sms".equals(o.optString("via"))) return o;
    }
    return null;
  }

  /** The WhatsApp message was sent (the Send button was tapped). */
  static synchronized void sent(Context c, String id) {
    drop(c, id);
    sp(c).edit().putLong("gdLastOk", System.currentTimeMillis()).apply();
  }

  /** A WhatsApp attempt failed or timed out: try once more, then text it (when allowed), else tell him. */
  static synchronized void failed(Context c, String id) {
    try {
      JSONArray q = queue(c), out = new JSONArray();
      for (int i = 0; i < q.length(); i++) {
        JSONObject o = q.getJSONObject(i);
        if (!id.equals(o.optString("id"))) {
          out.put(o);
          continue;
        }
        int t = o.optInt("tries", 0) + 1;
        if (t < MAX_TRIES) {
          o.put("tries", t);
          out.put(o);
          continue;
        }
        boolean ok = o.optBoolean("fb", true) && sendSms(c, o.optString("num"), o.optString("text"));
        long now = System.currentTimeMillis();
        if (ok) sp(c).edit().putLong("gdLastOk", now).apply();
        else {
          sp(c).edit().putLong("gdLastFail", now).apply();
          note(c, "WhatsApp message not sent", "Could not send the message to " + o.optString("n") + ". Open Settings, Accountability, and send a test.");
        }
      }
      sp(c).edit().putString(QUEUE, out.toString()).apply();
    } catch (Exception e) {
      // ignore
    }
  }

  private static void drop(Context c, String id) {
    try {
      JSONArray q = queue(c), out = new JSONArray();
      for (int i = 0; i < q.length(); i++) {
        JSONObject o = q.getJSONObject(i);
        if (!id.equals(o.optString("id"))) out.put(o);
      }
      sp(c).edit().putString(QUEUE, out.toString()).apply();
    } catch (Exception e) {
      // ignore
    }
  }

  static String digits(String num) {
    StringBuilder b = new StringBuilder();
    if (num != null) for (int i = 0; i < num.length(); i++) if (Character.isDigit(num.charAt(i))) b.append(num.charAt(i));
    String d = b.toString();
    while (d.startsWith("00")) d = d.substring(2);
    if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
    if (d.length() == 10) d = "91" + d; // numbers without a country code are Indian
    return d;
  }

  static Intent waIntent(String num, String text, String pkg) {
    String d = digits(num);
    if (d.length() < 8) return null;
    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + d + "?text=" + Uri.encode(text)));
    i.setPackage(pkg);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return i;
  }

  static boolean smsPerm(Context c) {
    return c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
  }

  static boolean sendSms(Context c, String num, String text) {
    if (!smsPerm(c)) return false;
    try {
      String d = digits(num);
      if (d.length() < 8) return false;
      SmsManager sm = Build.VERSION.SDK_INT >= 31 ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
      ArrayList<String> parts = sm.divideMessage(text);
      sm.sendMultipartTextMessage("+" + d, null, parts, null, null);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static void note(Context c, String title, String text) {
    try {
      NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm == null) return;
      if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
        nm.createNotificationChannel(new NotificationChannel(CH, "Adult block and messages", NotificationManager.IMPORTANCE_DEFAULT));
      }
      Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
      b.setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setSmallIcon(android.R.drawable.stat_notify_error).setAutoCancel(true);
      nm.notify(7400 + (int) (System.currentTimeMillis() % 90), b.build());
    } catch (Exception e) {
      // ignore
    }
  }

  // ---- time on the picked apps (for the "long on the phone" message) ----

  /** Called every service tick while the screen is on, with the app in front and the time since the last tick. */
  static void usageTick(Context c, String pkg, long dt, long now) {
    try {
      JSONObject m = msg(c);
      if (m == null || !m.optBoolean("on", false)) return;
      JSONObject u = m.optJSONObject("usage");
      if (u == null || !u.optBoolean("on", false)) return;
      SharedPreferences p = sp(c);
      int day = dayKey(now);
      if (p.getInt("gUsedDay", 0) != day) p.edit().putInt("gUsedDay", day).putLong("gUsed", 0).putInt("gSent", 0).apply();
      JSONArray ap = u.optJSONArray("apps");
      boolean hit = false;
      if (ap != null && pkg != null) {
        for (int i = 0; i < ap.length(); i++) {
          JSONObject o = ap.optJSONObject(i);
          if (o != null && pkg.equals(o.optString("pkg"))) hit = true;
        }
      }
      if (!hit) return;
      long used = p.getLong("gUsed", 0) + Math.max(0, Math.min(dt, 10000L));
      int sent = p.getInt("gSent", 0);
      long thr = Math.max(10, u.optInt("min", 60)) * 60000L;
      SharedPreferences.Editor ed = p.edit().putLong("gUsed", used);
      if (sent < 4 && used >= thr * (sent + 1)) {
        ed.putInt("gSent", sent + 1).apply();
        fire(c, "usage", String.valueOf(used / 60000L));
        return;
      }
      ed.apply();
    } catch (Exception e) {
      // ignore
    }
  }
}
