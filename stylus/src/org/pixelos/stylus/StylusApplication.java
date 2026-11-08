/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.stylus;

import android.app.Application;
import android.content.Context;
import android.preference.PreferenceManager;
import android.provider.Settings;

import com.xiaomi.touchservice.R;

public class StylusApplication extends Application {

    // Xiaomi pen apps only give the pencil engine their package name from this version on
    private static final String PENCIL_ENGINE_VERSION = "stylus_pencil_engine_version";

    private PenPairing mPenPairing;
    private StylusDeviceListener mStylusDeviceListener;

    @Override
    public void onCreate() {
        super.onCreate();

        Settings.System.putString(getContentResolver(), PENCIL_ENGINE_VERSION, "0.3.2");
        PreferenceManager.setDefaultValues(createDeviceProtectedStorageContext(),
                PreferenceManager.getDefaultSharedPreferencesName(this), Context.MODE_PRIVATE,
                R.xml.stylus_settings, true);

        mStylusDeviceListener = new StylusDeviceListener(this);
        mStylusDeviceListener.start();

        mPenPairing = new PenPairing(this);
        mPenPairing.start();
    }
}
