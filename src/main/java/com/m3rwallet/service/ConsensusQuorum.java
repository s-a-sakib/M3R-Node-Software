package com.m3rwallet.service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Shared weighted AND raw-count quorum rule used for transaction and block voting.
 * Formal consensus condition:
 *   (W_yes / W_total >= 2/3) AND (Votes_yes >= ceil(2N / 3)) AND (PeerConfirmation > 0 when N > 1)
 */
public final class ConsensusQuorum {
    private ConsensusQuorum() {}

    /**
     * Compute ceil(2N / 3)
     */
    public static int requiredVotes(int validatorCount) {
        if (validatorCount <= 0) return Integer.MAX_VALUE;
        return (int) (((long) 2 * validatorCount + 2) / 3); // ceil(2N/3)
    }

    public static boolean isReached(BigDecimal approvedWeight, BigDecimal totalWeight,
                                    int yesVotes, int validatorCount) {
        return isReached(approvedWeight, totalWeight, yesVotes, validatorCount, validatorCount <= 1 || yesVotes > 1);
    }

    public static boolean isReached(BigDecimal approvedWeight, BigDecimal totalWeight,
                                    int yesVotes, int validatorCount, boolean hasIndependentPeerConfirmation) {
        if (approvedWeight == null || totalWeight == null || validatorCount <= 0
                || approvedWeight.signum() < 0 || totalWeight.signum() <= 0
                || yesVotes < 0 || yesVotes > validatorCount) {
            return false;
        }
        if (validatorCount > 1 && !hasIndependentPeerConfirmation) {
            return false;
        }
        boolean weighted = approvedWeight.multiply(BigDecimal.valueOf(3))
                .compareTo(totalWeight.multiply(BigDecimal.valueOf(2))) >= 0;
        boolean count = yesVotes >= requiredVotes(validatorCount);
        return weighted && count;
    }

    public static boolean isReached(double approvedWeight, double totalWeight,
                                    int yesVotes, int validatorCount) {
        return isReached(approvedWeight, totalWeight, yesVotes, validatorCount, validatorCount <= 1 || yesVotes > 1);
    }

    public static boolean isReached(double approvedWeight, double totalWeight,
                                    int yesVotes, int validatorCount, boolean hasIndependentPeerConfirmation) {
        if (!Double.isFinite(approvedWeight) || !Double.isFinite(totalWeight)) return false;
        if (approvedWeight < 0.0 || totalWeight <= 0.0 || validatorCount <= 0 || yesVotes < 0 || yesVotes > validatorCount) {
            return false;
        }
        if (validatorCount > 1 && !hasIndependentPeerConfirmation) {
            return false;
        }
        // Account for IEEE 754 summation rounding across multiple validator weights
        double diff = (approvedWeight * 3.0) - (totalWeight * 2.0);
        boolean weighted = diff >= -1e-9;
        boolean count = yesVotes >= requiredVotes(validatorCount);
        return weighted && count;
    }

    /**
     * Tally voter addresses against the set of active eligible validators.
     * Deduplicates voters, filters out inactive or unknown voters, and calculates
     * total eligible weight and approved weight.
     */
    public record TallyResult(
            boolean reached,
            int yesVotes,
            int requiredVotes,
            int totalValidators,
            double approvedWeight,
            double totalWeight,
            double weightRatio,
            int peerConfirmations,
            Set<String> acceptedVoters
    ) {}

    public static TallyResult evaluateVotes(
            Collection<String> rawYesVoters,
            Map<String, Double> activeValidatorWeights,
            String proposerAddress
    ) {
        if (activeValidatorWeights == null || activeValidatorWeights.isEmpty()) {
            return new TallyResult(false, 0, Integer.MAX_VALUE, 0, 0.0, 0.0, 0.0, 0, Set.of());
        }

        // Filter eligible validators: only positive weight (> 0.0) with non-blank address
        Map<String, Double> eligibleWeights = new LinkedHashMap<>();
        double totalWeight = 0.0;
        for (Map.Entry<String, Double> entry : activeValidatorWeights.entrySet()) {
            Double w = entry.getValue();
            String addr = entry.getKey();
            if (w != null && w > 0.0 && addr != null && !addr.isBlank()) {
                eligibleWeights.put(addr, w);
                totalWeight += w;
            }
        }

        int totalValidators = eligibleWeights.size();
        if (totalValidators <= 0 || totalWeight <= 0.0) {
            return new TallyResult(false, 0, Integer.MAX_VALUE, 0, 0.0, 0.0, 0.0, 0, Set.of());
        }

        Set<String> uniqueYes = new LinkedHashSet<>();
        double approvedWeight = 0.0;
        int peerConfirmations = 0;

        if (rawYesVoters != null) {
            for (String voter : rawYesVoters) {
                if (voter != null && eligibleWeights.containsKey(voter)) {
                    if (uniqueYes.add(voter)) {
                        Double w = eligibleWeights.get(voter);
                        if (w != null && w > 0.0) {
                            approvedWeight += w;
                        }
                        if (proposerAddress != null && !proposerAddress.equals(voter)) {
                            peerConfirmations++;
                        }
                    }
                }
            }
        }

        int yesVotes = uniqueYes.size();
        int req = requiredVotes(totalValidators);
        double ratio = totalWeight > 0.0 ? approvedWeight / totalWeight : 0.0;
        boolean hasPeerConf = totalValidators <= 1 || peerConfirmations > 0;
        boolean reached = isReached(approvedWeight, totalWeight, yesVotes, totalValidators, hasPeerConf);

        return new TallyResult(reached, yesVotes, req, totalValidators, approvedWeight, totalWeight, ratio, peerConfirmations, uniqueYes);
    }
}
