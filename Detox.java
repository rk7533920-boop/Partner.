package com.partner.diary;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * App detox. He picks one or more apps and a length (an hour up to 30 days). Until the time is over the app cannot be
 * opened: the lockdown service sends it straight back to the home screen.
 *
 * The list lives here (in the lockdown preferences), not in the web page, so a restored backup or a bug in the page
 * can never shorten a detox. Entries are {id, pkg, name, from, until}. Rules:
 * - a detox can be made longer at any time, never shorter, and an app cannot be taken off the list early;
 * - the only way out is the regret window: 10 minutes after an app was added it can still be removed (for a slip of the finger);
 * - calls, the home screen, Settings and Partner itself can never be blocked.
 */
final class Detox {

  private Detox() {}

  static final String KEY = "detox";
  static final long REGRET = 10 * 60000L;
  static final long MAX = 30L * 86400000L;
  static final long MIN = 60000L;

  private static final Object LOCK = new Object();

  private static final Set<String> NEVER = new HashSet<>(
    Arrays.asList(
      "android", "com.android.systemui", "com.android.settings", "com.samsung.android.settings",
      "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer", "com.samsung.android.incallui",
      "com.android.incallui", "com.android.phone", "com.android.server.telecom", "com.android.emergency",
      "com.android.contacts", "com.google.android.contacts", "com.samsung.android.app.contacts",
      "com.google.android.packageinstaller", "com.android.packageinstaller"
    )
  );

  private static SharedPreferences sp(Context c) {
    return LockdownPlugin.sp(c);
  }

  /** The home screen app can never be blocked, otherwise there would be no way back. */
  private static String launcher(Context c) {
    try {
      Intent hi = new Intent(Intent.ACTION_MAIN);
      hi.addCategory(Intent.CATEGORY_HOME);
      ResolveInfo ri = c.getPackageManager().resolveActivity(hi, PackageManager.MATCH_DEFAULT_ONLY);
      if (ri != null && ri.activityInfo != null) return ri.activityInfo.packageName;
    } catch (Exception e) {
      // ignore
    }
    return "";
  }

  static boolean never(Context c, String pkg) {
    return pkg == null || pkg.isEmpty() || pkg.equals(c.getPackageName()) || NEVER.contains(pkg) || pkg.equals(launcher(c));
  }

  /** All entries that are still running. Finished ones are dropped from the saved list. */
  static JSONArray list(Context c) {
    long now = System.currentTimeMillis();
    synchronized (LOCK) {
      JSONArray out = new JSONArray();
      boolean dropped = false;
      try {
        JSONArray a = new JSONArray(sp(c).getString(KEY, "[]"));
        for (int i = 0; i < a.length(); i++) {
          JSONObject o = a.optJSONObject(i);
          if (o == null) continue;
          if (o.optLong("until", 0) > now) out.put(o);
          else dropped = true;
        }
      } catch (Exception e) {
        out = new JSONArray();
      }
      if (dropped) sp(c).edit().putString(KEY, out.toString()).apply();
      return out;
    }
  }

  static boolean any(Context c) {
    return list(c).length() > 0;
  }

