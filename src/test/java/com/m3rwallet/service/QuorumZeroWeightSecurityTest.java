package com.m3rwallet.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Part 6: Mathematical quorum integrity and prevention of zero-weight validator exploitation.
 */
class QuorumZeroWeightSecurityTest {

    @Test
    @DisplayName("1. One proposer with dominant weight cannot finalize alone without independent peer confirmation")
    void testDominantProposerCannotFinalizeWithoutPeer() {
        // N = 3 validators. Total weight = 100.0. Proposer has 90.0 (90% weight >= 66.7%).
        // But only proposer voted.
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("proposer", 90.0);
        weights.put("peer1", 5.0);
        weights.put("peer2", 5.0);

        List<String> votes = List.of("proposer");
        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(votes, weights, "proposer");

        // Fails because: yesVotes (1) < requiredVotes (2), and peerConfirmations (0) < 1
        assertFalse(tally.reached());
        assertEquals(1, tally.yesVotes());
        assertEquals(0, tally.peerConfirmations());
    }

    @Test
    @DisplayName("2. Zero-weight peer vote does NOT increase approved weight, does NOT count as peer confirmation")
    void testZeroWeightPeerCannotProvidePeerConfirmation() {
        // N = 3 validators with positive weight + 1 zero-weight validator
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("proposer", 10.0);
        weights.put("peer_legit", 10.0);
        weights.put("peer_zero", 0.0); // Zero weight!

        // Proposer and zero-weight peer vote YES
        List<String> votes = List.of("proposer", "peer_zero");
        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(votes, weights, "proposer");

        // Total eligible validators = 2 (proposer, peer_legit). peer_zero is NOT eligible.
        assertEquals(2, tally.totalValidators());
        // peer_zero must NOT count as a yesVote or peer confirmation!
        assertEquals(1, tally.yesVotes());
        assertEquals(0, tally.peerConfirmations());
        assertFalse(tally.reached());
    }

    @Test
    @DisplayName("3. Multiple zero-weight peers cannot satisfy count quorum or peer confirmation")
    void testMultipleZeroWeightPeersIgnored() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("proposer", 50.0);
        weights.put("zero1", 0.0);
        weights.put("zero2", 0.0);
        weights.put("zero3", 0.0);

        List<String> votes = List.of("proposer", "zero1", "zero2", "zero3");
        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(votes, weights, "proposer");

        // Only proposer is eligible
        assertEquals(1, tally.totalValidators());
        assertEquals(1, tally.yesVotes());
        assertEquals(0, tally.peerConfirmations());
        assertEquals(Set.of("proposer"), tally.acceptedVoters());
    }

    @Test
    @DisplayName("4. Unknown and inactive voters are strictly excluded from quorum calculation")
    void testUnknownAndInactiveVotersExcluded() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("v1", 10.0);
        weights.put("v2", 10.0);
        weights.put("v3", 10.0);

        List<String> votes = List.of("v1", "stranger_addr", "jailed_addr", "unknown_node");
        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(votes, weights, "v1");

        assertEquals(3, tally.totalValidators());
        assertEquals(1, tally.yesVotes());
        assertEquals(Set.of("v1"), tally.acceptedVoters());
        assertFalse(tally.reached());
    }

    @Test
    @DisplayName("5. Duplicate votes from the same validator count exactly once")
    void testDuplicateVotesCountOnce() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("v1", 10.0);
        weights.put("v2", 10.0);

        List<String> votes = List.of("v1", "v1", "v1", "v1");
        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(votes, weights, "v1");

        assertEquals(1, tally.yesVotes());
        assertEquals(0, tally.peerConfirmations());
        assertFalse(tally.reached());
    }

    @Test
    @DisplayName("6. All validators having zero weight fails closed safely")
    void testAllZeroWeightValidatorsFailClosed() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("v1", 0.0);
        weights.put("v2", 0.0);

        List<String> votes = List.of("v1", "v2");
        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(votes, weights, "v1");

        assertFalse(tally.reached());
        assertEquals(0, tally.totalValidators());
        assertEquals(0.0, tally.totalWeight());
    }

    @Test
    @DisplayName("7. Exactly required count but insufficient weight: REJECTED")
    void testCountThresholdMetButWeightFails() {
        // N = 3 validators. requiredVotes = ceil(6/3) = 2.
        // v1 (weight 1.0), v2 (weight 1.0), v3 (weight 8.0). Total weight = 10.0.
        // v1 and v2 vote YES -> 2 of 3 votes (66.7% count passes!).
        // But approved weight = 2.0 / 10.0 = 20% < 66.7%!
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("v1", 1.0);
        weights.put("v2", 1.0);
        weights.put("v3", 8.0);

        List<String> votes = List.of("v1", "v2");
        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(votes, weights, "v1");

        assertEquals(2, tally.yesVotes());
        assertEquals(2, tally.requiredVotes());
        assertEquals(1, tally.peerConfirmations());
        // Fails because weight threshold is not met!
        assertFalse(tally.reached());
    }

    @Test
    @DisplayName("8. Sufficient weight but insufficient count: REJECTED")
    void testWeightThresholdMetButCountFails() {
        // N = 4 validators. requiredVotes = ceil(8/3) = 3 votes.
        // v1 (weight 8.0), v2 (weight 1.0), v3 (weight 1.0), v4 (weight 1.0). Total = 11.0.
        // v1 (proposer) and v2 vote YES -> approved weight = 9.0 / 11.0 = 81.8% >= 66.7% (weight passes!).
        // But count = 2 < 3 required!
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("v1", 8.0);
        weights.put("v2", 1.0);
        weights.put("v3", 1.0);
        weights.put("v4", 1.0);

        List<String> votes = List.of("v1", "v2");
        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(votes, weights, "v1");

        assertEquals(2, tally.yesVotes());
        assertEquals(3, tally.requiredVotes());
        assertTrue(tally.approvedWeight() / tally.totalWeight() >= 2.0 / 3.0);
        // Fails because vote count threshold is not met!
        assertFalse(tally.reached());
    }

    @Test
    @DisplayName("9. Both thresholds satisfied: ACCEPTED")
    void testBothThresholdsSatisfiedAccepted() {
        // N = 3 validators. requiredVotes = 2. Total weight = 6.0.
        // v1 (proposer, weight 2.0) and v2 (peer, weight 2.0) vote YES.
        // Count = 2 >= 2. Weight = 4.0 / 6.0 = 66.67% >= 66.67%. Peer confirmation = 1 >= 1.
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("v1", 2.0);
        weights.put("v2", 2.0);
        weights.put("v3", 2.0);

        List<String> votes = List.of("v1", "v2");
        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(votes, weights, "v1");

        assertTrue(tally.reached());
        assertEquals(2, tally.yesVotes());
        assertEquals(2, tally.requiredVotes());
        assertEquals(1, tally.peerConfirmations());
    }
}
