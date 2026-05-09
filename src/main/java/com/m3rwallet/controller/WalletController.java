package com.m3rwallet.controller;

import com.m3rwallet.dto.*;
import com.m3rwallet.entity.Account;
import com.m3rwallet.entity.TxLedger;
import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.service.NodeConsensusService;
import com.m3rwallet.service.TxLedgerService;
import com.m3rwallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Slf4j
public class WalletController {
    private final WalletService walletService;
    private final TxLedgerService txLedgerService;
    private final NodeConsensusService nodeConsensusService;
    private final ConsensusProperties consensusProperties;

    /**
     * Factory method to create network-specific routers
     */
    @GetMapping("/{network}/fee")
    public ResponseEntity<FeeResponse> getFee(@PathVariable String network) {
        log.info("[FEE][{}]", network);
        return ResponseEntity.ok(FeeResponse.builder()
                .broadcastFee(walletService.getBroadcastFee())
                .percentFeeBps(walletService.getPercentFeeBps())
                .build());
    }

    @GetMapping("/{network}/account")
    public ResponseEntity<?> getAccount(
            @PathVariable String network,
            @RequestParam(required = false) String addr,
            @RequestParam(required = false) String address) {
        String finalAddr = addr != null ? addr : address;
        if (finalAddr == null || finalAddr.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    AccountInfoResponse.builder()
                            .status("ERROR")
                            .message("Missing addr")
                            .build()
            );
        }

