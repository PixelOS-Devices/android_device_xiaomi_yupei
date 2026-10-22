/*
 * SPDX-FileCopyrightText: 2026 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.pixelos.stylus;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UEventObserver;
import android.util.Log;

import java.util.Arrays;
import java.util.UUID;

/*
 * Stock sets every Xiaomi pen up over its MIPP GATT service when it
 * connects. The tablet assigns the pen two touch ids, tells it the
 * transmit parameters, frequency and voltage the touch controller listens
 * for, the time and whether it is docked, and then tells the touch driver
 * the pen is ready. Every command goes to FE11 and the pen answers each on
 * FE12, which moves the setup to the next step. Later the touch driver
 * reports frequency hops in MIPP_PEN_STATE uevents, which are passed on to
 * the pen the same way.
 */
final class PenMipp {

    private static final String TAG = "XiaomiStylus";

    private static final UUID MIPP_SERVICE = UUID.fromString("0000fe10-aa6c-462a-964a-7f2ed5b3e512");
    private static final UUID MIPP_COMMAND = UUID.fromString("0000fe11-aa6c-462a-964a-7f2ed5b3e512");
    private static final UUID MIPP_REPLY = UUID.fromString("0000fe12-aa6c-462a-964a-7f2ed5b3e512");
    private static final UUID CCC_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID DEVICE_INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    private static final UUID FIRMWARE_REVISION = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb");

    private static final int MTU = 512;

    // a write the stack is still busy with is retried like stock does
    private static final long RETRY_DELAY_MS = 100;
    private static final int MAX_RETRIES = 10;

    // touch modes the pen setup reports to the touch driver
    private static final int MODE_PEN_ID = 22;
    private static final int MODE_PEN_ACTIVE_STATE = 29;
    private static final int MODE_PEN_READY = 37;

    // what the touch driver gets in MODE_PEN_ID once the pen took a change
    private static final int ACK_FREQUENCY = 49;
    private static final int ACK_VOLTAGE = 50;

    private static final int HOP_FREQUENCY = 49;

    private static final byte[] CMD_READ_TOUCH_ID = { 0x00, 0x00 };
    private static final byte[] CMD_READ_FEATURE = { 0x01, 0x00 };
    private static final byte[] CMD_READ_WORK_STATUS = { 0x02, 0x00 };
    private static final byte[] CMD_READ_PAINTING_VERSION = { 0x03, 0x00 };
    private static final byte[] CMD_ASSIGN_ID = { 0x04, 0x00 };
    private static final byte[] CMD_READ_ACTIVE_STATE = { 0x52, 0x00 };

    // transmit frequency settings, indexed by the frequency the touch controller hopped to
    private static final byte[][] FREQUENCIES = {
        { (byte) 0x9b, 0x6f, (byte) 0xef, 0x43 },
        { (byte) 0xe9, 0x5b, (byte) 0xcd, 0x37 },
        { 0x40, (byte) 0x9c, (byte) 0xdd, 0x2c },
        { 0x20, 0x4e, (byte) 0xf7, 0x21 },
    };
    private static final byte[][] FREQUENCY_TAILS = {
        { 0x28, 0x00 }, { 0x3c, 0x00 }, { 0x3c, 0x00 }, { 0x3c, 0x00 },
    };

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private BluetoothGatt mGatt;
    private BluetoothGattCharacteristic mCommand;
    private boolean mStartWithActiveState;
    private BluetoothGattDescriptor mReplyDescriptor;
    private String mFirmware;

    private boolean mReady;
    private int mIdStage = 1;
    private byte mTouchId;
    private int mFrequency;
    private boolean mDocked;

