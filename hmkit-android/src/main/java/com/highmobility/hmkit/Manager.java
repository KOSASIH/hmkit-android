package com.highmobility.hmkit;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.highmobility.crypto.AccessCertificate;
import com.highmobility.crypto.DeviceCertificate;
import com.highmobility.btcore.HMBTCore;
import com.highmobility.hmkit.Error.DownloadAccessCertificateError;
import com.highmobility.utils.Base64;
import com.highmobility.value.Bytes;
import com.highmobility.value.DeviceSerial;
import com.highmobility.value.Issuer;
import com.highmobility.value.PrivateKey;
import com.highmobility.value.PublicKey;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by ttiganik on 03/08/16.
 */
public class Manager {
    private static final String TAG = "Manager";

    public enum Environment {
        TEST, STAGING, PRODUCTION
    }

    public enum LoggingLevel {
        NONE(0), DEBUG(1), ALL(2);

        private Integer level;

        LoggingLevel(int level) {
            this.level = level;
        }

        public int getValue() {
            return level;
        }
    }

    /**
     * DownloadCallback is used to notify the user about the certificate download result.
     */
    public interface DownloadCallback {
        /**
         * Invoked if the certificate download was successful.
         *
         * @param serial the vehicle or charger serial.
         */
        void onDownloaded(DeviceSerial serial);

        /**
         * Invoked when there was an error with the certificate download.
         *
         * @param error The error
         */
        void onDownloadFailed(DownloadAccessCertificateError error);
    }

    public static LoggingLevel loggingLevel = LoggingLevel.ALL;

    /**
     * The environment of the Web Service. If initialized, call {@link #terminate()} before
     * changing it.
     */
    public static Environment environment = Environment.PRODUCTION;

    /**
     * Set a custom environment url.
     */
    public static String customEnvironmentBaseUrl = null;

    HMBTCore core = new HMBTCore();
    BTCoreInterface coreInterface;
    private SharedBle ble;
    Storage storage;
    Context context;

    private static Manager instance;
    DeviceCertificate certificate;
    PrivateKey privateKey;
    PublicKey caPublicKey;

    private Scanner scanner;
    private Broadcaster broadcaster;
    WebService webService;
    Telematics telematics;

    Handler mainHandler;
    Handler workHandler = null;
    private Timer coreClockTimer;
    private HandlerThread workThread = new HandlerThread("BTCoreThread");

    SharedBle getBle() {
        if (ble == null) ble = new SharedBle(context);
        return ble;
    }

    /**
     * @return The singleton instance of Manager.
     */
    public static Manager getInstance() {
        if (instance == null) {
            instance = new Manager();
        }

        return instance;
    }

    /**
     * Initialize the SDK with the necessary properties. Call this before using any other
     * functionality.
     *
     * @param certificate The broadcaster certificate.
     * @param privateKey  32 byte private key with elliptic curve Prime 256v1.
     * @param caPublicKey 64 byte public key of the Certificate Authority.
     * @param ctx     the application context
     * @throws IllegalArgumentException if the parameters are invalid.
     * @throws IllegalStateException if the manager is still initialized and connected to links.
     */
    public void initialize(DeviceCertificate certificate,
                           PrivateKey privateKey,
                           PublicKey caPublicKey,
                           Context ctx) throws IllegalArgumentException, IllegalStateException {
        if (this.context != null) {
            terminate();
        }

        this.context = ctx.getApplicationContext();

        storage = new Storage(context);
        webService = new WebService(context);

        this.caPublicKey = caPublicKey;
        this.certificate = certificate;
        this.privateKey = privateKey;

        mainHandler = new Handler(context.getMainLooper());

        if (workThread.getState() == Thread.State.NEW) {
            workThread.start();
            workHandler = new Handler(workThread.getLooper());
        }

        if (coreInterface == null) {
            coreInterface = new BTCoreInterface(this);
            core.HMBTCoreInit(coreInterface);
        }

        startClock();

        Log.i(TAG, "Initialized High-Mobility " + getInfoString() + certificate.toString());
    }

    /**
     * Initialize the SDK with the necessary properties. Call this before using any other
     * functionality.
     *
     * @param certificate     The broadcaster certificate, in Base64.
     * @param privateKey      32 byte private key with elliptic curve Prime 256v1 in Base64.
     * @param issuerPublicKey 64 byte public key of the Certificate Authority in Base64.
     * @param context         the application context
     * @throws IllegalArgumentException if the parameters are invalid.
     */
    public void initialize(String certificate, String privateKey, String issuerPublicKey, Context
            context) throws IllegalArgumentException {
        DeviceCertificate decodedCert = new DeviceCertificate(new Bytes(Base64.decode(certificate)));
        PrivateKey decodedPrivateKey = new PrivateKey(Base64.decode(privateKey));
        PublicKey decodedIssuerPublicKey = new PublicKey(Base64.decode(issuerPublicKey));
        initialize(decodedCert, decodedPrivateKey, decodedIssuerPublicKey, context);
    }

