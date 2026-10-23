/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.bypass;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.io.FileWriter;
import java.io.IOException;

/*
 * Bypass charging votes the battery charging off in the MCA charge
 * interface while leaving the input on, so the charger powers the tablet
 * and the battery neither charges nor drains. The vote is held by the
 * kernel until it is withdrawn or the tablet reboots.
 */
final class Bypass {

    private static final String TAG = "XiaomiBypass";

    private static final String CHARGE_ENABLE =
            "/sys/devices/platform/soc/soc:mca_charge_interface/charge_enable";
    private static final String VOTER = "bypass all ";

    private static final String PREFS = "bypass";
    private static final String KEY_ENABLED = "enabled";

    private Bypass() {}

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static boolean setEnabled(Context context, boolean enabled) {
        if (!write(enabled)) {
            return false;
        }
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        TileService.requestListeningState(context,
                new ComponentName(context, BypassTileService.class));
        return true;
    }

    static void restore(Context context) {
        if (isEnabled(context)) {
            write(true);
        }
    }

    private static boolean write(boolean enabled) {
        try (FileWriter writer = new FileWriter(CHARGE_ENABLE)) {
            writer.write(VOTER + (enabled ? "0" : "1"));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + CHARGE_ENABLE, e);
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
