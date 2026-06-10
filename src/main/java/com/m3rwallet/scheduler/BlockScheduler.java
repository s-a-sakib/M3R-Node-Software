package com.m3rwallet.scheduler;

import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.BlockTransaction;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.service.BlockBroadcastService;
import com.m3rwallet.service.BlockProposalService;
import com.m3rwallet.service.SlashDetectionService;
import com.m3rwallet.service.PeerSyncService;
import com.m3rwallet.service.FeeDistributionService;
import com.m3rwallet.service.MempoolService;
import com.m3rwallet.service.NodeIdentityService;
import com.m3rwallet.service.ValidatorService;
import com.m3rwallet.service.BlockConsensusVoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;

@Component
public class BlockScheduler {
    private static final Logger log = LoggerFactory.getLogger(BlockScheduler.class);

    private final ValidatorService validatorService;
    private final BlockProposalService blockProposalService;
    private final SlashDetectionService slashDetectionService;
    private final PeerSyncService peerSyncService;
    private final MempoolService mempoolService;
    private final FeeDistributionService feeDistributionService;
    private final NodeIdentityService nodeIdentityService;
    private final BlockConsensusVoteService blockConsensusVoteService;
    private final boolean validatorEnabled;
    private final String selfUrl;
    private final String network;
    private final boolean skipEmptyBlocks;
    private final long slotDurationMs;
    private final int maxBlockSize;

    private final AtomicBoolean isProposing = new AtomicBoolean(false);
    private final AtomicLong lastProposedSlot = new AtomicLong(-1L);
    private final Set<Long> filledSlots = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Autowired(required = false)
    @Lazy
    private BlockBroadcastService blockBroadcastService;

    public BlockScheduler(ValidatorService validatorService,
                          BlockProposalService blockProposalService,
                          MempoolService mempoolService,
                          FeeDistributionService feeDistributionService,
                          SlashDetectionService slashDetectionService,
                          PeerSyncService peerSyncService,
                          NodeIdentityService nodeIdentityService,
                          BlockConsensusVoteService blockConsensusVoteService,
                          @Value("${app.validator.enabled:false}") boolean validatorEnabled,
                          @Value("${app.node.self-url:http://localhost:3000}") String selfUrl,
                          @Value("${app.blockchain.network:mainnet}") String network,
                          @Value("${app.blockchain.skip-empty-blocks:true}") boolean skipEmptyBlocks,
                          @Value("${app.validator.slot-duration-ms:15000}") long slotDurationMs,
                          @Value("${app.blockchain.max-block-size:5000}") int maxBlockSize) {
        this.validatorService = validatorService;
        this.blockProposalService = blockProposalService;
        this.mempoolService = mempoolService;
        this.feeDistributionService = feeDistributionService;
        this.slashDetectionService = slashDetectionService;
        this.peerSyncService = peerSyncService;
        this.nodeIdentityService = nodeIdentityService;
        this.blockConsensusVoteService = blockConsensusVoteService;
        this.validatorEnabled = validatorEnabled;
        this.selfUrl = selfUrl;
        this.network = network;
        this.skipEmptyBlocks = skipEmptyBlocks;
        this.slotDurationMs = slotDurationMs;
        this.maxBlockSize = maxBlockSize;
    }

