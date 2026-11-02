/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.touchservice.pencilengine;

interface IPencilEngine {
    void registerListener(IBinder callback);
    void unregisterListener();
    int triggerFunction(int function, int value);
    void registerListenerByPackageName(IBinder callback, String packageName);
}
