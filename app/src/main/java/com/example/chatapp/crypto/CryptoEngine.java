package com.example.chatapp.crypto;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

// Kyber imports
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberParameters;
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKeyPairGenerator;
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPublicKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKEMGenerator;
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKEMExtractor;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class CryptoEngine {

    // 🚨 DELETED THE STATIC BLOCK THAT WAS BREAKING ANDROID'S TLS 🚨

    public static final int NONCE_LEN = 12;
    public static final int KEY_LEN = 32;

    // Generate X25519 KeyPair
    public static KeyPair generateX25519KeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
        return kpg.generateKeyPair();
    }

    // ECDH (Diffie-Hellman)
    public static byte[] dh(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(privateKey);
        ka.doPhase(publicKey, true);
        return ka.generateSecret();
    }

    // HKDF-SHA256 (for Root Key and Chain Key derivations)
    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(ikm, salt, info));
        byte[] okm = new byte[length];
        hkdf.generateBytes(okm, 0, length);
        return okm;
    }

    // HMAC-SHA256 (for Symmetric Ratchet)
    public static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    // AES-GCM Encryption
    public static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] associatedData) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
        if (associatedData != null) {
            cipher.updateAAD(associatedData);
        }
        return cipher.doFinal(plaintext);
    }

    // AES-GCM Decryption
    public static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] associatedData) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
        if (associatedData != null) {
            cipher.updateAAD(associatedData);
        }
        return cipher.doFinal(ciphertext);
    }

    // ==========================================
    // 🔥 POST-QUANTUM KYBER KEM METHODS
    // ==========================================

    // Generate Kyber-768 KeyPair
    public static AsymmetricCipherKeyPair generateKyberKeyPair() {
        KyberKeyPairGenerator kpGen = new KyberKeyPairGenerator();
        kpGen.init(new KyberKeyGenerationParameters(new SecureRandom(), KyberParameters.kyber768));
        return kpGen.generateKeyPair();
    }

    // KEM Encapsulation (Sender generates shared secret + ciphertext)
    public static SecretWithEncapsulation kyberEncapsulate(byte[] peerPublicKeyBytes) {
        KyberPublicKeyParameters pubParams = new KyberPublicKeyParameters(KyberParameters.kyber768, peerPublicKeyBytes);
        KyberKEMGenerator kemGen = new KyberKEMGenerator(new SecureRandom());
        return kemGen.generateEncapsulated(pubParams);
    }

    // KEM Decapsulation (Receiver recovers shared secret using their private key + ciphertext)
    public static byte[] kyberDecapsulate(byte[] myPrivateKeyBytes, byte[] cipherText) {
        KyberPrivateKeyParameters privParams = new KyberPrivateKeyParameters(KyberParameters.kyber768, myPrivateKeyBytes);
        KyberKEMExtractor kemExt = new KyberKEMExtractor(privParams);
        return kemExt.extractSecret(cipherText);
    }
}