package com.m3rwallet.service;

import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.ValidatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for Proposal Statistics & Reputation Accounting (F-08).
 * Verifies that proposal counts and reputation are idempotently derived from canonical finalized blocks,
 * repeated block deliveries do not inflate counters, and reorganization rolls back proposal metrics.
 */
public class ProposalStatisticsAuditTest {

    private ValidatorService validatorService;
    private ValidatorRepository mockValidatorRepo;
    private BlockRepository mockBlockRepo;

    private final String network = "mainnet";
    private final String proposerAddress = "NProposerAddress1111111111111111111";

    @BeforeEach
    void setup() {
        mockValidatorRepo = mock(ValidatorRepository.class);
        mockBlockRepo = mock(BlockRepository.class);

        validatorService = new ValidatorService(
                mockValidatorRepo,
                mock(com.m3rwallet.repository.ValidatorWeightRepository.class),
                mock(com.m3rwallet.repository.SlashEventRepository.class),
                null, null, null
        );
        validatorService.setBlockRepository(mockBlockRepo);
    }

    @Test
    @DisplayName("F-08: Repeated delivery of the same block is idempotent and does not inflate proposal counts")
    void testProposalAccountingIdempotence() {
        Validator validator = Validator.builder()
                .address(proposerAddress)
                .network(network)
                .stakedAmount(BigDecimal.valueOf(5000L))
                .reliabilityScoreScaled(100000L) // 0.10
                .successfulProposals(10L)
                .totalProposals(10L)
                .status(Validator.ValidatorStatus.ACTIVE)
                .build();

        when(mockValidatorRepo.findByAddressAndNetwork(eq(proposerAddress), eq(network)))
                .thenReturn(Optional.of(validator));

        // When block 11 is finalized, canonical count in BlockRepository is 11
        when(mockBlockRepo.countByProposerAddressAndNetworkAndIsFinalized(eq(proposerAddress), eq(network), eq(true)))
                .thenReturn(11L);

        // First delivery: counts increment by 1
        validatorService.recordSuccessfulProposal(proposerAddress, network);
        assertEquals(11L, validator.getSuccessfulProposals());
        assertEquals(0.11, validator.getReliabilityScore(), 0.001);

        // Duplicate delivery from peer 2: canonical count in BlockRepository is still 11
        validatorService.recordSuccessfulProposal(proposerAddress, network);
        assertEquals(11L, validator.getSuccessfulProposals(), "Counter must NOT be double-counted");
        assertEquals(0.11, validator.getReliabilityScore(), 0.001, "Reliability must NOT be double-counted");

        // Duplicate delivery from peer 3
        validatorService.recordSuccessfulProposal(proposerAddress, network);
        assertEquals(11L, validator.getSuccessfulProposals());
        assertEquals(0.11, validator.getReliabilityScore(), 0.001);
    }

    @Test
    @DisplayName("F-08: Chain reorganization and block rollback decrements proposal counts and reputation")
    void testReorganizationRollsBackProposalMetrics() {
        Validator validator = Validator.builder()
                .address(proposerAddress)
                .network(network)
                .stakedAmount(BigDecimal.valueOf(5000L))
                .reliabilityScoreScaled(200000L) // 0.20
                .successfulProposals(20L)
                .totalProposals(20L)
                .status(Validator.ValidatorStatus.ACTIVE)
                .build();

        when(mockValidatorRepo.findByAddressAndNetwork(eq(proposerAddress), eq(network)))
                .thenReturn(Optional.of(validator));

        // After a chain rollback of 2 orphaned blocks, canonical count in DB drops to 18
        when(mockBlockRepo.countByProposerAddressAndNetworkAndIsFinalized(eq(proposerAddress), eq(network), eq(true)))
                .thenReturn(18L);

        // Reconcile/record: must roll back proposals from 20 to 18, and decrease reliability by 0.02
        validatorService.recordSuccessfulProposal(proposerAddress, network);

        assertEquals(18L, validator.getSuccessfulProposals(), "Proposals must decrease to match canonical blocks");
        assertEquals(0.18, validator.getReliabilityScore(), 0.001, "Reliability must decrease to match canonical blocks");
    }
}
