package com.example.chatapp.crypto;

import android.util.Base64;
import android.util.Log;

import com.example.chatapp.models.PreKeyBundle;
import org.bouncycastle.crypto.SecretWithEncapsulation;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

public class X3DHManager {
    private static final String TAG = "CRYPTO_PROTOCOL";

    public static class X3DHResult {
        public DoubleRatchet ratchet;
        public String ephemeralPublicBase64;
        public String pqCiphertextBase64; // 🔥 Holds the Kyber ciphertext
    }

    public static X3DHResult initiateHandshake(PreKeyBundle peerBundle, KeyPair myIdentityKey, String myName, String peerName) throws Exception {
        Log.d(TAG, "\n=======================================================");
        Log.d(TAG, "PQ-HYBRID X3DH PROTOCOL EXECUTION (INITIATOR)");
        Log.d(TAG, "Parameters loaded -> Curve: X25519, KEM: Kyber-768, Hash: SHA-256\n");

        KeyFactory kf = KeyFactory.getInstance("X25519");

        PublicKey peerIdentityPub = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(peerBundle.identityPublic, Base64.NO_WRAP)));
        PublicKey peerSignedPreKeyPub = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(peerBundle.signedPreKeyPublic, Base64.NO_WRAP)));
        byte[] peerPqPubBytes = Base64.decode(peerBundle.pqPreKeyPublic, Base64.NO_WRAP);

        Log.d(TAG, "-> Generating Classical Ephemeral Key Pair (EKA)...");
        KeyPair myEphemeralKey = CryptoEngine.generateX25519KeyPair();
        String ekPubBase64 = Base64.encodeToString(myEphemeralKey.getPublic().getEncoded(), Base64.NO_WRAP);

        PrivateKey ikaPriv = myIdentityKey.getPrivate();
        PrivateKey ekaPriv = myEphemeralKey.getPrivate();

        Log.d(TAG, "-> Calculating DH1, DH2, and DH3... [X25519]");
        byte[] dh1 = CryptoEngine.dh(ikaPriv, peerSignedPreKeyPub);
        byte[] dh2 = CryptoEngine.dh(ekaPriv, peerIdentityPub);
        byte[] dh3 = CryptoEngine.dh(ekaPriv, peerSignedPreKeyPub);

        Log.d(TAG, "-> Encapsulating Kyber Secret against peer's PQ Key...");
        SecretWithEncapsulation pqKEM = CryptoEngine.kyberEncapsulate(peerPqPubBytes);
        byte[] pqSharedSecret = pqKEM.getSecret();
        String pqCiphertext = Base64.encodeToString(pqKEM.getEncapsulation(), Base64.NO_WRAP);

        Log.d(TAG, "-> Calculating Hybrid SK = KDF(DH1 || DH2 || DH3 || PQ_SS)...");
        byte[] km = new byte[dh1.length + dh2.length + dh3.length + pqSharedSecret.length];
        System.arraycopy(dh1, 0, km, 0, dh1.length);
        System.arraycopy(dh2, 0, km, dh1.length, dh2.length);
        System.arraycopy(dh3, 0, km, dh1.length + dh2.length, dh3.length);
        System.arraycopy(pqSharedSecret, 0, km, dh1.length + dh2.length + dh3.length, pqSharedSecret.length);

        byte[] sharedSecret = CryptoEngine.hkdf(km, new byte[32], "MyHybridProtocol".getBytes(), 32);

        Log.d(TAG, "   INITIATOR DERIVED HYBRID SK: " + bytesToHex(sharedSecret));
        Log.d(TAG, "=======================================================\n");

        X3DHResult result = new X3DHResult();
        result.ratchet = new DoubleRatchet(sharedSecret, null, peerSignedPreKeyPub, false);
        result.ephemeralPublicBase64 = ekPubBase64;
        result.pqCiphertextBase64 = pqCiphertext;
        return result;
    }

    public static DoubleRatchet receiveHandshake(KeyPair myIdentityKey, KeyPair mySignedPreKey, byte[] myPqPrivateKey, String peerIdentityBase64, String peerEphemeralBase64, String pqCiphertextBase64) throws Exception {
        Log.d(TAG, "\n=======================================================");
        Log.d(TAG, "PHASE 3: RESPONDER RECEIVES HYBRID HANDSHAKE");

        KeyFactory kf = KeyFactory.getInstance("X25519");

        PublicKey peerIdentityPub = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(peerIdentityBase64, Base64.NO_WRAP)));
        PublicKey peerEphemeralPub = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(peerEphemeralBase64, Base64.NO_WRAP)));
        byte[] pqCiphertextBytes = Base64.decode(pqCiphertextBase64, Base64.NO_WRAP);

        PrivateKey ikbPriv = myIdentityKey.getPrivate();
        PrivateKey spkbPriv = mySignedPreKey.getPrivate();

        Log.d(TAG, "-> Repeating the DH calculations to derive classical SK segments...");
        byte[] dh1 = CryptoEngine.dh(spkbPriv, peerIdentityPub);
        byte[] dh2 = CryptoEngine.dh(ikbPriv, peerEphemeralPub);
        byte[] dh3 = CryptoEngine.dh(spkbPriv, peerEphemeralPub);

        Log.d(TAG, "-> Decapsulating Kyber Secret from Ciphertext...");
        byte[] pqSharedSecret = CryptoEngine.kyberDecapsulate(myPqPrivateKey, pqCiphertextBytes);

        Log.d(TAG, "-> Deriving Hybrid SK from combined segments...");
        byte[] km = new byte[dh1.length + dh2.length + dh3.length + pqSharedSecret.length];
        System.arraycopy(dh1, 0, km, 0, dh1.length);
        System.arraycopy(dh2, 0, km, dh1.length, dh2.length);
        System.arraycopy(dh3, 0, km, dh1.length + dh2.length, dh3.length);
        System.arraycopy(pqSharedSecret, 0, km, dh1.length + dh2.length + dh3.length, pqSharedSecret.length);

        byte[] sharedSecret = CryptoEngine.hkdf(km, new byte[32], "MyHybridProtocol".getBytes(), 32);

        Log.d(TAG, "   RESPONDER DERIVED HYBRID SK: " + bytesToHex(sharedSecret));
        Log.d(TAG, "=======================================================\n");

        return new DoubleRatchet(sharedSecret, mySignedPreKey, null, true);
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}