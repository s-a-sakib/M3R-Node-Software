package com.m3rwallet.service;

import com.m3rwallet.entity.SlashEvent;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.entity.ValidatorWeight;
import com.m3rwallet.repository.SlashEventRepository;
import com.m3rwallet.repository.ValidatorRepository;
import com.m3rwallet.repository.ValidatorWeightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Core service implementing PoS+PoR validator logic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ValidatorService {

    private final ValidatorRepository validatorRepository;
    private final ValidatorWeightRepository validatorWeightRepository;
    private final SlashEventRepository slashEventRepository;

    @Value("${app.validator.minimum-stake:1000}")
    private long minimumStake;

    @Value("${app.validator.slot-duration-ms:15000}")
    private long slotDurationMs;

    /**
     * Calculate weight for a validator using formula:
     * W_v = 0.15 * log(1 + S_v) + 0.85 * R_v
     * Returns 0.0 for non-ACTIVE validators.
     */
    public double calculateWeight(Validator v) {
        if (v == null) throw new IllegalArgumentException("Validator is null");
        if (!v.isActive()) return 0.0d;
        double S_v = (v.getStakedAmount() == null) ? 0.0d : v.getStakedAmount().doubleValue();
        double R_v = v.getReliabilityScore();
        double stakeComponent = 0.15d * Math.log(1.0d + S_v);
        double reputationComponent = 0.85d * R_v;
        double weight = stakeComponent + reputationComponent;
        return Math.max(0.0d, weight);
    }

    /**
     * Select a validator deterministically for a given slot and network.
     * @throws RuntimeException when no active validators are found
     */
    public Validator selectValidatorForSlot(long slotNumber, String network) {
        List<Validator> validators = validatorRepository.findByNetworkAndStatus(network, Validator.ValidatorStatus.ACTIVE);
        if (validators == null || validators.isEmpty()) {
            throw new RuntimeException("No active validators for network: " + network);
        }

        List<Double> weights = new ArrayList<>();
        double totalWeight = 0.0d;
        for (Validator v : validators) {
            double w = calculateWeight(v);
            weights.add(w);
            totalWeight += w;
        }

        if (totalWeight <= 0.0d) {
            // fallback: return highest stake
            validators.sort(Comparator.comparing(v -> v.getStakedAmount()));
            return validators.get(0);
        }

        try {
            String seedStr = "slot:" + slotNumber + ":network:" + network;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] seed = md.digest(seedStr.getBytes());
            long seedLong = ByteBuffer.wrap(seed, 0, 8).getLong();
            long mod = Math.abs(seedLong % 1_000_000L);
            double random = (double) mod / 1_000_000.0d;

            double cumulative = 0.0d;
            for (int i = 0; i < validators.size(); i++) {
                cumulative += (weights.get(i) / totalWeight);
                if (random <= cumulative) {
                    Validator chosen = validators.get(i);
                    log.info("[SLOT_SELECT] slot={} network={} selected={} weight={} totalWeight={} random={}",
                            slotNumber, network, chosen.getAddress(), weights.get(i), totalWeight, random);
                    return chosen;
                }
            }
        } catch (Exception e) {
            log.error("Failed to compute deterministic seed: {}", e.getMessage());
        }

        // fallback
        return validators.get(0);
    }

    /**
     * Record a successful proposal: increments counters and increases reliability by 0.01 (capped at 1.0).
     */
    @Transactional
    public void recordSuccessfulProposal(String address, String network) {
        Validator v = validatorRepository.findByAddressAndNetwork(address, network)
                .orElseThrow(() -> new IllegalArgumentException("Validator not found: " + address));
        v.setSuccessfulProposals(v.getSuccessfulProposals() + 1);
        v.setTotalProposals(v.getTotalProposals() + 1);
        double newR = Math.min(1.0d, v.getReliabilityScore() + 0.01d);
        v.setReliabilityScore(newR);
        v.setLastProposalAt(System.currentTimeMillis());
        validatorRepository.save(v);
        log.info("[PROPOSAL] Recorded successful proposal for {} on {} newR={}", address, network, newR);
    }

    /**
     * Apply reputation decay R_v = 0.999 * R_v
     */
    @Transactional
    public void applyReputationDecay(String address, String network) {
        Validator v = validatorRepository.findByAddressAndNetwork(address, network)
                .orElseThrow(() -> new IllegalArgumentException("Validator not found: " + address));
        double newR = 0.999d * v.getReliabilityScore();
        v.setReliabilityScore(newR);
        validatorRepository.save(v);
        log.info("[DECAY] Applied reputation decay for {} on {} newR={}", address, network, newR);
    }

    /**
     * Slash a validator: reset reliability, burn 1 unit stake, create SlashEvent and mark weights stale.
     */
    @Transactional
    public void slashValidator(String address, String network, SlashEvent.SlashReason reason) {
        Validator v = validatorRepository.findByAddressAndNetwork(address, network)
                .orElseThrow(() -> new IllegalArgumentException("Validator not found: " + address));

        Long prevReputation = v.getReliabilityScoreScaled();
        // Reset reliability
        v.setReliabilityScore(0.0d);

        // Burn 1 unit (smallest unit)
        BigDecimal newStake = v.getStakedAmount().subtract(BigDecimal.ONE);
        v.setStakedAmount(newStake);
        v.setCorruptedProposals(v.getCorruptedProposals() + 1);

        // Create event
        SlashEvent event = SlashEvent.builder()
                .validatorAddress(address)
                .network(network)
                .slashReason(reason)
                .severity(SlashEvent.SlashSeverity.MEDIUM)
                .stakeSlashed(BigDecimal.ONE)
                .reputationBefore(prevReputation)
                .blockHeight(null)
                .build();
        slashEventRepository.save(event);

        if (newStake.compareTo(BigDecimal.valueOf(minimumStake)) < 0) {
            v.setStatus(Validator.ValidatorStatus.SLASHED);
            log.warn("Validator {} slashed below minimum stake", address);
        }

        // mark weights stale
        List<ValidatorWeight> cached = validatorWeightRepository.findByNetworkAndIsStale(network, Boolean.FALSE);
        for (ValidatorWeight w : cached) {
            w.setIsStale(Boolean.TRUE);
        }
        validatorWeightRepository.saveAll(cached);

        validatorRepository.save(v);
        log.info("[SLASH] Applied slash to {} reason={}", address, reason);
    }

    /**
     * Register a new validator with given stake. Throws if already registered or stake below minimum.
     */
    @Transactional
    public Validator registerValidator(String address, String network, BigDecimal stakeAmount) {
        if (validatorRepository.findByAddressAndNetwork(address, network).isPresent()) {
            throw new IllegalArgumentException("Validator already registered: " + address);
        }
        if (stakeAmount.compareTo(BigDecimal.valueOf(minimumStake)) < 0) {
            throw new IllegalArgumentException("Stake below minimum: " + stakeAmount);
        }

        Validator v = Validator.builder()
                .address(address)
                .network(network)
                .stakedAmount(stakeAmount)
                .reliabilityScoreScaled(0L)
                .status(Validator.ValidatorStatus.ACTIVE)
                .registeredAt(System.currentTimeMillis())
                .build();
        validatorRepository.save(v);
        log.info("[REGISTER] Validator registered {} on {} stake={}", address, network, stakeAmount);
        return v;
    }

    /**
     * Get validator weight (cached if available, otherwise recalculate and cache).
     */
    public double getValidatorWeight(String address, String network) {
        Optional<ValidatorWeight> cached = validatorWeightRepository.findByValidatorAddressAndNetworkAndIsStale(address, network, Boolean.FALSE);
        if (cached.isPresent()) {
            return cached.get().getWeight();
        }

        Validator v = validatorRepository.findByAddressAndNetwork(address, network)
                .orElseThrow(() -> new IllegalArgumentException("Validator not found: " + address));
        double weight = calculateWeight(v);
        ValidatorWeight w = ValidatorWeight.builder()
                .validatorAddress(address)
                .network(network)
                .weightScaled((long) (weight * 1_000_000L))
                .reliabilityScoreScaled(v.getReliabilityScoreScaled())
                .stakedAmountSnapshot(v.getStakedAmount())
                .calculatedAt(System.currentTimeMillis())
                .calculatedAtBlock(null)
                .isStale(Boolean.FALSE)
                .build();
        validatorWeightRepository.save(w);
        return weight;
    }

    /**
     * Recalculate weights for all ACTIVE validators on a network and cache them.
     */
    @Transactional
    public void recalculateAllWeights(String network, long blockHeight) {
        List<Validator> validators = validatorRepository.findByNetworkAndStatus(network, Validator.ValidatorStatus.ACTIVE);
        List<ValidatorWeight> newWeights = new ArrayList<>();
        for (Validator v : validators) {
            double weight = calculateWeight(v);
            ValidatorWeight w = ValidatorWeight.builder()
                    .validatorAddress(v.getAddress())
                    .network(network)
                    .weightScaled((long) (weight * 1_000_000L))
                    .reliabilityScoreScaled(v.getReliabilityScoreScaled())
                    .stakedAmountSnapshot(v.getStakedAmount())
                    .calculatedAt(System.currentTimeMillis())
                    .calculatedAtBlock(blockHeight)
                    .isStale(Boolean.FALSE)
                    .build();
            newWeights.add(w);
        }

        // mark old ones stale
        List<ValidatorWeight> old = validatorWeightRepository.findByNetworkAndIsStale(network, Boolean.FALSE);
        for (ValidatorWeight ow : old) ow.setIsStale(Boolean.TRUE);
        validatorWeightRepository.saveAll(old);

        validatorWeightRepository.saveAll(newWeights);
        log.info("Recalculated {} validator weights for block {}", newWeights.size(), blockHeight);
    }

    /**
     * Check if validator can participate: exists, ACTIVE, stake >= minimum and not jailed.
     */
    public boolean canValidatorParticipate(String address, String network) {
        Optional<Validator> opt = validatorRepository.findByAddressAndNetwork(address, network);
        if (opt.isEmpty()) return false;
        Validator v = opt.get();
        if (v.getStatus() != Validator.ValidatorStatus.ACTIVE) return false;
        if (v.getStakedAmount() == null || v.getStakedAmount().compareTo(BigDecimal.valueOf(minimumStake)) < 0) return false;
        if (v.getJailedUntil() != null && v.getJailedUntil() > System.currentTimeMillis()) return false;
        return true;
    }
}
