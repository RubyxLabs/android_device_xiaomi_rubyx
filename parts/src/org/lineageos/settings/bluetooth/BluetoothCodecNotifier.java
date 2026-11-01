/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.bluetooth;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;

/** Supplies the codec notification used by Ruby's stock MediaTek host stack. */
public final class BluetoothCodecNotifier {
    private static final String TAG = "RubyBluetoothCodec";
    // Vendor OGF 0x3f + OCF 0x195 = HCI opcode 0xfd95.
    private static final int CODEC_STATE_OCF = 0x195;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter mAdapter;
    private BluetoothA2dp mA2dp;
    private boolean mProfileRequested;
    private VendorCallback mCallback;
    private BluetoothDevice mLastDevice;
    private byte[] mLastParameters;

    public BluetoothCodecNotifier(Context context) {
        mContext = context.getApplicationContext();
        BluetoothManager manager = mContext.getSystemService(BluetoothManager.class);
        mAdapter = manager == null ? null : manager.getAdapter();
    }

    public void start() {
        if (mAdapter == null) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        mContext.registerReceiver(mReceiver, filter, Manifest.permission.BLUETOOTH_PRIVILEGED,
                mHandler, Context.RECEIVER_EXPORTED);
        requestProfile();
        synchronizeCodec();
    }

    private void requestProfile() {
        if (!mProfileRequested) {
            mProfileRequested = mAdapter.getProfileProxy(mContext, mProfileListener,
                    BluetoothProfile.A2DP);
        }
    }

    private final BluetoothProfile.ServiceListener mProfileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    mHandler.post(() -> {
                        mA2dp = (BluetoothA2dp) proxy;
                        clearLastRequest();
                        synchronizeCodec();
                    });
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    mHandler.post(() -> {
                        mA2dp = null;
                        clearLastRequest();
                    });
                }
            };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF
                        || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    unregisterCallback();
                    return;
                }
                if (state != BluetoothAdapter.STATE_ON) {
                    return;
                }
                requestProfile();
                clearLastRequest();
            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())
                    || BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED.equals(intent.getAction())) {
                clearLastRequest();
            }
            synchronizeCodec();
        }
    };

    private void clearLastRequest() {
        mLastDevice = null;
        mLastParameters = null;
    }

    private void unregisterCallback() {
        VendorCallback callback = mCallback;
        mCallback = null;
        clearLastRequest();
        if (callback != null) {
            try {
                mAdapter.unregisterBluetoothHciVendorSpecificCallback(callback);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot unregister vendor callback", e);
            }
        }
    }

    private void synchronizeCodec() {
        if (!mAdapter.isEnabled() || mA2dp == null) {
            return;
        }
        try {
            if (mCallback == null) {
                VendorCallback callback = new VendorCallback();
                mAdapter.registerBluetoothHciVendorSpecificCallback(Collections.emptySet(),
                        mContext.getMainExecutor(), callback);
                mCallback = callback;
            }
            // Read the selected codec from Bluetooth itself, never from broadcast extras.
            for (BluetoothDevice device : mAdapter.getActiveDevices(BluetoothProfile.A2DP)) {
                if (device == null) {
                    continue;
                }
                BluetoothCodecStatus status = mA2dp.getCodecStatus(device);
                BluetoothCodecConfig codec = status == null ? null : status.getCodecConfig();
                if (codec == null) {
                    continue;
                }
                int type = codec.getCodecType();
                // These Android codec IDs match the stock IDs. Vendor codec IDs differ
                // between ROMs; leave them alone until their wire mapping is established.
                if (type < BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC
                        || type > BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
                    return;
                }
                byte[] parameters = {
                        (byte) ((int) codec.getCodecSpecific3() == 0 ? 0 : 1),
                        (byte) (type + 1), 0, 0
                };
                if (device.equals(mLastDevice) && Arrays.equals(parameters, mLastParameters)) {
                    return;
                }
                mAdapter.sendBluetoothHciVendorSpecificCommand(CODEC_STATE_OCF, parameters);
                mLastDevice = device;
                mLastParameters = parameters;
                Log.i(TAG, "Requested stock codec notification: codecType=" + type
                        + " lowLatency=" + parameters[0]);
                return;
            }
        } catch (RuntimeException e) {
            clearLastRequest();
            Log.e(TAG, "Cannot notify Bluetooth firmware of selected codec", e);
        }
    }

    private final class VendorCallback
            implements BluetoothAdapter.BluetoothHciVendorSpecificCallback {
        @Override
        public void onCommandStatus(int ocf, int status) {
            if (this == mCallback && ocf == CODEC_STATE_OCF) {
                reportResult(status);
            }
        }

        @Override
        public void onCommandComplete(int ocf, byte[] returnParameters) {
            if (this == mCallback && ocf == CODEC_STATE_OCF) {
                reportResult(returnParameters.length == 0 ? -1 : returnParameters[0] & 0xff);
            }
        }

        private void reportResult(int status) {
            if (status == 0) {
                Log.i(TAG, "Stock codec notification accepted by controller");
            } else {
                clearLastRequest();
                Log.w(TAG, "Stock codec notification failed: status=" + status);
            }
        }

        @Override
        public void onEvent(int code, byte[] data) {}
    }
}