    /**
     * Call this function when the SDK is not used anymore - for instance when killing the app.
     * It clears all the internal processes, unregisters all BroadcastReceivers and enables
     * re-initializing the SDK with new certificates.
     * <p>
     * Terminate will fail if a connected link still exists. Disconnect all the links before
     * terminating the SDK.
     * <p>
     * Stored certificates are not deleted.
     *
     * @throws IllegalStateException when there are links still connected.
     */
    public void terminate() throws IllegalStateException {
        if (context == null) return; // already not initialized

        if (broadcaster.getLinks().size() > 0) {
            throw new IllegalStateException("Terminate should not be called if a connected link " +
                    "exists. Disconnect from all of the links.");
        }

        coreClockTimer.cancel();
        coreClockTimer = null;

        if (ble != null) {
            ble.terminate();
            ble = null;
        }

        webService.cancelAllRequests();
        webService = null;

        context = null;
    }

    /**
     * @return The Broadcaster instance
     * @throws IllegalStateException when SDK is not initialized
     */
    public Broadcaster getBroadcaster() throws IllegalStateException {
        if (context == null) throw new IllegalStateException("SDK not initialized");
        if (broadcaster == null) broadcaster = new Broadcaster(this);

        return broadcaster;
    }

    /**
     * @return The Telematics instance
     * @throws IllegalStateException when SDK is not initialized
     */
    public Telematics getTelematics() throws IllegalStateException {
        if (context == null) throw new IllegalStateException("SDK not initialized");
        if (telematics == null) telematics = new Telematics(this);

        return telematics;
    }

    /**
     * @return The Scanner Instance
     * @throws IllegalStateException when SDK is not initialized
     */

    Scanner getScanner() throws IllegalStateException {
        if (context == null) throw new IllegalStateException("SDK not initialized");
        if (scanner == null) scanner = new Scanner(this);
        return scanner;
    }

    /**
     * @return The device certificate that is used by the SDK to identify itself.
     * @throws IllegalStateException when SDK is not initialized
     */
    public DeviceCertificate getDeviceCertificate() throws IllegalStateException {
        if (context == null) throw new IllegalStateException("SDK not initialized");
        return certificate;
    }

    /**
     * @return An SDK description string containing version name and type(mobile or wear).
     * @throws IllegalStateException when SDK is not initialized
     */
    public String getInfoString() throws IllegalStateException {
        if (context == null) throw new IllegalStateException("SDK not initialized");

        String infoString = "Android ";
        infoString += BuildConfig.VERSION_NAME;

        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            infoString += " w";
        } else {
            infoString += " m";
        }

