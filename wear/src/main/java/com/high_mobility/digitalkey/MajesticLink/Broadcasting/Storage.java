package com.high_mobility.digitalkey.MajesticLink.Broadcasting;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.high_mobility.digitalkey.MajesticLink.Shared.AccessCertificate;
import com.high_mobility.digitalkey.MajesticLink.Shared.Certificate;
import com.high_mobility.digitalkey.MajesticLink.Shared.DeviceCertificate;
import com.high_mobility.digitalkey.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Created by ttiganik on 14/04/16.
 *
 * Storage is used to access device's storage, where certificates are securely stored.
 *
 * Uses Android Keystore.
 */
class Storage {
    private static final String ACCESS_CERTIFICATE_STORAGE_KEY = "ACCESS_CERTIFICATE_STORAGE_KEY";

    private SharedPreferences settings;
    private SharedPreferences.Editor editor;

    DeviceCertificate deviceCertificate;

    Storage(Context ctx) {
        settings = ctx.getSharedPreferences("com.hm.wearable.UserPrefs",
                Context.MODE_PRIVATE );
        editor = settings.edit();
    }

    AccessCertificate[] getCertificates() {
        Set<String> bytesStringSet = settings.getStringSet(ACCESS_CERTIFICATE_STORAGE_KEY, null);

        if (bytesStringSet != null && bytesStringSet.size() > 0) {
            AccessCertificate[] certificates = new AccessCertificate[bytesStringSet.size()];

            int counter = 0;
            for (String bytesString : bytesStringSet) {
                AccessCertificate cert = new AccessCertificate(Utils.bytesFromHex(bytesString));
                certificates[counter] = cert;
                counter++;
            }

            return certificates;
        }

        return null;
    }

    void setCertificates(AccessCertificate[] certificates) {
        HashSet<String> stringSet = new HashSet<>();

        for (Certificate cert : certificates) {
            stringSet.add(Utils.hexFromBytes(cert.getBytes()));
        }

        editor.putStringSet(ACCESS_CERTIFICATE_STORAGE_KEY, stringSet);
        editor.commit();
    }

    AccessCertificate[] getRegisteredCertificates() {
        byte[] serialNumber = deviceCertificate.getSerial();
        AccessCertificate[] certificates = getCertificates();
        ArrayList<AccessCertificate> storedCertificates = new ArrayList<>();

        for (AccessCertificate cert : certificates) {
            if (Arrays.equals(cert.getProviderSerial(), serialNumber)) {
                storedCertificates.add(cert);
            }
        }

        if (storedCertificates.size() > 0) {
            return storedCertificates.toArray(new AccessCertificate[storedCertificates.size()]);
        }

        return null;
    }

    AccessCertificate[] getStoredCertificates() {
        byte[] serialNumber = deviceCertificate.getSerial();
        AccessCertificate[] certificates = getCertificates();
        ArrayList<AccessCertificate> storedCertificates = new ArrayList<>();

        for (AccessCertificate cert : certificates) {
            if (!Arrays.equals(cert.getProviderSerial(), serialNumber)) {
                storedCertificates.add(cert);
            }
        }

        if (storedCertificates.size() > 0) {
            return storedCertificates.toArray(new AccessCertificate[storedCertificates.size()]);
        }

        return null;
    }

    void resetStorage() {
        editor.remove(ACCESS_CERTIFICATE_STORAGE_KEY);
        editor.commit();
    }

    boolean deleteCertificateWithGainingSerial(byte[] serial) {
        AccessCertificate[] certs = getCertificates();

        int removedIndex = -1;
        for (int i=0; i<certs.length; i++) {
            AccessCertificate cert = certs[i];
            if (Arrays.equals(cert.getGainerSerial(), serial)) {
                removedIndex = i;
                break;
            }
        }

        if (removedIndex != -1) {
            AccessCertificate[] newCerts = removeAtIndex(removedIndex, certs);
            setCertificates(newCerts);
            return true;
        }
        else {
            return false;
        }
    }

    boolean deleteCertificateWithProvidingSerial(byte[] serial) {
        AccessCertificate[] certs = getCertificates();

        int removedIndex = -1;
        for (int i=0; i<certs.length; i++) {
            AccessCertificate cert = certs[i];
            if (Arrays.equals(cert.getProviderSerial(), serial)) {
                removedIndex = i;
                break;
            }
        }

        if (removedIndex != -1) {
            AccessCertificate[] newCerts = removeAtIndex(removedIndex, certs);
            setCertificates(newCerts);
            return true;
        }
        else {
            return false;
        }
    }

    AccessCertificate certWithProvidingSerial(byte[] serial) {
        AccessCertificate[] certs = getCertificates();

        for (int i=0; i<certs.length; i++) {
            AccessCertificate cert = certs[i];
            if (Arrays.equals(cert.getProviderSerial(), serial)) {
                return cert;
            }
        }

        return null;
    }

    AccessCertificate certWithGainingSerial(byte[] serial) {
        AccessCertificate[] certs = getCertificates();

        for (int i = 0; i < certs.length; i++) {
            AccessCertificate cert = certs[i];
            if (Arrays.equals(cert.getGainerSerial(), serial)) {
                return cert;
            }
        }

        return null;
    }

    void storeCertificate(AccessCertificate certificate, byte[] CAPublicKey) throws Exception {
        AccessCertificate[] certs = getCertificates();

        if (certs.length >= 5) throw new Exception(); // TODO throw Error.StorageFull exception

        for (int i = 0; i < certs.length; i++) {
            AccessCertificate cert = certs[i];
            if (Arrays.equals(cert.getGainerSerial(), certificate.getGainerSerial())
                && Arrays.equals(cert.getProviderSerial(), certificate.getProviderSerial())
                && Arrays.equals(cert.getGainerPublicKey(), certificate.getGainerPublicKey())) {
                    deleteCertificateWithGainingSerial(certificate.getGainerSerial());
            }
        }

        certs = getCertificates();
        AccessCertificate[] newCerts = new AccessCertificate[certs.length + 1];

        newCerts[newCerts.length - 1] = certificate;
        setCertificates(newCerts);
    }

    private AccessCertificate[] removeAtIndex(int removedIndex, AccessCertificate[] certs) {
        AccessCertificate[] newCerts = new AccessCertificate[certs.length - 1];

        for (int i=0; i<certs.length; i++) {
            if (i < removedIndex) {
                newCerts[i] = certs[i];
            }
            else if (i > removedIndex){
                newCerts[i - 1] = certs[i];
            }
        }

        return newCerts;
    }

    private void _test_Storage() {
        editor.clear();
        editor.commit();
        AccessCertificate cert1 = new AccessCertificate(Utils.bytesFromHex("***REMOVED***"));
        AccessCertificate cert2 = new AccessCertificate(Utils.bytesFromHex("***REMOVED***"));

        AccessCertificate[] certs = new AccessCertificate[] {cert1, cert2};
        setCertificates(certs);

        AccessCertificate[] readCerts = getCertificates();
        boolean success = true;
        if (readCerts != null) {
            outerLoop:
            for (AccessCertificate storedCert : certs) {
                for (AccessCertificate readCert : readCerts) {
                    if (Arrays.equals(storedCert.getGainerPublicKey(), readCert.getGainerPublicKey())) {
                        continue outerLoop;
                    }
                }

                success = false;
                break;
            }
        }
        else {
            success = false;
        }

        Log.i("", "TEST: store certs " + success);
    }
}
