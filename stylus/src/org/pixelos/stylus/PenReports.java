/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.stylus;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;

/*
 * The Focus Pen and Focus Pen Pro measure tip pressure themselves and send
 * it over Bluetooth as HID report 5, next to the transmit frequencies they use.
 * The touch firmware needs that report to tell contact from hover, so
 * pass every report on to the touch HAL the way stock does. Report 2
 * carries the pen gestures.
 */
final class PenReports {

    private static final String TAG = "XiaomiStylus";

    private static final String HIDRAW_CLASS = "/sys/class/hidraw";
    // hidraw nodes link to their HID device, named bus:vendor:product.instance
    private static final String PEN_HID_ID = "0005:%04X:%04X.";

    private static final int REPORT_GESTURE = 2;
    private static final int REPORT_PRESSURE = 5;
    private static final int REPORT_PRESSURE_LEN = 15;
    private static final int MODE_PEN_PRESSURE = 1088;

    private final PenGestures mGestures;

    private Thread mThread;

    PenReports(PenGestures gestures) {
        mGestures = gestures;
    }

    synchronized void start(int vendorId, int productId) {
        if (mThread != null && mThread.isAlive()) {
            return;
        }

        String node = findPenNode(String.format(PEN_HID_ID, vendorId, productId));
        if (node == null) {
            Log.w(TAG, "No hidraw node for the pen");
            return;
        }

        mThread = new Thread(() -> forward(node), "PenReports");
        mThread.start();
    }

    synchronized void stop() {
        if (mThread != null) {
            mThread.interrupt();
            mThread = null;
        }
    }

    private static String findPenNode(String hidId) {
        File[] nodes = new File(HIDRAW_CLASS).listFiles();
        if (nodes == null) {
            return null;
        }

        for (File node : nodes) {
            try {
                if (Files.readSymbolicLink(node.toPath()).toString().contains(hidId)) {
                    return "/dev/" + node.getName();
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to read " + node, e);
            }
        }
        return null;
    }

    private void forward(String node) {
        Log.i(TAG, "Forwarding pen reports from " + node);
        byte[] buf = new byte[64];
        try (FileInputStream in = new FileInputStream(node)) {
            while (!Thread.currentThread().isInterrupted()) {
                int len = in.read(buf);
                if (len < 0) {
                    break;
                }
                if (len > 1 && buf[0] == REPORT_GESTURE) {
                    mGestures.onReport(buf[1] & 0xff);
                    continue;
                }
                if (len != REPORT_PRESSURE_LEN || buf[0] != REPORT_PRESSURE) {
                    continue;
                }

                int[] report = new int[len];
                for (int i = 0; i < len; i++) {
                    report[i] = buf[i] & 0xff;
                }
                TouchFeature.setModeLongValue(MODE_PEN_PRESSURE, report);
            }
        } catch (IOException e) {
            Log.i(TAG, "Pen reports stopped: " + e.getMessage());
        }
    }
}
