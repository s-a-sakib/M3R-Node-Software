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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for Part 5: Historical Validator-Set Verification across roster and stake changes.
 */
class HistoricalValidatorSnapshotTest {

    private BlockBroadcastService broadcastService;
    private BlockValidationService validationService;
    private ValidatorService validatorService;
    private ValidatorRepository mockValidatorRepo;
    private BlockRepository mockBlockRepo;

    static class Key {
        ECPrivateKeyParameters priv;
        String pubHex;
        String address;
    }

    private Key v1;
    private Key v2;
    private Key v3;

    private Key generateKey() {
        X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        ECDomainParameters domain = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
        ECKeyPairGenerator gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(domain, new SecureRandom()));
        AsymmetricCipherKeyPair pair = gen.generateKeyPair();
        Key k = new Key();
        k.priv = (ECPrivateKeyParameters) pair.getPrivate();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) pair.getPublic();
        byte[] comp = pub.getQ().getEncoded(true);
        k.pubHex = Hex.encodeHexString(comp);
        k.address = AddressUtil.deriveAddressFromPublicKey(comp);
        return k;
    }

    private String signVote(Key key, long height, String hash, String network) {
        String canonical = "BLOCK_VOTE|" + network + "|" + height + "|" + hash + "|" + key.address;
        byte[] digest = CryptoUtil.keccak256(canonical.getBytes(StandardCharsets.UTF_8));
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, key.priv);
        BigInteger[] sig = signer.generateSignature(digest);
        byte[] raw = CryptoUtil.formatRawSignature(sig[0], sig[1]);
        return Hex.encodeHexString(raw);
    }

    @BeforeEach
    void setup() {
        v1 = generateKey();
        v2 = generateKey();
        v3 = generateKey();

        mockBlockRepo = Mockito.mock(BlockRepository.class);
        mockValidatorRepo = Mockito.mock(ValidatorRepository.class);

        validatorService = new ValidatorService(
                mockValidatorRepo,
                Mockito.mock(com.m3rwallet.repository.ValidatorWeightRepository.class),
                Mockito.mock(com.m3rwallet.repository.SlashEventRepository.class),
                null, null, null
        );

        validationService = new BlockValidationService();
        ReflectionTestUtils.setField(validationService, "blockRepo", mockBlockRepo);
        ReflectionTestUtils.setField(validationService, "validatorRepo", mockValidatorRepo);
        ReflectionTestUtils.setField(validationService, "validatorService", validatorService);
        ReflectionTestUtils.setField(validationService, "defaultNetwork", "mainnet");

        broadcastService = new BlockBroadcastService(mockBlockRepo, null, null, null, null);
        ReflectionTestUtils.setField(broadcastService, "blockValidationService", validationService);
        ReflectionTestUtils.setField(broadcastService, "validatorRepo", mockValidatorRepo);
        ReflectionTestUtils.setField(broadcastService, "validatorService", validatorService);
        ReflectionTestUtils.setField(broadcastService, "peerUrls", List.of("http://peer1:3000", "http://peer2:4000"));
        broadcastService.setAllowSoloFinalization(false);
    }

    @Test
    @DisplayName("1. Block validates against historical snapshot after validator stakes change")
    void testHistoricalBlockValidatesAfterStakeChange() {
        // Height 10 validator roster: v1 (stake 1000), v2 (stake 1000)
        List<Validator> originalActive = List.of(
                Validator.builder().address(v1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).reliabilityScoreScaled(900000L).build(),
                Validator.builder().address(v2.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).reliabilityScoreScaled(900000L).build()
        );
        var snapshot = validatorService.recordSnapshot("mainnet", 10L, originalActive);
        String originalValSetHash = snapshot.snapshotHash();

        // Create historical block payload for height 10
        String blockHash = "block10_hash_0000000000000000000000000000000000000000000000000000";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("blockHeight", 10L);
        payload.put("blockHash", blockHash);
        payload.put("validatorSetHash", originalValSetHash);
        payload.put("proposerAddress", v1.address);

        Map<String, Object> cert = new LinkedHashMap<>();
        cert.put("blockHeight", 10L);
        cert.put("blockHash", blockHash);
        cert.put("network", "mainnet");
        cert.put("voters", List.of(v1.address, v2.address));
        cert.put("signatures", Map.of(
                v1.address, Map.of("publicKey", v1.pubHex, "signature", signVote(v1, 10L, blockHash, "mainnet")),
                v2.address, Map.of("publicKey", v2.pubHex, "signature", signVote(v2, 10L, blockHash, "mainnet"))
        ));
        payload.put("consensusCertificate", cert);

        // Current active roster has now CHANGED (v1 increased stake to 50000, altering current hash)
        List<Validator> evolvedActive = List.of(
                Validator.builder().address(v1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(50000)).reliabilityScoreScaled(900000L).build(),
                Validator.builder().address(v2.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).reliabilityScoreScaled(900000L).build()
        );
        when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(evolvedActive);

        // Verification must find and use the historical snapshot and SUCCEED!
        assertTrue(broadcastService.verifyConsensusCertificate(payload, "mainnet"));
    }

    @Test
    @DisplayName("2. Historical certificate remains verifiable after an original validator deactivates")
    void testHistoricalCertificateVerifiableAfterValidatorDeactivation() {
        // Height 5: v1 and v2 active
        List<Validator> originalActive = List.of(
                Validator.builder().address(v1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(2000)).reliabilityScoreScaled(900000L).build(),
                Validator.builder().address(v2.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(2000)).reliabilityScoreScaled(900000L).build()
        );
        var snapshot = validatorService.recordSnapshot("mainnet", 5L, originalActive);

        String blockHash = "block5_hash_00000000000000000000000000000000000000000000000000000";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("blockHeight", 5L);
        payload.put("blockHash", blockHash);
        payload.put("validatorSetHash", snapshot.snapshotHash());
        payload.put("proposerAddress", v1.address);

        Map<String, Object> cert = new LinkedHashMap<>();
        cert.put("blockHeight", 5L);
        cert.put("blockHash", blockHash);
        cert.put("network", "mainnet");
        cert.put("voters", List.of(v1.address, v2.address));
        cert.put("signatures", Map.of(
                v1.address, Map.of("publicKey", v1.pubHex, "signature", signVote(v1, 5L, blockHash, "mainnet")),
                v2.address, Map.of("publicKey", v2.pubHex, "signature", signVote(v2, 5L, blockHash, "mainnet"))
        ));
        payload.put("consensusCertificate", cert);

        // Now in current state: v2 got SLASHED/INACTIVE! Only v1 is active!
        List<Validator> currentActive = List.of(
                Validator.builder().address(v1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(2000)).reliabilityScoreScaled(900000L).build()
        );
        when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(currentActive);

        // Historical certificate should still verify against original snapshot
        assertTrue(broadcastService.verifyConsensusCertificate(payload, "mainnet"));
    }

    @Test
    @DisplayName("3. Adding a new validator does not alter historical snapshot")
    void testAddingNewValidatorDoesNotAlterSnapshot() {
        List<Validator> originalActive = List.of(
                Validator.builder().address(v1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).reliabilityScoreScaled(900000L).build()
        );
        var snapshot = validatorService.recordSnapshot("mainnet", 1L, originalActive);
        String origHash = snapshot.snapshotHash();

        // Adding v3 to current active validators
        List<Validator> newActive = List.of(
                Validator.builder().address(v1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).reliabilityScoreScaled(900000L).build(),
                Validator.builder().address(v3.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).reliabilityScoreScaled(900000L).build()
        );
        String newHash = BlockValidationService.computeValidatorSetHash(newActive);
        assertNotEquals(origHash, newHash);

        // The snapshot for origHash remains unaltered
        Optional<ValidatorService.ValidatorSetSnapshot> retrieved = validatorService.getSnapshotByHash(origHash);
        assertTrue(retrieved.isPresent());
        assertEquals(origHash, retrieved.get().snapshotHash());
        assertEquals(1, retrieved.get().validators().size());
    }

    @Test
    @DisplayName("4. Changing a snapshot field produces a different snapshot hash")
    void testSnapshotHashChangesOnFieldModification() {
        List<Validator> vList1 = List.of(
                Validator.builder().address(v1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).reliabilityScoreScaled(900000L).build()
        );
        List<Validator> vList2 = List.of(
                Validator.builder().address(v1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1001)).reliabilityScoreScaled(900000L).build()
        );
        String hash1 = BlockValidationService.computeValidatorSetHash(vList1);
        String hash2 = BlockValidationService.computeValidatorSetHash(vList2);
        assertNotEquals(hash1, hash2);
    }

    @Test
    @DisplayName("5. Historical block referencing missing snapshot is rejected (fails closed)")
    void testHistoricalBlockWithMissingSnapshotRejected() {
        String nonExistentHash = "e".repeat(64);
        String blockHash = "block99_hash_000000000000000000000000000000000000000000000000000";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("blockHeight", 99L);
        payload.put("blockHash", blockHash);
        payload.put("validatorSetHash", nonExistentHash);
        payload.put("proposerAddress", v1.address);

        Map<String, Object> cert = new LinkedHashMap<>();
        cert.put("blockHeight", 99L);
        cert.put("blockHash", blockHash);
        cert.put("network", "mainnet");
        cert.put("voters", List.of(v1.address));
        cert.put("signatures", Map.of(
                v1.address, Map.of("publicKey", v1.pubHex, "signature", signVote(v1, 99L, blockHash, "mainnet"))
        ));
        payload.put("consensusCertificate", cert);

        // Current active validators have a different hash
        when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(List.of(
                Validator.builder().address(v1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(5000)).reliabilityScoreScaled(950000L).build()
        ));

        // Missing historical snapshot must fail closed!
        assertFalse(broadcastService.verifyConsensusCertificate(payload, "mainnet"));
    }

    @Test
    @DisplayName("6. Two nodes produce identical snapshot hash from equivalent validator data")
    void testSnapshotHashDeterminismAcrossNodes() {
        List<Validator> nodeA = List.of(
                Validator.builder().address(v1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(new BigDecimal("1000")).reliabilityScoreScaled(900000L).build(),
                Validator.builder().address(v2.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(new BigDecimal("2000")).reliabilityScoreScaled(850000L).build()
        );
        // Node B has same data with different list order and decimal representations
        List<Validator> nodeB = List.of(
                Validator.builder().address(v2.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(new BigDecimal("2000.00")).reliabilityScoreScaled(850000L).build(),
                Validator.builder().address(v1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(new BigDecimal("1000.0")).reliabilityScoreScaled(900000L).build()
        );

        String hashA = BlockValidationService.computeValidatorSetHash(nodeA);
        String hashB = BlockValidationService.computeValidatorSetHash(nodeB);
        assertEquals(hashA, hashB, "Validator set hash must be completely deterministic across nodes regardless of insertion order or decimal trailing zeros");
    }
}
