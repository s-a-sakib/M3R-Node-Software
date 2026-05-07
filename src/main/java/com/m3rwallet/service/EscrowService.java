package com.m3rwallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.m3rwallet.entity.Escrow;
import com.m3rwallet.repository.EscrowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EscrowService {
    private final EscrowRepository escrowRepository;
    private final ObjectMapper objectMapper;

    /**
     * Get escrow by network and escrow ID
     */
    public Escrow getEscrow(String network, String escrowId) {
        Optional<Escrow> escrow = escrowRepository.findByNetworkAndEscrowId(network, escrowId);
        return escrow.orElse(null);
    }

    /**
     * Get escrow for update (for transaction locking)
     */
    public Escrow getEscrowForUpdate(String network, String escrowId) {
        Optional<Escrow> escrow = escrowRepository.findForUpdate(network, escrowId);
        return escrow.orElse(null);
    }

    /**
     * Save escrow
     */
    @Transactional
    public void setEscrow(String network, String escrowId, String buyer, String seller, 
                          String arbiter, String amount, Map<String, Object> rawData) {
        Escrow escrow = new Escrow();
        escrow.setNetwork(network);
        escrow.setEscrowId(escrowId);
        escrow.setBuyer(buyer);
        escrow.setSeller(seller);
        escrow.setArbiter(arbiter);
        escrow.setAmount(amount);
        escrow.setCreatedAt(System.currentTimeMillis());
        try {
            escrow.setRawData(objectMapper.writeValueAsString(rawData));
        } catch (Exception e) {
            log.error("Error serializing escrow data", e);
        }
        escrowRepository.save(escrow);
    }

    /**
     * Delete escrow
     */
    @Transactional
    public void deleteEscrow(String network, String escrowId) {
        Optional<Escrow> escrow = escrowRepository.findByNetworkAndEscrowId(network, escrowId);
        if (escrow.isPresent()) {
            escrowRepository.delete(escrow.get());
        }
    }

    /**
     * Get all escrows for a network
     */
    public List<Escrow> getEscrowsByNetwork(String network) {
        return escrowRepository.findByNetwork(network);
    }

    /**
     * Get escrow count for a network
     */
    public Long getNetworkEscrowCount(String network) {
        return escrowRepository.findByNetwork(network).stream().count();
    }
}
