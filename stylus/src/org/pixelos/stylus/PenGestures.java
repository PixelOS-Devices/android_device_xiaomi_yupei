/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.stylus;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.xiaomi.touchservice.pencilengine.PencilEngineManagerService;

import java.util.Arrays;
import java.util.List;

/*
 * The Focus Pen Pro sends its squeeze, double tap and swipe gestures in
 * HID report 2, one keyboard usage per gesture. The kernel keeps that
 * report off the input devices, so the gestures come from hidraw and each
 * one runs its action here as an injected key or scroll. A pen app
 * that asked the pencil engine for the gestures gets the double tap and
 * swipes itself while it is in front, as the button keys stock sends it.
 */
final class PenGestures {

    private static final String TAG = "XiaomiStylus";

    // HID keyboard usages of the gestures in report 2
    private static final int USAGE_SQUEEZE = 0x6e;
    private static final int USAGE_DOUBLE_TAP = 0x58;
    private static final int USAGE_SWIPE_UP = 0x61;
    private static final int USAGE_SWIPE_DOWN = 0x5b;

    static final String SQUEEZE = "squeeze";
    static final String DOUBLE_TAP = "double_tap";
    static final String SWIPE_UP = "swipe_up";
    static final String SWIPE_DOWN = "swipe_down";

    static final String ACTION_NONE = "none";
    static final String ACTION_PIE = "pie";
    static final String ACTION_NOTES = "notes";
    static final String ACTION_SCREENSHOT = "screenshot";
    static final String ACTION_ASSISTANT = "assistant";
    static final String ACTION_RECENTS = "recents";
    static final String ACTION_PLAY_PAUSE = "play_pause";
    static final String ACTION_SCROLL_UP = "scroll_up";
    static final String ACTION_SCROLL_DOWN = "scroll_down";

    // pen haptic types and strength stock uses for a double tap and a swipe
    private static final int HAPTIC_DOUBLE_TAP = 3;
    private static final int HAPTIC_SWIPE = 4;
    private static final int HAPTIC_STRENGTH = 128;

    // mouse wheel steps one swipe scrolls by, most apps ignore page keys
    private static final float SCROLL_STEPS = 8;

    private static final List<String> PIE_ACTIONS = Arrays.asList(
            ACTION_NOTES, ACTION_SCREENSHOT, ACTION_ASSISTANT);

    private final Context mContext;
    private final PenMipp mMipp;
    private final InputManager mInputManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final PenPie mPie;

    PenGestures(Context context, PenMipp mipp) {
        mContext = context;
        mMipp = mipp;
        mInputManager = context.getSystemService(InputManager.class);
        mPie = new PenPie(context, this::run);
    }

    void onReport(int usage) {
        String gesture = gestureFor(usage);
        if (gesture != null) {
            mHandler.post(() -> onGesture(gesture));
        }
    }

    private void onGesture(String gesture) {
        if (!mContext.getSystemService(PowerManager.class).isInteractive()) {
            return;
        }

        if (!SQUEEZE.equals(gesture)
                && PencilEngineManagerService.wantsGestures(focusedPackage())) {
            Log.i(TAG, "Pen " + gesture + ", passing it to the app");
            vibrate(gesture);
            injectKey(appKeyCodeFor(gesture));
            return;
        }

        String action = actionFor(gesture);
        Log.i(TAG, "Pen " + gesture + ", running " + action);
        if (ACTION_NONE.equals(action)) {
            return;
        }

        vibrate(gesture);
        run(action);
    }

    void run(String action) {
        if (ACTION_PIE.equals(action)) {
            mPie.toggle(PIE_ACTIONS);
            return;
        }
        if (ACTION_SCROLL_UP.equals(action)) {
            injectScroll(SCROLL_STEPS);
            return;
        }
        if (ACTION_SCROLL_DOWN.equals(action)) {
            injectScroll(-SCROLL_STEPS);
            return;
        }

        int keyCode = keyCodeFor(action);
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            injectKey(keyCode);
        }
    }

    private void vibrate(String gesture) {
        if (DOUBLE_TAP.equals(gesture)) {
            mMipp.vibrate(HAPTIC_DOUBLE_TAP, HAPTIC_STRENGTH);
        } else if (SWIPE_UP.equals(gesture) || SWIPE_DOWN.equals(gesture)) {
            mMipp.vibrate(HAPTIC_SWIPE, HAPTIC_STRENGTH);
        }
    }

    private void injectKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD);
        mInputManager.injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        mInputManager.injectInputEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP),
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    private void injectScroll(float steps) {
        DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = metrics.widthPixels / 2f;
        coords.y = metrics.heightPixels / 2f;
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, steps);

        long now = SystemClock.uptimeMillis();
        MotionEvent scroll = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                new MotionEvent.PointerProperties[] { properties },
                new MotionEvent.PointerCoords[] { coords }, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        mInputManager.injectInputEvent(scroll, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        scroll.recycle();
    }

    private static String focusedPackage() {
        try {
            ActivityTaskManager.RootTaskInfo info =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            if (info == null || info.topActivity == null) {
                return null;
            }
            return info.topActivity.getPackageName();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get the focused app", e);
            return null;
        }
    }

    private static String gestureFor(int usage) {
        switch (usage) {
            case USAGE_SQUEEZE:
                return SQUEEZE;
            case USAGE_DOUBLE_TAP:
                return DOUBLE_TAP;
            case USAGE_SWIPE_UP:
                return SWIPE_UP;
            case USAGE_SWIPE_DOWN:
                return SWIPE_DOWN;
            default:
                return null;
        }
    }

    private static int appKeyCodeFor(String gesture) {
        switch (gesture) {
            case DOUBLE_TAP:
                return KeyEvent.KEYCODE_BUTTON_8;
            case SWIPE_UP:
                return KeyEvent.KEYCODE_BUTTON_9;
            default:
                return KeyEvent.KEYCODE_BUTTON_10;
        }
    }

    private static String actionFor(String gesture) {
        switch (gesture) {
            case SQUEEZE:
                return ACTION_PIE;
            case SWIPE_UP:
                return ACTION_SCROLL_UP;
            case SWIPE_DOWN:
                return ACTION_SCROLL_DOWN;
            default:
                return ACTION_NONE;
        }
    }

    private static int keyCodeFor(String action) {
        switch (action) {
            case ACTION_NOTES:
                return KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL;
            case ACTION_SCREENSHOT:
                return KeyEvent.KEYCODE_SYSRQ;
            case ACTION_ASSISTANT:
                return KeyEvent.KEYCODE_ASSIST;
            case ACTION_RECENTS:
                return KeyEvent.KEYCODE_APP_SWITCH;
            case ACTION_PLAY_PAUSE:
                return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            default:
                return KeyEvent.KEYCODE_UNKNOWN;
        }
    }
}
