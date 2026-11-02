/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.touchservice.pencilengine;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import java.util.Set;

/*
 * Xiaomi pen apps bind this service by name and ask it for the pen
 * gestures (touch film) while their editor is open. Stock then lets the
 * gesture keys through to the app in front. Only touch film is handled,
 * every other function is accepted and ignored.
 */
public final class PencilEngineManagerService extends Service {

    private static final String TAG = "XiaomiStylus";

    private static final int FUNCTION_TOUCH_FILM = 2;

    private static final SparseArray<Client> sClients = new SparseArray<>();
    private static final Set<String> sTouchFilmPackages = new ArraySet<>();

    private final IPencilEngine.Stub mBinder = new IPencilEngine.Stub() {
        @Override
        public void registerListener(IBinder callback) {
            register(callback, getPackageManager().getNameForUid(Binder.getCallingUid()));
        }

        @Override
        public void registerListenerByPackageName(IBinder callback, String packageName) {
            register(callback, packageName);
        }

        @Override
        public void unregisterListener() {
            unregister(Binder.getCallingPid());
        }

        @Override
        public int triggerFunction(int function, int value) {
            synchronized (sClients) {
                Client client = sClients.get(Binder.getCallingPid());
                if (client == null) {
                    return -1;
                }
                if (function == FUNCTION_TOUCH_FILM) {
                    setTouchFilm(client, value != 0);
                }
                return 0;
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public static boolean wantsGestures(String packageName) {
        synchronized (sClients) {
            return packageName != null && sTouchFilmPackages.contains(packageName);
        }
    }

    private static void register(IBinder callback, String packageName) {
        int pid = Binder.getCallingPid();
        if (callback == null || packageName == null) {
            return;
        }

        synchronized (sClients) {
            if (sClients.get(pid) != null) {
                return;
            }
            Client client = new Client(pid, packageName, callback);
            try {
                callback.linkToDeath(client, 0);
            } catch (RemoteException e) {
                return;
            }
            sClients.put(pid, client);
            Log.i(TAG, "Pencil engine client " + packageName + " registered");
        }
    }

    private static void unregister(int pid) {
        synchronized (sClients) {
            Client client = sClients.get(pid);
            if (client == null) {
                return;
            }
            setTouchFilm(client, false);
            client.mCallback.unlinkToDeath(client, 0);
            sClients.remove(pid);
            Log.i(TAG, "Pencil engine client " + client.mPackageName + " unregistered");
        }
    }

    private static void setTouchFilm(Client client, boolean enabled) {
        if (client.mTouchFilm == enabled) {
            return;
        }
        client.mTouchFilm = enabled;
        if (enabled) {
            sTouchFilmPackages.add(client.mPackageName);
        } else {
            sTouchFilmPackages.remove(client.mPackageName);
        }
        Log.i(TAG, "Pen gestures " + (enabled ? "go to " : "no longer go to ")
                + client.mPackageName);
    }

    private static final class Client implements IBinder.DeathRecipient {

        final int mPid;
        final String mPackageName;
        final IBinder mCallback;
        boolean mTouchFilm;

        Client(int pid, String packageName, IBinder callback) {
            mPid = pid;
            mPackageName = packageName;
            mCallback = callback;
        }

        @Override
        public void binderDied() {
            unregister(mPid);
        }
    }
}
