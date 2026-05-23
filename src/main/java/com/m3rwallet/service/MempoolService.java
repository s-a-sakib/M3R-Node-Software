package com.m3rwallet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class MempoolService {
    private static final Logger log = LoggerFactory.getLogger(MempoolService.class);

    private final ConcurrentHashMap<String, PendingTx> pendingTxs = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Value("${app.blockchain.network:mainnet}")
    private String nodeNetwork;

    public record PendingTx(
            String txHash,
            String senderAddress,
            String recipientAddress,
            String value,
            long fee,
            long broadcastFee,
            long consensusFee,
            String broadcasterAddress,
            String network,
            long receivedAt,
            String rawTxHex
    ) {}

    public boolean addTransaction(PendingTx tx) {
        try {
            if (tx == null || tx.txHash() == null) return false;
            if (tx.network() == null) return false;
            if (!nodeNetwork.equalsIgnoreCase(tx.network())) {
                log.debug("Rejecting tx {} — wrong network {} (node is {})", tx.txHash(), tx.network(), nodeNetwork);
                return false;
            }
        } catch (Exception e) {
            log.warn("Mempool addTransaction validation failed: {}", e.getMessage());
            return false;
        }
        PendingTx prev = pendingTxs.putIfAbsent(tx.txHash(), tx);
        if (prev != null) {
            return false;
        }
        log.info("Mempool: added tx {}, total pending: {}", tx.txHash(), pendingTxs.size());
        return true;
    }

    public List<PendingTx> getPendingTransactions(String network, int maxCount) {
        final String net = (network == null) ? "" : network;
        List<PendingTx> filtered = pendingTxs.values().stream()
                .filter(tx -> net.equals(tx.network()))
                .collect(Collectors.toList());
        // deterministic sort by txHash
        Collections.sort(filtered, (a, b) -> a.txHash().compareTo(b.txHash()));
        if (filtered.size() <= maxCount) return new ArrayList<>(filtered);
        return new ArrayList<>(filtered.subList(0, maxCount));
    }

    public void removeTransaction(String txHash) {
        if (txHash == null) return;
        pendingTxs.remove(txHash);
    }

    public void removeTransactions(List<String> txHashes) {
        if (txHashes == null || txHashes.isEmpty()) return;
        for (String h : txHashes) pendingTxs.remove(h);
    }

    public int size(String network) {
        if (network == null) return pendingTxs.size();
        final String net = network;
        return (int) pendingTxs.values().stream().filter(tx -> net.equals(tx.network())).count();
    }

    public boolean contains(String txHash) {
        return txHash != null && pendingTxs.containsKey(txHash);
    }

    public void clear(String network) {
        if (network == null) {
            pendingTxs.clear();
            return;
        }
        final String net = network;
        List<String> toRemove = pendingTxs.values().stream()
            .filter(tx -> net.equals(tx.network()))
            .map(PendingTx::txHash)
            .collect(Collectors.toList());
        for (String h : toRemove) pendingTxs.remove(h);
    }

    public int evictExpiredTransactions(String network, long maxAgeMs) {
        long now = System.currentTimeMillis();
        final String net = network;
        List<String> toRemove = pendingTxs.values().stream()
            .filter(tx -> net == null || net.equals(tx.network()))
            .filter(tx -> (now - tx.receivedAt()) > maxAgeMs)
            .map(PendingTx::txHash)
            .collect(Collectors.toList());
        toRemove.forEach(pendingTxs::remove);
        int removed = toRemove.size();
        if (removed > 0) log.info("Mempool eviction: removed {} expired txs for network {}", removed, network);
        return removed;
    }
}
