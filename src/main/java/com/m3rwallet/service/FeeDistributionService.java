package com.m3rwallet.service;

import com.m3rwallet.entity.Account;
import com.m3rwallet.entity.BlockTransaction;
import com.m3rwallet.entity.BroadcasterEarning;
import com.m3rwallet.entity.ProposerEarning;
import com.m3rwallet.repository.AccountRepository;
import com.m3rwallet.repository.BlockTransactionRepository;
import com.m3rwallet.repository.BroadcasterEarningRepository;
import com.m3rwallet.repository.ProposerEarningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.List;

/**
 * Service for fee calculation and distribution (broadcast + consensus shares).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeeDistributionService {

    @Value("${app.fee-policy.broadcast-fee-bps:2000}")
    private int broadcastFeeBps;

    @Value("${app.fee-policy.consensus-fee-bps:8000}")
    private int consensusFeeBps;

    @Value("${app.fee-policy.base-fee:100}")
    private long baseFee;

    @Value("${app.fee-policy.per-byte-fee:1}")
    private long perByteFee;

    private final AccountRepository accountRepository;
    private final BroadcasterEarningRepository broadcasterEarningRepository;
    private final ProposerEarningRepository proposerEarningRepository;
    private final BlockTransactionRepository blockTransactionRepository;

    public record FeeBreakdown(long totalFee, long broadcastFee, long consensusFee) {}

    /**
     * Calculate fees for a transaction given size in bytes.
     */
    public FeeBreakdown calculateFees(int txSizeBytes) {
        long totalFee = baseFee + (txSizeBytes * perByteFee);
        return splitTotalFee(totalFee);
    }

    public FeeBreakdown splitTotalFee(long totalFee) {
        long broadcastFee = (totalFee * broadcastFeeBps) / 10000;
        long consensusFee = totalFee - broadcastFee;
        return new FeeBreakdown(totalFee, broadcastFee, consensusFee);
    }

    /**
     * Record broadcast fee and credit to broadcaster account.
     */
    @Transactional
    public void recordBroadcastFee(String txHash, String broadcasterAddress, String network, long broadcastFee, Long blockHeight) {
        BroadcasterEarning e = BroadcasterEarning.builder()
                .txHash(txHash)
                .broadcasterAddress(broadcasterAddress)
                .network(network)
                .broadcastFee(broadcastFee)
                .blockHeight(blockHeight)
                .build();
        broadcasterEarningRepository.save(e);

        Account acct = accountRepository.findByNetworkAndAddress(network, broadcasterAddress).orElseGet(() -> {
            Account a = new Account();
            a.setNetwork(network);
            a.setAddress(broadcasterAddress);
            a.setBalance("0");
            a.setNonce(0L);
            return a;
        });

        BigInteger bal = new BigInteger(acct.getBalance());
        bal = bal.add(BigInteger.valueOf(broadcastFee));
        acct.setBalance(bal.toString());
        accountRepository.save(acct);
        log.info("Broadcast fee {} credited to {}", broadcastFee, broadcasterAddress);
    }

    /**
     * Distribute consensus fees to proposer for a block.
     */
    @Transactional
    public void distributeConsensusFees(com.m3rwallet.entity.Block block, String network) {
        List<BlockTransaction> txs = blockTransactionRepository.findByBlockHeight(block.getBlockHeight());
        long totalConsensusFee = 0L;
        int txCount = 0;
        for (BlockTransaction bt : txs) {
            if (bt.getConsensusFee() != null) {
                totalConsensusFee += bt.getConsensusFee();
                txCount++;
            }
        }

        String proposer = block.getProposerAddress();
        Account acct = accountRepository.findByNetworkAndAddress(network, proposer).orElseGet(() -> {
            Account a = new Account();
            a.setNetwork(network);
            a.setAddress(proposer);
            a.setBalance("0");
            a.setNonce(0L);
            return a;
        });

        BigInteger bal = new BigInteger(acct.getBalance());
        bal = bal.add(BigInteger.valueOf(totalConsensusFee));
        acct.setBalance(bal.toString());
        accountRepository.save(acct);

        ProposerEarning pe = ProposerEarning.builder()
                .proposerAddress(proposer)
                .network(network)
                .blockHeight(block.getBlockHeight())
                .txCount(txCount)
                .totalConsensusFee(totalConsensusFee)
                .build();
        proposerEarningRepository.save(pe);

        log.info("Consensus fee {} distributed to proposer {} for block {}", totalConsensusFee, proposer, block.getBlockHeight());
    }

    /**
     * Get broadcaster stats.
     */
    public record BroadcasterStats(String address, long txCount, long totalEarnings) {}

    public BroadcasterStats getBroadcasterStats(String address, String network) {
        List<BroadcasterEarning> list = broadcasterEarningRepository.findByBroadcasterAddressAndNetwork(address, network);
        long txCount = (list == null) ? 0L : list.size();
        Long total = broadcasterEarningRepository.sumBroadcastFeeByBroadcasterAddressAndNetwork(address, network);
        return new BroadcasterStats(address, txCount, (total == null) ? 0L : total);
    }

    /**
     * Get proposer stats.
     */
    public record ProposerStats(String address, long blocksProposed, long totalEarnings) {}

    public ProposerStats getProposerStats(String address, String network) {
        List<ProposerEarning> list = proposerEarningRepository.findByProposerAddressAndNetwork(address, network);
        long blocks = (list == null) ? 0L : list.size();
        Long total = proposerEarningRepository.sumTotalConsensusFeeByProposerAddressAndNetwork(address, network);
        return new ProposerStats(address, blocks, (total == null) ? 0L : total);
    }
}
