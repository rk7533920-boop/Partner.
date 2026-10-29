package com.partner.diary;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.telecom.Call;
import android.telecom.CallScreeningService;

/**
 * Sees every normal phone call, coming in and going out (Android 10 and newer, once Partner is chosen as the call screening app).
 * During the night window a call to or from somebody he did not allow is rejected. Every other call is written down with
 * the answer "allowed or not", so the lockdown service knows later whether a running call has to be ended at 11 PM.
 */
public class NightCallScreen extends CallScreeningService {

  @Override
  public void onScreenCall(Call.Details details) {
    CallResponse.Builder b = new CallResponse.Builder();
    try {
      long now = System.currentTimeMillis();
      String num = "";
      try {
        Uri h = details.getHandle();
        if (h != null && h.getSchemeSpecificPart() != null) num = h.getSchemeSpecificPart();
      } catch (Exception e) {
        // hidden number
      }
      Context c = this;
      NightRule.applyPending(c, now);
      boolean ok = NightRule.isEmergency(num) || NightRule.allowedNumber(c, num);
      boolean night = NightRule.activeEnd(c, now) > 0;
      SharedPreferences p = NightRule.sp(c);
      if (night && !ok) {
        b.setDisallowCall(true).setRejectCall(true);
        p.edit().putLong("nightLastBlock", now).apply();
      } else {
        // remembered so that a call he started before 11 PM with somebody not on the list can be ended when the night begins
        p.edit().putBoolean("curCallOk", ok).putLong("curCallTs", now).apply();
      }
    } catch (Exception e) {
      // answer below, a screener that does not answer would delay every call
    }
    respondToCall(details, b.build());
  }
}
