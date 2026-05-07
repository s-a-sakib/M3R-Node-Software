package com.m3rwallet.service;

import com.m3rwallet.entity.TxLedger;
import com.m3rwallet.repository.TxLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TxLedgerService {

    private final TxLedgerRepository txLedgerRepository;

    /** Hex-20 representation of the zero address — never write a ledger entry for it. */
    private static final String ZERO_ADDR_20 = "0000000000000000000000000000000000000000";

    /**
     * Write a single ledger entry for one participant of a transaction.
     * Silently skips the zero address and duplicate entries (for idempotency).
     *
     * @param network        "mainnet", "testnet", or "legacy"
     * @param txHash         SHA-256 hex of the raw transaction
     * @param participantAddr hex-20 address of the role holder
     * @param type           e.g. "SEND", "RECEIVE", "ESCROW_CREATE", …
     * @param amount         transfer / escrow amount as decimal string
     * @param fee            fee paid as decimal string
     * @param fromAddr       hex-20 sender
     * @param toAddr         hex-20 recipient / beneficiary
     * @param escrowId       hex-64 escrow ID, or null for plain transfers
     * @param now            Unix milliseconds timestamp
     */
    @Transactional
    public void recordEntry(
            String network, String txHash,
            String participantAddr, String type,
            String amount, String fee,
            String fromAddr, String toAddr,
            String escrowId, long now) {

        // Guard: never record for the zero address (un-set arbiter field)
        if (participantAddr == null || participantAddr.isBlank()
                || ZERO_ADDR_20.equals(participantAddr)) {
            return;
        }

        // Guard: idempotency — skip if this (network, hash, participantAddr) already exists
        if (txLedgerRepository.findByNetworkAndTxHashAndParticipantAddr(
                network, txHash, participantAddr).isPresent()) {
            log.debug("[LEDGER] Duplicate skipped: {} {} {}", network, txHash, participantAddr);
            return;
        }

        TxLedger entry = new TxLedger();
        entry.setNetwork(network);
        entry.setTxHash(txHash);
        entry.setParticipantAddr(participantAddr);
        entry.setType(type);
        entry.setAmount(amount);
        entry.setFee(fee != null ? fee : "0");
        entry.setFromAddr(fromAddr != null ? fromAddr : "");
        entry.setToAddr(toAddr != null ? toAddr : "");
        entry.setEscrowId(escrowId);
        entry.setStatus("CONFIRMED");
        entry.setCreatedAt(now);

        txLedgerRepository.save(entry);
        log.info("[LEDGER][{}] {} → {} type={} amount={}", network, participantAddr, txHash, type, amount);
    }

    /**
     * Return all ledger entries for a participant on a network,
     * ordered newest-first.
     */
    public List<TxLedger> getLedger(String network, String participantAddr) {
        return txLedgerRepository.findByNetworkAndParticipantAddr(network, participantAddr);
    }
}
