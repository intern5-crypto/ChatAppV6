package com.example.chatapp.crypto;

import android.util.Base64;

import com.example.chatapp.models.RatchetHeader;

import org.json.JSONObject;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

public class DoubleRatchet {
    private byte[] RK;
    private byte[] CKs;
    private byte[] CKr;
    private KeyPair DHs;
    private PublicKey DHr;
    private int PN = 0;
    private int Ns = 0;
    private int Nr = 0;

    // Empty constructor for restoring state
    private DoubleRatchet() {}

    public DoubleRatchet(byte[] sharedSecret, KeyPair myInitialRatchetKey, PublicKey peerInitialRatchetKey, boolean isBob) throws Exception {
        this.RK = sharedSecret;
        if (isBob) {
            this.DHs = myInitialRatchetKey;
            this.DHr = null;
        } else {
            this.DHs = CryptoEngine.generateX25519KeyPair();
            this.DHr = peerInitialRatchetKey;
            byte[] dhOut = CryptoEngine.dh(this.DHs.getPrivate(), this.DHr);
            byte[] kdfOut = CryptoEngine.hkdf(dhOut, this.RK, "DoubleRatchet-RootChain".getBytes(), 64);
            this.RK = Arrays.copyOfRange(kdfOut, 0, 32);
            this.CKs = Arrays.copyOfRange(kdfOut, 32, 64);
        }
    }

    private byte[][] kdfCk(byte[] ck) throws Exception {
        byte[] messageKey = CryptoEngine.hmacSha256(ck, new byte[]{0x01});
        byte[] nextChainKey = CryptoEngine.hmacSha256(ck, new byte[]{0x02});
        return new byte[][]{nextChainKey, messageKey};
    }

    public RatchetHeader encrypt(byte[] plaintext) throws Exception {
        if (CKs == null) throw new Exception("Sending chain not initialized");
        byte[][] ckOut = kdfCk(CKs);
        this.CKs = ckOut[0];
        byte[] mk = ckOut[1];

        RatchetHeader header = new RatchetHeader(DHs.getPublic().getEncoded(), PN, Ns);
        this.Ns++;

        byte[] nonce = new byte[12];
        byte[] ciphertext = CryptoEngine.aesGcmEncrypt(mk, nonce, plaintext, header.getAssociatedData());
        header.ciphertext = ciphertext;
        return header;
    }

    public byte[] decrypt(RatchetHeader header, PublicKey peerDhPub) throws Exception {
        if (this.DHr == null || !Arrays.equals(peerDhPub.getEncoded(), this.DHr.getEncoded())) {
            this.PN = this.Ns;
            this.Ns = 0;
            this.Nr = 0;
            this.DHr = peerDhPub;

            byte[] dhOutRecv = CryptoEngine.dh(this.DHs.getPrivate(), this.DHr);
            byte[] kdfOutRecv = CryptoEngine.hkdf(dhOutRecv, this.RK, "DoubleRatchet-RootChain".getBytes(), 64);
            this.RK = Arrays.copyOfRange(kdfOutRecv, 0, 32);
            this.CKr = Arrays.copyOfRange(kdfOutRecv, 32, 64);

            this.DHs = CryptoEngine.generateX25519KeyPair();
            byte[] dhOutSend = CryptoEngine.dh(this.DHs.getPrivate(), this.DHr);
            byte[] kdfOutSend = CryptoEngine.hkdf(dhOutSend, this.RK, "DoubleRatchet-RootChain".getBytes(), 64);
            this.RK = Arrays.copyOfRange(kdfOutSend, 0, 32);
            this.CKs = Arrays.copyOfRange(kdfOutSend, 32, 64);
        }

        byte[][] ckOut = kdfCk(CKr);
        this.CKr = ckOut[0];
        byte[] mk = ckOut[1];
        this.Nr++;

        byte[] nonce = new byte[12];
        return CryptoEngine.aesGcmDecrypt(mk, nonce, header.ciphertext, header.getAssociatedData());
    }

    // ==========================================
    // 🔥 NEW: STATE SAVING & LOADING METHODS 🔥
    // ==========================================

    public String serializeState() {
        JSONObject obj = new JSONObject();
        try {
            if (RK != null) obj.put("RK", Base64.encodeToString(RK, Base64.NO_WRAP));
            if (CKs != null) obj.put("CKs", Base64.encodeToString(CKs, Base64.NO_WRAP));
            if (CKr != null) obj.put("CKr", Base64.encodeToString(CKr, Base64.NO_WRAP));
            if (DHs != null) {
                obj.put("DHs_pub", Base64.encodeToString(DHs.getPublic().getEncoded(), Base64.NO_WRAP));
                obj.put("DHs_priv", Base64.encodeToString(DHs.getPrivate().getEncoded(), Base64.NO_WRAP));
            }
            if (DHr != null) obj.put("DHr", Base64.encodeToString(DHr.getEncoded(), Base64.NO_WRAP));
            obj.put("PN", PN);
            obj.put("Ns", Ns);
            obj.put("Nr", Nr);
        } catch (Exception e) { e.printStackTrace(); }
        return obj.toString();
    }

    public static DoubleRatchet restoreState(String jsonStr) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            DoubleRatchet dr = new DoubleRatchet();

            if (obj.has("RK")) dr.RK = Base64.decode(obj.getString("RK"), Base64.NO_WRAP);
            if (obj.has("CKs")) dr.CKs = Base64.decode(obj.getString("CKs"), Base64.NO_WRAP);
            if (obj.has("CKr")) dr.CKr = Base64.decode(obj.getString("CKr"), Base64.NO_WRAP);

            KeyFactory kf = KeyFactory.getInstance("X25519");
            if (obj.has("DHs_pub") && obj.has("DHs_priv")) {
                dr.DHs = new KeyPair(
                        kf.generatePublic(new X509EncodedKeySpec(Base64.decode(obj.getString("DHs_pub"), Base64.NO_WRAP))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(obj.getString("DHs_priv"), Base64.NO_WRAP)))
                );
            }
            if (obj.has("DHr")) {
                dr.DHr = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(obj.getString("DHr"), Base64.NO_WRAP)));
            }

            dr.PN = obj.getInt("PN");
            dr.Ns = obj.getInt("Ns");
            dr.Nr = obj.getInt("Nr");
            return dr;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}