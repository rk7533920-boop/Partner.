package com.partner.diary;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Two jobs, both only with the notification access he gives Partner:
 * 1) keeps payment notifications of UPI apps (PhonePe, Paytm, Google Pay, BHIM, CRED) in a small queue the app reads, for the spending page;
 * 2) during the night window, hangs up WhatsApp (and other app) calls from people he did not allow, using the Hang up or Decline button of the call notification.
 */
public class PartnerNotifListener extends NotificationListenerService {

  static PartnerNotifListener inst;
  static final Object LOCK = new Object();

  private static final Set<String> PAY = new HashSet<>(
    Arrays.asList("com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user", "in.org.npci.upiapp", "com.dreamplug.androidapp")
  );
  private static final Set<String> VOIP = new HashSet<>(
    Arrays.asList(
      "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.tachyon", "com.facebook.orca",
      "com.instagram.android", "com.snapchat.android", "org.thoughtcrime.securesms", "com.skype.raider", "us.zoom.videomeetings"
    )
  );
  private static final Set<String> DIALER = new HashSet<>(
    Arrays.asList(
      "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer", "com.samsung.android.incallui",
      "com.android.incallui", "com.android.phone", "com.android.server.telecom"
    )
  );
  private final Map<String, Long> hung = new HashMap<>();
  // when a call notification was last seen (a gap of 8 seconds or more means a new call), and callers let through as repeat callers
  private final Map<String, Long> seenAt = new HashMap<>();
  private final Map<String, Long> passed = new HashMap<>();

  @Override
  public void onListenerConnected() {
    super.onListenerConnected();
    inst = this;
    try {
      sweepNow();
    } catch (Exception e) {
      // ignore
    }
  }

  @Override
  public void onListenerDisconnected() {
    if (inst == this) inst = null;
    super.onListenerDisconnected();
  }

  @Override
  public void onNotificationPosted(StatusBarNotification sbn) {
    try {
      handle(sbn);
    } catch (Exception e) {
      // never let one notification stop the listener
    }
  }

  /** Called by the lockdown service every few seconds during the night window (a call can already be running when the night begins). */
  static void sweep() {
    PartnerNotifListener l = inst;
    if (l == null) return;
    try {
      l.sweepNow();
    } catch (Exception e) {
      // ignore
    }
  }

  private void sweepNow() {
    StatusBarNotification[] all = getActiveNotifications();
    if (all == null) return;
    for (StatusBarNotification s : all) {
      try {
        nightHang(s, System.currentTimeMillis());
      } catch (Exception e) {
        // next one
      }
    }
  }

  private static String str(Bundle x, String k) {
    try {
      CharSequence c = x == null ? null : x.getCharSequence(k);
      return c == null ? "" : c.toString();
    } catch (Exception e) {
      return "";
    }
  }

  private void handle(StatusBarNotification sbn) {
    if (sbn == null) return;
    String pkg = sbn.getPackageName();
    if (pkg == null || pkg.equals(getPackageName())) return;
    long now = System.currentTimeMillis();
    nightHang(sbn, now);
    if (PAY.contains(pkg)) keepPayment(sbn, pkg);
  }

  // ---- payments ----

  private void keepPayment(StatusBarNotification sbn, String pkg) {
    Notification n = sbn.getNotification();
    if (n == null) return;
    Bundle x = n.extras;
    String title = str(x, Notification.EXTRA_TITLE);
    String text = str(x, Notification.EXTRA_BIG_TEXT);
    if (text.isEmpty()) text = str(x, Notification.EXTRA_TEXT);
    String all = (title + " " + text).toLowerCase(Locale.ROOT);
    if (!(all.contains("\u20b9") || all.contains("rs.") || all.contains("rs ") || all.contains("inr"))) return;
    String key = pkg + "|" + sbn.getPostTime() + "|" + Math.abs((title + text).hashCode());
    synchronized (LOCK) {
      SharedPreferences p = getSharedPreferences("partner_pay", Context.MODE_PRIVATE);
      try {
        JSONArray q = new JSONArray(p.getString("q", "[]"));
        for (int i = 0; i < q.length(); i++) {
          if (key.equals(q.getJSONObject(i).optString("k"))) return;
        }
        JSONObject o = new JSONObject();
        o.put("k", key);
        o.put("p", pkg);
        o.put("t", title.length() > 160 ? title.substring(0, 160) : title);
        o.put("x", text.length() > 400 ? text.substring(0, 400) : text);
        o.put("ts", sbn.getPostTime());
        q.put(o);
        JSONArray cut = new JSONArray();
        for (int i = Math.max(0, q.length() - 300); i < q.length(); i++) cut.put(q.get(i));
        p.edit().putString("q", cut.toString()).apply();
      } catch (Exception e) {
        // ignore
      }
    }
  }

