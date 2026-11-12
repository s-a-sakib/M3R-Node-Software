package com.m3rwallet.service;

import com.m3rwallet.controller.NodeSyncController;
import com.m3rwallet.controller.WalletController;
import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.BlockTransaction;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.BlockTransactionRepository;
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
import com.m3rwallet.config.ConsensusProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Endpoint-level integration tests for Part 2 and Part 4:
 * Verifying POST /api/node/block/receive (NodeSyncController) and
 * POST /{network}/blocks/receive (WalletController).
 */
class BlockReceiveEndpointAdversarialTest {

    private NodeSyncController nodeSyncController;
    private WalletController walletController;
    private BlockBroadcastService broadcastService;
    private BlockValidationService validationService;
    private ValidatorService validatorService;

    private BlockRepository mockBlockRepo;
    private BlockTransactionRepository mockBlockTxRepo;
    private ValidatorRepository mockValidatorRepo;
    private MempoolService mockMempoolService;
    private FeeDistributionService mockFeeService;

    static class TestKey {
        ECPrivateKeyParameters priv;
        String pubHex;
        String address;
    }

    private TestKey val1;
    private TestKey val2;
    private TestKey val3;

    private TestKey generateKey() {
        X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        ECDomainParameters domain = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
        ECKeyPairGenerator gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(domain, new SecureRandom()));
        AsymmetricCipherKeyPair pair = gen.generateKeyPair();
        TestKey k = new TestKey();
        k.priv = (ECPrivateKeyParameters) pair.getPrivate();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) pair.getPublic();
        byte[] comp = pub.getQ().getEncoded(true);
        k.pubHex = Hex.encodeHexString(comp);
        k.address = AddressUtil.deriveAddressFromPublicKey(comp);
        return k;
    }

    private String signVote(TestKey key, long height, String hash, String network) {
        String canonical = "BLOCK_VOTE|" + network + "|" + height + "|" + hash + "|" + key.address;
        byte[] digest = CryptoUtil.keccak256(canonical.getBytes(StandardCharsets.UTF_8));
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, key.priv);
        BigInteger[] sig = signer.generateSignature(digest);
        byte[] raw = CryptoUtil.formatRawSignature(sig[0], sig[1]);
        return Hex.encodeHexString(raw);
    }

    private String signHeader(TestKey key, byte[] headerBytes) {
        byte[] digest = CryptoUtil.keccak256(headerBytes);
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, key.priv);
        BigInteger[] sig = signer.generateSignature(digest);
        byte[] raw = CryptoUtil.formatRawSignature(sig[0], sig[1]);
        return Hex.encodeHexString(raw);
    }

    @BeforeEach
    void setup() {
        val1 = generateKey();
        val2 = generateKey();
        val3 = generateKey();

        mockBlockRepo = Mockito.mock(BlockRepository.class);
        mockBlockTxRepo = Mockito.mock(BlockTransactionRepository.class);
        mockValidatorRepo = Mockito.mock(ValidatorRepository.class);
        mockMempoolService = Mockito.mock(MempoolService.class);
        mockFeeService = Mockito.mock(FeeDistributionService.class);

        validatorService = Mockito.spy(new ValidatorService(
                mockValidatorRepo,
                Mockito.mock(com.m3rwallet.repository.ValidatorWeightRepository.class),
                Mockito.mock(com.m3rwallet.repository.SlashEventRepository.class),
                null, null, null
        ));

        validationService = new BlockValidationService();
        ReflectionTestUtils.setField(validationService, "blockRepo", mockBlockRepo);
        ReflectionTestUtils.setField(validationService, "validatorRepo", mockValidatorRepo);
        ReflectionTestUtils.setField(validationService, "validatorService", validatorService);
        ReflectionTestUtils.setField(validationService, "defaultNetwork", "mainnet");

        broadcastService = new BlockBroadcastService(
                mockBlockRepo, mockBlockTxRepo, mockMempoolService, mockFeeService, null
        );
        ReflectionTestUtils.setField(broadcastService, "blockValidationService", validationService);
        ReflectionTestUtils.setField(broadcastService, "validatorRepo", mockValidatorRepo);
        ReflectionTestUtils.setField(broadcastService, "validatorService", validatorService);
        ReflectionTestUtils.setField(broadcastService, "peerUrls", List.of("http://peer1:3000", "http://peer2:4000"));
        broadcastService.setAllowSoloFinalization(false);

        nodeSyncController = new NodeSyncController(null, mockBlockRepo, "mainnet", "http://localhost:3000");
        nodeSyncController.setBlockBroadcastService(broadcastService);

        walletController = new WalletController(
                Mockito.mock(WalletService.class),
                Mockito.mock(TxLedgerService.class),
                Mockito.mock(NodeConsensusService.class),
                new ConsensusProperties(),
                Mockito.mock(NodeIdentityService.class)
        );
        ReflectionTestUtils.setField(walletController, "blockBroadcastService", broadcastService);
        ReflectionTestUtils.setField(walletController, "blockRepo", mockBlockRepo);
        ReflectionTestUtils.setField(walletController, "validatorRepo", mockValidatorRepo);

        // Active validators in repository: val1 (weight 2.0), val2 (weight 2.0), val3 (weight 2.0)
        List<Validator> active = List.of(
                Validator.builder().address(val1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(2000)).reliabilityScoreScaled(950000L).build(),
                Validator.builder().address(val2.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(2000)).reliabilityScoreScaled(950000L).build(),
                Validator.builder().address(val3.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(2000)).reliabilityScoreScaled(950000L).build()
        );
        when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(active);
        when(mockValidatorRepo.findByAddressAndNetwork(eq(val1.address), anyString())).thenReturn(Optional.of(active.get(0)));
        when(mockBlockRepo.findById(anyLong())).thenReturn(Optional.empty());
        Mockito.doReturn(2.0d).when(validatorService).calculateWeight(any());
        Mockito.doAnswer(invocation -> active.get(0)).when(validatorService).selectValidatorForSlot(anyLong(), anyString());
    }

    private Map<String, Object> createBlockPayload(TestKey proposer, long height, long slot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 1);
        payload.put("network", "mainnet");
        payload.put("blockHeight", height);
        payload.put("slotNumber", slot);
        payload.put("parentBlockHash", "0".repeat(64));
        payload.put("merkleRoot", validationService.computeMerkleRoot(List.of()));
        payload.put("stateRoot", "0".repeat(64));
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("proposerAddress", proposer.address);
        payload.put("proposerPublicKey", proposer.pubHex);
        payload.put("proposerWeight", 2.0);
        payload.put("nonce", 1001L);
        payload.put("txCount", 0);
        payload.put("transactions", List.of());

        List<Validator> active = mockValidatorRepo.findByNetworkAndStatus("mainnet", Validator.ValidatorStatus.ACTIVE);
        String valSetHash = BlockValidationService.computeValidatorSetHash(active);
        payload.put("validatorSetHash", valSetHash);

        byte[] headerBytes = validationService.serializePayloadForSigning(payload);
        payload.put("proposerSignature", signHeader(proposer, headerBytes));

        String blockHash = validationService.computeBlockHash(payload);
        payload.put("blockHash", blockHash);
        return payload;
    }

    private Map<String, Object> createValidCertificate(long height, String hash, List<TestKey> signers) {
        Map<String, Object> cert = new LinkedHashMap<>();
        cert.put("blockHeight", height);
        cert.put("blockHash", hash);
        cert.put("network", "mainnet");
        cert.put("proposerAddress", val1.address);
        List<String> voters = new ArrayList<>();
        Map<String, Object> signatures = new LinkedHashMap<>();
        for (TestKey k : signers) {
            voters.add(k.address);
            signatures.put(k.address, Map.of(
                    "publicKey", k.pubHex,
                    "signature", signVote(k, height, hash, "mainnet")
            ));
        }
        cert.put("voters", voters);
        cert.put("signatures", signatures);
        return cert;
    }

    @Test
    @DisplayName("1. Arbitrary block submitted to NodeSyncController /api/node/block/receive without certificate is rejected")
    void testNodeSyncControllerRejectsArbitraryUnauthenticatedBlock() {
        Map<String, Object> payload = createBlockPayload(val1, 1L, 1L);
        payload.put("isFinalized", true);
        // NO consensus certificate provided!

        ResponseEntity<?> response = nodeSyncController.receiveBlock(payload);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(mockBlockRepo, never()).save(any(Block.class));
        verify(mockFeeService, never()).distributeBlockFees(any(), any());
    }

    @Test
    @DisplayName("2. Block with forged proposer signature submitted to endpoint is rejected")
    void testEndpointRejectsForgedProposerSignature() {
        Map<String, Object> payload = createBlockPayload(val1, 1L, 1L);
        payload.put("proposerSignature", "f".repeat(128)); // forged
        payload.put("isFinalized", true);

        ResponseEntity<?> response = nodeSyncController.receiveBlock(payload);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(mockBlockRepo, never()).save(any(Block.class));
    }

    @Test
    @DisplayName("3. Block claiming finalized=true with invalid certificate is rejected and canonical DB untouched")
    void testEndpointRejectsInvalidCertificateClaimingFinalized() {
        Map<String, Object> payload = createBlockPayload(val1, 1L, 1L);
        payload.put("isFinalized", true);
        // Insufficient quorum: only 1 of 3 voters, no peer confirmation
        Map<String, Object> cert = createValidCertificate(1L, (String) payload.get("blockHash"), List.of(val1));
        payload.put("consensusCertificate", cert);

        ResponseEntity<?> response = nodeSyncController.receiveBlock(payload);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(mockBlockRepo, never()).save(any(Block.class));
        verify(mockBlockTxRepo, never()).saveAll(any());
        verify(mockFeeService, never()).distributeBlockFees(any(), any());
        verify(mockMempoolService, never()).removeTransactions(any());
    }

    @Test
    @DisplayName("4. Valid finalized block with authentic quorum certificate is accepted via endpoint")
    void testEndpointAcceptsValidFinalizedBlock() {
        Map<String, Object> payload = createBlockPayload(val1, 1L, 1L);
        payload.put("isFinalized", true);
        // 2 of 3 votes: val1 and val2 (meets count quorum 2/3 and weight quorum 4.0/6.0)
        Map<String, Object> cert = createValidCertificate(1L, (String) payload.get("blockHash"), List.of(val1, val2));
        payload.put("consensusCertificate", cert);

        ResponseEntity<?> response = nodeSyncController.receiveBlock(payload);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());

        // Verify canonical persistence occurred
        verify(mockBlockRepo, atLeastOnce()).save(any(Block.class));
        verify(mockFeeService, atLeastOnce()).distributeBlockFees(any(), any());
    }

    @Test
    @DisplayName("5. Unfinalized proposal is staged in memory without touching canonical chain or evicting mempool")
    void testUnfinalizedProposalStagedWithoutPollutingLedger() {
        Map<String, Object> payload = createBlockPayload(val1, 1L, 1L);
        payload.put("isFinalized", false); // Unfinalized proposal propagation

        ResponseEntity<?> response = nodeSyncController.receiveBlock(payload);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals("STAGED_UNFINALIZED", body.get("status"));

        // Crucial security verifications: canonical state UNTOUCHED
        verify(mockBlockRepo, never()).save(any(Block.class));
        verify(mockBlockTxRepo, never()).saveAll(any());
        verify(mockFeeService, never()).distributeBlockFees(any(), any());
        verify(mockMempoolService, never()).removeTransactions(any());

        // Verify staged in memory
        assertFalse(broadcastService.getUnfinalizedProposals().isEmpty());
    }

    @Test
    @DisplayName("6. Subsequent finalized block at same height succeeds cleanly without fork conflict")
    void testFinalizedBlockSucceedsAfterPreviousUnfinalizedProposal() {
        Map<String, Object> proposal = createBlockPayload(val1, 1L, 1L);
        proposal.put("isFinalized", false);
        nodeSyncController.receiveBlock(proposal);

        // Subsequent finalized block arrives at height 1 with 2/3 certificate
        Map<String, Object> finalizedPayload = createBlockPayload(val1, 1L, 1L);
        finalizedPayload.put("isFinalized", true);
        Map<String, Object> cert = createValidCertificate(1L, (String) finalizedPayload.get("blockHash"), List.of(val1, val2));
        finalizedPayload.put("consensusCertificate", cert);

        ResponseEntity<?> response = nodeSyncController.receiveBlock(finalizedPayload);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(mockBlockRepo, atLeastOnce()).save(any(Block.class));
        verify(mockFeeService, atLeastOnce()).distributeBlockFees(any(), any());
    }

    @Test
    @DisplayName("7. WalletController receive endpoint also enforces certificate validation")
    void testWalletControllerEnforcesCertificateValidation() {
        Map<String, Object> payload = createBlockPayload(val1, 1L, 1L);
        payload.put("isFinalized", true);
        // No certificate -> rejected
        ResponseEntity<?> resp1 = walletController.receiveBlock("mainnet", payload);
        Map<?, ?> body1 = (Map<?, ?>) resp1.getBody();
        assertNotNull(body1);
        assertEquals("REJECTED", body1.get("status"));

        // With valid certificate -> accepted
        Map<String, Object> cert = createValidCertificate(1L, (String) payload.get("blockHash"), List.of(val1, val2));
        payload.put("consensusCertificate", cert);
        ResponseEntity<?> resp2 = walletController.receiveBlock("mainnet", payload);
        Map<?, ?> body2 = (Map<?, ?>) resp2.getBody();
        assertNotNull(body2);
        assertEquals("ACCEPTED", body2.get("status"));
    }
}
