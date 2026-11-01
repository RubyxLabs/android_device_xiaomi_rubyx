/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings;

import android.app.Application;
import android.os.UserHandle;
import android.util.Log;

import org.lineageos.settings.bluetooth.BluetoothCodecNotifier;

public final class PartsApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            return;
        }
        try {
            new BluetoothCodecNotifier(this).start();
        } catch (RuntimeException e) {
            Log.e("XiaomiParts", "Cannot start Bluetooth codec notifier", e);
        }
    }
}
