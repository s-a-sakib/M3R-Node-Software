package com.m3rwallet.service;

import com.m3rwallet.entity.SlashEvent;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.ValidatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class PoRPoSWeightValidationTest {

    private ValidatorService validatorService;
    private ValidatorRepository mockValidatorRepo;
    private com.m3rwallet.repository.ValidatorWeightRepository mockWeightRepo;
    private com.m3rwallet.repository.SlashEventRepository mockSlashRepo;
    private NodeIdentityService mockIdentityService;
    private ValidatorSyncService mockSyncService;
    private com.m3rwallet.repository.AccountRepository mockAccountRepo;

    @BeforeEach
    void setup() {
        mockValidatorRepo = Mockito.mock(ValidatorRepository.class);
        mockWeightRepo = Mockito.mock(com.m3rwallet.repository.ValidatorWeightRepository.class);
        mockSlashRepo = Mockito.mock(com.m3rwallet.repository.SlashEventRepository.class);
        mockIdentityService = Mockito.mock(NodeIdentityService.class);
        mockSyncService = Mockito.mock(ValidatorSyncService.class);
        mockAccountRepo = Mockito.mock(com.m3rwallet.repository.AccountRepository.class);

        validatorService = new ValidatorService(
                mockValidatorRepo,
                mockWeightRepo,
                mockSlashRepo,
                mockIdentityService,
                mockSyncService,
                mockAccountRepo
        );

        ReflectionTestUtils.setField(validatorService, "defaultNetwork", "mainnet");
        ReflectionTestUtils.setField(validatorService, "selectionMode", "weighted");
        ReflectionTestUtils.setField(validatorService, "alpha", 0.15d);
    }

    private Validator createValidator(String address, long stake, double reliability, Validator.ValidatorStatus status) {
        Validator v = new Validator();
        v.setAddress(address);
        v.setNetwork("mainnet");
        v.setStakedAmount(BigDecimal.valueOf(stake));
        v.setReliabilityScore(reliability);
        v.setStatus(status);
        v.setTotalProposals(0L);
        v.setSuccessfulProposals(0L);
        v.setCorruptedProposals(0L);
        return v;
    }

    @Test
    @DisplayName("Inactive, Jailed, or Slashed validators receive zero weight")
    void nonActiveValidatorsReceiveZeroWeight() {
        Validator inactive = createValidator("v_inactive", 50000L, 0.9, Validator.ValidatorStatus.INACTIVE);
        Validator jailed = createValidator("v_jailed", 50000L, 0.9, Validator.ValidatorStatus.JAILED);
        Validator slashed = createValidator("v_slashed", 50000L, 0.9, Validator.ValidatorStatus.SLASHED);

        assertEquals(0.0, validatorService.calculateWeight(inactive));
        assertEquals(0.0, validatorService.calculateWeight(jailed));
        assertEquals(0.0, validatorService.calculateWeight(slashed));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.15, 0.30, 0.50, 0.75, 1.0})
    @DisplayName("Evaluates weight calculation across alpha values in [0.0, 1.0]")
    void evaluatesWeightAcrossAlphaValues(double alpha) {
        Validator v = createValidator("v1", 10000L, 0.8, Validator.ValidatorStatus.ACTIVE);
        double weight = validatorService.calculateWeight(v, alpha);

        double expectedStakeComponent = alpha * Math.log(1.0 + 10000.0);
        double expectedReputationComponent = (1.0 - alpha) * 0.8;
        double expectedTotal = expectedStakeComponent + expectedReputationComponent;

        assertEquals(expectedTotal, weight, 0.0001);

        if (alpha == 0.0) {
            // Pure Proof-of-Reliability
            assertEquals(0.8, weight, 0.0001, "At alpha=0, weight must strictly equal reliability");
        } else if (alpha == 1.0) {
            // Pure Proof-of-Stake (logarithmic)
            assertEquals(Math.log(10001.0), weight, 0.0001, "At alpha=1, weight must strictly equal log(1 + S)");
        }
    }

    @Test
    @DisplayName("Validator selection frequency shifts between high-stake and high-reputation nodes as alpha varies")
    void validatorSelectionFrequencySimulation() {
        // Node A: High Stake (100,000), Low Reliability (0.2)
        Validator richNode = createValidator("val_A_rich", 100_000L, 0.20, Validator.ValidatorStatus.ACTIVE);
        // Node B: Low Stake (1,000), High Reliability (0.95)
        Validator reputableNode = createValidator("val_B_reputable", 1_000L, 0.95, Validator.ValidatorStatus.ACTIVE);

        List<Validator> validatorList = List.of(richNode, reputableNode);
        when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE)))
                .thenReturn(validatorList);

        int totalSlots = 500;

        // Test Alpha = 0.0 (Pure Reputation: Node B should dominate)
        validatorService.setAlpha(0.0);
        int nodeBChosenAlpha0 = 0;
        for (long slot = 0; slot < totalSlots; slot++) {
            Validator chosen = validatorService.selectValidatorForSlot(slot, "mainnet");
            if ("val_B_reputable".equals(chosen.getAddress())) nodeBChosenAlpha0++;
        }
        double nodeBRatioAlpha0 = (double) nodeBChosenAlpha0 / totalSlots;
        assertTrue(nodeBRatioAlpha0 > 0.70, "Reputable node should win >70% slots when alpha=0.0 (reputation-only)");

        // Test Alpha = 1.0 (Pure Stake: Node A should dominate)
        validatorService.setAlpha(1.0);
        int nodeAChosenAlpha1 = 0;
        for (long slot = 0; slot < totalSlots; slot++) {
            Validator chosen = validatorService.selectValidatorForSlot(slot, "mainnet");
            if ("val_A_rich".equals(chosen.getAddress())) nodeAChosenAlpha1++;
        }
        double nodeARatioAlpha1 = (double) nodeAChosenAlpha1 / totalSlots;
        assertTrue(nodeARatioAlpha1 > 0.55, "Rich node should win >55% slots when alpha=1.0 (stake-only)");
    }

    @Test
    @DisplayName("Reliability lifecycle: successful proposals increment, decay decays, slashing resets and burns stake")
    void reliabilityLifecycleTest() {
        Validator v = createValidator("val_lifecycle", 10_000L, 0.50, Validator.ValidatorStatus.ACTIVE);
        when(mockValidatorRepo.findByAddressAndNetwork(eq("val_lifecycle"), eq("mainnet")))
                .thenReturn(Optional.of(v));

        // 1. Successful proposal increment (+0.01)
        validatorService.recordSuccessfulProposal("val_lifecycle", "mainnet");
        assertEquals(0.51, v.getReliabilityScore(), 0.001);
        assertEquals(1L, v.getSuccessfulProposals());

        // 2. Reputation decay (0.999 * R)
        validatorService.applyReputationDecay("val_lifecycle", "mainnet");
        assertEquals(0.51 * 0.999, v.getReliabilityScore(), 0.001);

        // 3. Slashing: reset reliability to 0.0 and burn 1 stake unit
        validatorService.slashValidator("val_lifecycle", "mainnet", SlashEvent.SlashReason.DOUBLE_SIGN);
        assertEquals(0.0, v.getReliabilityScore(), 0.0001);
        assertEquals(BigDecimal.valueOf(9999L), v.getStakedAmount());
        assertEquals(1L, v.getCorruptedProposals());
    }

    @Test
    @DisplayName("Handles zero-weight validators without division by zero")
    void handlesZeroWeightValidatorsSafely() {
        Validator zeroNode = createValidator("val_zero", 0L, 0.0, Validator.ValidatorStatus.ACTIVE);
        when(mockValidatorRepo.findByNetworkAndStatus(eq("mainnet"), eq(Validator.ValidatorStatus.ACTIVE)))
                .thenReturn(List.of(zeroNode));

        // Total weight = 0.0. Falls back safely without throwing ArithmeticException
        Validator chosen = validatorService.selectValidatorForSlot(100L, "mainnet");
        assertNotNull(chosen);
        assertEquals("val_zero", chosen.getAddress());
    }
}
