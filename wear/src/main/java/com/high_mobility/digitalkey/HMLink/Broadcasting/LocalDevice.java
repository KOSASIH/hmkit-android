package com.high_mobility.digitalkey.HMLink.Broadcasting;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelUuid;
import android.util.Log;

import com.high_mobility.digitalkey.HMLink.Constants;
import com.high_mobility.digitalkey.HMLink.Device;
import com.high_mobility.digitalkey.HMLink.LinkException;
import com.high_mobility.digitalkey.HMLink.Shared.AccessCertificate;
import com.high_mobility.digitalkey.HMLink.Shared.DeviceCertificate;
import com.high_mobility.digitalkey.Utils;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

/**
 * Created by ttiganik on 12/04/16.
 */
public class LocalDevice extends Device {
    private static final String TAG = LocalDevice.class.getSimpleName();

    static final boolean ALLOWS_MULTIPLE_LINKS = false;

    public enum State { BLUETOOTH_UNAVAILABLE, IDLE, BROADCASTING }

    Context ctx;
    Storage storage;
    byte[] privateKey;
    byte[] CAPublicKey;
    LocalDeviceCallback callback;

    BluetoothManager mBluetoothManager;
    BluetoothAdapter mBluetoothAdapter;
    BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    BluetoothGattServer GATTServer;
    GATTServerCallback gattServerCallback;

    BluetoothGattCharacteristic readCharacteristic;
    BluetoothGattCharacteristic writeCharacteristic;

    static LocalDevice instance = null;
    public State state = State.IDLE;
    Link[] links = new Link[0];

    public static LocalDevice getInstance() {
        if (instance == null) {
            instance = new LocalDevice();
        }

        return instance;
    }

    public void registerCallback(LocalDeviceCallback callback) {
        this.callback = callback;
    }

    public void setDeviceCertificate(DeviceCertificate certificate, byte[] privateKey, byte[] CAPublicKey, Context ctx) {
        this.ctx = ctx;
        storage = new Storage(ctx);
        storage.deviceCertificate = certificate;
        this.privateKey = privateKey;
        this.CAPublicKey = CAPublicKey;
        storage = new Storage(ctx);
        createAdapter();
    }

    public AccessCertificate[] getRegisteredCertificates() {
        return storage.getRegisteredCertificates();
    }

    public AccessCertificate[] getStoredCertificates() {
        return storage.getStoredCertificates();
    }


    public Link[] getLinks() {
        return links;
    }

    public void startBroadcasting() throws LinkException {
        if (ALLOWS_MULTIPLE_LINKS == false && links.length != 0) {
            return;
        }

        checkIfBluetoothIsEnabled();

        createGATTService(); // TODO: test start/stop

        // start advertising
        if (mBluetoothLeAdvertiser == null) return;

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(Utils.ADVERTISE_UUID))
                .build();

        mBluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback);
    }

    public void stopBroadcasting() {
//        if (mBluetoothLeAdvertiser == null) return;
//        mBluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
        // TODO: this clears the GATT server as well and GATTServer.sendResponse fails with nullPointer.
        // TODO: Figure out how to stop with working server
    }

    public void registerCertificate(AccessCertificate certificate) throws LinkException {
        if (this.certificate == null) {
            throw new LinkException(LinkException.LinkExceptionCode.INTERNAL_ERROR);
        }

        if (Arrays.equals(this.certificate.getSerial(), certificate.getProviderSerial()) == false) {
            throw new LinkException(LinkException.LinkExceptionCode.INTERNAL_ERROR);
        }

        storage.storeCertificate(certificate, CAPublicKey);
    }

    public void storeCertificate(AccessCertificate certificate) throws LinkException {
        storage.storeCertificate(certificate, CAPublicKey);
    }

    public void revokeCertificate(byte[] serial) throws LinkException {
        if (storage.certWithGainingSerial(serial) == null
                || storage.certWithProvidingSerial(serial) == null) {
            throw new LinkException(LinkException.LinkExceptionCode.INTERNAL_ERROR);
        }

        storage.deleteCertificateWithGainingSerial(serial);
        storage.deleteCertificateWithProvidingSerial(serial);
    }

    public void reset() {
        storage.resetStorage();
        stopBroadcasting();

        try {
            startBroadcasting();
        } catch (LinkException e) {
            e.printStackTrace();
        }
    }

    void setAdapterName() {
        byte[] serialBytes = new byte[4];
        new Random().nextBytes(serialBytes);
        mBluetoothAdapter.setName(Utils.hexFromBytes(serialBytes));
    }

    private void createGATTService() {
        if (GATTServer.getService(Constants.SERVICE_UUID) == null) {
            Log.i(TAG, "createGATTService");
            // create the service
            BluetoothGattService service = new BluetoothGattService(Constants.SERVICE_UUID,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY);

            readCharacteristic =
                    new BluetoothGattCharacteristic(Constants.READ_CHAR_UUID,
                            BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                            BluetoothGattCharacteristic.PERMISSION_READ);

            UUID confUUUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
            readCharacteristic.addDescriptor(new BluetoothGattDescriptor(confUUUID, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));

            writeCharacteristic =
                    new BluetoothGattCharacteristic(Constants.WRITE_CHAR_UUID,
                            BluetoothGattCharacteristic.PROPERTY_WRITE,
                            BluetoothGattCharacteristic.PERMISSION_WRITE);

            service.addCharacteristic(readCharacteristic);
            service.addCharacteristic(writeCharacteristic);

            GATTServer.addService(service);
        }
        else {
            Log.i(TAG, "createGATTService: service already exists");
        }
    }

    private void createAdapter() {
        mBluetoothManager = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        setAdapterName();

        gattServerCallback = new GATTServerCallback(this);
        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        GATTServer = mBluetoothManager.openGattServer(ctx, gattServerCallback);
    }

    private void checkIfBluetoothIsEnabled() throws LinkException {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            setState(State.BLUETOOTH_UNAVAILABLE);
            throw new LinkException(LinkException.LinkExceptionCode.BLUETOOTH_OFF);
        }

        if (!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            setState(State.BLUETOOTH_UNAVAILABLE);
            throw new LinkException(LinkException.LinkExceptionCode.UNSUPPORTED);
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "Start advertise " + mBluetoothAdapter.getName());
            setState(State.BROADCASTING);
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "Start Advertise Failed: " + errorCode);
            setState(State.IDLE);
        }
    };

    private void setState(State state) {
        if (this.state != state) {
            State oldState = this.state;
            this.state = state;
            Log.i(TAG, "set local device state from " + oldState + " to " + state);
            if (callback != null) {
                callback.localDeviceStateChanged(state, oldState);
            }
        }
    }

}
