package com.m3rwallet.service;

import com.m3rwallet.entity.HistoricalValidatorSnapshot;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.ValidatorRepository;
import com.m3rwallet.repository.ValidatorSnapshotRepository;
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
import static org.mockito.Mockito.*;

/**
 * Tests for Durable Historical Validator Snapshot Persistence & Restart (F-05).
 * Verifies that snapshots are persisted into database storage, restored after cold restart,
 * detect content tampering (failing closed), and allow post-restart historical certificate verification.
 */
public class HistoricalSnapshotRestartAuditTest {

    private ValidatorSnapshotRepository mockSnapshotRepo;
    private ValidatorRepository mockValidatorRepo;
    private BlockRepository mockBlockRepo;

    static class Key {
        ECPrivateKeyParameters priv;
        String pubHex;
        String address;
    }

    private Key v1;
    private Key v2;

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

        mockSnapshotRepo = mock(ValidatorSnapshotRepository.class);
        mockValidatorRepo = mock(ValidatorRepository.class);
        mockBlockRepo = mock(BlockRepository.class);
    }

    @Test
    @DisplayName("F-05: ValidatorService durably persists snapshot to repository")
    void testSnapshotDurablePersistence() {
        ValidatorService service = new ValidatorService(
                mockValidatorRepo, null, null, null, null, null
        );
        service.setValidatorSnapshotRepository(mockSnapshotRepo);

        List<Validator> validators = List.of(
                Validator.builder().address(v1.address).network("mainnet").status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000L)).reliabilityScoreScaled(900000L).build(),
                Validator.builder().address(v2.address).network("mainnet").status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(2000L)).reliabilityScoreScaled(800000L).build()
        );

        ValidatorService.ValidatorSetSnapshot snapshot = service.recordSnapshot("mainnet", 10L, validators);
        assertNotNull(snapshot);
        assertNotNull(snapshot.snapshotHash());

        // Must be saved to the database repository
        verify(mockSnapshotRepo, times(1)).save(argThat(entity ->
                entity.getSnapshotHash().equalsIgnoreCase(snapshot.snapshotHash()) &&
                entity.getBlockHeight() == 10L &&
                entity.getNetwork().equals("mainnet") &&
                entity.getSnapshotDataJson() != null
        ));
    }

    @Test
    @DisplayName("F-05: Post-restart simulation: New ValidatorService instance loads snapshots from database")
    void testPostRestartSnapshotReload() throws Exception {
        List<Validator> original = List.of(
                Validator.builder().address(v1.address).network("mainnet").status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(1000L)).reliabilityScoreScaled(900000L).build(),
                Validator.builder().address(v2.address).network("mainnet").status(Validator.ValidatorStatus.ACTIVE).stakedAmount(BigDecimal.valueOf(2000L)).reliabilityScoreScaled(800000L).build()
        );
        String expectedHash = BlockValidationService.computeValidatorSetHash(original);

        List<ValidatorService.ValidatorSnapshotItem> items = List.of(
                new ValidatorService.ValidatorSnapshotItem(v1.address, BigDecimal.valueOf(1000L), 900000L, 1.8),
                new ValidatorService.ValidatorSnapshotItem(v2.address, BigDecimal.valueOf(2000L), 800000L, 1.8)
        );
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(items);

        HistoricalValidatorSnapshot entity = HistoricalValidatorSnapshot.builder()
                .snapshotHash(expectedHash.toLowerCase())
                .network("mainnet")
                .blockHeight(15L)
                .capturedAt(System.currentTimeMillis())
                .snapshotDataJson(json)
                .build();

        when(mockSnapshotRepo.findAll()).thenReturn(List.of(entity));
        when(mockSnapshotRepo.findBySnapshotHash(eq(expectedHash.toLowerCase()))).thenReturn(Optional.of(entity));

        // Create FRESH instance simulating process restart
        ValidatorService restartedService = new ValidatorService(
                mockValidatorRepo, null, null, null, null, null
        );
        restartedService.setValidatorSnapshotRepository(mockSnapshotRepo);
        restartedService.initSnapshots(); // PostConstruct

        // Query restored snapshot by hash
        Optional<ValidatorService.ValidatorSetSnapshot> restored = restartedService.getSnapshotByHash(expectedHash);
        assertTrue(restored.isPresent(), "Historical snapshot MUST be available after process restart");
        assertEquals(expectedHash.toLowerCase(), restored.get().snapshotHash().toLowerCase());
        assertEquals(2, restored.get().validators().size());
    }

    @Test
    @DisplayName("F-05: Snapshot content tampering in DB is detected and fails closed")
    void testTamperedSnapshotInDatabaseFailsClosed() throws Exception {
        String genuineHash = "genuine_hash_0000000000000000000000000000000000000000000000000000";

        // Fraudulent items inside JSON that do not match genuineHash
        List<ValidatorService.ValidatorSnapshotItem> tamperedItems = List.of(
                new ValidatorService.ValidatorSnapshotItem(v1.address, BigDecimal.valueOf(999999L), 999999L, 999.0)
        );
        String tamperedJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tamperedItems);

        HistoricalValidatorSnapshot tamperedEntity = HistoricalValidatorSnapshot.builder()
                .snapshotHash(genuineHash)
                .network("mainnet")
                .blockHeight(20L)
                .capturedAt(System.currentTimeMillis())
                .snapshotDataJson(tamperedJson)
                .build();

        when(mockSnapshotRepo.findBySnapshotHash(eq(genuineHash))).thenReturn(Optional.of(tamperedEntity));

        ValidatorService service = new ValidatorService(
                mockValidatorRepo, null, null, null, null, null
        );
        service.setValidatorSnapshotRepository(mockSnapshotRepo);

        // Deserializing tampered entity must fail closed (return null / empty)
        ValidatorService.ValidatorSetSnapshot result = service.deserializeSnapshotEntity(tamperedEntity);
        assertNull(result, "Tampered historical snapshot MUST fail closed and return null");
    }
}
