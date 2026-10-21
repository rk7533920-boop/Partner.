package com.partner.diary;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import java.util.List;

/**
 * Android only lists an app under "Digital assistant app" when it also declares a speech recognition
 * service. This one does no recognition itself: it hands the audio request to the phone's normal
 * recognizer (Google), so voice typing keeps working even if Android makes this the default.
 */
public class PartnerRecognitionService extends RecognitionService {

    private SpeechRecognizer inner;

    private ComponentName pickEngine() {
        List<ResolveInfo> list = getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        ComponentName any = null;
        if (list != null) {
            for (ResolveInfo r : list) {
                if (r.serviceInfo == null || getPackageName().equals(r.serviceInfo.packageName)) continue;
                ComponentName c = new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
                if (r.serviceInfo.packageName.contains("google")) return c;
                if (any == null) any = c;
            }
        }
        return any;
    }

    private void drop() {
        try {
            if (inner != null) {
                inner.cancel();
                inner.destroy();
            }
        } catch (Exception e) {
            // ignore
        }
        inner = null;
    }

    @Override
    protected void onStartListening(Intent intent, final Callback cb) {
        drop();
        try {
            ComponentName eng = pickEngine();
            if (eng == null) {
                cb.error(SpeechRecognizer.ERROR_CLIENT);
                return;
            }
            inner = SpeechRecognizer.createSpeechRecognizer(this, eng);
            inner.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) { try { cb.readyForSpeech(p); } catch (Exception e) { } }
                @Override public void onBeginningOfSpeech() { try { cb.beginningOfSpeech(); } catch (Exception e) { } }
                @Override public void onRmsChanged(float v) { try { cb.rmsChanged(v); } catch (Exception e) { } }
                @Override public void onBufferReceived(byte[] b) { try { cb.bufferReceived(b); } catch (Exception e) { } }
                @Override public void onEndOfSpeech() { try { cb.endOfSpeech(); } catch (Exception e) { } }
                @Override public void onError(int code) { try { cb.error(code); } catch (Exception e) { } }
                @Override public void onResults(Bundle r) { try { cb.results(r); } catch (Exception e) { } }
                @Override public void onPartialResults(Bundle r) { try { cb.partialResults(r); } catch (Exception e) { } }
                @Override public void onEvent(int t, Bundle b) { }
            });
            inner.startListening(intent);
        } catch (Exception e) {
            try {
                cb.error(SpeechRecognizer.ERROR_CLIENT);
            } catch (Exception x) {
                // ignore
            }
        }
    }

    @Override
    protected void onStopListening(Callback cb) {
        try {
            if (inner != null) inner.stopListening();
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    protected void onCancel(Callback cb) {
        drop();
    }

    @Override
    public void onDestroy() {
        drop();
        super.onDestroy();
    }
}
