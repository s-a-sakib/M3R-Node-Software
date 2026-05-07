package com.m3rwallet.service;

import com.m3rwallet.entity.Transaction;
import com.m3rwallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {
    private final TransactionRepository transactionRepository;

    /**
     * Get transaction by network and hash
     */
    public Transaction getTx(String network, String hash) {
        Optional<Transaction> tx = transactionRepository.findByNetworkAndHash(network, hash);
        return tx.orElse(null);
    }

    /**
     * Save transaction
     */
    @Transactional
    public void setTx(String network, String hash, String status) {
        Transaction tx = transactionRepository.findByNetworkAndHash(network, hash)
                .orElse(new Transaction());
        tx.setNetwork(network);
        tx.setHash(hash);
        tx.setStatus(status);
        tx.setCreatedAt(System.currentTimeMillis());
        transactionRepository.save(tx);
    }

    /**
     * Get all transactions for a network
     */
    public List<Transaction> getTransactionsByNetwork(String network) {
        return transactionRepository.findByNetwork(network);
    }

    /**
     * Get transaction count for a network
     */
    public Long getNetworkTransactionCount(String network) {
        return transactionRepository.findByNetwork(network).stream().count();
    }
}
