package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.ValidatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Adversarial tests for ValidatorSyncService verifying elimination of validator state poisoning (F-03).
 * Verifies that unauthenticated peer JSON responses cannot forge stake, reliability scores,
 * proposal counts, or inject unknown validators into active voting sets.
 */
public class ValidatorSyncSecurityAdversarialTest {

    private ValidatorSyncService validatorSyncService;
    private ValidatorRepository mockValidatorRepo;
    private RestTemplate mockRestTemplate;
    private ConsensusProperties consensusProperties;

    private final String network = "mainnet";
    private final String victimAddress = "NVictimValidatorAddress111111111111111";

    @BeforeEach
    void setup() {
        mockValidatorRepo = mock(ValidatorRepository.class);
        mockRestTemplate = mock(RestTemplate.class);

        consensusProperties = new ConsensusProperties();
        consensusProperties.setEnabled(true);
        consensusProperties.setPeers(List.of("http://peer1:3000"));

        validatorSyncService = new ValidatorSyncService();
        ReflectionTestUtils.setField(validatorSyncService, "consensusProperties", consensusProperties);
        ReflectionTestUtils.setField(validatorSyncService, "restTemplate", mockRestTemplate);
        ReflectionTestUtils.setField(validatorSyncService, "validatorRepository", mockValidatorRepo);
        ReflectionTestUtils.setField(validatorSyncService, "network", network);
    }

    @Test
    @DisplayName("F-03: Unauthenticated peer JSON reporting forged stake is rejected and local stake preserved")
    void testPeerCannotForgeStake() {
        BigDecimal localAuthoritativeStake = BigDecimal.valueOf(5000L);
        Validator localValidator = Validator.builder()
                .address(victimAddress)
                .network(network)
                .stakedAmount(localAuthoritativeStake)
                .reliabilityScoreScaled(950000L)
                .successfulProposals(10L)
                .totalProposals(10L)
                .status(Validator.ValidatorStatus.ACTIVE)
                .build();

        when(mockValidatorRepo.findByAddressAndNetwork(eq(victimAddress), eq(network)))
                .thenReturn(Optional.of(localValidator));

        // Malicious peer claims victim has 999,999,999 staked amount
        Map<String, Object> forgedPeerData = new LinkedHashMap<>();
        forgedPeerData.put("address", victimAddress);
        forgedPeerData.put("network", network);
        forgedPeerData.put("stakedAmount", "999999999");
        forgedPeerData.put("stakeAmount", "999999999");

        boolean result = validatorSyncService.savePeerValidator(forgedPeerData);

        // Local authoritative stake must remain untouched!
        assertEquals(localAuthoritativeStake, localValidator.getStakedAmount());
        verify(mockValidatorRepo, never()).save(localValidator);
    }

    @Test
    @DisplayName("F-03: Unauthenticated peer JSON reporting inflated reliability is rejected (Math.max not used)")
    void testPeerCannotInflateReliabilityScore() {
        Long localAuthoritativeReliability = 500000L; // 0.50
        Validator localValidator = Validator.builder()
                .address(victimAddress)
                .network(network)
                .stakedAmount(BigDecimal.valueOf(1000L))
                .reliabilityScoreScaled(localAuthoritativeReliability)
                .successfulProposals(5L)
                .totalProposals(10L)
                .status(Validator.ValidatorStatus.ACTIVE)
                .build();

        when(mockValidatorRepo.findByAddressAndNetwork(eq(victimAddress), eq(network)))
                .thenReturn(Optional.of(localValidator));

        // Malicious peer reports 1,000,000 (perfect 1.0 reliability)
        Map<String, Object> forgedPeerData = new LinkedHashMap<>();
        forgedPeerData.put("address", victimAddress);
        forgedPeerData.put("network", network);
        forgedPeerData.put("reliabilityScoreScaled", 1000000L);
        forgedPeerData.put("reliabilityScore", 1.0d);

        validatorSyncService.savePeerValidator(forgedPeerData);

        // Authoritative score must remain untouched
        assertEquals(localAuthoritativeReliability, localValidator.getReliabilityScoreScaled());
        verify(mockValidatorRepo, never()).save(localValidator);
    }

