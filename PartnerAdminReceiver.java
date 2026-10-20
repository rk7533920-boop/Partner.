package com.partner.diary;

import android.app.admin.DeviceAdminReceiver;

// While this is active, Android will not let Partner be uninstalled until it is turned off first.
public class PartnerAdminReceiver extends DeviceAdminReceiver {}
