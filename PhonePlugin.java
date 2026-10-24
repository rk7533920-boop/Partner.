package com.partner.diary;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.TimeZone;

@CapacitorPlugin(
  name = "Phone",
  permissions = {
    @Permission(alias = "calendar", strings = { Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR })
  }
)
public class PhonePlugin extends Plugin {

  private boolean calOk() {
    return getPermissionState("calendar") == PermissionState.GRANTED;
  }

  @PluginMethod
  public void setAlarm(PluginCall call) {
    Integer hour = call.getInt("hour");
    Integer minute = call.getInt("minute", 0);
    if (hour == null || hour < 0 || hour > 23 || minute == null || minute < 0 || minute > 59) {
      call.reject("Bad time");
      return;
    }
    Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
      .putExtra(AlarmClock.EXTRA_HOUR, (int) hour)
      .putExtra(AlarmClock.EXTRA_MINUTES, (int) minute)
      .putExtra(AlarmClock.EXTRA_MESSAGE, call.getString("label", "Partner"))
      .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    JSArray d = call.getArray("days");
    if (d != null && d.length() > 0) {
      ArrayList<Integer> ds = new ArrayList<>();
      try {
        for (int k = 0; k < d.length(); k++) ds.add(d.getInt(k));
      } catch (Exception e) {
        // ignore a bad day
      }
      if (!ds.isEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, ds);
    }
    try {
      getContext().startActivity(i);
      call.resolve();
    } catch (Exception e) {
      call.reject("No clock app could set the alarm");
    }
  }

  @PluginMethod
  public void cancelAlarm(PluginCall call) {
    String mode = call.getString("mode", "next");
    Intent i = new Intent(AlarmClock.ACTION_DISMISS_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if ("time".equals(mode)) {
      Integer hour = call.getInt("hour");
      Integer minute = call.getInt("minute", 0);
      if (hour == null || hour < 0 || hour > 23 || minute == null || minute < 0 || minute > 59) {
        call.reject("Bad time");
        return;
      }
      i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
        .putExtra(AlarmClock.EXTRA_HOUR, (int) hour)
        .putExtra(AlarmClock.EXTRA_MINUTES, (int) minute);
    } else if ("label".equals(mode)) {
      String label = call.getString("label", "");
      if (label == null || label.isEmpty()) {
        call.reject("Missing label");
        return;
      }
      i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL).putExtra(AlarmClock.EXTRA_MESSAGE, label);
    } else if ("all".equals(mode)) {
      i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL);
    } else {
      i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT);
    }
    try {
      getContext().startActivity(i);
      call.resolve();
    } catch (Exception e) {
      call.reject("No clock app could close the alarm");
    }
  }

  @PluginMethod
  public void hasCalendar(PluginCall call) {
    JSObject r = new JSObject();
    r.put("granted", calOk());
    call.resolve(r);
  }

  @PluginMethod
  public void requestCalendar(PluginCall call) {
    if (calOk()) {
      hasCalendar(call);
      return;
    }
    requestPermissionForAlias("calendar", call, "calPerm");
  }

  @PermissionCallback
  private void calPerm(PluginCall call) {
    hasCalendar(call);
  }

  private long calId(ContentResolver cr) {
    Cursor c = cr.query(
      CalendarContract.Calendars.CONTENT_URI,
      new String[] { CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.OWNER_ACCOUNT },
      CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=" + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR + " AND " + CalendarContract.Calendars.VISIBLE + "=1",
      null,
      null
    );
    long first = -1, primary = -1;
    if (c != null) {
      try {
        while (c.moveToNext()) {
          long id = c.getLong(0);
          if (first < 0) first = id;
          String a = c.getString(1), o = c.getString(2);
          if (primary < 0 && a != null && a.equals(o)) primary = id;
        }
      } finally {
        c.close();
      }
    }
    return primary >= 0 ? primary : first;
  }

  @PluginMethod
  public void addEvent(PluginCall call) {
    if (!calOk()) {
      call.reject("Calendar permission is off");
      return;
    }
    String title = call.getString("title");
    Double s = call.getDouble("start"), e = call.getDouble("end");
    if (title == null || s == null) {
      call.reject("Missing title or start");
      return;
    }
    long start = s.longValue();
    long end = e != null ? e.longValue() : start + 3600000L;
    ContentResolver cr = getContext().getContentResolver();
    long cal = calId(cr);
    if (cal < 0) {
      call.reject("No calendar found on this phone");
      return;
    }
    ContentValues v = new ContentValues();
    v.put(CalendarContract.Events.DTSTART, start);
    v.put(CalendarContract.Events.DTEND, end);
    v.put(CalendarContract.Events.TITLE, title);
    v.put(CalendarContract.Events.DESCRIPTION, call.getString("notes", ""));
    v.put(CalendarContract.Events.EVENT_LOCATION, call.getString("location", ""));
    v.put(CalendarContract.Events.CALENDAR_ID, cal);
    v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
    Uri u = cr.insert(CalendarContract.Events.CONTENT_URI, v);
    if (u == null) {
      call.reject("Could not add the event");
      return;
    }
    long id = ContentUris.parseId(u);
    Integer rem = call.getInt("remind", 10);
    if (rem != null && rem > 0) {
      try {
        ContentValues r = new ContentValues();
        r.put(CalendarContract.Reminders.EVENT_ID, id);
        r.put(CalendarContract.Reminders.MINUTES, rem);
        r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
        cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
      } catch (Exception ex) {
        // the event is saved even if the reminder is not
      }
    }
    JSObject o = new JSObject();
    o.put("id", id);
    call.resolve(o);
  }

  @PluginMethod
  public void deleteEvent(PluginCall call) {
    if (!calOk()) {
      call.reject("Calendar permission is off");
      return;
    }
    Double id = call.getDouble("id");
    if (id == null) {
      call.reject("Missing id");
      return;
    }
    getContext().getContentResolver().delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.longValue()), null, null);
    call.resolve();
  }

  @PluginMethod
  public void events(PluginCall call) {
    if (!calOk()) {
      call.reject("Calendar permission is off");
      return;
    }
    Double f = call.getDouble("from"), t = call.getDouble("to");
    if (f == null || t == null) {
      call.reject("Missing range");
      return;
    }
    JSArray arr = new JSArray();
    Cursor c = CalendarContract.Instances.query(
      getContext().getContentResolver(),
      new String[] { CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_ID },
      f.longValue(),
      t.longValue()
    );
    if (c != null) {
      try {
        while (c.moveToNext() && arr.length() < 30) {
          JSObject o = new JSObject();
          o.put("title", c.getString(0));
          o.put("start", c.getLong(1));
          o.put("end", c.getLong(2));
          o.put("allDay", c.getInt(3) == 1);
          long eid = c.getLong(4);
          o.put("id", eid);
          boolean rep = false;
          Cursor ec = getContext().getContentResolver().query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eid),
            new String[] { CalendarContract.Events.RRULE },
            null,
            null,
            null
          );
          if (ec != null) {
            try {
              if (ec.moveToFirst()) {
                String rr = ec.getString(0);
                rep = rr != null && !rr.isEmpty();
              }
            } finally {
              ec.close();
            }
          }
          o.put("repeats", rep);
          arr.put(o);
        }
      } finally {
        c.close();
      }
    }
    JSObject r = new JSObject();
    r.put("events", arr);
    call.resolve(r);
  }
}
