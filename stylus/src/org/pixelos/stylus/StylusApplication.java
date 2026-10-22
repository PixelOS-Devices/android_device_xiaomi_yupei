/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.stylus;

import android.app.Application;

public class StylusApplication extends Application {

    private PenPairing mPenPairing;
    private StylusDeviceListener mStylusDeviceListener;

    @Override
    public void onCreate() {
        super.onCreate();

        mStylusDeviceListener = new StylusDeviceListener(this);
        mStylusDeviceListener.start();

        mPenPairing = new PenPairing(this);
        mPenPairing.start();
    }
}
