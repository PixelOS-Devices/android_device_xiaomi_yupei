/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.emulators;

import android.app.Application;

public class EmulatorApplication extends Application {

    private GameModeController mGameModeController;

    @Override
    public void onCreate() {
        super.onCreate();

        mGameModeController = new GameModeController(this);
        mGameModeController.start();
    }
}
