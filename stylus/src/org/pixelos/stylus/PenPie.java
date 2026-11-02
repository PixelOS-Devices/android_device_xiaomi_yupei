/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.stylus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import java.util.List;
import java.util.function.Consumer;

/*
 * A pie of actions that opens around the pen when it is squeezed, like the
 * stock shortcut panel. The pen position is only watched for a moment after
 * the squeeze, and the pie opens in the middle of the screen when the pen
 * isn't hovering.
 */
final class PenPie implements PieView.Listener {

    private static final String TAG = "XiaomiStylus";

    // how long to wait for the pen to show up before opening in the middle
    private static final long POSITION_TIMEOUT_MS = 100;
    // lets the pie leave the screen before a screenshot is taken
    private static final long ACTION_DELAY_MS = 200;

    private final Context mContext;
    private final Consumer<String> mRunner;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mShowInMiddle = () -> show(-1, -1);

    private InputMonitor mMonitor;
    private InputEventReceiver mReceiver;
    private WindowManager mWindowManager;
    private PieView mView;
    private List<String> mActions;

    private final BroadcastReceiver mScreenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dismiss();
        }
    };

    PenPie(Context context, Consumer<String> runner) {
        mContext = context;
        mRunner = runner;
        mContext.registerReceiver(mScreenOffReceiver,
                new IntentFilter(Intent.ACTION_SCREEN_OFF), null, mHandler);
    }

    void toggle(List<String> actions) {
        if (mView != null || mMonitor != null) {
            dismiss();
            return;
        }
        if (actions.isEmpty() || isKeyguardLocked()) {
            return;
        }
        if (actions.size() == 1) {
            mRunner.accept(actions.get(0));
            return;
        }

        mActions = actions;
        mMonitor = mContext.getSystemService(InputManager.class)
                .monitorGestureInput("PenPie", Display.DEFAULT_DISPLAY);
        mReceiver = new PenReceiver(mMonitor.getInputChannel());
        mHandler.postDelayed(mShowInMiddle, POSITION_TIMEOUT_MS);
    }

    private void show(float x, float y) {
        stopMonitor();

        Display display = mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        Context windowContext = mContext.createWindowContext(display,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        mWindowManager = windowContext.getSystemService(WindowManager.class);
        mView = new PieView(windowContext, mActions, x, y, this);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.setTitle("PenPie");
        mWindowManager.addView(mView, params);
    }

    private void dismiss() {
        stopMonitor();
        if (mView != null) {
            mWindowManager.removeViewImmediate(mView);
            mView = null;
        }
    }

    private void stopMonitor() {
        mHandler.removeCallbacks(mShowInMiddle);
        if (mMonitor != null) {
            mReceiver.dispose();
            mMonitor.dispose();
            mReceiver = null;
            mMonitor = null;
        }
    }

    @Override
    public void onSelected(String action) {
        dismiss();
        mHandler.postDelayed(() -> mRunner.accept(action), ACTION_DELAY_MS);
    }

    @Override
    public void onDismiss() {
        dismiss();
    }

    private static boolean isKeyguardLocked() {
        try {
            return WindowManagerGlobal.getWindowManagerService().isKeyguardLocked();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get the keyguard state", e);
            return true;
        }
    }

    private final class PenReceiver extends InputEventReceiver {

        PenReceiver(InputChannel channel) {
            super(channel, Looper.getMainLooper());
        }

        @Override
        public void onInputEvent(InputEvent event) {
            if (event instanceof MotionEvent
                    && ((MotionEvent) event).getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                float x = ((MotionEvent) event).getRawX();
                float y = ((MotionEvent) event).getRawY();
                mHandler.post(() -> {
                    if (mMonitor != null) {
                        show(x, y);
                    }
                });
            }
            finishInputEvent(event, false);
        }
    }
}
