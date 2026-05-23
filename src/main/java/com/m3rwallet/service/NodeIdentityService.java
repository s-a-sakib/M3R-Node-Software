package com.m3rwallet.service;

import com.m3rwallet.util.AddressUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.math.ec.ECPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

@Service
@Slf4j
public class NodeIdentityService {

    @Value("${app.node.private-key:}")
    private String configuredPrivateKey;

    @Value("${app.validator.address:}")
    private String legacyAddress;

    private String nodePrivateKeyHex;
    private String nodePublicKeyHex;
    private String nodeAddress;
    private boolean privateKeyBacked;

    @PostConstruct
    public void initialize() {
        try {
            if (configuredPrivateKey != null && !configuredPrivateKey.isBlank()) {
                nodePrivateKeyHex = normalizePrivateKey(configuredPrivateKey);
                privateKeyBacked = true;
                log.info("[IDENTITY] Using configured private key");
            } else {
                nodePrivateKeyHex = generateNewPrivateKey();
                privateKeyBacked = true;
                log.warn("[IDENTITY] *** NO PRIVATE KEY CONFIGURED ***");
                log.warn("[IDENTITY] Generated ephemeral key (NOT persisted)");
                log.warn("[IDENTITY] Add to startup args: --app.node.private-key={}", nodePrivateKeyHex);
            }

            nodePublicKeyHex = derivePublicKey(nodePrivateKeyHex);
            nodeAddress = deriveAddress(nodePublicKeyHex);

            log.info("[IDENTITY] ==========================================");
            log.info("[IDENTITY] Node Address : {}", nodeAddress);
            log.info("[IDENTITY] Public Key   : {}...{}",
                    nodePublicKeyHex.substring(0, 12),
                    nodePublicKeyHex.substring(nodePublicKeyHex.length() - 6));
            log.info("[IDENTITY] ==========================================");
        } catch (Exception e) {
            log.error("[IDENTITY] Failed to initialize: {}", e.getMessage());
            nodeAddress = legacyAddress != null && !legacyAddress.isBlank()
                    ? legacyAddress
                    : "unknown-" + System.currentTimeMillis();
            nodePublicKeyHex = "";
            nodePrivateKeyHex = "";
            privateKeyBacked = false;
            log.warn("[IDENTITY] Using fallback address: {}", nodeAddress);
        }
    }

    private String normalizePrivateKey(String privateKeyHex) {
        String normalized = privateKeyHex.trim().toLowerCase().replaceFirst("^0x", "");
        if (!normalized.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("app.node.private-key must be a 32-byte hex value");
        }
        BigInteger d = new BigInteger(normalized, 16);
        if (d.signum() <= 0 || d.compareTo(getCurveParams().getN()) >= 0) {
            throw new IllegalArgumentException("app.node.private-key is outside secp256k1 range");
        }
        return normalized;
    }

    private String generateNewPrivateKey() {
        ECDomainParameters domain = getCurveParams();
        ECKeyPairGenerator gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(domain, new SecureRandom()));
        AsymmetricCipherKeyPair pair = gen.generateKeyPair();
        ECPrivateKeyParameters priv = (ECPrivateKeyParameters) pair.getPrivate();
        return String.format("%064x", priv.getD());
    }

    private String derivePublicKey(String privateKeyHex) {
        ECDomainParameters domain = getCurveParams();
        BigInteger privKey = new BigInteger(privateKeyHex, 16);
        ECPoint pubKeyPoint = domain.getG().multiply(privKey).normalize();
        byte[] compressed = pubKeyPoint.getEncoded(true);
        return bytesToHex(compressed);
    }

    private String deriveAddress(String pubKeyHex) {
        byte[] pubKeyBytes = hexToBytes(pubKeyHex);
        byte[] hash = keccak256(pubKeyBytes);
        byte[] addressBytes = Arrays.copyOfRange(hash, hash.length - 20, hash.length);
        return encodeAddress(addressBytes);
    }

    private byte[] keccak256(byte[] input) {
        KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
    }

    private String encodeAddress(byte[] addressBytes) {
        try {
            return AddressUtil.encodeHex20ToBase58(bytesToHex(addressBytes));
        } catch (Exception e) {
            log.warn("[IDENTITY] Base58 address encode failed: {}", e.getMessage());
            return "M3R" + bytesToHex(addressBytes).toUpperCase();
        }
    }

    private ECDomainParameters getCurveParams() {
        X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        return new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
    }

    public String getNodeAddress() {
        return nodeAddress;
    }

    public String getAddress() {
        return nodeAddress;
    }

    public String getAddressOrUnknown() {
        return nodeAddress == null || nodeAddress.isBlank() ? "unknown" : nodeAddress;
    }

    public String getNodePublicKey() {
        return nodePublicKeyHex;
    }

    public String getPublicKeyCompressedHex() {
        return nodePublicKeyHex;
    }

    public String getPrivateKeyHex() {
        return nodePrivateKeyHex;
    }

    public boolean isPrivateKeyBacked() {
        return privateKeyBacked;
    }

    public byte[] signData(byte[] data) {
        if (nodePrivateKeyHex == null || nodePrivateKeyHex.isBlank()) {
            throw new IllegalStateException("Node identity has no private key");
        }
        ECDomainParameters domain = getCurveParams();
        BigInteger privKey = new BigInteger(nodePrivateKeyHex, 16);
        ECPrivateKeyParameters privParams = new ECPrivateKeyParameters(privKey, domain);
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, privParams);
        BigInteger[] sig = signer.generateSignature(data);
        byte[] r = sig[0].toByteArray();
        byte[] s = sig[1].toByteArray();
        byte[] result = new byte[64];
        System.arraycopy(r, Math.max(0, r.length - 32), result, Math.max(0, 32 - r.length), Math.min(32, r.length));
        System.arraycopy(s, Math.max(0, s.length - 32), result, Math.max(0, 64 - s.length), Math.min(32, s.length));
        return result;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
