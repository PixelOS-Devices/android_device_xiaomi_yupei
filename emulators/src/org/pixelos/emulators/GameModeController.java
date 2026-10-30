/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.emulators;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArraySet;
import android.util.Log;

import java.util.Arrays;
import java.util.Set;

/*
 * GameManagerService only turns on the GAME power mode for apps that
 * declare the game category, which most emulators don't. Hold the mode
 * while a listed emulator or a real game is on top, so emulators get the
 * same power HAL treatment as games.
 */
final class GameModeController implements ActivityManager.OnUidImportanceListener {

    private static final String TAG = "EmulatorGameMode";

    private static final int MODE_GAME = 15;
    private static final long REASSERT_DELAY_MS = 500;

    private final ActivityManager mActivityManager;
    private final PackageManager mPackageManager;
    private final IPowerManager mPowerManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Set<String> mEmulators;
    private final Set<Integer> mTopUids = new ArraySet<>();
    private final Runnable mReassert = () -> setGameMode(true);

    private boolean mEnabled;

    GameModeController(Context context) {
        mActivityManager = context.getSystemService(ActivityManager.class);
        mPackageManager = context.getPackageManager();
        mPowerManager = IPowerManager.Stub.asInterface(
                ServiceManager.getService(Context.POWER_SERVICE));
        mEmulators = new ArraySet<>(Arrays.asList(
                context.getResources().getStringArray(R.array.emulator_packages)));
    }

    void start() {
        mActivityManager.addOnUidImportanceListener(this,
                RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
    }

    @Override
    public void onUidImportance(int uid, int importance) {
        final boolean top = importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        mHandler.post(() -> update(uid, top));
    }

    private void update(int uid, boolean top) {
        if (top && isGame(uid)) {
            mTopUids.add(uid);
        } else {
            mTopUids.remove(uid);
        }

        final boolean enable = !mTopUids.isEmpty();
        if (enable == mEnabled) {
            return;
        }
        mEnabled = enable;
        setGameMode(enable);

        // GameManagerService may turn the mode off for the same switch
        // once it sees a non-game on top, so say it once more
        mHandler.removeCallbacks(mReassert);
        if (enable) {
            mHandler.postDelayed(mReassert, REASSERT_DELAY_MS);
        }
    }

    private boolean isGame(int uid) {
        final String[] packages = mPackageManager.getPackagesForUid(uid);
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (mEmulators.contains(pkg)) {
                return true;
            }
            try {
                final ApplicationInfo info = mPackageManager.getApplicationInfo(pkg, 0);
                if (info.category == ApplicationInfo.CATEGORY_GAME) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
        return false;
    }

    private void setGameMode(boolean enabled) {
        try {
            mPowerManager.setPowerMode(MODE_GAME, enabled);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set game mode " + enabled, e);
        }
    }
}
