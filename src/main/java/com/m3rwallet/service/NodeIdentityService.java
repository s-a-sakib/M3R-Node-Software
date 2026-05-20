package com.m3rwallet.service;

import com.m3rwallet.util.AddressUtil;
import com.m3rwallet.util.CryptoUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Arrays;

@Service
@Slf4j
public class NodeIdentityService {
    private static final ECParameterSpec SECP256K1 = ECNamedCurveTable.getParameterSpec("secp256k1");
    private static final BigInteger CURVE_N =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    private final String configuredPrivateKey;
    private final String configuredValidatorAddress;

    @Getter
    private final String address;

    @Getter
    private final String publicKeyCompressedHex;

    @Getter
    private final boolean privateKeyBacked;

    public NodeIdentityService(@Value("${app.node.private-key:}") String configuredPrivateKey,
                               @Value("${app.validator.address:}") String configuredValidatorAddress) {
        this.configuredPrivateKey = configuredPrivateKey == null ? "" : configuredPrivateKey.trim();
        this.configuredValidatorAddress = configuredValidatorAddress == null ? "" : configuredValidatorAddress.trim();

        if (!this.configuredPrivateKey.isBlank()) {
            byte[] privateKey = parsePrivateKey(this.configuredPrivateKey);
            byte[] publicKeyCompressed = deriveCompressedPublicKey(privateKey);
            byte[] publicKeyHash = CryptoUtil.keccak256(publicKeyCompressed);
            byte[] payload20 = Arrays.copyOfRange(publicKeyHash, 12, 32);
            String hex20 = Hex.encodeHexString(payload20);
            this.address = AddressUtil.encodeHex20ToBase58(hex20);
            this.publicKeyCompressedHex = Hex.encodeHexString(publicKeyCompressed);
            this.privateKeyBacked = true;
            log.info("Node identity loaded from app.node.private-key: {}", this.address);
            return;
        }

        this.address = this.configuredValidatorAddress;
        this.publicKeyCompressedHex = "";
        this.privateKeyBacked = false;
        if (this.address == null || this.address.isBlank()) {
            log.warn("No node identity configured. Set app.node.private-key or app.validator.address.");
        } else {
            log.info("Node identity loaded from app.validator.address: {}", this.address);
        }
    }

    public String getAddressOrUnknown() {
        return address == null || address.isBlank() ? "unknown" : address;
    }

    private byte[] parsePrivateKey(String privateKeyHex) {
        String normalized = privateKeyHex.startsWith("0x") || privateKeyHex.startsWith("0X")
                ? privateKeyHex.substring(2)
                : privateKeyHex;
        if (!normalized.matches("(?i)^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("app.node.private-key must be a 32-byte hex value");
        }
        try {
            byte[] privateKey = Hex.decodeHex(normalized);
            BigInteger d = new BigInteger(1, privateKey);
            if (d.compareTo(BigInteger.ONE) < 0 || d.compareTo(CURVE_N) >= 0) {
                throw new IllegalArgumentException("app.node.private-key is outside secp256k1 range");
            }
            return privateKey;
        } catch (DecoderException e) {
            throw new IllegalArgumentException("app.node.private-key is not valid hex", e);
        }
    }

    private byte[] deriveCompressedPublicKey(byte[] privateKey) {
        BigInteger d = new BigInteger(1, privateKey);
        ECPoint q = SECP256K1.getG().multiply(d).normalize();
        return q.getEncoded(true);
    }
}