  // ---- night calls ----

  private static boolean isHangWord(String t) {
    String s = t.toLowerCase(Locale.ROOT);
    return s.contains("hang up") || s.contains("hangup") || s.contains("decline") || s.contains("end call") || s.contains("reject") || s.contains("leave call") || s.equals("end") || s.contains("disconnect");
  }

  /** The name of the person on a call notification, or an empty text when the notification only says "incoming call". */
  private static String callerName(String title, String text) {
    String t = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
    String x = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    boolean tBad = t.isEmpty() || t.contains("call") || t.equals("whatsapp") || t.equals("telegram");
    if (!tBad) return t;
    boolean xBad = x.isEmpty() || x.contains("call");
    return xBad ? "" : x;
  }

  /** Finds the Hang up or Decline button of a call notification and presses it, when the person is not on the allowed list. */
  private void nightHang(StatusBarNotification sbn, long now) {
    if (sbn == null) return;
    String pkg = sbn.getPackageName();
    if (pkg == null || pkg.equals(getPackageName()) || DIALER.contains(pkg)) return;
    Notification n = sbn.getNotification();
    if (n == null || n.actions == null) return;
    boolean call = Notification.CATEGORY_CALL.equals(n.category) || VOIP.contains(pkg);
    if (!call) return;
    if (NightRule.activeEnd(this, now) == 0) return;
    if (NightRule.breakNow(this, now)) return; // a planned break: nothing is hung up
    Notification.Action hang = null;
    for (Notification.Action a : n.actions) {
      if (a != null && a.title != null && isHangWord(a.title.toString())) {
        hang = a;
        break;
      }
    }
    if (hang == null || hang.actionIntent == null) return;
    Bundle x = n.extras;
    String title = str(x, Notification.EXTRA_TITLE), text = str(x, Notification.EXTRA_TEXT);
    // a name that is on his list is left alone; messages that only look like calls have no Hang up button, so they never get here
    if (NightRule.allowedName(this, title) || NightRule.allowedName(this, text)) return;
    String k = sbn.getKey();
    // a second call from the same person within a few minutes rings (repeat callers), unless he is on the never list
    String who = callerName(title, text);
    boolean never = NightRule.neverName(this, title) || NightRule.neverName(this, text);
    Long prev = seenAt.get(k);
    boolean fresh = prev == null || now - prev > 8000L;
    seenAt.put(k, now);
    if (seenAt.size() > 60) {
      for (String old : new java.util.ArrayList<>(seenAt.keySet())) {
        Long v = seenAt.get(old);
        if (v != null && now - v > 60000L) seenAt.remove(old);
      }
    }
    if (!never && who.length() >= 3) {
      Long ps = passed.get(who);
      if (ps != null && now - ps < 20000L) {
        passed.put(who, now); // the call that was let through is still showing
        return;
      }
      if (fresh && NightRule.repeatPass(this, "w" + who, now)) {
        passed.put(who, now);
        NightRule.sp(this).edit().putLong("nightLastRepeat", now).apply();
        return;
      }
    }
    Long last = hung.get(k);
    if (last != null && now - last < 4000L) return;
    hung.put(k, now);
    try {
      hang.actionIntent.send();
      NightRule.sp(this).edit().putLong("nightLastCut", now).apply();
    } catch (PendingIntent.CanceledException e) {
      // the call is already gone
    }
  }
}
