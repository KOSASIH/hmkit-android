package com.high_mobility.HMLink;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;

/**
 * Created by ttiganik on 01/06/16.
 */
public class SharedBle {
    Context ctx;

    BluetoothManager mBluetoothManager;
    BluetoothAdapter mBluetoothAdapter;

    public Handler mainThreadHandler;

    static SharedBle instance;
    public static SharedBle getInstance(Context context) {
        if (instance == null) {
            instance = new SharedBle();
            instance.ctx = context;
            instance.mainThreadHandler = new Handler(context.getMainLooper());
            instance.createAdapter();
        }

        return instance;
    }

    public BluetoothManager getManager() {
        return mBluetoothManager;
    }

    public BluetoothAdapter getAdapter() {
        return mBluetoothAdapter;
    }

    public boolean isBluetoothSupported() {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    public boolean isBluetoothOn() {
        return (getAdapter() != null && getAdapter().isEnabled());
    }

    void createAdapter() {
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = mBluetoothManager.getAdapter();
            setAdapterName();
        }
    }

    void setAdapterName() {

    }
}
