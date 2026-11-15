package com.partner.diary;

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Base64;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a QR code (or a common barcode) from one camera frame. The page opens the camera itself (getUserMedia) and sends
 * a small grey-scale frame: {w, h, data} where data is w*h bytes (brightness 0..255), base64. Answer: {text}, empty when
 * no code was found in this frame. Used by the wake-up task "Scan my QR code": the alarm stops only for the saved code.
 */
@CapacitorPlugin(name = "Qr")
public class QrPlugin extends Plugin {

    /** {granted}: the camera permission is on. The page checks this before it opens the camera for the wake-up task. */
    @PluginMethod
    public void camera(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
        call.resolve(r);
    }

    /** Shows the Android permission question for the camera. The answer is read with camera(). */
    @PluginMethod
    public void requestCamera(PluginCall call) {
        boolean ok = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!ok && getActivity() != null) {
            ActivityCompat.requestPermissions(getActivity(), new String[] { Manifest.permission.CAMERA }, 7311);
        }
        JSObject r = new JSObject();
        r.put("granted", ok);
        call.resolve(r);
    }

    @PluginMethod
    public void decode(PluginCall call) {
        Integer w = call.getInt("w");
        Integer h = call.getInt("h");
        String d = call.getString("data");
        if (w == null || h == null || d == null || w < 20 || h < 20 || w > 2000 || h > 2000) {
            call.reject("bad_image");
            return;
        }
        byte[] lum;
        try {
            lum = Base64.decode(d, Base64.DEFAULT);
        } catch (Exception e) {
            call.reject("bad_data");
            return;
        }
        if (lum.length < w * h) {
            call.reject("bad_size");
            return;
        }
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> f = new ArrayList<>();
        f.add(BarcodeFormat.QR_CODE);
        f.add(BarcodeFormat.EAN_13);
        f.add(BarcodeFormat.EAN_8);
        f.add(BarcodeFormat.UPC_A);
        f.add(BarcodeFormat.CODE_128);
        f.add(BarcodeFormat.CODE_39);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, f);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        MultiFormatReader reader = new MultiFormatReader();
        reader.setHints(hints);
        JSObject r = new JSObject();
        String text = "";
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(lum, w, h, 0, 0, w, h, false);
            Result res = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
            if (res != null && res.getText() != null) text = res.getText();
        } catch (Exception e) {
            // no code in this frame
        } finally {
            reader.reset();
        }
        r.put("text", text);
        call.resolve(r);
    }
}
