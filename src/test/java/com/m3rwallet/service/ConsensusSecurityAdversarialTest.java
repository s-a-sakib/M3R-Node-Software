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
import org.junit.jupiter.api.Nested;
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
 * Adversarial test suite for M3R consensus certificate verification,
 * block finalization security, solo-mode gating, proposer signatures,
 * and validator-set commitment integrity.
 */
class ConsensusSecurityAdversarialTest {

    private BlockBroadcastService broadcastService;
    private BlockValidationService validationService;
    private BlockRepository mockBlockRepo;
    private ValidatorRepository mockValidatorRepo;
    private ValidatorService mockValidatorService;

    static class TestValidatorKey {
        ECPrivateKeyParameters privateKey;
        String publicKeyHex;
        String address;
    }

    private TestValidatorKey generateTestKey() {
        X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        ECDomainParameters domain = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
        ECKeyPairGenerator gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(domain, new SecureRandom()));
        AsymmetricCipherKeyPair pair = gen.generateKeyPair();
        TestValidatorKey key = new TestValidatorKey();
        key.privateKey = (ECPrivateKeyParameters) pair.getPrivate();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) pair.getPublic();
        byte[] comp = pub.getQ().getEncoded(true);
        key.publicKeyHex = Hex.encodeHexString(comp);
        key.address = AddressUtil.deriveAddressFromPublicKey(comp);
        return key;
    }

    private String signVote(TestValidatorKey key, long height, String hash, String network) {
        String canonical = "BLOCK_VOTE|" + network + "|" + height + "|" + hash + "|" + key.address;
        byte[] digest = CryptoUtil.keccak256(canonical.getBytes(StandardCharsets.UTF_8));
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, key.privateKey);
        BigInteger[] sig = signer.generateSignature(digest);
        byte[] raw = CryptoUtil.formatRawSignature(sig[0], sig[1]);
        return Hex.encodeHexString(raw);
    }

    private String signHeader(TestValidatorKey key, byte[] headerBytes) {
        byte[] digest = CryptoUtil.keccak256(headerBytes);
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, key.privateKey);
        BigInteger[] sig = signer.generateSignature(digest);
        byte[] raw = CryptoUtil.formatRawSignature(sig[0], sig[1]);
        return Hex.encodeHexString(raw);
    }

    private Map<String, Object> createSignedBlockPayload(TestValidatorKey proposer, long height, long slot, String network) {
        return createSignedBlockPayload(proposer, height, slot, network, "0".repeat(64));
    }

    private Map<String, Object> createSignedBlockPayload(TestValidatorKey proposer, long height, long slot, String network, String valSetHash) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 1);
        payload.put("blockHeight", height);
        payload.put("slotNumber", slot);
        payload.put("parentBlockHash", "0".repeat(64));
        payload.put("merkleRoot", validationService.computeMerkleRoot(List.of()));
        payload.put("stateRoot", "0".repeat(64));
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("proposerAddress", proposer.address);
        payload.put("proposerPublicKey", proposer.publicKeyHex);
        payload.put("proposerWeight", 2.0);
        payload.put("validatorSetHash", (valSetHash != null && !valSetHash.isBlank()) ? valSetHash : "0".repeat(64));
        payload.put("nonce", 1001L);
        payload.put("network", network);
        payload.put("txCount", 0);
        payload.put("transactions", List.of());

        byte[] headerBytes = validationService.serializePayloadForSigning(payload);
        payload.put("proposerSignature", signHeader(proposer, headerBytes));

        String blockHash = validationService.computeBlockHash(payload);
        payload.put("blockHash", blockHash);
        return payload;
    }

    @BeforeEach
    void setup() {
        mockBlockRepo = Mockito.mock(BlockRepository.class);
        mockValidatorRepo = Mockito.mock(ValidatorRepository.class);
        mockValidatorService = Mockito.mock(ValidatorService.class);

        broadcastService = new BlockBroadcastService(mockBlockRepo, null, null, null, null);
        ReflectionTestUtils.setField(broadcastService, "validatorRepo", mockValidatorRepo);
        ReflectionTestUtils.setField(broadcastService, "validatorService", mockValidatorService);
        ReflectionTestUtils.setField(broadcastService, "peerUrls", List.of("http://peer1:3000", "http://peer2:4000"));
        broadcastService.setAllowSoloFinalization(false);

        validationService = new BlockValidationService();
        ReflectionTestUtils.setField(validationService, "blockRepo", mockBlockRepo);
        ReflectionTestUtils.setField(validationService, "validatorRepo", mockValidatorRepo);
        ReflectionTestUtils.setField(validationService, "validatorService", mockValidatorService);
        ReflectionTestUtils.setField(validationService, "defaultNetwork", "mainnet");
        when(mockValidatorService.calculateWeight(any())).thenReturn(2.0);
    }

    @Nested
    @DisplayName("A. Certificate Adversarial Tests")
    class CertificateAdversarialTests {

        private TestValidatorKey val1;
        private TestValidatorKey val2;
        private TestValidatorKey val3;
        private List<Validator> activeValidators;
        private Map<String, Object> blockPayload;
        private String blockHash;

        @BeforeEach
        void initValidators() {
            val1 = generateTestKey(); // Proposer
            val2 = generateTestKey(); // Peer 1
            val3 = generateTestKey(); // Peer 2

            activeValidators = List.of(
                    Validator.builder().address(val1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).build(),
                    Validator.builder().address(val2.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).build(),
                    Validator.builder().address(val3.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).build()
            );
            when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(activeValidators);
            when(mockValidatorService.calculateWeight(any())).thenReturn(1.0);

            String expectedValSetHash = BlockValidationService.computeValidatorSetHash(activeValidators);
            blockPayload = createSignedBlockPayload(val1, 10L, 10L, "mainnet", expectedValSetHash);
            blockHash = (String) blockPayload.get("blockHash");
        }

        private Map<String, Object> createValidCertificate(List<TestValidatorKey> voters) {
            Map<String, Object> cert = new LinkedHashMap<>();
            cert.put("blockHeight", 10L);
            cert.put("blockHash", blockHash);
            cert.put("network", "mainnet");
            cert.put("proposerAddress", val1.address);

            List<String> voterAddrs = new ArrayList<>();
            Map<String, Object> signatures = new LinkedHashMap<>();
            for (TestValidatorKey k : voters) {
                voterAddrs.add(k.address);
                signatures.put(k.address, Map.of(
                        "publicKey", k.publicKeyHex,
                        "signature", signVote(k, 10L, blockHash, "mainnet")
                ));
            }
            cert.put("voters", voterAddrs);
            cert.put("signatures", signatures);
            return cert;
        }

        @Test
        @DisplayName("1. Valid certificate with valid signatures is accepted")
        void testValidCertificateAccepted() {
            Map<String, Object> cert = createValidCertificate(List.of(val1, val2));
            blockPayload.put("consensusCertificate", cert);
            assertTrue(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("2. Certificate with a forged voter list is rejected")
        void testForgedVoterListRejected() {
            // Include val3 in voter list, but provide no signature for val3
            Map<String, Object> cert = createValidCertificate(List.of(val1, val2));
            List<String> forgedVoters = new ArrayList<>(List.of(val1.address, val2.address, val3.address));
            cert.put("voters", forgedVoters);
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("3. Certificate with a missing signature is rejected")
        void testMissingSignatureRejected() {
            Map<String, Object> cert = createValidCertificate(List.of(val1, val2));
            @SuppressWarnings("unchecked")
            Map<String, Object> sigs = (Map<String, Object>) cert.get("signatures");
            sigs.remove(val2.address);
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("4. Certificate with an invalid signature is rejected")
        void testInvalidSignatureRejected() {
            Map<String, Object> cert = createValidCertificate(List.of(val1, val2));
            @SuppressWarnings("unchecked")
            Map<String, Object> sigs = (Map<String, Object>) cert.get("signatures");
            sigs.put(val2.address, Map.of(
                    "publicKey", val2.publicKeyHex,
                    "signature", "a".repeat(128) // Corrupted 64-byte signature
            ));
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("5. Certificate with mismatched public key is rejected")
        void testMismatchedPublicKeyRejected() {
            TestValidatorKey attackerKey = generateTestKey();
            Map<String, Object> cert = createValidCertificate(List.of(val1));
            @SuppressWarnings("unchecked")
            Map<String, Object> sigs = (Map<String, Object>) cert.get("signatures");

            // Attacker uses their own public key for val2's address
            sigs.put(val2.address, Map.of(
                    "publicKey", attackerKey.publicKeyHex,
                    "signature", signVote(attackerKey, 10L, blockHash, "mainnet")
            ));
            cert.put("voters", List.of(val1.address, val2.address));
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("6. Certificate with mismatched voter address is rejected")
        void testMismatchedVoterAddressRejected() {
            Map<String, Object> cert = createValidCertificate(List.of(val1, val2));
            @SuppressWarnings("unchecked")
            Map<String, Object> sigs = (Map<String, Object>) cert.get("signatures");
            // Signature entry is associated with wrong address
            Object val2Sig = sigs.remove(val2.address);
            sigs.put(val3.address, val2Sig);
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("7. Duplicate voter entries are rejected and cannot increase quorum")
        void testDuplicateVoterRejected() {
            Map<String, Object> cert = createValidCertificate(List.of(val1, val2));
            cert.put("voters", List.of(val1.address, val2.address, val2.address));
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("8. Unknown validator signatures are rejected")
        void testUnknownValidatorSignatureRejected() {
            TestValidatorKey stranger = generateTestKey();
            Map<String, Object> cert = createValidCertificate(List.of(val1, stranger));
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("9. Inactive validator signatures are rejected")
        void testInactiveValidatorSignatureRejected() {
            TestValidatorKey jailedVal = generateTestKey();
            Validator jailed = Validator.builder()
                    .address(jailedVal.address)
                    .status(Validator.ValidatorStatus.JAILED)
                    .stakedAmount(BigDecimal.valueOf(1000))
                    .build();
            // Jailed validator is not returned by findByNetworkAndStatus ACTIVE
            Map<String, Object> cert = createValidCertificate(List.of(val1, jailedVal));
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("10. Certificate with insufficient weighted quorum is rejected")
        void testInsufficientWeightedQuorumRejected() {
            // Asymmetric weights: val1=1.0, val2=1.0, val3=8.0 (total = 10.0, quorum = 6.67)
            when(mockValidatorService.calculateWeight(argThat(v -> v != null && v.getAddress().equals(val1.address)))).thenReturn(1.0);
            when(mockValidatorService.calculateWeight(argThat(v -> v != null && v.getAddress().equals(val2.address)))).thenReturn(1.0);
            when(mockValidatorService.calculateWeight(argThat(v -> v != null && v.getAddress().equals(val3.address)))).thenReturn(8.0);

            // Votes from val1 + val2 = 2.0 / 10.0 = 20% < 66.7%
            Map<String, Object> cert = createValidCertificate(List.of(val1, val2));
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("11. Certificate with insufficient count quorum is rejected")
        void testInsufficientCountQuorumRejected() {
            // 4 validators, need ceil(8/3) = 3 votes
            TestValidatorKey val4 = generateTestKey();
            List<Validator> fourValidators = List.of(
                    Validator.builder().address(val1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).build(),
                    Validator.builder().address(val2.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).build(),
                    Validator.builder().address(val3.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).build(),
                    Validator.builder().address(val4.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).build()
            );
            when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(fourValidators);

            // Only 2 of 4 vote -> count quorum fails
            Map<String, Object> cert = createValidCertificate(List.of(val1, val2));
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("12. Certificate with incorrect block height is rejected")
        void testIncorrectBlockHeightRejected() {
            Map<String, Object> cert = createValidCertificate(List.of(val1, val2));
            cert.put("blockHeight", 999L); // Wrong height
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("13. Certificate with incorrect block hash is rejected")
        void testIncorrectBlockHashRejected() {
            Map<String, Object> cert = createValidCertificate(List.of(val1, val2));
            cert.put("blockHash", "f".repeat(64)); // Wrong hash
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("14. Certificate replayed on another network is rejected")
        void testReplayedNetworkRejected() {
            // Sign for testnet
            Map<String, Object> testnetCert = new LinkedHashMap<>();
            testnetCert.put("blockHeight", 10L);
            testnetCert.put("blockHash", blockHash);
            testnetCert.put("network", "testnet");
            testnetCert.put("voters", List.of(val1.address, val2.address));
            testnetCert.put("signatures", Map.of(
                    val1.address, Map.of("publicKey", val1.publicKeyHex, "signature", signVote(val1, 10L, blockHash, "testnet")),
                    val2.address, Map.of("publicKey", val2.publicKeyHex, "signature", signVote(val2, 10L, blockHash, "testnet"))
            ));
            blockPayload.put("consensusCertificate", testnetCert);

            // Verifying on mainnet node -> rejected because network identifier does not match
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("15. Certificate with mismatched network identifier is rejected")
        void testMismatchedNetworkIdentifierRejected() {
            Map<String, Object> cert = createValidCertificate(List.of(val1, val2));
            cert.put("network", "devnet");
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("16. Proposer-only vote cannot satisfy independent peer confirmation")
        void testProposerOnlyVoteCannotSatisfyPeerConfirmation() {
            // Proposer alone votes
            Map<String, Object> cert = createValidCertificate(List.of(val1));
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("17. Signature map and voter list size inconsistency is rejected")
        void testSignatureMapAndVoterListInconsistencyRejected() {
            Map<String, Object> cert = createValidCertificate(List.of(val1, val2));
            cert.put("voters", List.of(val1.address)); // 1 voter, 2 signatures
            blockPayload.put("consensusCertificate", cert);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("18. Tampered certificate metadata cannot alter authenticated tally")
        void testTamperedCertificateMetadataIgnored() {
            Map<String, Object> cert = createValidCertificate(List.of(val1)); // Only 1 vote
            // Attacker fakes quorum summary fields in certificate JSON
            cert.put("yesVotes", 3);
            cert.put("approvedWeight", 100.0);
            cert.put("totalWeight", 100.0);
            cert.put("weightRatio", 1.0);
            cert.put("peerConfirmations", 2);

            blockPayload.put("consensusCertificate", cert);
            // Must still fail because recomputed tally uses authenticated signatures
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }
    }

    @Nested
    @DisplayName("B. Solo-Mode Operating Tests")
    class SoloModeTests {

        private TestValidatorKey val1;
        private Map<String, Object> blockPayload;

        @BeforeEach
        void initSolo() {
            val1 = generateTestKey();
            blockPayload = createSignedBlockPayload(val1, 1L, 1L, "mainnet");
        }

        @Test
        @DisplayName("1. No peers and solo mode disabled: finalization rejected")
        void testNoPeersSoloDisabled_Rejected() {
            ReflectionTestUtils.setField(broadcastService, "peerUrls", List.of());
            broadcastService.setAllowSoloFinalization(false);

            blockPayload.put("isFinalized", true);
            // No valid certificate provided and solo mode disabled -> fails closed
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("2. No peers and solo mode enabled: explicit solo path permitted")
        void testNoPeersSoloEnabled_Permitted() {
            ReflectionTestUtils.setField(broadcastService, "peerUrls", List.of());
            broadcastService.setAllowSoloFinalization(true);

            blockPayload.put("isFinalized", true);
            assertTrue(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("3. Empty certificate in multi-validator mode: rejected")
        void testEmptyCertificateInMultiValidator_Rejected() {
            ReflectionTestUtils.setField(broadcastService, "peerUrls", List.of("http://peer1:3000"));
            broadcastService.setAllowSoloFinalization(false);

            blockPayload.put("isFinalized", true);
            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }

        @Test
        @DisplayName("4. Solo certificate cannot bypass multi-validator quorum when peers exist")
        void testSoloCertificateCannotBypassWhenPeersExist() {
            ReflectionTestUtils.setField(broadcastService, "peerUrls", List.of("http://peer1:3000", "http://peer2:4000"));
            broadcastService.setAllowSoloFinalization(true); // even if true, peers exist!

            TestValidatorKey val2 = generateTestKey();
            List<Validator> active = List.of(
                    Validator.builder().address(val1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).build(),
                    Validator.builder().address(val2.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).build()
            );
            when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(active);

            // Certificate with only solo proposer vote
            Map<String, Object> soloCert = new LinkedHashMap<>();
            soloCert.put("blockHeight", 1L);
            soloCert.put("blockHash", blockPayload.get("blockHash"));
            soloCert.put("voters", List.of(val1.address));
            soloCert.put("signatures", Map.of(val1.address, Map.of(
                    "publicKey", val1.publicKeyHex,
                    "signature", signVote(val1, 1L, (String) blockPayload.get("blockHash"), "mainnet")
            )));
            blockPayload.put("consensusCertificate", soloCert);

            assertFalse(broadcastService.verifyConsensusCertificate(blockPayload, "mainnet"));
        }
    }

    @Nested
    @DisplayName("C. Proposer-Signature Validation Tests")
    class ProposerSignatureTests {

        private TestValidatorKey proposer;
        private Map<String, Object> validPayload;

        @BeforeEach
        void initProposer() {
            proposer = generateTestKey();
            Validator leader = Validator.builder()
                    .address(proposer.address)
                    .status(Validator.ValidatorStatus.ACTIVE)
                    .stakedAmount(BigDecimal.valueOf(1000))
                    .build();
            List<Validator> active = List.of(leader);
            when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(active);
            when(mockValidatorService.selectValidatorForSlot(eq(1L), anyString())).thenReturn(leader);
            when(mockValidatorRepo.findByAddressAndNetwork(eq(proposer.address), anyString())).thenReturn(Optional.of(leader));
            when(mockValidatorService.calculateWeight(any())).thenReturn(2.0);

            String expectedValSetHash = BlockValidationService.computeValidatorSetHash(active);
            validPayload = createSignedBlockPayload(proposer, 1L, 1L, "mainnet", expectedValSetHash);
        }

        @Test
        @DisplayName("1. Valid proposer signature is accepted")
        void testValidProposerSignatureAccepted() {
            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(validPayload, "mainnet");
            assertTrue(result.valid());
            assertTrue(result.violations().isEmpty());
        }

        @Test
        @DisplayName("2. Missing proposer signature is rejected")
        void testMissingProposerSignatureRejected() {
            validPayload.remove("proposerSignature");
            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(validPayload, "mainnet");
            assertFalse(result.valid());
            assertTrue(result.violations().contains("MISSING_PROPOSER_SIGNATURE"));
        }

        @Test
        @DisplayName("3. Empty proposer signature is rejected")
        void testEmptyProposerSignatureRejected() {
            validPayload.put("proposerSignature", "   ");
            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(validPayload, "mainnet");
            assertFalse(result.valid());
            assertTrue(result.violations().contains("MISSING_PROPOSER_SIGNATURE"));
        }

        @Test
        @DisplayName("4. UNSIGNED proposer signature is rejected")
        void testUnsignedProposerSignatureRejected() {
            validPayload.put("proposerSignature", "UNSIGNED");
            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(validPayload, "mainnet");
            assertFalse(result.valid());
            assertTrue(result.violations().contains("PROPOSER_SIGNATURE_UNSIGNED"));
        }

        @Test
        @DisplayName("5. PEER_BROADCAST proposer signature is rejected")
        void testPeerBroadcastProposerSignatureRejected() {
            validPayload.put("proposerSignature", "PEER_BROADCAST");
            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(validPayload, "mainnet");
            assertFalse(result.valid());
            assertTrue(result.violations().contains("PROPOSER_SIGNATURE_PEER_BROADCAST"));
        }

        @Test
        @DisplayName("6. Invalid signature is rejected")
        void testInvalidSignatureRejected() {
            validPayload.put("proposerSignature", "00".repeat(64));
            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(validPayload, "mainnet");
            assertFalse(result.valid());
            assertTrue(result.violations().contains("PROPOSER_SIGNATURE_INVALID"));
        }

        @Test
        @DisplayName("7. Invalid public key is rejected")
        void testInvalidPublicKeyRejected() {
            validPayload.put("proposerPublicKey", "02deadbeef"); // Invalid key length
            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(validPayload, "mainnet");
            assertFalse(result.valid());
            assertTrue(result.violations().contains("INVALID_PROPOSER_PUBKEY"));
        }

        @Test
        @DisplayName("8. Public-key/address mismatch is rejected")
        void testPublicKeyAddressMismatchRejected() {
            TestValidatorKey other = generateTestKey();
            validPayload.put("proposerPublicKey", other.publicKeyHex);
            byte[] headerBytes = validationService.serializePayloadForSigning(validPayload);
            validPayload.put("proposerSignature", signHeader(other, headerBytes));

            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(validPayload, "mainnet");
            assertFalse(result.valid());
            assertTrue(result.violations().contains("PROPOSER_PUBKEY_MISMATCH"));
        }

        @Test
        @DisplayName("9. Modified block header causes signature verification failure")
        void testModifiedBlockHeaderRejected() {
            // Header is signed with slot 1, then slotNumber is tampered to 2
            validPayload.put("slotNumber", 2L);
            // Update hash to prevent hash check short-circuit, so signature check runs
            validPayload.put("blockHash", validationService.computeBlockHash(validPayload));

            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(validPayload, "mainnet");
            assertFalse(result.valid());
            assertTrue(result.violations().contains("PROPOSER_SIGNATURE_INVALID")
                    || result.violations().contains("SLOT_LEADER_LOOKUP_FAILED"));
        }

        @Test
        @DisplayName("10. Unauthorized/unregistered proposer is rejected")
        void testUnauthorizedProposerRejected() {
            when(mockValidatorRepo.findByAddressAndNetwork(eq(proposer.address), anyString())).thenReturn(Optional.empty());
            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(validPayload, "mainnet");
            assertFalse(result.valid());
            assertTrue(result.violations().contains("PROPOSER_NOT_REGISTERED"));
        }

        @Test
        @DisplayName("11. Incorrect slot leader is rejected")
        void testIncorrectSlotLeaderRejected() {
            TestValidatorKey rightfulLeader = generateTestKey();
            Validator rightful = Validator.builder()
                    .address(rightfulLeader.address)
                    .status(Validator.ValidatorStatus.ACTIVE)
                    .build();
            when(mockValidatorService.selectValidatorForSlot(eq(1L), anyString())).thenReturn(rightful);

            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(validPayload, "mainnet");
            assertFalse(result.valid());
            assertTrue(result.violations().contains("PROPOSER_NOT_SLOT_LEADER"));
        }

        @Test
        @DisplayName("12. Failed validator lookup is rejected")
        void testFailedValidatorLookupRejected() {
            when(mockValidatorService.selectValidatorForSlot(anyLong(), anyString()))
                    .thenThrow(new RuntimeException("Database timeout on leader selection"));

            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(validPayload, "mainnet");
            assertFalse(result.valid());
            assertTrue(result.violations().contains("SLOT_LEADER_LOOKUP_FAILED"));
        }
    }

    @Nested
    @DisplayName("D. Historical Validator-Set & Commitment Tests")
    class HistoricalValidatorSetTests {

        private TestValidatorKey val1;
        private TestValidatorKey val2;

        @BeforeEach
        void init() {
            val1 = generateTestKey();
            val2 = generateTestKey();
        }

        @Test
        @DisplayName("1. Validator set commitment hash matches genuine active roster")
        void testValidatorSetCommitmentMatchesActiveRoster() {
            List<Validator> active = List.of(
                    Validator.builder().address(val1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(5000)).reliabilityScoreScaled(950000L).build(),
                    Validator.builder().address(val2.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(3000)).reliabilityScoreScaled(890000L).build()
            );
            when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(active);

            String expectedHash = BlockValidationService.computeValidatorSetHash(active);
            assertNotNull(expectedHash);
            assertEquals(64, expectedHash.length());

            Map<String, Object> payload = createSignedBlockPayload(val1, 5L, 5L, "mainnet", expectedHash);
            Validator leader = active.get(0).getAddress().equals(val1.address) ? active.get(0) : active.get(1);
            when(mockValidatorService.selectValidatorForSlot(eq(5L), anyString())).thenReturn(leader);
            when(mockValidatorRepo.findByAddressAndNetwork(eq(val1.address), anyString())).thenReturn(Optional.of(leader));

            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
            assertFalse(result.violations().contains("VALIDATOR_SET_HASH_MISMATCH"));
        }

        @Test
        @DisplayName("2. Mismatched validator set commitment hash is rejected")
        void testMismatchedValidatorSetCommitmentRejected() {
            List<Validator> active = List.of(
                    Validator.builder().address(val1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(5000)).reliabilityScoreScaled(950000L).build()
            );
            when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(active);

            Map<String, Object> payload = createSignedBlockPayload(val1, 5L, 5L, "mainnet", "e".repeat(64)); // Fraudulent commitment

            when(mockValidatorService.selectValidatorForSlot(eq(5L), anyString())).thenReturn(active.get(0));
            when(mockValidatorRepo.findByAddressAndNetwork(eq(val1.address), anyString())).thenReturn(Optional.of(active.get(0)));

            BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
            assertFalse(result.valid());
            assertTrue(result.violations().contains("VALIDATOR_SET_HASH_MISMATCH"));
        }
    }
}
