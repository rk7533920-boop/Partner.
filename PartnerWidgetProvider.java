package com.partner.diary;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Base of the four home screen widgets. Every update redraws all of them from the latest snapshot. */
public abstract class PartnerWidgetProvider extends AppWidgetProvider {

  @Override
  public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
    PartnerWidgets.refresh(c);
  }
}
