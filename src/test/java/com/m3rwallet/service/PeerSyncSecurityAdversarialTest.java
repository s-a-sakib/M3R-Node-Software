package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.Peer;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.BlockTransactionRepository;
import com.m3rwallet.repository.PeerRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Adversarial tests for PeerSyncService verifying elimination of consensus bypass (F-01),
 * unauthenticated rollback prevention (F-02), and catch-up validation (F-04).
 */
public class PeerSyncSecurityAdversarialTest {

    private PeerSyncService peerSyncService;
    private PeerRepository mockPeerRepo;
    private BlockRepository mockBlockRepo;
    private BlockTransactionRepository mockBlockTxRepo;
    private RestTemplate mockRestTemplate;
    private BlockBroadcastService broadcastService;
    private BlockValidationService validationService;
    private ValidatorRepository mockValidatorRepo;
    private ValidatorService mockValidatorService;

    static class TestKey {
        ECPrivateKeyParameters priv;
        String pubHex;
        String address;
    }

    private TestKey val1;
    private TestKey val2;
    private TestKey val3;
    private List<Validator> activeValidators;

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

        activeValidators = List.of(
                Validator.builder().address(val1.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).reliabilityScoreScaled(900000L).build(),
                Validator.builder().address(val2.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).reliabilityScoreScaled(900000L).build(),
                Validator.builder().address(val3.address).status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000)).reliabilityScoreScaled(900000L).build()
        );

        mockPeerRepo = mock(PeerRepository.class);
        mockBlockRepo = mock(BlockRepository.class);
        mockBlockTxRepo = mock(BlockTransactionRepository.class);
        mockRestTemplate = mock(RestTemplate.class);
        mockValidatorRepo = mock(ValidatorRepository.class);
        mockValidatorService = mock(ValidatorService.class);

        when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(activeValidators);
        when(mockValidatorRepo.findByAddressAndNetwork(eq(val1.address), anyString())).thenReturn(Optional.of(activeValidators.get(0)));
        when(mockValidatorService.calculateWeight(any())).thenReturn(2.0);
        when(mockValidatorService.selectValidatorForSlot(anyLong(), anyString())).thenReturn(activeValidators.get(0));

        validationService = new BlockValidationService();
        ReflectionTestUtils.setField(validationService, "blockRepo", mockBlockRepo);
        ReflectionTestUtils.setField(validationService, "validatorRepo", mockValidatorRepo);
        ReflectionTestUtils.setField(validationService, "validatorService", mockValidatorService);
        ReflectionTestUtils.setField(validationService, "defaultNetwork", "mainnet");

        broadcastService = new BlockBroadcastService(mockBlockRepo, mockBlockTxRepo, null, null, null);
        ReflectionTestUtils.setField(broadcastService, "blockValidationService", validationService);
        ReflectionTestUtils.setField(broadcastService, "validatorRepo", mockValidatorRepo);
        ReflectionTestUtils.setField(broadcastService, "validatorService", mockValidatorService);
        ReflectionTestUtils.setField(broadcastService, "peerUrls", List.of("http://peer1:3000", "http://peer2:4000"));
        broadcastService.setAllowSoloFinalization(false);

        ConsensusProperties consensusProperties = new ConsensusProperties();
        consensusProperties.setEnabled(true);
        peerSyncService = new PeerSyncService(
                mockPeerRepo, mockBlockRepo, mockBlockTxRepo, mockRestTemplate,
                "http://localhost:3000", "", "mainnet", consensusProperties
        );
        peerSyncService.setBlockBroadcastService(broadcastService);
        peerSyncService.setValidatorService(mockValidatorService);
    }

    private Map<String, Object> createValidBlockPayload(long height, long slot, TestKey proposer) {
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
        String valSetHash = BlockValidationService.computeValidatorSetHash(activeValidators);
        payload.put("validatorSetHash", valSetHash);
        payload.put("nonce", 1001L);
        payload.put("txCount", 0);
        payload.put("transactions", List.of());

        byte[] headerBytes = validationService.serializePayloadForSigning(payload);
        payload.put("proposerSignature", signHeader(proposer, headerBytes));

        String blockHash = validationService.computeBlockHash(payload);
        payload.put("blockHash", blockHash);

        Map<String, Object> cert = new LinkedHashMap<>();
        cert.put("blockHeight", height);
        cert.put("blockHash", blockHash);
        cert.put("network", "mainnet");
        cert.put("proposerAddress", proposer.address);
        cert.put("voters", List.of(val1.address, val2.address));
        cert.put("signatures", Map.of(
                val1.address, Map.of("publicKey", val1.pubHex, "signature", signVote(val1, height, blockHash, "mainnet")),
                val2.address, Map.of("publicKey", val2.pubHex, "signature", signVote(val2, height, blockHash, "mainnet"))
        ));
        payload.put("consensusCertificate", cert);
        payload.put("isFinalized", true);

        return payload;
    }

    private Peer createTestPeer(String url) {
        Peer p = new Peer();
        p.setPeerUrl(url);
        p.setNetwork("mainnet");
        p.setIsAlive(true);
        p.setFirstSeenAt(System.currentTimeMillis());
        p.setLastSeenAt(System.currentTimeMillis());
        p.setFailCount(0);
        return p;
    }

    @Test
    @DisplayName("F-01: Direct PeerSyncService forged-block injection is rejected and not saved to BlockRepository")
    void testPeerSyncRejectsForgedBlockInjection() {
        Peer peer = createTestPeer("http://malicious-peer:3000");
        when(mockPeerRepo.findByNetworkAndIsAlive("mainnet", true)).thenReturn(List.of(peer));
        when(mockBlockRepo.findTopByNetworkOrderByBlockHeightDesc("mainnet")).thenReturn(Optional.empty());

        // Peer reports latest height = 1
        when(mockRestTemplate.getForEntity(eq("http://malicious-peer:3000/api/node/block/latest?network=mainnet"), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("latestBlockHeight", 1L), HttpStatus.OK));

        // Malicious peer returns block with FORGED signature
        Map<String, Object> forgedBlock = createValidBlockPayload(1L, 1L, val1);
        forgedBlock.put("proposerSignature", "f".repeat(128)); // Forged!
        when(mockRestTemplate.getForEntity(eq("http://malicious-peer:3000/mainnet/blocks/1"), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(forgedBlock, HttpStatus.OK));

        peerSyncService.syncMissingBlocks();

        // Must FAIL CLOSED: block must NOT be saved!
        verify(mockBlockRepo, never()).save(any(Block.class));
    }

    @Test
    @DisplayName("F-01: PeerSyncService rejects block claiming finalized=true without certificate")
    void testPeerSyncRejectsMissingCertificateBlock() {
        Peer peer = createTestPeer("http://peer1:3000");
        when(mockPeerRepo.findByNetworkAndIsAlive("mainnet", true)).thenReturn(List.of(peer));
        when(mockBlockRepo.findTopByNetworkOrderByBlockHeightDesc("mainnet")).thenReturn(Optional.empty());

        when(mockRestTemplate.getForEntity(eq("http://peer1:3000/api/node/block/latest?network=mainnet"), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("latestBlockHeight", 1L), HttpStatus.OK));

        Map<String, Object> uncertBlock = createValidBlockPayload(1L, 1L, val1);
        uncertBlock.remove("consensusCertificate"); // Stripped certificate!
        when(mockRestTemplate.getForEntity(eq("http://peer1:3000/mainnet/blocks/1"), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(uncertBlock, HttpStatus.OK));

        peerSyncService.syncMissingBlocks();

        verify(mockBlockRepo, never()).save(any(Block.class));
    }

    @Test
    @DisplayName("F-02: PeerSyncService rollback attack on finalized blocks is rejected (safety invariant)")
    void testRollbackAttackOnFinalizedBlocksDenied() {
        Block finalizedLocalBlock = new Block();
        finalizedLocalBlock.setBlockHeight(5L);
        finalizedLocalBlock.setBlockHash("local_finalized_hash_00000000000000000000000000000000000000000000");
        finalizedLocalBlock.setIsFinalized(true);

        when(mockBlockRepo.findByNetworkAndBlockHeightGreaterThan("mainnet", 4L))
                .thenReturn(List.of(finalizedLocalBlock));

        // Attempting to rollback finalized block 5 must throw IllegalStateException and abort!
        assertThrows(IllegalStateException.class, () -> {
            peerSyncService.rollbackToHeight(4L, "mainnet");
        });

        // Block must NEVER be deleted
        verify(mockBlockRepo, never()).delete(finalizedLocalBlock);
    }

    @Test
    @DisplayName("F-02: Peer presenting higher chain with fork conflict at finalized height is halted without deleting local history")
    void testPeerSyncForkConflictAtFinalizedHeightHaltsSync() {
        Peer peer = createTestPeer("http://competing-peer:3000");
        when(mockPeerRepo.findByNetworkAndIsAlive("mainnet", true)).thenReturn(List.of(peer));

        Block localFinalized = new Block();
        localFinalized.setBlockHeight(2L);
        localFinalized.setBlockHash("canonical_local_hash_00000000000000000000000000000000000000000000");
        localFinalized.setIsFinalized(true);

        when(mockBlockRepo.findTopByNetworkOrderByBlockHeightDesc("mainnet"))
                .thenReturn(Optional.of(localFinalized));
        when(mockBlockRepo.findByBlockHeightAndNetwork(2L, "mainnet"))
                .thenReturn(Optional.of(localFinalized));

        // Peer claims height 3 (higher than local height 2)
        when(mockRestTemplate.getForEntity(eq("http://competing-peer:3000/api/node/block/latest?network=mainnet"), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("latestBlockHeight", 3L), HttpStatus.OK));

        // Peer serves a conflicting block at height 2
        Map<String, Object> conflictingBlock = createValidBlockPayload(2L, 2L, val1);
        conflictingBlock.put("blockHash", "competing_fork_hash_0000000000000000000000000000000000000000000");

        when(mockRestTemplate.getForEntity(eq("http://competing-peer:3000/mainnet/blocks/3"), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(conflictingBlock, HttpStatus.OK));

        peerSyncService.syncMissingBlocks();

        // Local canonical finalized block must NOT be deleted or replaced
        verify(mockBlockRepo, never()).delete(localFinalized);
    }

    @Test
    @DisplayName("F-01 & F-04: PeerSyncService successfully synchronizes and finalizes valid certified block")
    void testPeerSyncSuccessfullyFinalizesValidCertifiedBlock() {
        Peer peer = createTestPeer("http://honest-peer:3000");
        when(mockPeerRepo.findByNetworkAndIsAlive("mainnet", true)).thenReturn(List.of(peer));
        when(mockBlockRepo.findTopByNetworkOrderByBlockHeightDesc("mainnet")).thenReturn(Optional.empty());

        when(mockRestTemplate.getForEntity(eq("http://honest-peer:3000/api/node/block/latest?network=mainnet"), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("latestBlockHeight", 1L), HttpStatus.OK));

        Map<String, Object> validPayload = createValidBlockPayload(1L, 1L, val1);
        when(mockRestTemplate.getForEntity(eq("http://honest-peer:3000/mainnet/blocks/1"), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(validPayload, HttpStatus.OK));

        peerSyncService.syncMissingBlocks();

        // Valid block passing shared pipeline must be saved
        verify(mockBlockRepo, times(1)).save(any(Block.class));
    }
}
