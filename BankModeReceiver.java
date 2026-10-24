package com.partner.diary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires when a banking-mode pause is over and turns the accessibility service back on. */
public class BankModeReceiver extends BroadcastReceiver {

  @Override
  public void onReceive(Context context, Intent intent) {
    LockdownAcc.endBank(context);
  }
}
