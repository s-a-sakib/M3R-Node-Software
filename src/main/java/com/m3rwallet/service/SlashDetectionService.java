package com.m3rwallet.service;

import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.SlashEvent;
import com.m3rwallet.entity.SlashEvent.SlashReason;
import com.m3rwallet.entity.SlashEvent.SlashSeverity;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.entity.Validator.ValidatorStatus;
import com.m3rwallet.entity.ValidatorWeight;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.SlashEventRepository;
import com.m3rwallet.repository.ValidatorRepository;
import com.m3rwallet.repository.ValidatorWeightRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class SlashDetectionService {
    private final ValidatorRepository validatorRepository;
    private final ValidatorWeightRepository validatorWeightRepository;
    private final SlashEventRepository slashEventRepository;
    private final BlockRepository blockRepository;
    private final long minimumStake;
    private final String defaultNetwork;

    public SlashDetectionService(ValidatorRepository validatorRepository,
                                 ValidatorWeightRepository validatorWeightRepository,
                                 SlashEventRepository slashEventRepository,
                                 BlockRepository blockRepository,
                                 @Value("${app.validator.minimum-stake:1000}") long minimumStake,
                                 @Value("${app.blockchain.network:mainnet}") String defaultNetwork) {
        this.validatorRepository = validatorRepository;
        this.validatorWeightRepository = validatorWeightRepository;
        this.slashEventRepository = slashEventRepository;
        this.blockRepository = blockRepository;
        this.minimumStake = minimumStake;
        this.defaultNetwork = defaultNetwork;
    }

    public boolean detectDoubleSign(String proposerAddress, long slotNumber, String newBlockHash, String network) {
        if (network == null) network = defaultNetwork;
        try {
            Optional<Block> opt = blockRepository.findBySlotNumberAndProposerAddressAndNetwork(slotNumber, proposerAddress, network);
            if (opt.isPresent()) {
                Block found = opt.get();
                if (found.getBlockHash() != null && !found.getBlockHash().equals(newBlockHash)) {
                    log.warn("DOUBLE SIGN DETECTED: validator {} proposed 2 blocks in slot {}", proposerAddress, slotNumber);
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("detectDoubleSign failed: {}", e.getMessage());
        }
        return false;
    }

    public List<String> detectInvalidProposal(Block block) {
        List<String> violations = new ArrayList<>();
        if (block == null) return violations;
        if (block.getMerkleRoot() == null || block.getMerkleRoot().isBlank()) violations.add("NULL_MERKLE_ROOT");
        if (block.getBlockHash() == null || block.getBlockHash().isBlank()) violations.add("NULL_BLOCK_HASH");
        if (block.getProposerAddress() == null || block.getProposerAddress().isBlank()) violations.add("NULL_PROPOSER");
        if (block.getTxCount() == null || block.getTxCount() < 0) violations.add("NEGATIVE_TX_COUNT");
        if (block.getTimestamp() == null || block.getTimestamp() <= 0) violations.add("INVALID_TIMESTAMP");
        if (block.getSlotNumber() == null || block.getSlotNumber() <= 0) violations.add("INVALID_SLOT");
        return violations;
    }

    @Transactional
    public SlashEvent slash(String validatorAddress,
                            String network,
                            SlashReason reason,
                            SlashSeverity severity,
                            Long blockHeight,
                            String evidence) {
        if (network == null) network = defaultNetwork;
        Optional<Validator> vopt = validatorRepository.findByAddressAndNetwork(validatorAddress, network);
        if (vopt.isEmpty()) {
            log.warn("slash: validator not found {} @ {}", validatorAddress, network);
            return null;
        }
        Validator v = vopt.get();
        long repBefore = (v.getReliabilityScoreScaled() == null) ? 0L : v.getReliabilityScoreScaled();
        BigDecimal slashAmount = BigDecimal.ZERO;

        if (severity == SlashSeverity.MINOR) {
            long newScaled = (long) (repBefore * 0.95d);
            v.setReliabilityScoreScaled(newScaled);
        } else if (severity == SlashSeverity.MEDIUM) {
            v.setReliabilityScoreScaled(0L);
            BigDecimal staked = (v.getStakedAmount() == null) ? BigDecimal.ZERO : v.getStakedAmount();
            slashAmount = staked.multiply(BigDecimal.valueOf(2)).divide(BigDecimal.valueOf(100));
            v.setStakedAmount(staked.subtract(slashAmount));
            v.setJailedUntil(System.currentTimeMillis() + (50L * 15000L));
            v.setStatus(ValidatorStatus.JAILED);
        } else if (severity == SlashSeverity.SEVERE) {
            v.setReliabilityScoreScaled(0L);
            BigDecimal staked = (v.getStakedAmount() == null) ? BigDecimal.ZERO : v.getStakedAmount();
            slashAmount = staked.multiply(BigDecimal.valueOf(10)).divide(BigDecimal.valueOf(100));
            v.setStakedAmount(staked.subtract(slashAmount));
            v.setJailedUntil(System.currentTimeMillis() + (200L * 15000L));
            v.setStatus(ValidatorStatus.JAILED);
        }

        if (v.getStakedAmount() == null) v.setStakedAmount(BigDecimal.ZERO);
        if (v.getStakedAmount().compareTo(BigDecimal.valueOf(minimumStake)) < 0) {
            v.setStatus(ValidatorStatus.SLASHED);
            log.warn("Validator {} permanently slashed (below min stake)", validatorAddress);
        }

        validatorRepository.save(v);

        // mark weights stale
        try {
            List<ValidatorWeight> weights = validatorWeightRepository.findByValidatorAddressAndNetwork(validatorAddress, network);
            if (weights != null && !weights.isEmpty()) {
                for (ValidatorWeight w : weights) w.setIsStale(true);
                validatorWeightRepository.saveAll(weights);
            }
        } catch (Exception e) {
            log.warn("Failed to mark validator weights stale: {}", e.getMessage());
        }

        SlashEvent ev = SlashEvent.builder()
                .validatorAddress(validatorAddress)
                .network(network)
                .slashReason(reason)
                .severity(severity)
                .stakeSlashed(slashAmount)
                .reputationBefore(repBefore)
                .blockHeight(blockHeight)
                .evidence(evidence)
                .build();
        SlashEvent saved = slashEventRepository.save(ev);

        log.info("Slashed validator {} | reason: {} | severity: {} | stake burned: {} | rep before: {}",
                validatorAddress, reason, severity, slashAmount, repBefore);
        return saved;
    }

    @Transactional
    public boolean releaseFromJail(String validatorAddress, String network) {
        if (network == null) network = defaultNetwork;
        Optional<Validator> vopt = validatorRepository.findByAddressAndNetwork(validatorAddress, network);
        if (vopt.isEmpty()) return false;
        Validator v = vopt.get();
        if (v.getStatus() != ValidatorStatus.JAILED) return false;
        Long jailedUntil = v.getJailedUntil();
        long now = System.currentTimeMillis();
        if (jailedUntil != null && jailedUntil > now) return false;
        v.setStatus(ValidatorStatus.ACTIVE);
        v.setJailedUntil(null);
        validatorRepository.save(v);
        log.info("Validator {} released from jail", validatorAddress);
        return true;
    }

    @Scheduled(fixedRate = 30000)
    public void checkAndReleaseJailedValidators() {
        try {
            List<Validator> jailed = validatorRepository.findByStatus(ValidatorStatus.JAILED);
            int released = 0;
            if (jailed != null) {
                for (Validator v : jailed) {
                    boolean r = false;
                    try { r = releaseFromJail(v.getAddress(), v.getNetwork()); } catch (Exception e) { log.warn("releaseFromJail failed: {}", e.getMessage()); }
                    if (r) released++;
                }
            }
            if (released > 0) log.info("Released {} jailed validators", released);
        } catch (Exception e) {
            log.warn("checkAndReleaseJailedValidators failed: {}", e.getMessage());
        }
    }

    public List<SlashEvent> getSlashHistory(String validatorAddress, String network) {
        if (network == null) network = defaultNetwork;
        return slashEventRepository.findByValidatorAddressAndNetworkOrderByCreatedAtDesc(validatorAddress, network);
    }

    public boolean canParticipate(String validatorAddress, String network) {
        if (network == null) network = defaultNetwork;
        Optional<Validator> vopt = validatorRepository.findByAddressAndNetwork(validatorAddress, network);
        if (vopt.isEmpty()) return false;
        Validator v = vopt.get();
        if (v.getStatus() != ValidatorStatus.ACTIVE) return false;
        if (v.getStakedAmount() == null) return false;
        if (v.getStakedAmount().compareTo(BigDecimal.valueOf(minimumStake)) < 0) return false;
        Long jailedUntil = v.getJailedUntil();
        long now = System.currentTimeMillis();
        return jailedUntil == null || jailedUntil < now;
    }
}
