package com.m3rwallet.service;

import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.ValidatorRepository;
import com.m3rwallet.util.AddressUtil;
import com.m3rwallet.util.CryptoUtil;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class BlockFinalizationSecurityTest {

    private BlockValidationService validationService;
    private BlockRepository mockBlockRepo;
    private ValidatorRepository mockValidatorRepo;
    private ValidatorService mockValidatorService;

    @BeforeEach
    void setup() {
        validationService = new BlockValidationService();
        mockBlockRepo = Mockito.mock(BlockRepository.class);
        mockValidatorRepo = Mockito.mock(ValidatorRepository.class);
        mockValidatorService = Mockito.mock(ValidatorService.class);

        ReflectionTestUtils.setField(validationService, "blockRepo", mockBlockRepo);
        ReflectionTestUtils.setField(validationService, "validatorRepo", mockValidatorRepo);
        ReflectionTestUtils.setField(validationService, "validatorService", mockValidatorService);
        ReflectionTestUtils.setField(validationService, "defaultNetwork", "mainnet");
    }

    private Map<String, Object> createValidPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 1);
        payload.put("blockHeight", 1L);
        payload.put("slotNumber", 1L);
        payload.put("parentBlockHash", "0".repeat(64));
        payload.put("merkleRoot", "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470");
        payload.put("stateRoot", "0".repeat(64));
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("proposerAddress", "val1_address");
        payload.put("proposerWeight", 1.0);
        payload.put("nonce", 12345L);
        payload.put("network", "mainnet");
        payload.put("txCount", 0);
        payload.put("transactions", List.of());

        String blockHash = validationService.computeBlockHash(payload);
        payload.put("blockHash", blockHash);
        return payload;
    }

    @Test
    @DisplayName("Detects header tampering and block hash mismatch")
    void detectsHeaderTampering() {
        Map<String, Object> payload = createValidPayload();
        payload.put("nonce", 99999L); // Tamper nonce without updating blockHash

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("BLOCK_HASH_MISMATCH"));
    }

    @Test
    @DisplayName("Detects Merkle root mismatch when transactions do not match header root")
    void detectsMerkleRootMismatch() {
        Map<String, Object> payload = createValidPayload();
        payload.put("merkleRoot", "f".repeat(64));
        payload.put("transactions", List.of("a".repeat(64)));
        payload.put("txCount", 1);
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("MERKLE_ROOT_MISMATCH"));
    }

    @Test
    @DisplayName("Detects txCount mismatch when transaction list size differs from claimed txCount")
    void detectsTxCountMismatch() {
        Map<String, Object> payload = createValidPayload();
        payload.put("txCount", 5); // 0 txs in list, but claims 5
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("TX_COUNT_MISMATCH"));
    }

    @Test
    @DisplayName("Detects unauthorized proposer when proposer is not the elected slot leader")
    void detectsUnauthorizedProposerForSlot() {
        Map<String, Object> payload = createValidPayload();
        payload.put("proposerAddress", "malicious_proposer");
        payload.put("blockHash", validationService.computeBlockHash(payload));

        Validator legitimateLeader = new Validator();
        legitimateLeader.setAddress("elected_validator_address");
        legitimateLeader.setStatus(Validator.ValidatorStatus.ACTIVE);

        when(mockValidatorService.selectValidatorForSlot(eq(1L), anyString())).thenReturn(legitimateLeader);
        when(mockValidatorRepo.findByAddressAndNetwork(eq("malicious_proposer"), anyString()))
                .thenReturn(Optional.of(Validator.builder().address("malicious_proposer").status(Validator.ValidatorStatus.ACTIVE).build()));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("PROPOSER_NOT_SLOT_LEADER"));
    }

    @Test
    @DisplayName("Detects invalid proposer secp256k1 cryptographic signature")
    void detectsInvalidProposerSignature() {
        Map<String, Object> payload = createValidPayload();
        payload.put("proposerSignature", "f".repeat(128)); // 64 bytes of junk signature
        payload.put("proposerPublicKey", "02" + "a".repeat(64)); // 33-byte compressed key

        when(mockValidatorRepo.findByAddressAndNetwork(anyString(), anyString()))
                .thenReturn(Optional.of(Validator.builder().address("val1_address").status(Validator.ValidatorStatus.ACTIVE).build()));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("PROPOSER_PUBKEY_MISMATCH") || result.violations().contains("PROPOSER_SIGNATURE_INVALID"));
    }

    static class TestKey {
        ECPrivateKeyParameters priv;
        String pubHex;
        String address;
    }

    private TestKey generateTestKey() {
        X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        ECDomainParameters domain = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
        ECKeyPairGenerator gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(domain, new SecureRandom()));
        AsymmetricCipherKeyPair pair = gen.generateKeyPair();
        TestKey tk = new TestKey();
        tk.priv = (ECPrivateKeyParameters) pair.getPrivate();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) pair.getPublic();
        byte[] comp = pub.getQ().getEncoded(true);
        tk.pubHex = Hex.encodeHexString(comp);
        tk.address = AddressUtil.deriveAddressFromPublicKey(comp);
        return tk;
    }

    private String signVote(TestKey key, long height, String hash, String network) {
        String canonical = "BLOCK_VOTE|" + network + "|" + height + "|" + hash + "|" + key.address;
        byte[] digest = CryptoUtil.keccak256(canonical.getBytes(StandardCharsets.UTF_8));
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, key.priv);
        BigInteger[] sig = signer.generateSignature(digest);
        byte[] res = CryptoUtil.formatRawSignature(sig[0], sig[1]);
        return Hex.encodeHexString(res);
    }

    @Test
    @DisplayName("BlockBroadcastService rejects finalization when consensus certificate is missing or invalid")
    void rejectsFinalizationWithoutValidCertificate() {
        BlockBroadcastService broadcastService = new BlockBroadcastService(
                mockBlockRepo, null, null, null, null
        );
        ReflectionTestUtils.setField(broadcastService, "validatorRepo", mockValidatorRepo);
        ReflectionTestUtils.setField(broadcastService, "validatorService", mockValidatorService);
        ReflectionTestUtils.setField(broadcastService, "peerUrls", List.of("http://peer1:3000", "http://peer2:4000"));

        Map<String, Object> payloadWithoutCert = createValidPayload();
        payloadWithoutCert.put("isFinalized", true);

        // Without certificate -> verification returns false
        assertFalse(broadcastService.verifyConsensusCertificate(payloadWithoutCert, "mainnet"));

        TestKey val1 = generateTestKey();
        TestKey val2 = generateTestKey();
        TestKey val3 = generateTestKey();

        payloadWithoutCert.put("proposerAddress", val1.address);
        String blockHash = String.valueOf(payloadWithoutCert.get("blockHash"));

        // With certificate but insufficient quorum (1 of 3)
        Map<String, Object> invalidCert = new HashMap<>();
        invalidCert.put("blockHeight", 1L);
        invalidCert.put("blockHash", blockHash);
        invalidCert.put("network", "mainnet");
        invalidCert.put("voters", List.of(val1.address));
        Map<String, Object> sigs1 = new HashMap<>();
        sigs1.put(val1.address, Map.of("publicKey", val1.pubHex, "signature", signVote(val1, 1L, blockHash, "mainnet")));
        invalidCert.put("signatures", sigs1);
        payloadWithoutCert.put("consensusCertificate", invalidCert);

        List<Validator> active = List.of(
                Validator.builder().address(val1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(java.math.BigDecimal.valueOf(1000)).build(),
                Validator.builder().address(val2.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(java.math.BigDecimal.valueOf(1000)).build(),
                Validator.builder().address(val3.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(java.math.BigDecimal.valueOf(1000)).build()
        );
        when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(active);
        when(mockValidatorService.calculateWeight(any())).thenReturn(1.0);

        assertFalse(broadcastService.verifyConsensusCertificate(payloadWithoutCert, "mainnet"));

        // With certificate having 2 of 3 votes including peer confirmation and valid signatures -> reaches quorum
        invalidCert.put("voters", List.of(val1.address, val2.address));
        Map<String, Object> sigs2 = new HashMap<>(sigs1);
        sigs2.put(val2.address, Map.of("publicKey", val2.pubHex, "signature", signVote(val2, 1L, blockHash, "mainnet")));
        invalidCert.put("signatures", sigs2);
        assertTrue(broadcastService.verifyConsensusCertificate(payloadWithoutCert, "mainnet"));
    }
}
