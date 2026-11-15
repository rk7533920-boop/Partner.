package com.partner.diary;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import org.json.JSONObject;

/**
 * Rings the wake-up alarm until the mission is done (WakeAlarm.done) or the safety limit is reached (WakeAlarm.giveUp).
 * It keeps the alarm volume up, vibrates, shows a full-screen notification that opens the mission, and lights the screen.
 * It stops the moment the ring state in the preferences is cleared, so the web page only has to call Wake.done().
 */
public class WakeService extends Service {
  static final int NID = 7200;
  private static final String CH = "partner_wake";

  private final Handler h = new Handler(Looper.getMainLooper());
  private MediaPlayer mp;
  private Vibrator vib;
  private PowerManager.WakeLock wl, screen;
  private long t0 = 0;
  private boolean running = false;
  private boolean vibOn = false;

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  @Override
  public int onStartCommand(Intent i, int flags, int startId) {
    if (WakeAlarm.ringStart(this) <= 0) {
      stopAll();
      return START_NOT_STICKY;
    }
    postNotification();
    if (!running) {
      running = true;
      vibOn = false;
      t0 = System.currentTimeMillis();
      lightScreen();
      startSound();
      h.post(loop);
    }
    WakeAlarm.openMission(this);
    return START_STICKY;
  }

  private final Runnable loop = new Runnable() {
    @Override
    public void run() {
      if (WakeAlarm.ringStart(WakeService.this) <= 0) {
        stopAll();
        return;
      }
      keepLoud();
      // the accessibility service brings the mission back to the front if he leaves it
      h.postDelayed(this, 700);
    }
  };

  private void postNotification() {
    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
      NotificationChannel ch = new NotificationChannel(CH, "Wake-up alarm", NotificationManager.IMPORTANCE_HIGH);
      ch.setSound(null, null); // the alarm sound is played by this service itself
      ch.enableVibration(false);
      ch.setBypassDnd(true);
      ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
      nm.createNotificationChannel(ch);
    }
    PendingIntent open = PendingIntent.getActivity(this, 7201, WakeAlarm.missionIntent(this),
      PendingIntent.FLAG_UPDATE_CURRENT | SpeakerPlugin.immutable());
    boolean check = "check".equals(WakeAlarm.kind(this));
    Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
    b.setContentTitle(check ? "Are you awake?" : "Wake up")
      .setContentText(check ? "Do the quick task to show you are up." : "Do the wake-up task to stop the alarm.")
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentIntent(open)
      .setFullScreenIntent(open, true)
      .setCategory(Notification.CATEGORY_ALARM)
      .setOngoing(true);
    if (Build.VERSION.SDK_INT >= 29) startForeground(NID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    else startForeground(NID, b.build());
  }

  private void lightScreen() {
    try {
      PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
      if (wl == null) wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "partner:wake");
      if (!wl.isHeld()) wl.acquire(WakeAlarm.RING_MAX + 60000L);
      if (screen == null) {
        screen = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "partner:wakescreen");
      }
      if (!screen.isHeld()) screen.acquire(15000L);
    } catch (Exception e) {
      // the sound still rings
    }
  }

  private int volPct() {
    try {
      JSONObject o = WakeAlarm.cfg(this);
      return o == null ? 100 : Math.max(30, Math.min(100, o.optInt("vol", 100)));
    } catch (Exception e) {
      return 100;
    }
  }

  /** Seconds the sound takes to rise from almost silent to full. Calm by default (2 minutes); the "are you awake" ring is shorter. */
  private float rampSec() {
    int r = 120;
    try {
      JSONObject o = WakeAlarm.cfg(this);
      if (o != null) r = Math.max(30, Math.min(300, o.optInt("ramp", 120)));
    } catch (Exception e) {
      // default
    }
    if ("check".equals(WakeAlarm.kind(this))) r = Math.min(r, 45);
    return r;
  }

  private void keepLoud() {
    try {
      AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
      int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
      int want = Math.max(1, Math.round(max * volPct() / 100f));
      if (am.getStreamVolume(AudioManager.STREAM_ALARM) < want) am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0);
      // the sound rises slowly (ease-in), so it wakes him gently instead of with a jolt
      float x = Math.min(1f, (System.currentTimeMillis() - t0) / (rampSec() * 1000f));
      float f = 0.03f + 0.97f * x * x;
      if (mp != null) mp.setVolume(f, f);
      if (mp != null && !mp.isPlaying()) mp.start();
      // soft vibration joins only when the sound is already about a third of the way up
      if (!vibOn && x >= 0.35f) {
        vibOn = true;
        softVibrate();
      }
    } catch (Exception e) {
      // try again on the next round
    }
  }

  private void softVibrate() {
    try {
      vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
      if (vib == null) return;
      if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(new long[] { 0, 350, 1650 }, new int[] { 0, 90, 0 }, 0));
      else vib.vibrate(new long[] { 0, 350, 1650 }, 0);
    } catch (Exception e) {
      // ignore
    }
  }

  private void startSound() {
    try {
      mp = new MediaPlayer();
      mp.setAudioAttributes(new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
      boolean calm = true;
      JSONObject o = WakeAlarm.cfg(this);
      if (o != null) calm = !"system".equals(o.optString("tone", "calm"));
      int rid = calm ? getResources().getIdentifier("wake_calm", "raw", getPackageName()) : 0;
      if (rid != 0) {
        android.content.res.AssetFileDescriptor afd = getResources().openRawResourceFd(rid);
        mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        afd.close();
      } else {
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        mp.setDataSource(this, u);
      }
      mp.setLooping(true);
      mp.setVolume(0.03f, 0.03f);
      mp.setOnPreparedListener(x -> x.start());
      mp.prepareAsync();
    } catch (Exception e) {
      // the notification and the later vibration still work
      vibOn = true;
      softVibrate();
    }
  }

  private void stopAll() {
    running = false;
    h.removeCallbacksAndMessages(null);
    try {
      if (mp != null) {
        mp.stop();
        mp.release();
      }
    } catch (Exception e) {
      // ignore
    }
    mp = null;
    try {
      if (vib != null) vib.cancel();
    } catch (Exception e) {
      // ignore
    }
    try {
      if (wl != null && wl.isHeld()) wl.release();
      if (screen != null && screen.isHeld()) screen.release();
    } catch (Exception e) {
      // ignore
    }
    if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE);
    else stopForeground(true);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    running = false;
    h.removeCallbacksAndMessages(null);
    try {
      if (mp != null) mp.release();
      if (vib != null) vib.cancel();
      if (wl != null && wl.isHeld()) wl.release();
      if (screen != null && screen.isHeld()) screen.release();
    } catch (Exception e) {
      // ignore
    }
    super.onDestroy();
  }
}
