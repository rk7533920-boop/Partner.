package com.partner.diary;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home screen widgets: quick actions (4x1), Today (4x2) and two small ones (2x2).
 * The page writes a small snapshot (routine progress, running activity, protein and calories) with Widget.update().
 * Buttons that only record something (tick the next routine step, end or start study) do not open the app: they add an
 * entry to a list ("ops") and change the widget at once; the page merges the list the next time it runs (Widget.ops()).
 * Buttons that need the app (Talk, Meal, Workout) open it with an extra the page reads (Widget.consume()).
 */
final class PartnerWidgets {

  static final String PREF = "partner_widget";

  static SharedPreferences sp(Context c) {
    return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
  }

  static JSONObject snap(Context c) {
    try {
      String s = sp(c).getString("snap", "");
      return s.isEmpty() ? new JSONObject() : new JSONObject(s);
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  /** Opens the app. quick = true starts the same talk mode as the lock-screen button. */
  static PendingIntent open(Context c, int code, String act, boolean quick) {
    Intent i = new Intent(c, MainActivity.class);
    i.setAction("com.partner.diary.WIDGET." + act);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    if (quick) i.putExtra("quick", true);
    else i.putExtra("wact", act);
    return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /** A button that records something without opening the app. */
  static PendingIntent op(Context c, int code, String act) {
    Intent i = new Intent(c, WidgetActReceiver.class);
    i.setAction("com.partner.diary.WOP." + act);
    i.putExtra("op", act);
    return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private static boolean fresh(JSONObject s) {
    long ts = s.optLong("ts", 0);
    return ts > 0 && System.currentTimeMillis() - ts < 20L * 3600000L;
  }

  private static String dur(long ms) {
    long m = Math.max(1, ms / 60000L);
    return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
  }

  static RemoteViews quick(Context c) {
    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_quick);
    v.setOnClickPendingIntent(R.id.wq_talk, open(c, 1, "talk", true));
    v.setOnClickPendingIntent(R.id.wq_meal, open(c, 2, "meal", false));
    v.setOnClickPendingIntent(R.id.wq_start, op(c, 3, "study"));
    v.setOnClickPendingIntent(R.id.wq_routine, op(c, 4, "tick"));
    v.setOnClickPendingIntent(R.id.wq_workout, open(c, 5, "workout", false));
    return v;
  }

  static RemoteViews today(Context c) {
    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_today);
    JSONObject s = snap(c);
    boolean ok = fresh(s);
    JSONObject r = s.optJSONObject("routine");
    if (r != null) {
      int total = Math.max(1, r.optInt("total", 1)), done = Math.min(total, r.optInt("done", 0));
      v.setTextViewText(R.id.wt_rn, r.optString("n", "Routine"));
      v.setTextViewText(R.id.wt_rd, done + " of " + r.optInt("total", 0) + " done");
      v.setProgressBar(R.id.wt_rp, total, done, false);
      JSONArray left = r.optJSONArray("left");
      v.setViewVisibility(R.id.wt_tick, left != null && left.length() > 0 ? View.VISIBLE : View.GONE);
    } else {
      v.setTextViewText(R.id.wt_rn, "Routine");
      v.setTextViewText(R.id.wt_rd, ok ? "None set up" : "Open Partner");
      v.setProgressBar(R.id.wt_rp, 100, 0, false);
      v.setViewVisibility(R.id.wt_tick, View.GONE);
    }
    JSONObject o = s.optJSONObject("running");
    if (o != null) {
      long ts = o.optLong("ts", 0);
      v.setViewVisibility(R.id.wt_run, View.VISIBLE);
      v.setTextViewText(R.id.wt_ra, o.optString("text", "Running"));
      v.setTextViewText(
        R.id.wt_rb,
        "since " + new SimpleDateFormat("h:mm a", Locale.US).format(new Date(ts)) + ", " + dur(System.currentTimeMillis() - ts)
      );
    } else {
      v.setViewVisibility(R.id.wt_run, View.GONE);
    }
    bar(v, R.id.wt_pt, R.id.wt_pb, "Protein", s.optJSONObject("pro"), " g", ok);
    bar(v, R.id.wt_ct, R.id.wt_cb, "Calories", s.optJSONObject("cal"), "", ok);
    v.setOnClickPendingIntent(R.id.wt_root, open(c, 10, "home", false));
    v.setOnClickPendingIntent(R.id.wt_tick, op(c, 11, "tick"));
    v.setOnClickPendingIntent(R.id.wt_end, op(c, 12, "end"));
    return v;
  }

  private static void bar(RemoteViews v, int tid, int bid, String name, JSONObject d, String unit, boolean ok) {
    if (d == null || !ok) {
      v.setTextViewText(tid, name);
      v.setProgressBar(bid, 100, 0, false);
      return;
    }
    int val = d.optInt("v", 0), goal = d.optInt("g", 0);
    String f = String.format(Locale.US, "%,d", val);
    v.setTextViewText(tid, goal > 0 ? name + " " + f + " / " + String.format(Locale.US, "%,d", goal) + unit : name + " " + f + unit);
    v.setProgressBar(bid, goal > 0 ? goal : 100, goal > 0 ? Math.min(val, goal) : 0, false);
  }

  static RemoteViews talk(Context c) {
    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_talk);
    v.setOnClickPendingIntent(R.id.wk_root, open(c, 20, "talk", true));
    return v;
  }

  static RemoteViews meal(Context c) {
    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_meal);
    JSONObject s = snap(c);
    JSONObject p = s.optJSONObject("pro");
    if (p != null && fresh(s) && p.optInt("g", 0) > 0) {
      v.setTextViewText(R.id.wm_sub, "Protein " + p.optInt("v", 0) + " of " + p.optInt("g", 0) + " g");
    } else {
      v.setTextViewText(R.id.wm_sub, "Tap to log");
    }
    v.setOnClickPendingIntent(R.id.wm_root, open(c, 21, "meal", false));
    return v;
  }

  static void refresh(Context c) {
    try {
      AppWidgetManager m = AppWidgetManager.getInstance(c);
      push(m, c, QuickWidget.class, quick(c));
      push(m, c, TodayWidget.class, today(c));
      push(m, c, TalkWidget.class, talk(c));
      push(m, c, MealWidget.class, meal(c));
    } catch (Exception e) {
      // a launcher without widget support
    }
  }

  private static void push(AppWidgetManager m, Context c, Class<?> k, RemoteViews v) {
    int[] ids = m.getAppWidgetIds(new ComponentName(c, k));
    if (ids != null && ids.length > 0) m.updateAppWidget(ids, v);
  }
}