    @PostConstruct
    public void startupSync() {
        try {
            peerSyncService.syncMissingBlocks();
            log.info("Peer sync missing blocks executed on startup");
        } catch (Exception e) {
            log.warn("Startup syncMissingBlocks failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.validator.slot-duration-ms:15000}",
            initialDelayString = "${app.validator.startup-proposal-delay-ms:15000}")
    public void proposeBlockForCurrentSlot() {
        if (!validatorEnabled) return;
        long slotNumber = System.currentTimeMillis() / slotDurationMs;
        if (filledSlots.contains(slotNumber)) {
            log.debug("[SLOT {}] Already filled by peer, skipping", slotNumber);
            filledSlots.remove(slotNumber);
            return;
        }
        filledSlots.removeIf(s -> s < slotNumber - 10);
        log.info("[SLOT {}] Scheduler heartbeat", slotNumber);
        if (slotNumber == lastProposedSlot.get()) return;
        if (isProposing.getAndSet(true)) return;
        try {
            Validator selected = validatorService.selectValidatorForSlot(slotNumber, network);
            double weight = (selected == null) ? 0.0 : validatorService.calculateWeight(selected);
            String selAddr = (selected == null) ? "<none>" : selected.getAddress();
            String thisNodeAddress = nodeIdentityService.getNodeAddress();
            log.info("[SLOT {}] Selected validator: {} (weight: {})", slotNumber, selAddr, weight);
            log.debug("[SLOT {}] Selected: {} | This node: {} | selfUrl: {}",
                    slotNumber, selAddr, thisNodeAddress, selfUrl);
            if (selected == null || !selAddr.equals(thisNodeAddress)) {
                log.info("[SLOT {}] Not our turn. Skipping.", slotNumber);
                return;
            }
            // check double sign for this node before building
            boolean isDoubleSign = false;
            try {
                isDoubleSign = slashDetectionService.detectDoubleSign(thisNodeAddress, slotNumber, "PENDING", network);
            } catch (Exception e) {
                log.warn("Double sign detection failed: {}", e.getMessage());
            }
            if (isDoubleSign) {
                log.warn("[SLOT {}] Double sign detected for this node! Skipping.", slotNumber);
                return;
            }
            log.info("[SLOT {}] *** THIS NODE IS PROPOSER ***", slotNumber);

            List<MempoolService.PendingTx> pending = mempoolService.getPendingTransactions(network, maxBlockSize);
            log.info("[SLOT {}] Collected {} pending txs from mempool", slotNumber, pending.size());

            if (pending.isEmpty() && skipEmptyBlocks) {
                log.debug("[SLOT {}] Mempool empty. Skipping.", slotNumber);
                return;
            }

            Block block = blockProposalService.buildBlock(slotNumber, selected, pending, network);
            List<BlockTransaction> txs = blockProposalService.createBlockTransactions(pending, block);
            Block saved = blockProposalService.saveBlock(block, txs);
            // DO NOT finalize yet — broadcast for voting first
            Block finalized = null;
            try {
                log.info("[SLOT {}] Broadcasting block {} for consensus voting", slotNumber, saved.getBlockHeight());
                boolean consensusReached = false;
                try {
                    consensusReached = blockConsensusVoteService.collectVotes(saved, network);
                } catch (Exception e) {
                    log.warn("[SLOT {}] Vote collection failed: {}", slotNumber, e.getMessage());
                }
                if (consensusReached) {
                    try {
                        finalized = blockProposalService.finalizeBlock(saved, network);
                        boolean feeOk = false;
                        try {
                            feeDistributionService.distributeBlockFees(finalized, network);
                            feeOk = true;
                        } catch (Exception e) {
                            log.warn("[SLOT {}] Fee distribution failed: {}", slotNumber, e.getMessage());
                        }
                        if (feeOk) {
                            try {
                                blockProposalService.saveBlock(finalized, null);
                            } catch (Exception e) {
                                log.warn("[SLOT {}] Could not persist feeDistributed flag: {}", slotNumber, e.getMessage());
                            }
                        }
                        if (blockBroadcastService != null) {
                            blockBroadcastService.broadcastFinalizedBlock(finalized, network);
                        }
                        log.info("[SLOT {}] Block {} finalized with 2/3 consensus", slotNumber, finalized.getBlockHeight());
                    } catch (Exception e) {
                        log.warn("[SLOT {}] Finalize after consensus failed: {}", slotNumber, e.getMessage());
                    }
                } else {
                    try {
                        blockProposalService.deleteBlock(saved);
                    } catch (Exception e) {
                        log.warn("[SLOT {}] Could not delete rejected block {}: {}", slotNumber, saved.getBlockHeight(), e.getMessage());
                    }
                    log.warn("[SLOT {}] Block {} rejected — no 2/3 consensus", slotNumber, saved.getBlockHeight());
                }
            } catch (Exception e) {
                log.warn("[SLOT {}] Consensus broadcast failed (non-fatal): {}", slotNumber, e.getMessage());
            }

            

            if (finalized != null) {
                mempoolService.removeTransactions(pending.stream().map(MempoolService.PendingTx::txHash).collect(Collectors.toList()));
                lastProposedSlot.set(slotNumber);
                log.info("[SLOT {}] Block {} proposed and finalized. Hash: {}", slotNumber, finalized.getBlockHeight(), finalized.getBlockHash());
            } else {
                log.info("[SLOT {}] Block proposal did not finalize (no consensus)", slotNumber);
            }

            List<String> violations = new java.util.ArrayList<>();
            try {
                if (finalized != null) {
                    violations = slashDetectionService.detectInvalidProposal(finalized);
                }
            } catch (Exception e) {
                log.warn("Invalid proposal detection failed: {}", e.getMessage());
            }
            if (violations != null && !violations.isEmpty()) {
                log.warn("[SLOT {}] Block has violations: {}", slotNumber, violations);
            }
        } catch (Exception e) {
            log.error("[SLOT {}] Block proposal FAILED: {}", slotNumber, e.getMessage(), e);
        } finally {
            isProposing.set(false);
        }
    }

    public void markSlotFilled(long slotNumber) {
        filledSlots.add(slotNumber);
        log.debug("[SLOT {}] Marked as filled by peer", slotNumber);
    }

    @Scheduled(fixedRate = 60000)
    public void evictExpiredMempool() {
        int removed = mempoolService.evictExpiredTransactions(network, 300_000L);
        log.info("Mempool eviction complete. Removed: {}", removed);
    }
}
