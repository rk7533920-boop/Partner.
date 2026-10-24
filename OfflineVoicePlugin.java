package com.partner.diary;

import android.content.res.AssetManager;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline Hindi voice. The Piper voice files are bundled in the APK under assets/hindi_voice
 * by the GitHub workflow, and sherpa-onnx runs them on the phone, no internet and no key.
 * JS side: Capacitor.Plugins.OfflineVoice.status() / warmup() / synth({text, speed}).
 */
@CapacitorPlugin(name = "OfflineVoice")
public class OfflineVoicePlugin extends Plugin {

  private static final String DIR = "hindi_voice";
  private final ExecutorService pool = Executors.newSingleThreadExecutor();
  private OfflineTts tts;

  private boolean hasAsset(String name) {
    try {
      String[] f = getContext().getAssets().list(DIR);
      if (f != null) {
        for (String n : f) {
          if (n.equals(name)) return true;
        }
      }
    } catch (Exception e) {
      // ignore
    }
    return false;
  }

  private String onnxName() {
    try {
      String[] f = getContext().getAssets().list(DIR);
      if (f != null) {
        for (String n : f) {
          if (n.endsWith(".onnx")) return n;
        }
      }
    } catch (Exception e) {
      // ignore
    }
    return null;
  }

  private String voiceName() {
    try (InputStream in = getContext().getAssets().open(DIR + "/VOICE_NAME")) {
      return new String(readAll(in), "UTF-8").trim();
    } catch (Exception e) {
      return "Hindi voice";
    }
  }

  private static byte[] readAll(InputStream in) throws Exception {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) > 0) o.write(b, 0, n);
    return o.toByteArray();
  }

  private void copyAsset(AssetManager am, String src, File dst) throws Exception {
    String[] kids = am.list(src);
    if (kids == null || kids.length == 0) {
      InputStream in;
      try {
        in = am.open(src);
      } catch (Exception e) {
        dst.mkdirs(); // an empty folder
        return;
      }
      File parent = dst.getParentFile();
      if (parent != null) parent.mkdirs();
      try (InputStream i = in; OutputStream out = new FileOutputStream(dst)) {
        byte[] b = new byte[65536];
        int n;
        while ((n = i.read(b)) > 0) out.write(b, 0, n);
      }
      return;
    }
    dst.mkdirs();
    for (String k : kids) copyAsset(am, src + "/" + k, new File(dst, k));
  }

  private static void deleteTree(File f) {
    if (f == null || !f.exists()) return;
    File[] kids = f.listFiles();
    if (kids != null) {
      for (File k : kids) deleteTree(k);
    }
    f.delete();
  }

  private synchronized OfflineTts engine() throws Exception {
    if (tts != null) return tts;
    AssetManager am = getContext().getAssets();
    String onnx = onnxName();
    if (onnx == null || !hasAsset("tokens.txt")) {
      throw new Exception("This build has no offline voice file");
    }
    // espeak-ng needs real files, so the small data folder is copied out of the APK once
    File root = new File(getContext().getFilesDir(), DIR);
    File data = new File(root, "espeak-ng-data");
    File mark = new File(root, ".ok");
    String tag = voiceName();
    boolean fresh = false;
    if (mark.exists()) {
      try (InputStream in = new java.io.FileInputStream(mark)) {
        fresh = tag.equals(new String(readAll(in), "UTF-8").trim()) && data.exists();
      } catch (Exception e) {
        fresh = false;
      }
    }
    if (!fresh) {
      deleteTree(data);
      root.mkdirs();
      copyAsset(am, DIR + "/espeak-ng-data", data);
      try (OutputStream out = new FileOutputStream(mark)) {
        out.write(tag.getBytes("UTF-8"));
      }
    }
    OfflineTtsVitsModelConfig v = new OfflineTtsVitsModelConfig();
    v.setModel(DIR + "/" + onnx);
    v.setTokens(DIR + "/tokens.txt");
    v.setDataDir(data.getAbsolutePath());
    OfflineTtsModelConfig m = new OfflineTtsModelConfig();
    m.setVits(v);
    m.setNumThreads(2);
    m.setDebug(false);
    m.setProvider("cpu");
    OfflineTtsConfig cfg = new OfflineTtsConfig();
    cfg.setModel(m);
    tts = new OfflineTts(am, cfg);
    return tts;
  }

  private static void le16(byte[] o, int p, int v) {
    o[p] = (byte) (v & 0xff);
    o[p + 1] = (byte) ((v >> 8) & 0xff);
  }

  private static void le32(byte[] o, int p, int v) {
    le16(o, p, v & 0xffff);
    le16(o, p + 2, (v >> 16) & 0xffff);
  }

  private static void put(byte[] o, int p, String s) {
    for (int i = 0; i < s.length(); i++) o[p + i] = (byte) s.charAt(i);
  }

  private static byte[] wav(float[] s, int rate) {
    int n = s.length;
    byte[] o = new byte[44 + n * 2];
    put(o, 0, "RIFF");
    le32(o, 4, 36 + n * 2);
    put(o, 8, "WAVE");
    put(o, 12, "fmt ");
    le32(o, 16, 16);
    le16(o, 20, 1);
    le16(o, 22, 1);
    le32(o, 24, rate);
    le32(o, 28, rate * 2);
    le16(o, 32, 2);
    le16(o, 34, 16);
    put(o, 36, "data");
    le32(o, 40, n * 2);
    for (int i = 0; i < n; i++) {
      float f = s[i];
      if (f > 1f) f = 1f;
      if (f < -1f) f = -1f;
      le16(o, 44 + i * 2, (short) (f * 32767f));
    }
    return o;
  }

  @PluginMethod
  public void status(PluginCall call) {
    JSObject r = new JSObject();
    boolean ok = onnxName() != null && hasAsset("tokens.txt");
    r.put("available", ok);
    r.put("voice", ok ? voiceName() : "");
    r.put("loaded", tts != null);
    call.resolve(r);
  }

  @PluginMethod
  public void warmup(final PluginCall call) {
    pool.execute(
      new Runnable() {
        @Override
        public void run() {
          try {
            engine();
            call.resolve();
          } catch (Throwable e) {
            call.reject(String.valueOf(e.getMessage()));
          }
        }
      }
    );
  }

  @PluginMethod
  public void synth(final PluginCall call) {
    final String text = call.getString("text", "");
    Float sp = call.getFloat("speed", 1f);
    final float speed = sp == null ? 1f : Math.max(0.6f, Math.min(1.6f, sp));
    if (text == null || text.trim().isEmpty()) {
      call.reject("Nothing to say");
      return;
    }
    pool.execute(
      new Runnable() {
        @Override
        public void run() {
          try {
            OfflineTts t = engine();
            GeneratedAudio a = t.generate(text, 0, speed);
            float[] samples = a.getSamples();
            if (samples == null || samples.length == 0) {
              call.reject("The voice made no sound");
              return;
            }
            int rate = a.getSampleRate();
            JSObject r = new JSObject();
            r.put("audio", Base64.encodeToString(wav(samples, rate), Base64.NO_WRAP));
            r.put("sampleRate", rate);
            call.resolve(r);
          } catch (Throwable e) {
            call.reject(String.valueOf(e.getMessage()));
          }
        }
      }
    );
  }
}
