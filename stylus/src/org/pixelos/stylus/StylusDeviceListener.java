/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.stylus;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.InputDevice;

/*
 * The touch controller only scans for the pen once it is told which pen
 * is connected over Bluetooth. Report the pen version of every Xiaomi
 * stylus input device, ORed with 0x10 while it is connected.
 */
final class StylusDeviceListener implements InputManager.InputDeviceListener {

    private static final String TAG = "XiaomiStylus";

    private static final int PEN_CONNECTED = 0x10;

    private final InputManager mInputManager;
    private final BluetoothAdapter mAdapter;
    private final PenMipp mMipp;
    private final PenReports mReports = new PenReports();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // input device id -> pen version
    private final SparseIntArray mPens = new SparseIntArray();

    StylusDeviceListener(Context context) {
        mInputManager = context.getSystemService(InputManager.class);
        mAdapter = context.getSystemService(BluetoothManager.class).getAdapter();
        mMipp = new PenMipp(context);
    }

    void start() {
        mMipp.start();
        mInputManager.registerInputDeviceListener(this, mHandler);

        for (int id : mInputManager.getInputDeviceIds()) {
            onInputDeviceAdded(id);
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        InputDevice device = mInputManager.getInputDevice(deviceId);
        if (device == null || mPens.indexOfKey(deviceId) >= 0) {
            return;
        }

        int version = getPenVersion(device.getVendorId(), device.getProductId());
        if (version == 0) {
            return;
        }

        mPens.put(deviceId, version);
        Log.i(TAG, "Pen " + device.getName() + " connected, version " + version);
        TouchFeature.setModeValue(TouchFeature.MODE_PEN_CONNECTION, version | PEN_CONNECTED);

        String address = device.getBluetoothAddress();
        if (address != null && mAdapter != null) {
            // stock reads the active state first only on this pen
            boolean activeStateFirst = device.getVendorId() == 0x0022
                    && device.getProductId() == 0x5081;
            mMipp.connect(mAdapter.getRemoteDevice(address), activeStateFirst);
        }
        if (version == 8) {
            mReports.start();
        }
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        int index = mPens.indexOfKey(deviceId);
        if (index < 0) {
            return;
        }

        int version = mPens.valueAt(index);
        mPens.removeAt(index);
        Log.i(TAG, "Pen disconnected, version " + version);
        TouchFeature.setModeValue(TouchFeature.MODE_PEN_CONNECTION, version);

        if (mPens.size() == 0) {
            mMipp.disconnect();
            mReports.stop();
        }
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {}

    private static int getPenVersion(int vendorId, int productId) {
        if (vendorId == 0x1915) {
            switch (productId) {
                case 0xeaea:
                    return 1;
                case 0x4d81:
                    return 2;
            }
        } else if (vendorId == 0x0022) {
            switch (productId) {
                case 0x3280:
                case 0x3281:
                case 0x4d80:
                    return 3;
                case 0x4e83:
                    return 4;
                case 0x3183:
                    return 5;
                case 0x3283:
                    return 6;
                case 0x4f84:
                    return 7;
                case 0x5081:
                    return 8;
            }
        }
        return 0;
    }
}
