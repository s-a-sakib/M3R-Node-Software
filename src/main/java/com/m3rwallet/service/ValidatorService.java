package com.m3rwallet.service;

import com.m3rwallet.entity.SlashEvent;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.entity.ValidatorWeight;
import com.m3rwallet.repository.SlashEventRepository;
import com.m3rwallet.repository.ValidatorRepository;
import com.m3rwallet.repository.ValidatorWeightRepository;
import com.m3rwallet.repository.AccountRepository;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
    private final NodeIdentityService nodeIdentityService;
    private final ValidatorSyncService validatorSyncService;
    private final AccountRepository accountRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.m3rwallet.repository.ValidatorSnapshotRepository validatorSnapshotRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.m3rwallet.repository.BlockRepository blockRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public void setValidatorSnapshotRepository(com.m3rwallet.repository.ValidatorSnapshotRepository validatorSnapshotRepository) {
        this.validatorSnapshotRepository = validatorSnapshotRepository;
    }

    public void setBlockRepository(com.m3rwallet.repository.BlockRepository blockRepository) {
        this.blockRepository = blockRepository;
    }

    public void setObjectMapper(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private com.fasterxml.jackson.databind.ObjectMapper getObjectMapper() {
        if (this.objectMapper == null) {
            this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        }
        return this.objectMapper;
    }

    @Value("${app.validator.minimum-stake:1000}")
    private long minimumStake;

    @Value("${app.validator.slot-duration-ms:15000}")
    private long slotDurationMs;

    @Value("${app.validator.enabled:false}")
    private boolean validatorEnabled;

    @Value("${app.validator.stake:10000}")
    private long defaultStake;

    @Value("${app.blockchain.network:mainnet}")
    private String defaultNetwork;

    @Value("${app.validator.require-balance:false}")
    private boolean requireBalance;

    @Value("${app.validator.selection-mode:round-robin}")
    private String selectionMode;

    @Value("${app.validator.alpha:0.15}")
    private double alpha = 0.15d;

    public record ValidatorSnapshotItem(
            String address,
            BigDecimal stakedAmount,
            Long reliabilityScoreScaled,
            double weight
    ) {}

    public record ValidatorSetSnapshot(
            String snapshotHash,
            String network,
            Long blockHeight,
            Long timestamp,
            List<ValidatorSnapshotItem> validators,
            Map<String, Double> weights
    ) {}

    private final Map<String, ValidatorSetSnapshot> snapshotCacheByHash = new ConcurrentHashMap<>();
    private final Map<String, ValidatorSetSnapshot> snapshotCacheByHeight = new ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    public void initSnapshots() {
        if (validatorSnapshotRepository != null) {
            try {
                List<com.m3rwallet.entity.HistoricalValidatorSnapshot> list = validatorSnapshotRepository.findAll();
                for (com.m3rwallet.entity.HistoricalValidatorSnapshot entity : list) {
                    ValidatorSetSnapshot snap = deserializeSnapshotEntity(entity);
                    if (snap != null) {
                        snapshotCacheByHash.put(snap.snapshotHash().toLowerCase(), snap);
                        if (snap.network() != null && snap.blockHeight() != null) {
                            snapshotCacheByHeight.put(snap.network() + ":" + snap.blockHeight(), snap);
                        }
                    }
                }
                log.info("[SNAPSHOT] Loaded {} durable validator snapshots from database", snapshotCacheByHash.size());
            } catch (Exception e) {
                log.warn("[SNAPSHOT] Could not preload snapshots: {}", e.getMessage());
            }
        }
    }

    public Optional<ValidatorSetSnapshot> getSnapshotByHash(String snapshotHash) {
        if (snapshotHash == null || snapshotHash.isBlank()) return Optional.empty();
        String normHash = snapshotHash.trim().toLowerCase();
        ValidatorSetSnapshot cached = snapshotCacheByHash.get(normHash);
        if (cached != null) return Optional.of(cached);

        if (validatorSnapshotRepository != null) {
            try {
                Optional<com.m3rwallet.entity.HistoricalValidatorSnapshot> entityOpt = validatorSnapshotRepository.findBySnapshotHash(normHash);
                if (entityOpt.isPresent()) {
                    ValidatorSetSnapshot reconstructed = deserializeSnapshotEntity(entityOpt.get());
                    if (reconstructed != null) {
                        snapshotCacheByHash.put(normHash, reconstructed);
                        if (reconstructed.network() != null && reconstructed.blockHeight() != null) {
                            snapshotCacheByHeight.put(reconstructed.network() + ":" + reconstructed.blockHeight(), reconstructed);
                        }
                        return Optional.of(reconstructed);
                    }
                }
            } catch (Exception e) {
                log.warn("[SNAPSHOT] Failed to load snapshot {} from database: {}", normHash, e.getMessage());
            }
        }
        return Optional.empty();
    }

    public ValidatorSetSnapshot deserializeSnapshotEntity(com.m3rwallet.entity.HistoricalValidatorSnapshot entity) {
        if (entity == null || entity.getSnapshotDataJson() == null) return null;
        try {
            com.fasterxml.jackson.core.type.TypeReference<List<ValidatorSnapshotItem>> typeRef =
                    new com.fasterxml.jackson.core.type.TypeReference<>() {};
            List<ValidatorSnapshotItem> items = getObjectMapper().readValue(entity.getSnapshotDataJson(), typeRef);
            if (items == null) return null;

            // Reconstruct active validators and recompute validatorSetHash to verify integrity
            List<Validator> reconstructedValidators = new ArrayList<>();
            Map<String, Double> weights = new LinkedHashMap<>();
            for (ValidatorSnapshotItem item : items) {
                Validator val = Validator.builder()
                        .address(item.address())
                        .network(entity.getNetwork())
                        .stakedAmount(item.stakedAmount())
                        .reliabilityScoreScaled(item.reliabilityScoreScaled())
                        .status(Validator.ValidatorStatus.ACTIVE)
                        .build();
                reconstructedValidators.add(val);
                if (item.weight() > 0.0) {
                    weights.put(item.address(), item.weight());
                } else {
                    double w = calculateWeight(val);
                    if (w > 0.0) weights.put(val.getAddress(), w);
                }
            }

            String recomputedHash = BlockValidationService.computeValidatorSetHash(reconstructedValidators);
            if (!recomputedHash.equalsIgnoreCase(entity.getSnapshotHash())) {
                log.error("[SECURITY] Historical validator snapshot hash TAMPERING DETECTED for height {}! Expected: {} but recomputed from stored contents: {}. Failing closed.",
                        entity.getBlockHeight(), entity.getSnapshotHash(), recomputedHash);
                return null; // Fail closed!
            }

            return new ValidatorSetSnapshot(
                    entity.getSnapshotHash().toLowerCase(),
                    entity.getNetwork(),
                    entity.getBlockHeight(),
                    entity.getCapturedAt(),
                    items,
                    weights
            );
        } catch (Exception e) {
            log.error("[SECURITY] Failed to deserialize historical snapshot {}: {}", entity.getSnapshotHash(), e.getMessage());
            return null;
        }
    }

    public Optional<ValidatorSetSnapshot> getSnapshotForBlock(String network, Long blockHeight, String snapshotHash) {
        if (snapshotHash != null && !snapshotHash.isBlank()) {
            Optional<ValidatorSetSnapshot> byHash = getSnapshotByHash(snapshotHash);
            if (byHash.isPresent()) return byHash;
        }
        if (network != null && blockHeight != null) {
            return Optional.ofNullable(snapshotCacheByHeight.get(network + ":" + blockHeight));
        }
        return Optional.empty();
    }

    public ValidatorSetSnapshot recordSnapshot(String network, Long blockHeight, List<Validator> validators) {
        if (validators == null) validators = List.of();
        List<ValidatorSnapshotItem> items = new ArrayList<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Validator v : validators) {
            if (v != null && v.getAddress() != null && !v.getAddress().isBlank() && v.isActive()) {
                double w = calculateWeight(v);
                items.add(new ValidatorSnapshotItem(v.getAddress(), v.getStakedAmount(), v.getReliabilityScoreScaled(), w));
                if (w > 0.0) {
                    weights.put(v.getAddress(), w);
                }
            }
        }
        String hash = BlockValidationService.computeValidatorSetHash(validators);
        String normHash = hash.toLowerCase();

        // Durable persistence in database
        if (validatorSnapshotRepository != null) {
            try {
                Optional<com.m3rwallet.entity.HistoricalValidatorSnapshot> existing = validatorSnapshotRepository.findBySnapshotHash(normHash);
                if (existing.isPresent()) {
                    // Ensure snapshot cannot be silently overwritten with differing contents
                    ValidatorSetSnapshot existingSnap = deserializeSnapshotEntity(existing.get());
                    if (existingSnap != null && !normHash.equalsIgnoreCase(existingSnap.snapshotHash())) {
                        throw new IllegalStateException("Snapshot collision or tampering for hash: " + normHash);
                    }
                } else {
                    String json = getObjectMapper().writeValueAsString(items);
                    com.m3rwallet.entity.HistoricalValidatorSnapshot entity = com.m3rwallet.entity.HistoricalValidatorSnapshot.builder()
                            .snapshotHash(normHash)
                            .network(network == null ? defaultNetwork : network)
                            .blockHeight(blockHeight == null ? 0L : blockHeight)
                            .capturedAt(System.currentTimeMillis())
                            .snapshotDataJson(json)
                            .build();
                    validatorSnapshotRepository.save(entity);
                    log.info("[SNAPSHOT] Durably persisted historical snapshot {} for height {}", normHash, blockHeight);
                }
            } catch (Exception e) {
                log.warn("[SNAPSHOT] Could not durably persist snapshot {}: {}", normHash, e.getMessage());
            }
        }

        ValidatorSetSnapshot snapshot = new ValidatorSetSnapshot(
                normHash, network, blockHeight, System.currentTimeMillis(), items, weights);
        snapshotCacheByHash.put(normHash, snapshot);
        if (network != null && blockHeight != null) {
            snapshotCacheByHeight.put(network + ":" + blockHeight, snapshot);
        }
        return snapshot;
    }

    public ValidatorSetSnapshot captureCurrentSnapshot(String network, Long blockHeight) {
        List<Validator> active = getActiveValidators(network);
        return recordSnapshot(network, blockHeight, active);
    }

    public double getAlpha() {
        return this.alpha;
    }

    public List<Validator> getActiveValidators(String network) {
        if (validatorRepository == null) return List.of();
        return validatorRepository.findByNetworkAndStatus(network, Validator.ValidatorStatus.ACTIVE);
    }

    public void setAlpha(double alpha) {
        this.alpha = Math.max(0.0d, Math.min(1.0d, alpha));
    }

    /**
     * Calculate weight for a validator using formula:
     * W_v = alpha * log(1 + S_v) + (1 - alpha) * R_v
     * Returns 0.0 for non-ACTIVE validators.
     */
    public double calculateWeight(Validator v) {
        return calculateWeight(v, this.alpha);
    }

    public double calculateWeight(Validator v, double alphaParam) {
        if (v == null) throw new IllegalArgumentException("Validator is null");
        if (!v.isActive()) return 0.0d;
        double a = Math.max(0.0d, Math.min(1.0d, alphaParam));
        double S_v = (v.getStakedAmount() == null) ? 0.0d : v.getStakedAmount().doubleValue();
        double R_v = v.getReliabilityScore();
        double stakeComponent = a * Math.log(1.0d + S_v);
        double reputationComponent = (1.0d - a) * R_v;
        double weight = stakeComponent + reputationComponent;
        return Math.max(0.0d, weight);
    }

    /**
     * Select a validator deterministically for a given slot and network.
     * @throws RuntimeException when no active validators are found
     */
    public Validator selectValidatorForSlot(long slotNumber, String network) {
        return selectValidatorForSlot(slotNumber, network, null);
    }

    public Validator selectValidatorForSlot(long slotNumber, String network, List<Validator> pool) {
        List<Validator> rawValidators = (pool != null && !pool.isEmpty())
                ? pool
                : validatorRepository.findByNetworkAndStatus(network, Validator.ValidatorStatus.ACTIVE);
        if (rawValidators == null || rawValidators.isEmpty()) {
            throw new RuntimeException("No active validators for network: " + network);
        }

        List<Validator> validators = new ArrayList<>(rawValidators);
        validators.sort(Comparator.comparing(Validator::getAddress));

        if (!"weighted".equalsIgnoreCase(selectionMode)) {
            int idx = Math.floorMod(slotNumber, validators.size());
            Validator chosen = validators.get(idx);
            log.info("[SLOT_SELECT] slot={} network={} selected={} mode=round-robin validatorCount={}",
                    slotNumber, network, chosen.getAddress(), validators.size());
            return chosen;
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
            validators.sort(Comparator.comparing(Validator::getStakedAmount, Comparator.reverseOrder()));
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
     * Record a successful proposal: derives counts deterministically and idempotently from canonical finalized blocks.
     */
    @Transactional
    public void recordSuccessfulProposal(String address, String network) {
        Validator v = validatorRepository.findByAddressAndNetwork(address, network)
                .orElseThrow(() -> new IllegalArgumentException("Validator not found: " + address));

        long prevProposals = v.getSuccessfulProposals() == null ? 0L : v.getSuccessfulProposals();
        double currentR = v.getReliabilityScore();

        if (blockRepository != null) {
            long canonicalProposals = blockRepository.countByProposerAddressAndNetworkAndIsFinalized(address, network, true);
            v.setSuccessfulProposals(canonicalProposals);
            long currentTotal = v.getTotalProposals() == null ? 0L : v.getTotalProposals();
            v.setTotalProposals(Math.max(currentTotal, canonicalProposals));

            if (canonicalProposals > prevProposals) {
                long delta = canonicalProposals - prevProposals;
                double newR = Math.min(1.0d, currentR + (delta * 0.01d));
                v.setReliabilityScore(newR);
            } else if (canonicalProposals < prevProposals) {
                long delta = prevProposals - canonicalProposals;
                double newR = Math.max(0.0d, currentR - (delta * 0.01d));
                v.setReliabilityScore(newR);
            }
            // If canonicalProposals == prevProposals, idempotent (no change to reliability)
            v.setLastProposalAt(System.currentTimeMillis());
            validatorRepository.save(v);
            log.info("[PROPOSAL] Recorded canonical proposal for {} on {} count={} R={}", address, network, canonicalProposals, v.getReliabilityScore());
        } else {
            long nextProposals = prevProposals + 1L;
            v.setSuccessfulProposals(nextProposals);
            long currentTotal = v.getTotalProposals() == null ? 0L : v.getTotalProposals();
            v.setTotalProposals(Math.max(currentTotal, nextProposals));
            double newR = Math.min(1.0d, currentR + 0.01d);
            v.setReliabilityScore(newR);
            v.setLastProposalAt(System.currentTimeMillis());
            validatorRepository.save(v);
            log.info("[PROPOSAL] Recorded proposal for {} on {} count={} R={}", address, network, nextProposals, newR);
        }
    }

    /**
     * Reconciles proposal counts and reputation from canonical finalized block records.
     */
    @Transactional
    public void reconcileProposalStatistics(String address, String network) {
        recordSuccessfulProposal(address, network);
    }

    @Transactional
    public void reconcileAllValidatorProposalStatistics(String network) {
        if (validatorRepository == null || blockRepository == null) return;
        List<Validator> all = validatorRepository.findByNetwork(network);
        if (all == null) return;
        for (Validator v : all) {
            if (v.getAddress() != null && !v.getAddress().isBlank()) {
                try {
                    recordSuccessfulProposal(v.getAddress(), network);
                } catch (Exception e) {
                    log.warn("Could not reconcile stats for {}: {}", v.getAddress(), e.getMessage());
                }
            }
        }
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

        if (requireBalance) {
            try {
                java.util.Optional<com.m3rwallet.entity.Account> account = accountRepository.findByNetworkAndAddress(network, address);
                java.math.BigDecimal availableBalance = account.map(a -> new java.math.BigDecimal(a.getBalance())).orElse(java.math.BigDecimal.ZERO);
                if (availableBalance.compareTo(stakeAmount) < 0) {
                    throw new IllegalArgumentException("Insufficient balance to stake. Available: " + availableBalance + " Required: " + stakeAmount);
                }
                if (account.isPresent()) {
                    com.m3rwallet.entity.Account acc = account.get();
                    java.math.BigDecimal newBalance = new java.math.BigDecimal(acc.getBalance()).subtract(stakeAmount);
                    acc.setBalance(newBalance.toString());
                    accountRepository.save(acc);
                    log.info("Locked {} stake from account {}", stakeAmount, address);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Stake registration failed: " + e.getMessage());
            }
        } else {
            log.warn("Stake balance check DISABLED (app.validator.require-balance=false)");
        }

        Validator v = Validator.builder()
                .address(address)
                .network(network)
                .stakedAmount(stakeAmount)
                .reliabilityScoreScaled(0L)
                .totalProposals(0L)
                .successfulProposals(0L)
                .corruptedProposals(0L)
                .status(Validator.ValidatorStatus.ACTIVE)
                .registeredAt(System.currentTimeMillis())
                .build();
        Validator saved = validatorRepository.save(v);
        log.info("[REGISTER] Validator registered {} on {} stake={}", address, network, stakeAmount);
        try {
            if (validatorSyncService != null) {
                validatorSyncService.broadcastValidatorRegistration(saved);
            }
        } catch (Exception e) {
            log.warn("Validator sync failed (non-fatal): {}", e.getMessage());
        }
        return saved;
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

    @jakarta.annotation.PostConstruct
    public void autoRegisterIfEnabled() {
        if (!validatorEnabled) return;
        String validatorAddress = nodeIdentityService.getNodeAddress();
        if (validatorAddress == null || validatorAddress.isBlank()) return;
        if ("unknown".equals(validatorAddress)) return;
        try {
            if (validatorRepository.findByAddressAndNetwork(validatorAddress, defaultNetwork).isPresent()) {
                log.info("Auto-register skipped: {} already registered on {}", validatorAddress, defaultNetwork);
                return;
            }
            registerValidator(validatorAddress, defaultNetwork, java.math.BigDecimal.valueOf(defaultStake));
            log.info("Auto-registered validator {} on {}", validatorAddress, defaultNetwork);
        } catch (Exception e) {
            log.warn("Auto-register failed (non-fatal): {}", e.getMessage());
        }
    }
}