  /** When this app is free again, or 0 when it is not blocked. */
  static long until(Context c, String pkg) {
    if (pkg == null || pkg.isEmpty()) return 0;
    JSONArray a = list(c);
    long best = 0;
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o != null && pkg.equals(o.optString("pkg")) && o.optLong("until", 0) > best) best = o.optLong("until", 0);
    }
    return best;
  }

  static String name(Context c, String pkg) {
    JSONArray a = list(c);
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o != null && pkg.equals(o.optString("pkg"))) {
        String n = o.optString("name", "");
        return n.isEmpty() ? pkg : n;
      }
    }
    return pkg;
  }

  /**
   * Adds apps to the detox, or makes a running one longer. Returns "" when done, otherwise the reason it was refused.
   * An app that is already blocked for longer keeps its longer time.
   */
  static String add(Context c, JSONArray apps, long until) {
    long now = System.currentTimeMillis();
    if (apps == null || apps.length() == 0) return "Pick at least one app.";
    if (until < now + MIN) return "The end time is too close.";
    if (until > now + MAX) until = now + MAX;
    synchronized (LOCK) {
      JSONArray cur = list(c);
      JSONArray out = new JSONArray();
      Set<String> done = new HashSet<>();
      try {
        for (int i = 0; i < apps.length(); i++) {
          JSONObject a = apps.optJSONObject(i);
          if (a == null) continue;
          String pkg = a.optString("pkg", "");
          if (never(c, pkg)) return "This app cannot be blocked: " + (a.optString("name", pkg)) + ".";
        }
        for (int i = 0; i < cur.length(); i++) {
          JSONObject o = cur.getJSONObject(i);
          String pkg = o.optString("pkg");
          for (int j = 0; j < apps.length(); j++) {
            JSONObject a = apps.optJSONObject(j);
            if (a != null && pkg.equals(a.optString("pkg"))) {
              if (until > o.optLong("until", 0)) o.put("until", until);
              done.add(pkg);
            }
          }
          out.put(o);
        }
        for (int j = 0; j < apps.length(); j++) {
          JSONObject a = apps.optJSONObject(j);
          if (a == null) continue;
          String pkg = a.optString("pkg");
          if (done.contains(pkg)) continue;
          done.add(pkg);
          JSONObject o = new JSONObject();
          o.put("id", "d" + Long.toString(now, 36) + Integer.toString(j));
          o.put("pkg", pkg);
          String nm = a.optString("name", pkg);
          o.put("name", nm.length() > 40 ? nm.substring(0, 40) : nm);
          o.put("from", now);
          o.put("until", until);
          out.put(o);
        }
      } catch (Exception e) {
        return "Could not save the detox.";
      }
      sp(c).edit().putString(KEY, out.toString()).apply();
    }
    return "";
  }

  /** Takes one app off the list. Only allowed in the regret window right after it was added. Returns "" when done. */
  static String cancel(Context c, String id) {
    long now = System.currentTimeMillis();
    synchronized (LOCK) {
      JSONArray cur = list(c);
      JSONArray out = new JSONArray();
      boolean found = false;
      String err = "";
      try {
        for (int i = 0; i < cur.length(); i++) {
          JSONObject o = cur.getJSONObject(i);
          if (id != null && id.equals(o.optString("id"))) {
            found = true;
            if (now - o.optLong("from", 0) > REGRET) {
              err = o.optString("name", "This app") + " is blocked until it ends. The 10 minutes to undo are over.";
              out.put(o);
            }
          } else {
            out.put(o);
          }
        }
      } catch (Exception e) {
        return "Could not change the detox.";
      }
      if (!found) return "That block is already over.";
      if (!err.isEmpty()) return err;
      sp(c).edit().putString(KEY, out.toString()).apply();
    }
    return "";
  }

  /**
   * Settings pages that could switch the detox off (the accessibility service, device admin, Partner's own page) are
   * closed while a detox runs. This is a best effort: it looks at the page title and class, and phones word them differently.
   */
  static boolean settingsGuard(Context c, String pkg, String cls, String text) {
    if (pkg == null) return false;
    boolean settings = pkg.equals("com.android.settings") || pkg.equals("com.samsung.android.settings");
    boolean installer = pkg.equals("com.google.android.packageinstaller") || pkg.equals("com.android.packageinstaller") || pkg.equals("com.samsung.android.packageinstaller");
    if (!settings && !installer) return false;
    // a running detox, or the wake-up guard (evening until the wake-up task is done)
    if (!any(c) && !WakeAlarm.guard(c, System.currentTimeMillis())) return false;
    String s = ((cls == null ? "" : cls) + " " + (text == null ? "" : text)).toLowerCase(Locale.ROOT);
    // the uninstall question of the system is closed too (device admin already stops it, this is the second lock)
    if (installer) return s.contains("uninstall");
    return s.contains("accessibility") || s.contains("deviceadmin") || s.contains("device admin") || s.contains("partner");
  }

  /** "2 d 3 h", "5 h 20 min", "40 min". */
  static String left(long ms) {
    long m = Math.max(1, (ms + 59999L) / 60000L);
    long d = m / 1440, h = (m % 1440) / 60, mi = m % 60;
    if (d > 0) return d + " d" + (h > 0 ? " " + h + " h" : "");
    if (h > 0) return h + " h" + (mi > 0 ? " " + mi + " min" : "");
    return mi + " min";
  }
}
