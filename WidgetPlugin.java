package com.partner.diary;

import android.content.SharedPreferences;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Bridge between the page and the home screen widgets (see PartnerWidgets). */
@CapacitorPlugin(name = "Widget")
public class WidgetPlugin extends Plugin {

  // an app-opening widget button waits here until the window has focus
  static volatile String pendingAct = null;
  private static WidgetPlugin inst;

  @Override
  public void load() {
    inst = this;
  }

  static void ping() {
    if (inst != null) inst.notifyListeners("wact", new JSObject());
  }

  static void pingOps() {
    if (inst != null) inst.notifyListeners("wops", new JSObject());
  }

  /** The page sends the latest numbers. They are ignored while taps from the widget are still waiting to be merged. */
  @PluginMethod
  public void update(PluginCall call) {
    SharedPreferences sp = PartnerWidgets.sp(getContext());
    String ops = sp.getString("ops", "[]");
    if (ops == null || ops.length() <= 2) {
      sp.edit().putString("snap", call.getString("json", "")).apply();
      PartnerWidgets.refresh(getContext());
    }
    call.resolve();
  }

  @PluginMethod
  public void consume(PluginCall call) {
    String a = pendingAct;
    if (a != null && MainActivity.focused) pendingAct = null;
    else a = null;
    JSObject r = new JSObject();
    r.put("act", a == null ? "" : a);
    call.resolve(r);
  }

  /** Taps recorded by the widget since the last call (a JSON array). The list is emptied. */
  @PluginMethod
  public void ops(PluginCall call) {
    SharedPreferences sp = PartnerWidgets.sp(getContext());
    String s = sp.getString("ops", "[]");
    sp.edit().putString("ops", "[]").apply();
    JSObject r = new JSObject();
    r.put("ops", s == null ? "[]" : s);
    call.resolve(r);
  }
}