    private final UEventObserver mObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEvent event) {
            String state = event.get("MIPP_PEN_STATE");
            String hall3 = event.get("POWER_SUPPLY_PEN_HALL3");
            String hall4 = event.get("POWER_SUPPLY_PEN_HALL4");
            mHandler.post(() -> {
                if (state != null) {
                    onPenState(state);
                }
                if (hall3 != null && hall4 != null) {
                    onHall(hall3, hall4);
                }
            });
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mReady) {
                send(screenCommand(Intent.ACTION_SCREEN_ON.equals(intent.getAction())));
            }
        }
    };

    private final BluetoothGattCallback mCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) {
            mHandler.post(() -> {
                if (gatt != mGatt) {
                    return;
                }
                if (state == BluetoothGatt.STATE_CONNECTED) {
                    gatt.discoverServices();
                } else if (state == BluetoothGatt.STATE_DISCONNECTED) {
                    Log.i(TAG, "Pen GATT disconnected: " + status);
                    stop();
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            mHandler.post(() -> onServices(gatt, status));
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            mHandler.post(() -> {
                if (gatt != mGatt) {
                    return;
                }
                if (FIRMWARE_REVISION.equals(characteristic.getUuid())
                        && status == BluetoothGatt.GATT_SUCCESS && value != null) {
                    mFirmware = new String(value).trim();
                    Log.i(TAG, "Pen firmware " + mFirmware);
                }
                enableReplies(gatt);
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                int status) {
            mHandler.post(() -> {
                if (gatt == mGatt && MIPP_REPLY.equals(descriptor.getCharacteristic().getUuid())) {
                    gatt.requestMtu(MTU);
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            mHandler.post(() -> {
                if (gatt != mGatt || mReady) {
                    return;
                }
                Log.i(TAG, "Setting up the pen, mtu " + mtu);
                send(mStartWithActiveState ? CMD_READ_ACTIVE_STATE : CMD_READ_TOUCH_ID);
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, byte[] value) {
            if (MIPP_REPLY.equals(characteristic.getUuid())) {
                mHandler.post(() -> onReply(value));
            }
        }
    };

    PenMipp(Context context) {
        mContext = context;
    }

    void start() {
        mObserver.startObserving("MIPP_PEN_STATE");
        mObserver.startObserving("POWER_SUPPLY_PEN_HALL3");

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenReceiver, filter, null, mHandler);
    }

    void connect(BluetoothDevice device, boolean startWithActiveState) {
        if (mGatt != null) {
            return;
        }

        Log.i(TAG, "Connecting to the pen GATT of " + device.getAddress());
        mStartWithActiveState = startWithActiveState;
        mGatt = device.connectGatt(mContext, false, mCallback, BluetoothDevice.TRANSPORT_LE);
    }

    void disconnect() {
        if (mGatt != null) {
            mGatt.disconnect();
        }
        stop();
    }

    private void stop() {
        if (mGatt != null) {
            mGatt.close();
            mGatt = null;
        }
        if (mReady) {
            TouchFeature.setModeValue(MODE_PEN_READY, 0);
        }
        mCommand = null;
        mReplyDescriptor = null;
        mFirmware = null;
        mReady = false;
        mIdStage = 1;
        mFrequency = 0;
    }

    private void onServices(BluetoothGatt gatt, int status) {
        if (gatt != mGatt) {
            return;
        }

        BluetoothGattService service = gatt.getService(MIPP_SERVICE);
        if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
            Log.w(TAG, "Pen has no MIPP service: " + status);
            return;
        }

        mCommand = service.getCharacteristic(MIPP_COMMAND);
        BluetoothGattCharacteristic reply = service.getCharacteristic(MIPP_REPLY);
        if (mCommand == null || reply == null) {
            Log.w(TAG, "Pen MIPP service is incomplete");
            return;
        }

        gatt.setCharacteristicNotification(reply, true);
        mReplyDescriptor = reply.getDescriptor(CCC_DESCRIPTOR);

        // the touch firmware gets the pen firmware version once the pen is set up
        BluetoothGattService info = gatt.getService(DEVICE_INFO_SERVICE);
        BluetoothGattCharacteristic firmware =
                info != null ? info.getCharacteristic(FIRMWARE_REVISION) : null;
        if (firmware == null || !gatt.readCharacteristic(firmware)) {
            enableReplies(gatt);
        }
    }

    private void enableReplies(BluetoothGatt gatt) {
        if (mReplyDescriptor == null) {
            gatt.requestMtu(MTU);
            return;
        }
        gatt.writeDescriptor(mReplyDescriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
    }

    private void onReply(byte[] reply) {
        if (reply == null || reply.length < 2) {
            return;
        }
        Log.i(TAG, "Pen replied " + Arrays.toString(reply));

        int cmd = reply[0] & 0xff;
        int arg = reply[1] & 0xff;

        switch (cmd) {
            case 0x53: // active state changed
                if (reply.length >= 3 && arg == 1) {
                    TouchFeature.setModeValue(MODE_PEN_ACTIVE_STATE, reply[2] & 0xff);
                }
                return;
            case 0xd2: // active state
                if (reply.length >= 3 && arg == 1) {
                    TouchFeature.setModeValue(MODE_PEN_ACTIVE_STATE, reply[2] & 0xff);
                }
                if (!mReady) {
                    send(CMD_READ_TOUCH_ID);
                }
                return;
            case 0x80: // touch id
                if (arg == 1 && !mReady) {
                    send(CMD_READ_FEATURE);
                }
                return;
            case 0x81: // feature
                if (arg == 2 && !mReady) {
                    send(CMD_READ_WORK_STATUS);
                }
                return;
            case 0x82: // work status
                if (arg == 2 && !mReady) {
                    send(CMD_READ_PAINTING_VERSION);
                }
                return;
            case 0x83: // painting version
                if (arg == 3 && !mReady) {
                    send(CMD_ASSIGN_ID);
                }
                return;
            case 0x84: // assigned id
                if (arg == 1 && reply.length >= 3) {
                    onIdAssigned(reply[2]);
                }
                return;
        }

        if (arg != 0) {
            return;
        }

        switch (cmd) {
            case 0xb0: // transmit parameters
                if (!mReady) {
                    send(frequencyCommand());
                }
                break;
            case 0xb1: // frequency
                if (mReady) {
                    TouchFeature.setModeValue(MODE_PEN_ID, ACK_FREQUENCY);
                } else {
                    send(voltageCommand());
                }
                break;
            case 0xb2: // voltage
                if (mReady) {
                    TouchFeature.setModeValue(MODE_PEN_ID, ACK_VOLTAGE);
                } else if (mIdStage == 1) {
                    // the pen takes a second id the same way
                    mIdStage = 2;
                    send(CMD_ASSIGN_ID);
                } else {
                    mIdStage = 1;
                    send(timestampCommand());
                }
                break;
            case 0xb3: // timestamp
                if (!mReady) {
                    send(hallCommand());
                }
                break;
            case 0xd1: // hall state
                if (!mReady) {
                    mReady = true;
                    if (mFirmware != null) {
                        TouchFeature.setModePackageName(MODE_PEN_ID, mFirmware);
                    }
                    TouchFeature.setModeValue(MODE_PEN_READY, 1);
                    PowerManager pm = mContext.getSystemService(PowerManager.class);
                    send(screenCommand(pm == null || pm.isInteractive()));
                    Log.i(TAG, "Pen is set up");
                }
                break;
        }
    }

    private void onIdAssigned(byte id) {
        mTouchId = id;
        int value = (mIdStage == 1 ? 1 : 3) | ((id & 0xff) << 8);
        Log.i(TAG, "Pen id " + (id & 0xff) + " for stage " + mIdStage);
        TouchFeature.setModeValue(MODE_PEN_ID, value);
        if (!mReady) {
            send(parameterCommand());
        }
    }

    private void onPenState(String state) {
        int value;
        try {
            value = Integer.parseInt(state);
        } catch (NumberFormatException e) {
            return;
        }

        int cmd = (value >> 16) & 0xff;
        mTouchId = (byte) ((value >> 8) & 0xff);
        if (cmd == HOP_FREQUENCY && mCommand != null) {
            mFrequency = value & 0xff;
            Log.i(TAG, "Touch hopped to frequency " + mFrequency);
            send(frequencyCommand());
        }
    }

    private void onHall(String hall3, String hall4) {
        boolean docked = "0".equals(hall3) || "0".equals(hall4);
        if (docked == mDocked) {
            return;
        }

        mDocked = docked;
        if (mReady) {
            send(hallCommand());
        }
    }

    private void send(byte[] value) {
        send(value, 0);
    }

    private void send(byte[] value, int attempt) {
        if (mGatt == null || mCommand == null) {
            return;
        }

        int ret = mGatt.writeCharacteristic(mCommand, value, mCommand.getWriteType());
        if (ret == BluetoothGatt.GATT_SUCCESS) {
            Log.i(TAG, "Sent " + Arrays.toString(value));
            return;
        }

        if (attempt < MAX_RETRIES) {
            mHandler.postDelayed(() -> send(value, attempt + 1), RETRY_DELAY_MS);
            return;
        }
        Log.w(TAG, "Failed to send " + Arrays.toString(value) + " to the pen: " + ret);
    }

    private byte[] parameterCommand() {
        byte[] cmd = new byte[32];
        cmd[0] = 0x30;
        cmd[1] = 0x1e;
        // the tablet Bluetooth address, like stock
        System.arraycopy(hostAddress(), 0, cmd, 2, 6);
        cmd[8] = 0x03;
        cmd[9] = mTouchId;
        System.arraycopy(new byte[] { (byte) 0xa0, 0x1e, 0x3e, 0x17, 0x1a, 0x04, 0x1a }, 0,
                cmd, 10, 7);
        cmd[17] = (byte) (mIdStage == 2 ? 0x01 : 0x03);
        System.arraycopy(new byte[] { 0x00, 0x00, 0x0d, 0x68, 0x00, 0x00, 0x05, 0x28, 0x00,
                0x00, 0x0d, 0x68 }, 0, cmd, 18, 12);
        cmd[30] = (byte) 0xf4;
        cmd[31] = 0x01;
        return cmd;
    }

    private static byte[] hostAddress() {
        byte[] address = new byte[6];
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        String mac = adapter != null ? adapter.getAddress() : null;
        if (mac == null) {
            Log.w(TAG, "No local Bluetooth address for the pen");
            return address;
        }
        String[] parts = mac.split(":");
        for (int i = 0; i < address.length && i < parts.length; i++) {
            address[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return address;
    }

    private byte[] frequencyCommand() {
        int index = mFrequency >= 0 && mFrequency < FREQUENCIES.length ? mFrequency : 0;
        byte[] cmd = new byte[10];
        cmd[0] = 0x31;
        cmd[1] = 0x08;
        cmd[2] = mTouchId;
        cmd[3] = 0x03;
        System.arraycopy(FREQUENCIES[index], 0, cmd, 4, 4);
        System.arraycopy(FREQUENCY_TAILS[index], 0, cmd, 8, 2);
        return cmd;
    }

    private byte[] voltageCommand() {
        return new byte[] { 0x32, 0x03, mTouchId, 0x28, 0x00 };
    }

    private byte[] timestampCommand() {
        long now = System.currentTimeMillis() / 1000;
        return new byte[] { 0x33, 0x04, (byte) (now >> 24), (byte) (now >> 16),
                (byte) (now >> 8), (byte) now };
    }

    private static byte[] screenCommand(boolean on) {
        return new byte[] { 0x61, 0x01, (byte) (on ? 1 : 0) };
    }

    private byte[] hallCommand() {
        return new byte[] { 0x51, 0x01, (byte) (mDocked ? 0 : 1) };
    }
}
