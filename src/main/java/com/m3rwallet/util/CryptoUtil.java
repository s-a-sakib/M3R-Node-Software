package com.m3rwallet.util;

import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;

import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

public class CryptoUtil {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static byte[] keccak256(byte[] message) {
        KeccakDigest digest = new KeccakDigest(256);
        byte[] hash = new byte[digest.getDigestSize()];
        if (message != null && message.length > 0) {
            digest.update(message, 0, message.length);
        }
        digest.doFinal(hash, 0);
        return hash;
    }

    public static boolean verifySignature(byte[] msgHash, byte[] signatureBytes, byte[] pubKeyBytes) {
        try {
            ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
            KeyFactory kf = KeyFactory.getInstance("ECDSA", "BC");
            ECPoint q = spec.getCurve().decodePoint(pubKeyBytes);
            ECPublicKeySpec pubKeySpec = new ECPublicKeySpec(q, spec);
            PublicKey pk = kf.generatePublic(pubKeySpec);

            Signature signer = Signature.getInstance("NONEwithECDSA", "BC");
            signer.initVerify(pk);
            signer.update(msgHash);
            return signer.verify(signatureBytes);
        } catch (Exception e) {
            System.err.println("Crypto verification error: " + e.getMessage());
            return false;
        }
    }
}
