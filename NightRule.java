package com.partner.diary;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.PhoneNumberUtils;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
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

  private static boolean numberIn(Context c, String list, String num) {
    String d = last10(num);
    if (d.length() < 6) return false;
    JSONObject n = cfg(c);
    JSONArray a = n == null ? null : n.optJSONArray(list);
    if (a == null) return false;
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o != null && last10(o.optString("num", "")).equals(d)) return true;
    }
    return false;
  }

  private static boolean nameIn(Context c, String list, String name) {
    if (name == null) return false;
    String x = name.trim().toLowerCase(Locale.ROOT);
    if (x.length() < 3) return false;
    JSONObject n = cfg(c);
    JSONArray a = n == null ? null : n.optJSONArray(list);
    if (a == null) return false;
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      String y = o.optString("n", "").trim().toLowerCase(Locale.ROOT);
      if (y.length() >= 3 && (x.equals(y) || x.contains(y) || y.contains(x))) return true;
    }
    return false;
  }

  /** A hidden number is never an allowed number. */
  static boolean allowedNumber(Context c, String num) {
    return numberIn(c, "allow", num);
  }

  /** WhatsApp and other apps show a name, not a number. */
  static boolean allowedName(Context c, String name) {
    return nameIn(c, "allow", name);
  }

  /** People who stay blocked at night even when they call a second time. */
  static boolean neverNumber(Context c, String num) {
    return numberIn(c, "never", num);
  }

  static boolean neverName(Context c, String name) {
    return nameIn(c, "never", name);
  }

  /**
   * Break mode: the app writes a start and an end (milliseconds) when he has planned a break in advance.
   * While it runs nothing automatic happens: no lock, no night rule, no call blocking, no spoken or written reminders.
   */
  static boolean breakNow(Context c, long now) {
    SharedPreferences p = sp(c);
    long f = p.getLong("brkFrom", 0), t = p.getLong("brkTo", 0);
    return f > 0 && t > f && now >= f && now < t;
  }

  /**
   * Repeat callers, like Samsung Do Not Disturb: somebody who was stopped by the night rule and calls again within the set
   * minutes (default 15) gets through, and keeps getting through while he keeps calling. The key is the last 10 digits of
   * the number or the name shown on a WhatsApp call. Returns true when this call must ring. A call from somebody on the
   * never list, and a call with no usable number or name, is never let through. The first call of a pair is written down here.
   */
  static synchronized boolean repeatPass(Context c, String key, long now) {
    if (key == null || key.length() < 3) return false;
    JSONObject n = cfg(c);
    if (n == null || !n.optBoolean("rep", true)) return false;
    long win = Math.max(1, Math.min(60, n.optInt("repMin", 15))) * 60000L;
    SharedPreferences p = sp(c);
    JSONObject r;
    try {
      r = new JSONObject(p.getString("nightRep", "{}"));
    } catch (Exception e) {
      r = new JSONObject();
    }
    boolean pass = false;
    try {
      long ps = r.optLong(key + "#p", 0), bl = r.optLong(key, 0);
      if (ps > 0 && now - ps <= win) {
        pass = true;
        r.put(key + "#p", now);
      } else if (bl > 0 && now - bl <= win) {
        pass = true;
        r.put(key + "#p", now);
      } else {
        r.put(key, now);
      }
      List<String> old = new ArrayList<>();
      Iterator<String> it = r.keys();
      while (it.hasNext()) {
        String k = it.next();
        if (now - r.optLong(k, 0) > 86400000L) old.add(k);
      }
      for (String k : old) r.remove(k);
      p.edit().putString("nightRep", r.toString()).apply();
    } catch (Exception e) {
      return false;
    }
    return pass;
  }
}
