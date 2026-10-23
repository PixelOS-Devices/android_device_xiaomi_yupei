/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.bypass;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/*
 * The smart charge driver caps the battery charge current by temperature
 * while bypass is requested and the scene is a game, so the charger feeds
 * the tablet instead of the battery. Bypass is requested with the bypass
 * bit (10) of smart_chg plus its enable bit, and the high limit table is
 * picked with the game scene. The kernel only bypasses between 5 and 90%.
 */
public class BypassTileService extends TileService {

    private static final String TAG = "XiaomiBypass";

    private static final String NODE_DIR = "/sys/devices/platform/soc/soc:smart_charge/";
    private static final String SMART_CHG = NODE_DIR + "smart_chg";
    private static final String SCENE = NODE_DIR + "scene";

    private static final int BYPASS_ON = (1 << 10) | 1;
    private static final int BYPASS_OFF = 1 << 10;
    private static final int SCENE_GAME = 18;
    private static final int SCENE_NORMAL = 0;

    @Override
    public void onStartListening() {
        updateTile(isEnabled());
    }

    @Override
    public void onClick() {
        boolean enable = !isEnabled();
        if (enable) {
            write(SCENE, SCENE_GAME);
            write(SMART_CHG, BYPASS_ON);
        } else {
            write(SMART_CHG, BYPASS_OFF);
            write(SCENE, SCENE_NORMAL);
        }
        updateTile(isEnabled());
    }

    private void updateTile(boolean enabled) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    // the kernel does not report the bypass bit back, but the scene sticks
    private static boolean isEnabled() {
        try (BufferedReader reader = new BufferedReader(new FileReader(SCENE))) {
            String line = reader.readLine();
            return line != null && Integer.parseInt(line.trim()) == SCENE_GAME;
        } catch (IOException | NumberFormatException e) {
            Log.w(TAG, "Failed to read " + SCENE, e);
            return false;
        }
    }

    private static void write(String path, int value) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(Integer.toString(value));
        } catch (IOException e) {
            Log.w(TAG, "Failed to write " + value + " to " + path, e);
        }
    }
}
