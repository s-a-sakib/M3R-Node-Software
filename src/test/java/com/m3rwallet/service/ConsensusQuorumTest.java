package com.m3rwallet.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ConsensusQuorumTest {

    @Test
    @DisplayName("Accepts when both weighted and vote-count thresholds are satisfied")
    void acceptsWhenBothThresholdsAreMet() {
        // N = 4, ceil(8/3) = 3 votes required. Weight: 7.0 / 10.0 = 70% >= 66.67%
        assertTrue(ConsensusQuorum.isReached(7.0, 10.0, 3, 4));
    }

    @Test
    @DisplayName("Rejects when weighted condition fails even if raw vote-count passes")
    void rejectsWhenWeightedThresholdFailsEvenWhenCountPasses() {
        // N = 4, yesVotes = 3 (passes count 3 >= 3), but weight = 6.0 / 10.0 = 60% < 66.67%
        assertFalse(ConsensusQuorum.isReached(6.0, 10.0, 3, 4));
    }

    @Test
    @DisplayName("Rejects when vote-count fails even if weighted threshold passes")
    void rejectsWhenCountThresholdFailsEvenWhenWeightPasses() {
        // N = 4, yesVotes = 2 (fails count 2 < 3), but weight = 9.0 / 10.0 = 90% >= 66.67%
        assertFalse(ConsensusQuorum.isReached(9.0, 10.0, 2, 4));
    }

    @Test
    @DisplayName("Rejects when neither weighted nor count condition passes")
    void rejectsWhenNeitherConditionPasses() {
        assertFalse(ConsensusQuorum.isReached(5.0, 10.0, 2, 4));
    }

    @Test
    @DisplayName("Accepts exact two-thirds boundary for 3 validators (2 of 3 votes and weight)")
    void acceptsExactlyTwoThirdsForThreeValidators() {
        // 2/3 weight and 2/3 votes
        assertTrue(ConsensusQuorum.isReached(2.0, 3.0, 2, 3));
        // Boundary test with BigDecimal
        assertTrue(ConsensusQuorum.isReached(new BigDecimal("2.0000"), new BigDecimal("3.0000"), 2, 3));
    }

    @Test
    @DisplayName("Rejects just below exact two-thirds boundary")
    void rejectsJustBelowTwoThirds() {
        // 1.999 / 3.000 < 2/3
        assertFalse(ConsensusQuorum.isReached(1.9999, 3.0, 2, 3));
    }

    @Test
    @DisplayName("Safely rejects zero total weight and negative weights")
    void safelyRejectsZeroTotalWeightAndInvalidVoteCounts() {
        assertFalse(ConsensusQuorum.isReached(0.0, 0.0, 3, 3));
        assertFalse(ConsensusQuorum.isReached(10.0, 10.0, 4, 3)); // votes > validatorCount
        assertFalse(ConsensusQuorum.isReached(-1.0, 10.0, 2, 3));
        assertFalse(ConsensusQuorum.isReached(Double.NaN, 10.0, 2, 3));
        assertFalse(ConsensusQuorum.isReached(10.0, Double.POSITIVE_INFINITY, 2, 3));
    }

    @ParameterizedTest
    @CsvSource({
            "1, 1",
            "2, 2",
            "3, 2",
            "4, 3",
            "5, 4",
            "6, 4",
            "7, 5",
            "10, 7"
    })
    @DisplayName("Verifies ceil(2N/3) threshold for various validator set sizes")
    void verifiesRequiredVoteThresholds(int n, int expectedRequired) {
        assertEquals(expectedRequired, ConsensusQuorum.requiredVotes(n));
    }

    @Test
    @DisplayName("Requires independent peer confirmation when validator count > 1")
    void requiresIndependentPeerConfirmationForMultiNode() {
        // N = 2, totalWeight = 10.0, proposer has 8.0 weight (80%), but no peer confirmed
        assertFalse(ConsensusQuorum.isReached(8.0, 10.0, 1, 2, false));
        // With peer confirmation (e.g. 2 votes)
        assertTrue(ConsensusQuorum.isReached(8.0, 10.0, 2, 2, true));
    }

    @Test
    @DisplayName("Solo node (N = 1) allows self-confirmation")
    void allowsSelfConfirmationInSoloMode() {
        assertTrue(ConsensusQuorum.isReached(1.0, 1.0, 1, 1, false));
    }

    @Test
    @DisplayName("Tally evaluation deduplicates votes and ignores unknown/inactive validators")
    void evaluateVotesDeduplicatesAndFilters() {
        Map<String, Double> activeValidators = new LinkedHashMap<>();
        activeValidators.put("val1", 2.0); // proposer
        activeValidators.put("val2", 2.0);
        activeValidators.put("val3", 2.0);

        // List with duplicate votes and an unregistered/inactive voter
        List<String> rawVotes = List.of(
                "val1", "val1", "val1", // duplicates of proposer
                "val2",                  // valid peer
                "unknown_val",           // unregistered
                "inactive_val"           // not in active map
        );

        ConsensusQuorum.TallyResult result = ConsensusQuorum.evaluateVotes(
                rawVotes, activeValidators, "val1"
        );

        // Expected: val1 and val2 count once each => 2 unique votes
        // Required votes = ceil(6/3) = 2
        // Approved weight = 4.0 / 6.0 = 66.67%
        // Peer confirmations = 1 (val2)
        assertTrue(result.reached());
        assertEquals(2, result.yesVotes());
        assertEquals(2, result.requiredVotes());
        assertEquals(3, result.totalValidators());
        assertEquals(4.0, result.approvedWeight(), 0.0001);
        assertEquals(6.0, result.totalWeight(), 0.0001);
        assertEquals(1, result.peerConfirmations());
        assertEquals(Set.of("val1", "val2"), result.acceptedVoters());
    }

    @Test
    @DisplayName("Tally evaluation rejects when missing peers result in insufficient quorum")
    void evaluateVotesRejectsOnMissingPeers() {
        Map<String, Double> activeValidators = new LinkedHashMap<>();
        activeValidators.put("val1", 1.0); // proposer
        activeValidators.put("val2", 1.0);
        activeValidators.put("val3", 1.0);

        // Only proposer voted, peers were missing/unreachable
        List<String> rawVotes = List.of("val1");

        ConsensusQuorum.TallyResult result = ConsensusQuorum.evaluateVotes(
                rawVotes, activeValidators, "val1"
        );

        assertFalse(result.reached());
        assertEquals(1, result.yesVotes());
        assertEquals(0, result.peerConfirmations());
    }
}