        try {
            Account account = walletService.getAccountInfo(network, finalAddr);
            if (account == null) {
                return ResponseEntity.ok(AccountInfoResponse.builder()
                        .status("OK")
                        .balance(java.math.BigInteger.ZERO)
                        .nonce(0L)
                        .build());
            }
            return ResponseEntity.ok(AccountInfoResponse.builder()
                    .status("OK")
                    .balance(new java.math.BigInteger(account.getBalance()))
                    .nonce(account.getNonce())
                    .build());
        } catch (Exception e) {
            log.error("[ACCOUNT][{}] Error:" + e.getMessage(), network);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    AccountInfoResponse.builder()
                            .status("ERROR")
                            .message("Internal Server Error")
                            .build()
            );
        }
    }

    @PostMapping("/{network}/tx/submit")
    public ResponseEntity<TxResponse> submitTransaction(
            @PathVariable String network,
            @RequestBody TxSubmitRequest request) {
        TxResponse requestError = validateSubmitRequest(request);
        if (requestError != null) {
            return ResponseEntity.badRequest().body(requestError);
        }

        String rawTxHex = request.getRawTxHex();
        String pubKeyCompressedHex = request.getPubKeyCompressedHex();

        log.info("[SUBMIT][{}] raw hex len: {}", network, rawTxHex.length());

        try {
            TxResponse consensusResponse = nodeConsensusService.isEnabled()
                    ? nodeConsensusService.submitWithConsensus(network, request)
                    : null;
            if (consensusResponse != null) {
                if ("ACCEPTED".equals(consensusResponse.getStatus())) {
                    return ResponseEntity.ok(consensusResponse);
                }
                return ResponseEntity.badRequest().body(consensusResponse);
            }

            String txHash = walletService.submitTransaction(network, rawTxHex, pubKeyCompressedHex);
            return ResponseEntity.ok(TxResponse.builder()
                    .status("ACCEPTED")
                    .txHash(txHash)
                    .message("OK")
                    .build());
        } catch (IllegalArgumentException e) {
            log.error("[SUBMIT][{}] REJECTED: {}", network, e.getMessage());
            return ResponseEntity.badRequest().body(
                    TxResponse.builder()
                            .status("REJECTED")
                            .message(e.getMessage())
                            .build()
            );
        } catch (Exception e) {
            log.error("[SUBMIT][{}] Server Error: ", network, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    TxResponse.builder()
                            .status("REJECTED")
                            .message("Internal server error processing transaction.")
                            .build()
            );
        }
    }

    @PostMapping("/{network}/node/tx/validate")
    public ResponseEntity<TxResponse> validateNodeTransaction(
            @PathVariable String network,
            @RequestBody TxSubmitRequest request,
            @RequestHeader(value = NodeConsensusService.CONSENSUS_TOKEN_HEADER, required = false) String token) {
        if (!isConsensusTokenAllowed(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    TxResponse.builder()
                            .status("REJECTED")
                            .message("Invalid consensus token")
                            .build()
            );
        }

        TxResponse requestError = validateSubmitRequest(request);
        if (requestError != null) {
            return ResponseEntity.ok(requestError);
        }

        try {
            String txHash = walletService.validateTransaction(network, request.getRawTxHex(), request.getPubKeyCompressedHex());
            return ResponseEntity.ok(TxResponse.builder()
                    .status("ACCEPTED")
                    .txHash(txHash)
                    .message("OK")
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(TxResponse.builder()
                    .status("REJECTED")
                    .message(e.getMessage())
                    .build());
        } catch (Exception e) {
            log.error("[NODE/VALIDATE][{}] Server Error: ", network, e);
            return ResponseEntity.ok(TxResponse.builder()
                    .status("REJECTED")
                    .message("Internal validation error")
                    .build());
        }
    }

    @PostMapping("/{network}/node/tx/execute")
    public ResponseEntity<TxResponse> executeNodeTransaction(
            @PathVariable String network,
            @RequestBody TxSubmitRequest request,
            @RequestHeader(value = NodeConsensusService.CONSENSUS_TOKEN_HEADER, required = false) String token) {
        if (!isConsensusTokenAllowed(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    TxResponse.builder()
                            .status("REJECTED")
                            .message("Invalid consensus token")
                            .build()
            );
        }

        TxResponse requestError = validateSubmitRequest(request);
        if (requestError != null) {
            return ResponseEntity.ok(requestError);
        }

        try {
            String txHash = walletService.executeTransaction(network, request.getRawTxHex(), request.getPubKeyCompressedHex());
            return ResponseEntity.ok(TxResponse.builder()
                    .status("ACCEPTED")
                    .txHash(txHash)
                    .message("OK")
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(TxResponse.builder()
                    .status("REJECTED")
                    .message(e.getMessage())
                    .build());
        } catch (Exception e) {
            log.error("[NODE/EXECUTE][{}] Server Error: ", network, e);
            return ResponseEntity.ok(TxResponse.builder()
                    .status("REJECTED")
                    .message("Internal execute error")
                    .build());
        }
    }

    private TxResponse validateSubmitRequest(TxSubmitRequest request) {
        if (request == null || request.getRawTxHex() == null || request.getRawTxHex().isEmpty()) {
            return TxResponse.builder()
                    .status("REJECTED")
                    .message("Missing rawTxHex")
                    .build();
        }
        if (request.getPubKeyCompressedHex() == null || request.getPubKeyCompressedHex().isEmpty()) {
            return TxResponse.builder()
                    .status("REJECTED")
                    .message("Missing pubKeyCompressedHex")
                    .build();
        }
        return null;
    }

    private boolean isConsensusTokenAllowed(String token) {
        String expected = consensusProperties.getSharedSecret();
        return expected == null || expected.isBlank() || expected.equals(token);
    }

    @GetMapping("/{network}/tx/status")
    public ResponseEntity<TxResponse> getTxStatus(
            @PathVariable String network,
            @RequestParam String hash) {
        if (hash == null || hash.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    TxResponse.builder()
                            .status("UNKNOWN")
                            .message("Missing hash")
                            .build()
            );
        }

        try {
            String status = walletService.getTxStatus(network, hash);
            return ResponseEntity.ok(TxResponse.builder()
                    .status(status)
                    .message("")
                    .build());
        } catch (Exception e) {
            log.error("[TX/STATUS][{}] Error: {}", network, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    TxResponse.builder()
                            .status("UNKNOWN")
                            .message("Internal Server Error")
                            .build()
            );
        }
    }

    @PostMapping("/{network}/arbiter/request")
    public ResponseEntity<ArbiterResponse> requestArbiter(@PathVariable String network) {
        return ResponseEntity.ok(ArbiterResponse.builder()
                .status("OK")
                .arbiterAddress("MckArbiter111111111111111111111111")
                .message("Mock arbiter assigned")
                .build());
    }

    @GetMapping("/{network}/arbiter/list")
    public ResponseEntity<?> getArbiterList(@PathVariable String network) {
        return ResponseEntity.ok(java.util.Map.of(
                "status", "OK",
                "arbiters", java.util.List.of(
                        java.util.Map.of(
                                "address", "MckArbiter111111111111111111111111",
                                "endpoint", "http://localhost:3000",
                                "stake", 10000
                        )
                )
        ));
    }

    @PostMapping("/{network}/faucet")
    public ResponseEntity<FaucetResponse> faucet(
            @PathVariable String network,
            @RequestBody FaucetRequest request) {
        if (!network.equals("testnet") && !network.equals("legacy")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    FaucetResponse.builder()
                            .status("ERROR")
                            .message("Faucet not available on mainnet")
                            .build()
            );
        }

        String addr = request.getAddr() != null ? request.getAddr() : request.getAddress();
        if (addr == null || addr.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    FaucetResponse.builder()
                            .status("ERROR")
                            .message("Missing addr")
                            .build()
            );
        }

        try {
            java.math.BigInteger amount = request.getAmount() != null && !request.getAmount().isEmpty()
                ? new java.math.BigInteger(request.getAmount())
                : new java.math.BigInteger("10000");

            walletService.executeFaucet(network, addr, amount);
            
            com.m3rwallet.entity.Account updatedAccount = walletService.getAccountInfo(network, addr);
            String newBal = updatedAccount != null ? updatedAccount.getBalance() : "0";

            return ResponseEntity.ok(FaucetResponse.builder()
                    .status("OK")
                    .newBalance(new java.math.BigInteger(newBal))
                    .message("Faucet funds transferred")
                    .build());
        } catch (Exception e) {
            log.error("[FAUCET][{}] Server Error: ", network, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    FaucetResponse.builder()
                            .status("ERROR")
                            .message("Internal Server Error")
                            .build()
            );
        }
    }

    /**
     * GET /{network}/tx/history?addr=&lt;hex20&gt;
     *
     * Returns all confirmed ledger entries where the given address is a participant,
     * ordered newest-first. Both outgoing and incoming entries are included so the
     * caller can build a complete transaction history without requiring a local record
     * of every operation.
     */
    @GetMapping("/{network}/tx/history")
    public ResponseEntity<?> getTxHistory(
            @PathVariable String network,
            @RequestParam(required = false) String addr) {

        if (addr == null || addr.isBlank()) {
            return ResponseEntity.badRequest().body(
                    TxHistoryResponse.builder()
                            .status("ERROR")
                            .message("Missing addr parameter")
                            .entries(java.util.Collections.emptyList())
                            .build()
            );
        }

        // Normalise: strip optional 0x prefix and lower-case
        String normalizedAddr = addr.startsWith("0x") ? addr.substring(2).toLowerCase() : addr.toLowerCase();

        try {
            List<TxLedger> entries = txLedgerService.getLedger(network, normalizedAddr);

            List<TxHistoryResponse.LedgerEntryDto> dtos = entries.stream()
                    .map(e -> TxHistoryResponse.LedgerEntryDto.builder()
                            .txHash(e.getTxHash())
                            .type(e.getType())
                            .amount(e.getAmount())
                            .fee(e.getFee())
                            .fromAddr(e.getFromAddr())
                            .toAddr(e.getToAddr())
                            .escrowId(e.getEscrowId())
                            .status(e.getStatus())
                            .createdAt(e.getCreatedAt())
                            .build())
                    .collect(Collectors.toList());

            log.info("[HISTORY][{}] {} => {} entries", network, normalizedAddr, dtos.size());
            return ResponseEntity.ok(
                    TxHistoryResponse.builder()
                            .status("OK")
                            .entries(dtos)
                            .build()
            );
        } catch (Exception e) {
            log.error("[HISTORY][{}] Error: {}", network, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    TxHistoryResponse.builder()
                            .status("ERROR")
                            .message("Internal Server Error")
                            .entries(java.util.Collections.emptyList())
                            .build()
            );
        }
    }

    @GetMapping("/{network}/health")
    public ResponseEntity<HealthResponse> health(@PathVariable String network) {
        return ResponseEntity.ok(HealthResponse.builder()
                .status("OK")
                .message("M3R Node Server (" + network + ") running on MySQL Database")
                .build());
    }
}
