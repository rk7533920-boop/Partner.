package com.partner.diary;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.PhoneNumberUtils;
import java.util.Calendar;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The late-night call rule. Shared by the lockdown service (locks the phone and ends calls), the call screener
 * (rejects calls to and from people he did not allow) and the notification listener (hangs up WhatsApp calls).
 * The setting is a JSON object saved by the app: {on, from, to, allow:[{n, num}]}.
 */
final class NightRule {

  private NightRule() {}

  static SharedPreferences sp(Context c) {
    return LockdownPlugin.sp(c);
  }

  static JSONObject cfg(Context c) {
    try {
      String s = sp(c).getString("night", "");
      return s == null || s.isEmpty() ? null : new JSONObject(s);
    } catch (Exception e) {
      return null;
    }
  }

  static int hm(String t, int d) {
    try {
      String[] p = t.split(":");
      return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
    } catch (Exception e) {
      return d;
    }
  }

  /** End time of the night window that is running right now, or 0 when none is. Windows run past midnight. */
  static long activeEnd(Context c, long now) {
    JSONObject n = cfg(c);
    if (n == null || !n.optBoolean("on", false)) return 0;
    int f = hm(n.optString("from", "23:00"), 1380);
    int t = hm(n.optString("to", "07:00"), 420);
    if (f == t) return 0;
    int len = t > f ? t - f : t + 1440 - f;
    for (int back = 0; back <= 1; back++) {
      Calendar cal = Calendar.getInstance();
      cal.setTimeInMillis(now);
      cal.add(Calendar.DAY_OF_YEAR, -back);
      cal.set(Calendar.HOUR_OF_DAY, f / 60);
      cal.set(Calendar.MINUTE, f % 60);
      cal.set(Calendar.SECOND, 0);
      cal.set(Calendar.MILLISECOND, 0);
      long start = cal.getTimeInMillis();
      long end = start + len * 60000L;
      if (now >= start && now < end) return end;
    }
    return 0;
  }

  /**
   * A change that makes the rule weaker waits 24 hours (the app saves it as a pending change). Once its time has come it
   * becomes the real setting, but never in the middle of a night window, so the lock is not cut short.
   */
  static void applyPending(Context c, long now) {
    SharedPreferences p = sp(c);
    long pa = p.getLong("nightPendAt", 0);
    String pend = p.getString("nightPend", "");
    if (pa > 0 && now >= pa && pend != null && pend.length() > 2 && activeEnd(c, now) == 0) {
      p.edit().putString("night", pend).putString("nightPend", "").putLong("nightPendAt", 0).apply();
    }
  }

  /** The last 10 digits of a phone number, so +91 98765 43210 and 098765-43210 are the same person. */
  static String last10(String s) {
    if (s == null) return "";
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch >= '0' && ch <= '9') b.append(ch);
    }
    String d = b.toString();
    return d.length() > 10 ? d.substring(d.length() - 10) : d;
  }

  static boolean isEmergency(String num) {
    if (num == null || num.isEmpty()) return false;
    String d = num.replaceAll("[^0-9]", "");
    if (d.equals("112") || d.equals("100") || d.equals("101") || d.equals("102") || d.equals("108") || d.equals("911") || d.equals("999")) return true;
    try {
      return PhoneNumberUtils.isEmergencyNumber(num);
    } catch (Exception e) {
      return false;
    }
  }

  /** A hidden number is never an allowed number. */
  static boolean allowedNumber(Context c, String num) {
    String d = last10(num);
    if (d.length() < 6) return false;
    JSONObject n = cfg(c);
    JSONArray a = n == null ? null : n.optJSONArray("allow");
    if (a == null) return false;
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o != null && last10(o.optString("num", "")).equals(d)) return true;
    }
    return false;
  }

  /** WhatsApp and other apps show a name, not a number. */
  static boolean allowedName(Context c, String name) {
    if (name == null) return false;
    String x = name.trim().toLowerCase(Locale.ROOT);
    if (x.length() < 3) return false;
    JSONObject n = cfg(c);
    JSONArray a = n == null ? null : n.optJSONArray("allow");
    if (a == null) return false;
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      String y = o.optString("n", "").trim().toLowerCase(Locale.ROOT);
      if (y.length() >= 3 && (x.equals(y) || x.contains(y) || y.contains(x))) return true;
    }
    return false;
  }
}
