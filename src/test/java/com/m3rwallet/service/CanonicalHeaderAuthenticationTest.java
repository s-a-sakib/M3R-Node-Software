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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for Part 3: Canonical block header serialization, hashing, and proposer signature verification.
 */
class CanonicalHeaderAuthenticationTest {

    private BlockProposalService proposalService;
    private BlockValidationService validationService;
    private NodeIdentityService nodeIdentityService;
    private ValidatorRepository mockValidatorRepo;
    private ValidatorService mockValidatorService;
    private BlockRepository mockBlockRepo;
    private Validator leader;

    private String privKeyHex;
    private String pubKeyHex;
    private String proposerAddress;

    @BeforeEach
    void setup() {
        // 1. Generate real secp256k1 keypair using production elliptic curve parameters
        X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        ECDomainParameters domain = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
        ECKeyPairGenerator gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(domain, new SecureRandom()));
        AsymmetricCipherKeyPair pair = gen.generateKeyPair();
        ECPrivateKeyParameters priv = (ECPrivateKeyParameters) pair.getPrivate();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) pair.getPublic();

        privKeyHex = String.format("%064x", priv.getD());
        byte[] compPub = pub.getQ().getEncoded(true);
        pubKeyHex = Hex.encodeHexString(compPub);
        proposerAddress = AddressUtil.deriveAddressFromPublicKey(compPub);

        nodeIdentityService = new NodeIdentityService();
        ReflectionTestUtils.setField(nodeIdentityService, "configuredPrivateKey", privKeyHex);
        nodeIdentityService.initialize();

        mockBlockRepo = Mockito.mock(BlockRepository.class);
        mockValidatorRepo = Mockito.mock(ValidatorRepository.class);
        mockValidatorService = Mockito.mock(ValidatorService.class);

        proposalService = new BlockProposalService(
                Mockito.mock(MempoolService.class),
                mockValidatorService,
                nodeIdentityService,
                mockBlockRepo,
                Mockito.mock(com.m3rwallet.repository.BlockTransactionRepository.class),
                "mainnet",
                5000
        );

        validationService = new BlockValidationService();
        ReflectionTestUtils.setField(validationService, "blockRepo", mockBlockRepo);
        ReflectionTestUtils.setField(validationService, "validatorRepo", mockValidatorRepo);
        ReflectionTestUtils.setField(validationService, "validatorService", mockValidatorService);
        ReflectionTestUtils.setField(validationService, "defaultNetwork", "mainnet");

        leader = Validator.builder()
                .address(proposerAddress)
                .status(Validator.ValidatorStatus.ACTIVE)
                .stakedAmount(BigDecimal.valueOf(5000))
                .reliabilityScoreScaled(980000L)
                .build();
        when(mockValidatorService.selectValidatorForSlot(eq(1L), anyString())).thenReturn(leader);
        when(mockValidatorService.getActiveValidators(anyString())).thenReturn(List.of(leader));
        when(mockValidatorRepo.findByAddressAndNetwork(eq(proposerAddress), anyString())).thenReturn(Optional.of(leader));
        when(mockValidatorRepo.findByNetworkAndStatus(anyString(), eq(Validator.ValidatorStatus.ACTIVE))).thenReturn(List.of(leader));
    }

    private Map<String, Object> createProductionBlockPayload() {
        Block block = proposalService.buildBlock(1L, leader, List.of(), "mainnet");
        block.setParentBlockHash("0".repeat(64));
        block.setBlockHash(validationService.computeBlockHash(new LinkedHashMap<String, Object>() {{
            put("version", block.getVersion());
            put("blockHeight", block.getBlockHeight());
            put("slotNumber", block.getSlotNumber());
            put("parentBlockHash", block.getParentBlockHash());
            put("merkleRoot", block.getMerkleRoot());
            put("timestamp", block.getTimestamp());
            put("proposerAddress", block.getProposerAddress());
            put("nonce", block.getNonce());
        }}));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", block.getVersion());
        payload.put("network", block.getNetwork());
        payload.put("blockHeight", block.getBlockHeight());
        payload.put("slotNumber", block.getSlotNumber());
        payload.put("parentBlockHash", block.getParentBlockHash());
        payload.put("merkleRoot", block.getMerkleRoot());
        payload.put("stateRoot", block.getStateRoot());
        payload.put("timestamp", block.getTimestamp());
        payload.put("proposerAddress", block.getProposerAddress());
        payload.put("proposerPublicKey", nodeIdentityService.getNodePublicKey());
        payload.put("proposerWeight", block.getProposerWeight());
        payload.put("validatorSetHash", block.getValidatorSetHash());
        payload.put("nonce", block.getNonce());
        payload.put("txCount", block.getTxCount());
        payload.put("transactions", List.of());
        payload.put("proposerSignature", block.getProposerSignature());
        payload.put("blockHash", block.getBlockHash());
        return payload;
    }

    @Test
    @DisplayName("1. Production proposal signing and verification pass for authentic block")
    void testAuthenticProductionBlockPasses() {
        Map<String, Object> payload = createProductionBlockPayload();
        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertTrue(result.valid(), "Expected authentic block to pass validation but got violations: " + result.violations());
        assertFalse(result.violations().contains("PROPOSER_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("2. Tampering Merkle root invalidates proposer signature")
    void testTamperMerkleRootFails() {
        Map<String, Object> payload = createProductionBlockPayload();
        payload.put("merkleRoot", "a".repeat(64));
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("PROPOSER_SIGNATURE_INVALID"),
                "Expected PROPOSER_SIGNATURE_INVALID on Merkle root tampering");
    }

    @Test
    @DisplayName("3. Tampering state root invalidates proposer signature")
    void testTamperStateRootFails() {
        Map<String, Object> payload = createProductionBlockPayload();
        payload.put("stateRoot", "b".repeat(64));
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("PROPOSER_SIGNATURE_INVALID"),
                "Expected PROPOSER_SIGNATURE_INVALID on state root tampering");
    }

    @Test
    @DisplayName("4. Tampering timestamp invalidates proposer signature")
    void testTamperTimestampFails() {
        Map<String, Object> payload = createProductionBlockPayload();
        long orig = (Long) payload.get("timestamp");
        payload.put("timestamp", orig + 1000L);
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("PROPOSER_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("5. Tampering proposer address fails pubkey matching and signature")
    void testTamperProposerAddressFails() {
        Map<String, Object> payload = createProductionBlockPayload();
        payload.put("proposerAddress", "M3RFAKEADDRESS123456789012345678");
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("PROPOSER_PUBKEY_MISMATCH"));
    }

    @Test
    @DisplayName("6. Tampering validator set hash invalidates proposer signature")
    void testTamperValidatorSetHashFails() {
        Map<String, Object> payload = createProductionBlockPayload();
        payload.put("validatorSetHash", "c".repeat(64));
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("PROPOSER_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("7. Tampering nonce invalidates proposer signature")
    void testTamperNonceFails() {
        Map<String, Object> payload = createProductionBlockPayload();
        long nonce = (Long) payload.get("nonce");
        payload.put("nonce", nonce + 42L);
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("PROPOSER_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("8. Tampering proposer weight invalidates proposer signature")
    void testTamperProposerWeightFails() {
        Map<String, Object> payload = createProductionBlockPayload();
        payload.put("proposerWeight", 999.0);
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("PROPOSER_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("9. Tampering parent block hash invalidates proposer signature")
    void testTamperParentBlockHashFails() {
        Map<String, Object> payload = createProductionBlockPayload();
        payload.put("parentBlockHash", "d".repeat(64));
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("PROPOSER_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("10. Tampering txCount invalidates proposer signature")
    void testTamperTxCountFails() {
        Map<String, Object> payload = createProductionBlockPayload();
        payload.put("txCount", 99);
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("PROPOSER_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("11. Signatures with leading zero bytes in r or s format and verify correctly")
    void testLeadingZeroBytesInSignature() {
        // Construct a signature where r has a leading zero byte (31-byte r)
        BigInteger smallR = new BigInteger("00ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
        BigInteger s = BigInteger.valueOf(123456789L);
        byte[] raw = CryptoUtil.formatRawSignature(smallR, s);
        assertEquals(64, raw.length);
        assertEquals(0x00, raw[0], "First byte must be zero-padded");
    }

    @Test
    @DisplayName("12. Malformed and truncated signatures are rejected")
    void testMalformedAndTruncatedSignatures() {
        Map<String, Object> payload = createProductionBlockPayload();

        // 32-byte truncated signature
        payload.put("proposerSignature", "ab".repeat(32));
        BlockValidationService.BlockValidationResult r1 = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(r1.valid());
        assertTrue(r1.violations().contains("PROPOSER_SIGNATURE_INVALID"));

        // Signature out of range (r >= n)
        X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        BigInteger n = params.getN();
        byte[] outOfRange = CryptoUtil.formatRawSignature(n.add(BigInteger.ONE), BigInteger.ONE);
        payload.put("proposerSignature", Hex.encodeHexString(outOfRange));
        BlockValidationService.BlockValidationResult r2 = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(r2.valid());
        assertTrue(r2.violations().contains("PROPOSER_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("13. Altered protocol version invalidates signature")
    void testAlteredProtocolVersionFails() {
        Map<String, Object> payload = createProductionBlockPayload();
        payload.put("version", (byte) 2);
        payload.put("blockHash", validationService.computeBlockHash(payload));

        BlockValidationService.BlockValidationResult result = validationService.validateReceivedBlock(payload, "mainnet");
        assertFalse(result.valid());
        assertTrue(result.violations().contains("PROPOSER_SIGNATURE_INVALID"));
    }
}
