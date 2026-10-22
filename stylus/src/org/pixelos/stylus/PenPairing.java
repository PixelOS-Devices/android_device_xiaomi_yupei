/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.stylus;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.UEventObserver;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/*
 * The pen charger reads the Bluetooth address of an attached pen and the
 * charging driver reports it in a power supply uevent. Bond with that pen
 * over LE, the way the stock Bluetooth extension does, so it shows up as
 * an input device without going through Bluetooth settings. Stock bonds
 * with the address directly. If that fails, the next report of the address
 * scans for the pen first and bonds with what the scan finds.
 */
final class PenPairing {

    private static final String TAG = "XiaomiStylus";

    private static final String PEN_MAC_KEY = "POWER_SUPPLY_PEN_MAC";
    private static final long SCAN_TIMEOUT_MS = 30000;

    private final Context mContext;
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private String mPendingAddress;
    private boolean mScanning;
    private boolean mBondFromScan;

    // pens a direct bond failed for, which get scanned for next time
    private final Set<String> mScanFirst = new HashSet<>();

    private final Runnable mScanTimeout = this::stopScan;

    private final UEventObserver mObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEvent event) {
            String address = parseAddress(event.get(PEN_MAC_KEY));
            if (address != null) {
                mHandler.post(() -> onPenAttached(address));
            }
        }
    };

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            stopScan();
            bond(result.getDevice(), true);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (!results.isEmpty()) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, results.get(0));
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Pen scan failed: " + errorCode);
            mScanning = false;
        }
    };

    private final BroadcastReceiver mPairingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice device =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (device == null || !device.getAddress().equals(mPendingAddress)) {
                return;
            }

            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
            if (variant != BluetoothDevice.PAIRING_VARIANT_CONSENT
                    && variant != BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION) {
                return;
            }

            // The pen was just attached to this tablet, so there is nothing to confirm
            device.setPairingConfirmation(true);
            abortBroadcast();
        }
    };

    private final BroadcastReceiver mBondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice device =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (device == null || !device.getAddress().equals(mPendingAddress)) {
                return;
            }

            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE);
            int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                    BluetoothDevice.BOND_NONE);

            if (state == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "Bonded with pen " + device.getAddress());
                mScanFirst.remove(device.getAddress());
            } else if (state == BluetoothDevice.BOND_NONE
                    && previous == BluetoothDevice.BOND_BONDING) {
                Log.w(TAG, "Bonding with pen " + device.getAddress() + " failed");
                if (mBondFromScan) {
                    mScanFirst.remove(device.getAddress());
                } else {
                    mScanFirst.add(device.getAddress());
                }
            }
        }
    };

    PenPairing(Context context) {
        mContext = context;
        mAdapter = context.getSystemService(BluetoothManager.class).getAdapter();
    }

    void start() {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mPairingReceiver, filter, Context.RECEIVER_EXPORTED);
        mContext.registerReceiver(mBondReceiver,
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                Context.RECEIVER_EXPORTED);

        mObserver.startObserving(PEN_MAC_KEY);
    }

    private void onPenAttached(String address) {
        if (mAdapter == null || !mAdapter.isEnabled()) {
            Log.i(TAG, "Pen " + address + " attached with Bluetooth off");
            return;
        }

        BluetoothDevice device = mAdapter.getRemoteDevice(address);
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            if (device.isConnected()) {
                return;
            }

            Log.i(TAG, "Pen " + address + " attached, connecting");
            device.connect();
            return;
        }

        if (mScanning || device.getBondState() == BluetoothDevice.BOND_BONDING) {
            return;
        }

        if (!mScanFirst.contains(address)) {
            Log.i(TAG, "Pen " + address + " attached");
            bond(device, false);
            return;
        }

        // Scan so the bond uses the address type the pen advertises with
        BluetoothLeScanner scanner = mAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            return;
        }

        Log.i(TAG, "Pen " + address + " attached, scanning");
        mPendingAddress = address;
        mScanning = true;
        scanner.startScan(
                List.of(new ScanFilter.Builder().setDeviceAddress(address).build()),
                new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build(),
                mScanCallback);
        mHandler.postDelayed(mScanTimeout, SCAN_TIMEOUT_MS);
    }

    private void stopScan() {
        if (!mScanning) {
            return;
        }

        mScanning = false;
        mHandler.removeCallbacks(mScanTimeout);

        BluetoothLeScanner scanner = mAdapter.getBluetoothLeScanner();
        if (scanner != null) {
            scanner.stopScan(mScanCallback);
        }
    }

    private void bond(BluetoothDevice device, boolean fromScan) {
        Log.i(TAG, "Bonding with pen " + device.getAddress());
        mPendingAddress = device.getAddress();
        mBondFromScan = fromScan;
        device.createBond(BluetoothDevice.TRANSPORT_LE);
    }

    /*
     * The driver prints the address as a hex number, so leading zero bytes
     * are dropped. Pad it back to six bytes and add the separators.
     */
    private static String parseAddress(String value) {
        if (value == null || value.isEmpty() || value.length() > 12) {
            return null;
        }

        long mac;
        try {
            mac = Long.parseLong(value, 16);
        } catch (NumberFormatException e) {
            return null;
        }

        if (mac == 0) {
            return null;
        }

        String hex = String.format(Locale.ROOT, "%012X", mac);
        StringBuilder address = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                address.append(':');
            }
            address.append(hex, i, i + 2);
        }
        return address.toString();
    }
}