    @Test
    @DisplayName("F-03: Unauthenticated peer JSON reporting inflated proposal counters is rejected")
    void testPeerCannotInflateProposalCounters() {
        Validator localValidator = Validator.builder()
                .address(victimAddress)
                .network(network)
                .stakedAmount(BigDecimal.valueOf(1000L))
                .reliabilityScoreScaled(900000L)
                .successfulProposals(3L)
                .totalProposals(3L)
                .status(Validator.ValidatorStatus.ACTIVE)
                .build();

        when(mockValidatorRepo.findByAddressAndNetwork(eq(victimAddress), eq(network)))
                .thenReturn(Optional.of(localValidator));

        // Malicious peer reports 1,000,000 successful proposals
        Map<String, Object> forgedPeerData = new LinkedHashMap<>();
        forgedPeerData.put("address", victimAddress);
        forgedPeerData.put("network", network);
        forgedPeerData.put("successfulProposals", 1000000L);
        forgedPeerData.put("totalProposals", 1000000L);

        validatorSyncService.savePeerValidator(forgedPeerData);

        // Local counter must not be inflated to peer's reported 1,000,000
        assertEquals(3L, localValidator.getSuccessfulProposals());
        assertEquals(3L, localValidator.getTotalProposals());
        verify(mockValidatorRepo, never()).save(localValidator);
    }

    @Test
    @DisplayName("F-03: Unknown validator reported by peer cannot be injected into active validator roster")
    void testUnknownPeerValidatorCannotBeInjected() {
        String unknownAddress = "NUnknownAttackerAddress222222222222";
        when(mockValidatorRepo.findByAddressAndNetwork(eq(unknownAddress), eq(network)))
                .thenReturn(Optional.empty());

        Map<String, Object> peerReport = new LinkedHashMap<>();
        peerReport.put("address", unknownAddress);
        peerReport.put("network", network);
        peerReport.put("stakedAmount", "500000");
        peerReport.put("reliabilityScoreScaled", 990000L);
        peerReport.put("status", "ACTIVE");

        boolean saved = validatorSyncService.savePeerValidator(peerReport);

        assertFalse(saved, "Unknown unauthenticated validator must be rejected");
        verify(mockValidatorRepo, never()).save(any(Validator.class));
    }

    @Test
    @DisplayName("F-03: Full sync pass from peer does not corrupt local validator repository")
    void testFullSyncPassRejectsPoisonedPeerRoster() {
        Validator legitValidator = Validator.builder()
                .address(victimAddress)
                .network(network)
                .stakedAmount(BigDecimal.valueOf(2000L))
                .reliabilityScoreScaled(800000L)
                .successfulProposals(2L)
                .totalProposals(2L)
                .status(Validator.ValidatorStatus.ACTIVE)
                .build();

        when(mockValidatorRepo.findByNetworkAndStatus(eq(network), eq(Validator.ValidatorStatus.ACTIVE)))
                .thenReturn(List.of(legitValidator));
        when(mockValidatorRepo.findByAddressAndNetwork(eq(victimAddress), eq(network)))
                .thenReturn(Optional.of(legitValidator));

        Map<String, Object> poisonedItem = new HashMap<>();
        poisonedItem.put("address", victimAddress);
        poisonedItem.put("network", network);
        poisonedItem.put("stakedAmount", "9999999");
        poisonedItem.put("reliabilityScoreScaled", 999999L);
        poisonedItem.put("successfulProposals", 9999L);

        Map<String, Object> peerResponse = Map.of("validators", List.of(poisonedItem));
        when(mockRestTemplate.getForObject(contains("/api/validator/list"), eq(Map.class)))
                .thenReturn(peerResponse);

        validatorSyncService.syncValidatorSet();

        // Authoritative state must be intact
        assertEquals(BigDecimal.valueOf(2000L), legitValidator.getStakedAmount());
        assertEquals(800000L, legitValidator.getReliabilityScoreScaled());
        assertEquals(2L, legitValidator.getSuccessfulProposals());
    }
}
