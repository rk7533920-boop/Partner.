package com.partner.diary;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Counts steps with the phone's own step counter (no other app, no internet). The sensor gives the steps since the phone
 * was started, so a count is always "now minus the value written down at the start". If the phone was restarted in between
 * (the value went down) the new value is used as it is.
 */
final class WalkSteps {

  private WalkSteps() {}

  interface Cb {
    void done(long steps); // -1 when the sensor gave nothing
  }

  static SharedPreferences sp(Context c) {
    return LockdownPlugin.sp(c);
  }

  static boolean permission(Context c) {
    return android.os.Build.VERSION.SDK_INT < 29 || c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
  }

  static boolean hasSensor(Context c) {
    try {
      SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
      return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
    } catch (Exception e) {
      return false;
    }
  }

  static boolean available(Context c) {
    return permission(c) && hasSensor(c);
  }

  /** One reading of the counter (value since boot), or -1 after 4 seconds. */
  static void read(Context c, Cb cb) {
    final Handler h = new Handler(Looper.getMainLooper());
    try {
      final SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
      final Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
      if (s == null || !permission(c)) {
        cb.done(-1);
        return;
      }
      final boolean[] fin = { false };
      final SensorEventListener l = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
          if (fin[0] || e.values == null || e.values.length == 0) return;
          fin[0] = true;
          sm.unregisterListener(this);
          cb.done((long) e.values[0]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
      };
      sm.registerListener(l, s, SensorManager.SENSOR_DELAY_FASTEST, 0, h);
      h.postDelayed(() -> {
        if (fin[0]) return;
        fin[0] = true;
        try {
          sm.unregisterListener(l);
        } catch (Exception e) {
          // ignore
        }
        cb.done(-1);
      }, 4000L);
    } catch (Exception e) {
      cb.done(-1);
    }
  }

  /** Starts a walk: writes down the counter now. */
  static void begin(Context c) {
    final long t = System.currentTimeMillis();
    sp(c).edit().putInt("walkOn", 1).putLong("walkAt", t).putLong("walkBase", -1).apply();
    read(c, v -> {
      if (v >= 0) sp(c).edit().putLong("walkBase", v).apply();
    });
  }

  static boolean active(Context c) {
    return sp(c).getInt("walkOn", 0) == 1;
  }

  static void end(Context c) {
    sp(c).edit().putInt("walkOn", 0).putLong("walkBase", -1).apply();
  }

  /** Steps walked since begin(), or -1 when unknown. */
  static void count(Context c, Cb cb) {
    final long base = sp(c).getLong("walkBase", -1);
    read(c, v -> {
      if (v < 0) {
        cb.done(-1);
        return;
      }
      if (base < 0) { // the first reading never came: start counting from now
        sp(c).edit().putLong("walkBase", v).apply();
        cb.done(0);
        return;
      }
      cb.done(v >= base ? v - base : v);
    });
  }
}
