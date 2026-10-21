package com.partner.diary;

import android.service.voice.VoiceInteractionService;

public class PartnerAssistService extends VoiceInteractionService {
    @Override
    public void onReady() {
        super.onReady();
        // Android keeps this service alive when Partner is the default assistant, so use it to
        // bring "Hey Partner" back after a restart if the phone allows it.
        try {
            HotwordService.restore(this);
        } catch (Exception e) {
            // ignore
        }
    }
}
