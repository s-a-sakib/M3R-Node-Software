package com.m3rwallet.service;

import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MultiNodeConsensusSimulationTest {

    private Map<String, Double> setupValidators(int n, double equalWeight) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 1; i <= n; i++) {
            map.put("node_" + i, equalWeight);
        }
        return map;
    }

    @ParameterizedTest
    @CsvSource({
            "3, 3, true",   // 3 of 3 honest votes in 3-node network
            "3, 2, true",   // 2 of 3 votes (1 node unavailable) in 3-node network -> reaches ceil(6/3)=2
            "3, 1, false",  // 1 of 3 votes (2 unavailable) in 3-node network -> fails ceil(6/3)=2
            "4, 4, true",   // 4 of 4 honest votes
            "4, 3, true",   // 3 of 4 votes (1 unavailable) -> reaches ceil(8/3)=3
            "4, 2, false",  // 2 of 4 votes (2 unavailable) -> fails ceil(8/3)=3
            "7, 7, true",   // 7 of 7 honest votes
            "7, 5, true",   // 5 of 7 votes (2 unavailable) -> reaches ceil(14/3)=5
            "7, 4, false"   // 4 of 7 votes (3 unavailable) -> fails ceil(14/3)=5
    })
    @DisplayName("Tests normal consensus and unavailable peer tolerance across 3, 4, and 7-node networks")
    void testConsensusAndTolerance(int totalNodes, int availableYesVotes, boolean expectedReached) {
        Map<String, Double> validatorSet = setupValidators(totalNodes, 10.0);
        List<String> rawVotes = new ArrayList<>();
        for (int i = 1; i <= availableYesVotes; i++) {
            rawVotes.add("node_" + i);
        }

        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(
                rawVotes, validatorSet, "node_1"
        );

        assertEquals(expectedReached, tally.reached(),
                String.format("N=%d, votes=%d: expected reached=%b", totalNodes, availableYesVotes, expectedReached));
        assertEquals(availableYesVotes, tally.yesVotes());
        assertEquals(totalNodes, tally.totalValidators());
    }

    @Test
    @DisplayName("Malicious peer: 1 malicious node in 4-node network cannot halt consensus if 3 honest nodes vote YES")
    void maliciousPeerToleratedWhenHonestQuorumMet() {
        int n = 4;
        Map<String, Double> validatorSet = setupValidators(n, 10.0);

        // Node 4 is Byzantine/malicious: refuses to vote or votes NO
        List<String> rawVotes = List.of("node_1", "node_2", "node_3");

        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(
                rawVotes, validatorSet, "node_1"
        );

        // Required is ceil(8/3) = 3. Weight is 30/40 = 75% >= 66.7%.
        assertTrue(tally.reached());
        assertEquals(3, tally.yesVotes());
        assertEquals(3, tally.requiredVotes());
        assertEquals(2, tally.peerConfirmations());
    }

    @Test
    @DisplayName("Malicious Sybil attempt: duplicate broadcasts from the same peer are deduplicated and do not multiply weight")
    void duplicateBroadcastsDoNotInflateTally() {
        int n = 3;
        Map<String, Double> validatorSet = setupValidators(n, 10.0);

        // Malicious peer spams 10 duplicate YES votes
        List<String> spammedVotes = List.of(
                "node_1",
                "node_2", "node_2", "node_2", "node_2", "node_2"
        );

        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(
                spammedVotes, validatorSet, "node_1"
        );

        // Deduplication leaves exactly 2 votes (node_1 and node_2)
        assertEquals(2, tally.yesVotes());
        assertEquals(20.0, tally.approvedWeight(), 0.001);
        assertEquals(30.0, tally.totalWeight(), 0.001);
        assertTrue(tally.reached());
    }

    @Test
    @DisplayName("Network Partition: 4-node network partitioned 2 vs 2 prevents split-brain finalization")
    void networkPartitionPreventsSplitBrain() {
        int n = 4;
        Map<String, Double> validatorSet = setupValidators(n, 10.0);

        // Partition A has node_1 and node_2
        List<String> partitionAVotes = List.of("node_1", "node_2");
        ConsensusQuorum.TallyResult tallyA = ConsensusQuorum.evaluateVotes(
                partitionAVotes, validatorSet, "node_1"
        );
        assertFalse(tallyA.reached(), "Partition A (2/4 nodes) must not finalize blocks");

        // Partition B has node_3 and node_4
        List<String> partitionBVotes = List.of("node_3", "node_4");
        ConsensusQuorum.TallyResult tallyB = ConsensusQuorum.evaluateVotes(
                partitionBVotes, validatorSet, "node_3"
        );
        assertFalse(tallyB.reached(), "Partition B (2/4 nodes) must not finalize blocks");
    }

    @Test
    @DisplayName("Network Partition: 7-node network partitioned 5 vs 2 allows majority to progress while minority halts")
    void asymmetricPartitionMajorityProgressesMinorityHalts() {
        int n = 7;
        Map<String, Double> validatorSet = setupValidators(n, 10.0);

        // Majority partition (5 nodes: nodes 1-5)
        List<String> majorityVotes = List.of("node_1", "node_2", "node_3", "node_4", "node_5");
        ConsensusQuorum.TallyResult majorityTally = ConsensusQuorum.evaluateVotes(
                majorityVotes, validatorSet, "node_1"
        );
        assertTrue(majorityTally.reached(), "Majority partition (5/7 nodes) must reach quorum");

        // Minority partition (2 nodes: nodes 6-7)
        List<String> minorityVotes = List.of("node_6", "node_7");
        ConsensusQuorum.TallyResult minorityTally = ConsensusQuorum.evaluateVotes(
                minorityVotes, validatorSet, "node_6"
        );
        assertFalse(minorityTally.reached(), "Minority partition (2/7 nodes) must halt");
    }

    @Test
    @DisplayName("Weighted Asymmetry: 2 of 3 nodes vote YES, but their combined weight is < 66.7% -> consensus rejects")
    void weightedAsymmetryPreventsLowWeightFinalization() {
        // Node 1 (proposer) = 1.0 weight
        // Node 2 = 1.0 weight
        // Node 3 = 10.0 weight
        // Total weight = 12.0
        Map<String, Double> validatorSet = new LinkedHashMap<>();
        validatorSet.put("node_1", 1.0);
        validatorSet.put("node_2", 1.0);
        validatorSet.put("node_3", 10.0);

        // Node 1 and Node 2 vote YES (2 of 3 count, but only 2.0 / 12.0 = 16.7% weight)
        List<String> rawVotes = List.of("node_1", "node_2");
        ConsensusQuorum.TallyResult tally = ConsensusQuorum.evaluateVotes(
                rawVotes, validatorSet, "node_1"
        );

        assertFalse(tally.reached(), "Must reject because approved weight ratio is only 16.7% (< 66.7%)");
        assertEquals(2, tally.yesVotes());
        assertEquals(2, tally.requiredVotes());
    }
}
