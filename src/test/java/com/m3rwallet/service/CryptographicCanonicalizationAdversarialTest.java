package com.m3rwallet.service;

import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.ValidatorRepository;
import com.m3rwallet.util.AddressUtil;
import com.m3rwallet.util.CryptoUtil;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Adversarial tests for Cryptographic Canonicalization (F-06, F-07, F-09).
 * Verifies secp256k1 low-s enforcement, rejection of malformed/zero-filled hex fields,
 * mandatory validatorSetHash and stateRoot validation, and empty-block Merkle root rules.
 */
public class CryptographicCanonicalizationAdversarialTest {

    private BlockValidationService validationService;
    private ValidatorService mockValidatorService;
    private ValidatorRepository mockValidatorRepo;
    private BlockRepository mockBlockRepo;

    private ECPrivateKeyParameters privateKey;
    private byte[] pubKeyCompressed;
    private String pubKeyHex;
    private String address;

    @BeforeEach
    void setup() {
        X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        ECDomainParameters domain = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
        ECKeyPairGenerator gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(domain, new SecureRandom()));
        AsymmetricCipherKeyPair pair = gen.generateKeyPair();
        privateKey = (ECPrivateKeyParameters) pair.getPrivate();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) pair.getPublic();
        pubKeyCompressed = pub.getQ().getEncoded(true);
        pubKeyHex = Hex.encodeHexString(pubKeyCompressed);
        address = AddressUtil.deriveAddressFromPublicKey(pubKeyCompressed);

        mockValidatorRepo = Mockito.mock(ValidatorRepository.class);
        mockValidatorService = Mockito.mock(ValidatorService.class);
        mockBlockRepo = Mockito.mock(BlockRepository.class);

        validationService = new BlockValidationService();
        ReflectionTestUtils.setField(validationService, "blockRepo", mockBlockRepo);
        ReflectionTestUtils.setField(validationService, "validatorRepo", mockValidatorRepo);
        ReflectionTestUtils.setField(validationService, "validatorService", mockValidatorService);
        ReflectionTestUtils.setField(validationService, "defaultNetwork", "mainnet");

        Validator v = Validator.builder()
                .address(address)
                .status(Validator.ValidatorStatus.ACTIVE)
                .stakedAmount(BigDecimal.valueOf(1000L))
                .build();
        when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE)))
                .thenReturn(List.of(v));
        when(mockValidatorRepo.findByAddressAndNetwork(eq(address), anyString()))
                .thenReturn(Optional.of(v));
        when(mockValidatorService.selectValidatorForSlot(anyLong(), anyString())).thenReturn(v);
        when(mockValidatorService.calculateWeight(any())).thenReturn(1.0);
    }

    private byte[] rawSign(byte[] digest, boolean forceHighS) {
        X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        BigInteger n = params.getN();
        BigInteger halfN = n.shiftRight(1);

        ECDSASigner signer = new ECDSASigner();
        signer.init(true, privateKey);
        BigInteger[] sig = signer.generateSignature(digest);
        BigInteger r = sig[0];
        BigInteger s = sig[1];

        if (forceHighS) {
            // Ensure s > n/2 (malleated high-s)
            if (s.compareTo(halfN) <= 0) {
                s = n.subtract(s);
            }
        } else {
            // Ensure s <= n/2 (canonical low-s)
            if (s.compareTo(halfN) > 0) {
                s = n.subtract(s);
            }
        }

        byte[] raw = new byte[64];
        byte[] rB = r.toByteArray();
        byte[] sB = s.toByteArray();
        System.arraycopy(rB, Math.max(0, rB.length - 32), raw, Math.max(0, 32 - rB.length), Math.min(32, rB.length));
        System.arraycopy(sB, Math.max(0, sB.length - 32), raw, 32 + Math.max(0, 32 - sB.length), Math.min(32, sB.length));
        return raw;
    }

    @Test
    @DisplayName("F-06: Strict Low-s Enforcement: High-s signature (s > n/2) is rejected")
    void testHighSSignatureRejected() {
        byte[] digest = CryptoUtil.keccak256("test_data_for_signing".getBytes(StandardCharsets.UTF_8));
        byte[] highSSig = rawSign(digest, true);

        // Verify that high-s signature is rejected
        boolean highSOk = CryptoUtil.verifyVoteSignature(digest, highSSig, pubKeyCompressed);
        assertFalse(highSOk, "High-s ECDSA signature MUST be rejected to eliminate signature malleability");

        // Verify that low-s signature of identical data is accepted
        byte[] lowSSig = rawSign(digest, false);
        boolean lowSOk = CryptoUtil.verifyVoteSignature(digest, lowSSig, pubKeyCompressed);
        assertTrue(lowSOk, "Canonical low-s signature MUST be accepted");
    }

    @Test
    @DisplayName("F-07: Malformed hex fields in canonical serialization fail closed with IllegalArgumentException")
    void testMalformedHexFieldsThrowIllegalArgumentException() {
        // Short hex (less than 32 bytes)
        assertThrows(IllegalArgumentException.class, () -> {
            Block.serializeCanonicalHeader(
                    (byte) 1, "mainnet", 1L, 1L,
                    "abc", // invalid short parentBlockHash
                    "0".repeat(64), "0".repeat(64),
                    System.currentTimeMillis(), address, 1.0,
                    "0".repeat(64), 1001L, 0
            );
        });

        // Non-hex characters
        assertThrows(IllegalArgumentException.class, () -> {
            Block.serializeCanonicalHeader(
                    (byte) 1, "mainnet", 1L, 1L,
                    "0".repeat(63) + "Z", // non-hex character
                    "0".repeat(64), "0".repeat(64),
                    System.currentTimeMillis(), address, 1.0,
                    "0".repeat(64), 1001L, 0
            );
        });

        // Null required parentBlockHash
        assertThrows(IllegalArgumentException.class, () -> {
            Block.serializeCanonicalHeader(
                    (byte) 1, "mainnet", 1L, 1L,
                    null,
                    "0".repeat(64), "0".repeat(64),
                    System.currentTimeMillis(), address, 1.0,
                    "0".repeat(64), 1001L, 0
            );
        });
    }

    @Test
    @DisplayName("F-07: Null or blank validatorSetHash throws IllegalArgumentException")
    void testNullValidatorSetHashThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            Block.serializeCanonicalHeader(
                    (byte) 1, "mainnet", 1L, 1L,
                    "0".repeat(64), "0".repeat(64), "0".repeat(64),
                    System.currentTimeMillis(), address, 1.0,
                    null, // null validatorSetHash
                    1001L, 0
            );
        });
    }

    @Test
    @DisplayName("F-07: BlockValidationService rejects payload with missing or malformed validatorSetHash")
    void testValidationRejectsMissingValidatorSetHash() {
        Map<String, Object> payload = createBasePayload();
        payload.remove("validatorSetHash");

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("MISSING_VALIDATOR_SET_HASH"));
    }

    @Test
    @DisplayName("F-07: BlockValidationService rejects payload with missing or malformed stateRoot")
    void testValidationRejectsMalformedStateRoot() {
        Map<String, Object> payload = createBasePayload();
        payload.put("stateRoot", "invalid_state_root");

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("MALFORMED_STATE_ROOT"));
    }

    @Test
    @DisplayName("F-07: Empty-block Merkle-root validation enforces canonical empty root rule")
    void testEmptyBlockMerkleRootEnforcement() {
        Map<String, Object> payload = createBasePayload();
        payload.put("txCount", 0);
        payload.put("transactions", List.of());
        payload.put("merkleRoot", "a".repeat(64)); // Arbitrary Merkle root for empty block

        byte[] headerBytes = validationService.serializePayloadForSigning(payload);
        payload.put("proposerSignature", Hex.encodeHexString(rawSign(CryptoUtil.keccak256(headerBytes), false)));
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("MERKLE_ROOT_MISMATCH"), "Empty block with arbitrary Merkle root must fail");

        // Now set the canonical empty Merkle root
        String canonicalEmptyRoot = validationService.computeMerkleRoot(List.of());
        payload.put("merkleRoot", canonicalEmptyRoot);
        byte[] validHeader = validationService.serializePayloadForSigning(payload);
        payload.put("proposerSignature", Hex.encodeHexString(rawSign(CryptoUtil.keccak256(validHeader), false)));
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult validResult = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(validResult.violations().contains("MERKLE_ROOT_MISMATCH"), "Canonical empty root must pass");
    }

    @Test
    @DisplayName("F-07 & F-09: Cryptographic binding between block hash and canonical header digest")
    void testBlockHashHeaderDigestBinding() {
        Map<String, Object> payload = createBasePayload();
        byte[] headerBytes = validationService.serializePayloadForSigning(payload);
        payload.put("proposerSignature", Hex.encodeHexString(rawSign(CryptoUtil.keccak256(headerBytes), false)));
        payload.put("blockHash", "claimed_mismatched_hash_0000000000000000000000000000000000000000");

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("BLOCK_HASH_MISMATCH"));
    }

    private Map<String, Object> createBasePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 1);
        payload.put("network", "mainnet");
        payload.put("blockHeight", 1L);
        payload.put("slotNumber", 1L);
        payload.put("parentBlockHash", "0".repeat(64));
        payload.put("merkleRoot", validationService.computeMerkleRoot(List.of()));
        payload.put("stateRoot", "0".repeat(64));
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("proposerAddress", address);
        payload.put("proposerPublicKey", pubKeyHex);
        payload.put("proposerWeight", 1.0);
        payload.put("validatorSetHash", BlockValidationService.computeValidatorSetHash(List.of(
                Validator.builder().address(address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000L)).build()
        )));
        payload.put("nonce", 1001L);
        payload.put("txCount", 0);
        payload.put("transactions", List.of());
        return payload;
    }
}
