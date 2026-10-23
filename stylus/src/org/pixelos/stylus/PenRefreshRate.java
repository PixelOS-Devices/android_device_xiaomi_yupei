/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.stylus;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/*
 * The touch controller only schedules pen scans while the panel runs at
 * 120 Hz, so stock SurfaceFlinger switches to it whenever the pen is in
 * use. Pin the refresh rate to 120 Hz while the pen is off the dock and
 * put the user's own limits back once it is docked or gone.
 */
final class PenRefreshRate {

    private static final String TAG = "XiaomiStylus";

    private static final String PEN_REFRESH_RATE = "120.0";
    private static final String[] KEYS = {
        Settings.System.MIN_REFRESH_RATE, Settings.System.PEAK_REFRESH_RATE,
    };

    private static final String PREFS = "pen_refresh_rate";
    private static final String PREF_PINNED = "pinned";

    private final ContentResolver mResolver;
    private final SharedPreferences mPrefs;

    PenRefreshRate(Context context) {
        mResolver = context.getContentResolver();
        mPrefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // a previous run may have died with the refresh rate pinned
        setPinned(false);
    }

    void setPinned(boolean pinned) {
        if (pinned == mPrefs.getBoolean(PREF_PINNED, false)) {
            return;
        }

        SharedPreferences.Editor editor = mPrefs.edit();
        for (String key : KEYS) {
            if (pinned) {
                editor.putString(key, Settings.System.getString(mResolver, key));
                Settings.System.putString(mResolver, key, PEN_REFRESH_RATE);
            } else {
                Settings.System.putString(mResolver, key, mPrefs.getString(key, null));
                editor.remove(key);
            }
        }
        editor.putBoolean(PREF_PINNED, pinned).commit();
        Log.i(TAG, (pinned ? "Pinned" : "Released") + " the refresh rate for the pen");
    }
}
