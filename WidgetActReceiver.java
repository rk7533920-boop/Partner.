package com.partner.diary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Widget buttons that record something without opening the app: tick the next routine step, end the running activity,
 * start study. The change is shown on the widget at once and kept in a list the page merges into the diary the next time
 * it runs.
 */
public class WidgetActReceiver extends BroadcastReceiver {

  @Override
  public void onReceive(Context c, Intent i) {
    String op = i == null ? null : i.getStringExtra("op");
    if (op == null) return;
    long now = System.currentTimeMillis();
    try {
      SharedPreferences sp = PartnerWidgets.sp(c);
      JSONObject s = PartnerWidgets.snap(c);
      JSONArray ops = new JSONArray(sp.getString("ops", "[]"));
      if ("tick".equals(op)) {
        JSONObject r = s.optJSONObject("routine");
        JSONArray left = r == null ? null : r.optJSONArray("left");
        if (left == null || left.length() == 0) {
          Toast.makeText(c, "Nothing left to tick.", Toast.LENGTH_SHORT).show();
          return;
        }
        JSONObject st = left.getJSONObject(0);
        ops.put(new JSONObject().put("a", "tick").put("sid", st.optString("id")).put("ts", now));
        JSONArray nl = new JSONArray();
        for (int k = 1; k < left.length(); k++) nl.put(left.get(k));
        r.put("left", nl);
        r.put("done", r.optInt("done", 0) + 1);
        s.put("routine", r);
        Toast.makeText(c, "Done: " + st.optString("t"), Toast.LENGTH_SHORT).show();
      } else if ("end".equals(op)) {
        JSONObject o = s.optJSONObject("running");
        if (o == null) return;
        ops.put(new JSONObject().put("a", "end").put("id", o.optString("id")).put("ts", now));
        s.remove("running");
        Toast.makeText(c, "Ended: " + o.optString("text"), Toast.LENGTH_SHORT).show();
      } else if ("study".equals(op)) {
        if (s.optJSONObject("running") != null) {
          Toast.makeText(c, "Something is already running. End it first.", Toast.LENGTH_SHORT).show();
          return;
        }
        ops.put(new JSONObject().put("a", "study").put("ts", now));
        s.put("running", new JSONObject().put("id", "").put("text", "Study").put("ts", now));
        Toast.makeText(c, "Study started", Toast.LENGTH_SHORT).show();
      } else {
        return;
      }
      sp.edit().putString("ops", ops.toString()).putString("snap", s.toString()).apply();
      PartnerWidgets.refresh(c);
      WidgetPlugin.pingOps();
    } catch (Exception e) {
      // ignore: the next snapshot from the page fixes the widget
    }
  }
}