        return infoString;
    }

    /**
     * Download and store a access certificate for the given access token. The access token needs to
     * be provided by the certificate provider.
     *
     * @param accessToken The token that is used to download the certificates
     * @param callback    A {@link DownloadCallback} object that is invoked after the download is
     *                    finished or failed
     * @throws IllegalStateException when SDK is not initialized
     */
    public void downloadCertificate(String accessToken,
                                    final DownloadCallback callback) throws IllegalStateException {
        if (context == null) throw new IllegalStateException("SDK not initialized");
        webService.requestAccessCertificate(accessToken,
                privateKey,
                getDeviceCertificate().getSerial(),
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        AccessCertificate certificate = null;
                        try {
                            certificate = storage.storeDownloadedCertificates(response);
                        } catch (Exception e) {
                            if (Manager.getInstance().loggingLevel.getValue() >= Manager
                                    .LoggingLevel.DEBUG.getValue()) {
                                Log.d(TAG, "storeDownloadedCertificates error: " + e.getMessage());
                            }
                            DownloadAccessCertificateError error = new
                                    DownloadAccessCertificateError(
                                    DownloadAccessCertificateError.Type.INVALID_SERVER_RESPONSE,
                                    0, e.getMessage());
                            callback.onDownloadFailed(error);
                        }

                        if (certificate != null)
                            callback.onDownloaded(certificate.getGainerSerial());
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        DownloadAccessCertificateError dispatchedError = null;

                        if (error.networkResponse != null) {
                            try {
                                JSONObject json = new JSONObject(new String(error.networkResponse
                                        .data));
                                Log.d(TAG, "onErrorResponse: " + json.toString());
                                if (json.has("message")) {
                                    dispatchedError = new DownloadAccessCertificateError(
                                            DownloadAccessCertificateError.Type.HTTP_ERROR,
                                            error.networkResponse.statusCode,
                                            json.getString("message"));
                                } else {
                                    dispatchedError = new DownloadAccessCertificateError(
                                            DownloadAccessCertificateError.Type.HTTP_ERROR,
                                            error.networkResponse.statusCode,
                                            new String(error.networkResponse.data));
                                }
                            } catch (JSONException e) {
                                dispatchedError = new DownloadAccessCertificateError(
                                        DownloadAccessCertificateError.Type.HTTP_ERROR,
                                        error.networkResponse.statusCode,
                                        "");
                            }
                        } else {
                            dispatchedError = new DownloadAccessCertificateError(
                                    DownloadAccessCertificateError.Type.NO_CONNECTION,
                                    -1,
                                    "Cannot connect to the web service. Check your internet " +
                                            "connection");
                        }

                        callback.onDownloadFailed(dispatchedError);
                    }
                });
    }

    /**
     * @return All Access Certificates where this device's serial is providing access.
     * @throws IllegalStateException when SDK is not initialized
     */
    public AccessCertificate[] getCertificates() throws IllegalStateException {
        if (context == null) throw new IllegalStateException("SDK not initialized");
        return storage.getCertificatesWithProvidingSerial(getDeviceCertificate().getSerial().getByteArray());
    }

    /**
     * @param context The application context
     * @return All Access Certificates where this device's serial is providing access.
     */
    public AccessCertificate[] getCertificates(Context context) {
        if (storage == null) storage = new Storage(context);
        return storage.getCertificatesWithProvidingSerial(getDeviceCertificate().getSerial().getByteArray());
    }

    /**
     * Find an Access Certificate with the given serial number.
     *
     * @param serial The serial number of the device that is gaining access.
     * @return An Access Certificate for the given serial if one exists, otherwise null.
     * @throws IllegalStateException when SDK is not initialized
     */
    public AccessCertificate getCertificate(DeviceSerial serial) throws IllegalStateException {
        if (context == null) throw new IllegalStateException("SDK not initialized");
        AccessCertificate[] certificates = storage.getCertificatesWithGainingSerial(serial.getByteArray());

        if (certificates != null && certificates.length > 0) {
            return certificates[0];
        }

        return null;
    }

    /**
     * Find a Access Certificate with the given serial number.
     *
     * @param serial The serial number of the device that is gaining access.
     * @param context The application context.
     * @return An Access Certificate for the given serial if one exists, otherwise null.
     */
    public AccessCertificate getCertificate(DeviceSerial serial, Context context) {
        if (storage == null) storage = new Storage(context);
        AccessCertificate[] certificates = storage.getCertificatesWithGainingSerial(serial.getByteArray());

        if (certificates != null && certificates.length > 0) {
            return certificates[0];
        }

        return null;
    }

    /**
     * Delete an access certificate.
     *
     * @param serial The serial of the device that is gaining access.
     * @return true if the certificate existed and was deleted successfully, otherwise false
     * @throws IllegalStateException when SDK is not initialized
     */
    public boolean deleteCertificate(DeviceSerial serial) throws IllegalStateException {
        if (context == null) throw new IllegalStateException("SDK not initialized");
        return storage.deleteCertificate(serial.getByteArray(), certificate.getSerial().getByteArray());
    }

    /**
     * Delete an access certificate.
     *
     * @param serial The serial of the device that is gaining access.
     * @param context The application context.
     * @return true if the certificate existed and was deleted successfully, otherwise false
     */
    public boolean deleteCertificate(DeviceSerial serial, Context context) {
        if (storage == null) storage = new Storage(context);
        return storage.deleteCertificate(serial.getByteArray(), certificate.getSerial().getByteArray());
    }

    /**
     * Deletes all the stored Access Certificates.
     *
     * @throws IllegalStateException when SDK is not initialized
     */
    public void deleteCertificates() throws IllegalStateException {
        if (context == null) throw new IllegalStateException("SDK not initialized");
        storage.resetStorage();
    }

    /**
     * Deletes all the stored Access Certificates.
     * @param context The application context.
     */
    public void deleteCertificates(Context context) {
        if (storage == null) storage = new Storage(context);
        storage.resetStorage();
    }

    void postToMainThread(Runnable runnable) {
        if (Looper.myLooper() != mainHandler.getLooper()) {
            mainHandler.post(runnable);
        } else {
            runnable.run();
        }
    }

    private void startClock() {
        if (coreClockTimer != null) return;

        coreClockTimer = new Timer();
        coreClockTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                workHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        core.HMBTCoreClock(coreInterface);
                    }
                });
            }
        }, 0, 1000);
    }
}
