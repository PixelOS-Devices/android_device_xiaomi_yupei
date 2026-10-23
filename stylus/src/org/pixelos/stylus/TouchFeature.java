/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.stylus;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import vendor.xiaomi.hw.touchfeature.ITouchFeature;

final class TouchFeature {

    private static final String TAG = "XiaomiStylus";
    private static final String SERVICE = ITouchFeature.DESCRIPTOR + "/default";

    private static final int TOUCH_ID = 0;

    // Tells the touch driver which pen is connected over Bluetooth
    static final int MODE_PEN_CONNECTION = 20;

    private TouchFeature() {}

    static void setModePackageName(int mode, String name) {
        IBinder binder = ServiceManager.waitForDeclaredService(SERVICE);
        if (binder == null) {
            Log.e(TAG, "Touch feature service is not available");
            return;
        }

        try {
            ITouchFeature.Stub.asInterface(binder).setModePackageName(TOUCH_ID, mode, name);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set touch mode " + mode + " name to " + name, e);
        }
    }

    static void setModeLongValue(int mode, int[] values) {
        IBinder binder = ServiceManager.waitForDeclaredService(SERVICE);
        if (binder == null) {
            Log.e(TAG, "Touch feature service is not available");
            return;
        }

        try {
            ITouchFeature.Stub.asInterface(binder).setModeLongValue(TOUCH_ID, mode,
                    values.length, values);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set touch mode " + mode + " data", e);
        }
    }

    static void setModeValue(int mode, int value) {
        IBinder binder = ServiceManager.waitForDeclaredService(SERVICE);
        if (binder == null) {
            Log.e(TAG, "Touch feature service is not available");
            return;
        }

        try {
            ITouchFeature.Stub.asInterface(binder).setModeValue(TOUCH_ID, mode, value);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set touch mode " + mode + " to " + value, e);
        }
    }
}
