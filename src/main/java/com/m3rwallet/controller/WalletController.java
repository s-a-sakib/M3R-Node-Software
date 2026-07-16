package com.m3rwallet.controller;

import com.m3rwallet.dto.*;
import com.m3rwallet.entity.Account;
import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.TxLedger;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.entity.Validator.ValidatorStatus;
import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.BlockTransactionRepository;
import com.m3rwallet.repository.EscrowRepository;
import com.m3rwallet.repository.TxLedgerRepository;
import com.m3rwallet.repository.ValidatorRepository;
import com.m3rwallet.repository.AccountRepository;
import com.m3rwallet.repository.TransactionRepository;
import com.m3rwallet.service.BlockBroadcastService;
import com.m3rwallet.service.FeeDistributionService;
import com.m3rwallet.service.MempoolService;
import com.m3rwallet.service.NodeIdentityService;
import com.m3rwallet.service.NodeConsensusService;
import com.m3rwallet.service.TxLedgerService;
import com.m3rwallet.service.PeerAuthService;
import com.m3rwallet.service.ValidatorService;
import com.m3rwallet.service.WalletService;
import com.m3rwallet.util.AddressUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Slf4j
public class WalletController {
    private final WalletService walletService;
    private final TxLedgerService txLedgerService;
    private final NodeConsensusService nodeConsensusService;
    private final ConsensusProperties consensusProperties;
    private final NodeIdentityService nodeIdentityService;

    @Autowired
    private BlockRepository blockRepo;

    @Autowired
    private BlockTransactionRepository blockTxRepo;

    @Autowired
    private TxLedgerRepository txLedgerRepository;

    @Autowired
    private ValidatorRepository validatorRepo;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private EscrowRepository escrowRepository;

    @Autowired(required = false)
    private ValidatorService validatorService;

    @Autowired(required = false)
    private MempoolService mempoolService;

    @Autowired(required = false)
    private FeeDistributionService feeDistributionService;

    @Autowired(required = false)
    @Lazy
    private BlockBroadcastService blockBroadcastService;

    @Autowired
    private PeerAuthService peerAuthService;

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
                    .validatorAddress(nodeIdentityService.getAddressOrUnknown())
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
            String txHash = walletService.executeTransaction(
                    network,
                    request.getRawTxHex(),
                    request.getPubKeyCompressedHex(),
                    request.getBroadcasterAddress());
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
        return peerAuthService.isAuthorized(token);
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

        String normalizedAddr = AddressUtil.resolveToHex20(addr);
        if (normalizedAddr == null || normalizedAddr.isBlank()) {
            return ResponseEntity.badRequest().body(
                    TxHistoryResponse.builder()
                            .status("ERROR")
                            .message("Invalid addr parameter")
                            .entries(java.util.Collections.emptyList())
                            .build()
            );
        }

        try {
            List<TxLedger> entries = txLedgerService.getLedger(network, normalizedAddr);

            List<TxHistoryResponse.LedgerEntryDto> dtos = entries.stream()
                    .map(e -> TxHistoryResponse.LedgerEntryDto.builder()
                            .txHash(e.getTxHash())
                            .type(e.getType())
                            .amount(e.getAmount())
                            .fee(e.getFee())
                            .fromAddr(AddressUtil.toDisplayAddress(e.getFromAddr()))
                            .toAddr(AddressUtil.toDisplayAddress(e.getToAddr()))
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

    @GetMapping("/{network}/blocks")
    @ResponseBody
    public ResponseEntity<?> getBlocks(
            @PathVariable String network,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Pageable pageable = PageRequest.of(
                    page, size,
                    Sort.by(Sort.Direction.DESC, "blockHeight"));
            Page<Block> blocks = blockRepo.findByNetwork(network, pageable);

            List<Map<String, Object>> result = blocks.stream()
                    .map(b -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("blockHeight",     b.getBlockHeight());
                        m.put("blockHash",       b.getBlockHash());
                        m.put("slotNumber",      b.getSlotNumber());
                        m.put("proposerAddress", b.getProposerAddress());
                        m.put("txCount",         b.getTxCount());
                        m.put("isFinalized",     b.getIsFinalized());
                        m.put("timestamp",       b.getTimestamp());
                        m.put("network",         b.getNetwork());
                        return m;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "blocks",        result,
                    "totalElements", blocks.getTotalElements(),
                    "totalPages",    blocks.getTotalPages(),
                    "currentPage",   page
            ));
        } catch (Exception e) {
            log.error("Error fetching blocks: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{network}/accounts")
    @ResponseBody
    public ResponseEntity<?> getAccounts(
            @PathVariable String network,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
            org.springframework.data.domain.Page<Account> accounts = accountRepository.findByNetwork(network, pageable);
            List<Map<String, Object>> result = accounts.stream().map(a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.getId());
                m.put("address", AddressUtil.toDisplayAddress(a.getAddress()));
                m.put("balance", a.getBalance());
                m.put("nonce", a.getNonce());
                m.put("network", a.getNetwork());
                return m;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(Map.of(
                    "content", result,
                    "totalElements", accounts.getTotalElements(),
                    "totalPages", accounts.getTotalPages(),
                    "currentPage", page,
                    "size", size
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{network}/transactions")
    @ResponseBody
    public ResponseEntity<?> getTransactions(
            @PathVariable String network,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            int safePage = Math.max(0, page);
            int safeSize = Math.max(1, Math.min(size, 100));
            int offset = safePage * safeSize;
            List<Map<String, Object>> all = transactionRepository.findByNetwork(network).stream()
                    .map(tx -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("hash", tx.getHash());
                        m.put("status", tx.getStatus());
                        m.put("createdAt", tx.getCreatedAt());
                        m.put("network", tx.getNetwork());
                        return m;
                    })
                    .collect(Collectors.toList());
            List<Map<String, Object>> pageContent = offset >= all.size()
                    ? List.of()
                    : all.subList(offset, Math.min(offset + safeSize, all.size()));
            return ResponseEntity.ok(Map.of(
                    "content", pageContent,
                    "totalElements", all.size(),
                    "currentPage", safePage,
                    "size", safeSize
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{network}/blocks/{height}")
    @ResponseBody
    public ResponseEntity<?> getBlock(
            @PathVariable String network,
            @PathVariable Long height) {
        try {
            Optional<Block> blockOpt = blockRepo.findByBlockHeightAndNetwork(height, network);
            if (blockOpt.isEmpty())
                return ResponseEntity.notFound().build();

            Block b = blockOpt.get();
            List<Map<String, Object>> txList =
                    blockTxRepo.findByBlockHeight(height).stream()
                            .map(tx -> {
                                Map<String, Object> t = new LinkedHashMap<>();
                                t.put("txHash",          tx.getTxHash());
                                t.put("sender",          AddressUtil.toDisplayAddress(tx.getSenderAddress()));
                                t.put("recipient",       AddressUtil.toDisplayAddress(tx.getRecipientAddress()));
                                t.put("value",           tx.getValue());
                                t.put("totalFee",        tx.getTotalFee());
                                t.put("broadcastFee",    tx.getBroadcastFee());
                                t.put("consensusFee",    tx.getConsensusFee());
                                t.put("broadcasterAddress", AddressUtil.toDisplayAddress(tx.getBroadcasterAddress()));
                                t.put("status",          tx.getStatus());
                                enrichBlockTransactionFromLedger(network, tx.getTxHash(), t);
                                return t;
                            })
                            .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("blockHeight",     b.getBlockHeight());
            result.put("blockHash",       b.getBlockHash());
            result.put("parentBlockHash", b.getParentBlockHash());
            result.put("slotNumber",      b.getSlotNumber());
            result.put("proposerAddress", b.getProposerAddress());
            result.put("proposerWeight",  b.getProposerWeight());
            result.put("merkleRoot",      b.getMerkleRoot());
            result.put("txCount",         b.getTxCount());
            result.put("isFinalized",     b.getIsFinalized());
            result.put("timestamp",       b.getTimestamp());
            result.put("network",         b.getNetwork());
            result.put("transactions",    txList);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private void enrichBlockTransactionFromLedger(String network, String txHash, Map<String, Object> txView) {
        if (txHash == null || txHash.isBlank()) {
            return;
        }
        boolean missingCoreDetails = txView.get("sender") == null
                || txView.get("recipient") == null
                || txView.get("value") == null
                || txView.get("totalFee") == null;
        if (!missingCoreDetails) {
            return;
        }
        try {
            List<TxLedger> ledgerEntries = txLedgerRepository.findByNetworkAndTxHash(network, txHash);
            if (ledgerEntries == null || ledgerEntries.isEmpty()) {
                return;
            }
            Optional<TxLedger> primary = ledgerEntries.stream()
                    .filter(l -> l.getType() != null && (
                            l.getType().equals("SEND")
                                    || l.getType().equals("ESCROW_CREATE")
                                    || l.getType().equals("ESCROW_RELEASE")
                                    || l.getType().equals("ESCROW_REFUND")))
                    .findFirst();
            TxLedger ledger = primary.orElse(ledgerEntries.get(0));
            txView.putIfAbsent("sender", AddressUtil.toDisplayAddress(ledger.getFromAddr()));
            txView.putIfAbsent("recipient", AddressUtil.toDisplayAddress(ledger.getToAddr()));
            txView.putIfAbsent("value", parseBigDecimalOrNull(ledger.getAmount()));
            Long totalFee = parseLongOrNull(ledger.getFee());
            txView.putIfAbsent("totalFee", totalFee);
            if (totalFee != null && feeDistributionService != null) {
                var fees = feeDistributionService.splitTotalFee(totalFee);
                txView.putIfAbsent("broadcastFee", fees.broadcastFee());
                txView.putIfAbsent("consensusFee", fees.consensusFee());
            }
        } catch (Exception e) {
            log.debug("Could not enrich block tx {} from ledger: {}", txHash, e.getMessage());
        }
    }

    private BigDecimal parseBigDecimalOrNull(String value) {
        try {
            return value == null ? null : new BigDecimal(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseLongOrNull(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }

    @PostMapping("/{network}/blocks/receive")
    @ResponseBody
    public ResponseEntity<?> receiveBlock(
            @PathVariable String network,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = PeerAuthService.CONSENSUS_TOKEN_HEADER, required = false) String token) {
        if (!peerAuthService.isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "REJECTED", "error", "Invalid or missing consensus token"));
        }
        try {
            if (blockBroadcastService == null) {
                return ResponseEntity.ok(Map.of("status", "DISABLED"));
            }
            Map<String, Object> result = blockBroadcastService.receiveBlock(payload, network);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error receiving block: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // Called by proposer to get this node's vote on a block
    @PostMapping("/{network}/blocks/vote")
    @ResponseBody
    public ResponseEntity<?> voteOnBlock(
            @PathVariable String network,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = PeerAuthService.CONSENSUS_TOKEN_HEADER, required = false) String token) {
        if (!peerAuthService.isAuthorized(token)) {
            Map<String, Object> rejected = new LinkedHashMap<>();
            rejected.put("vote", false);
            rejected.put("reason", "UNAUTHORIZED");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(rejected);
        }
        try {
            Long blockHeight = getLong(payload, "blockHeight");
            String blockHash = (String) payload.get("blockHash");
            String proposerAddress = (String) payload.get("proposerAddress");
            String merkleRoot = (String) payload.get("merkleRoot");
            Long slotNumber = getLong(payload, "slotNumber");
            Long txCount = getLong(payload, "txCount");
            Long timestamp = getLong(payload, "timestamp");

            String myAddress = nodeIdentityService != null ? nodeIdentityService.getNodeAddress() : "unknown";

            // Validation checks
            List<String> violations = new java.util.ArrayList<>();

            // Check 1: Block height must be next expected
            Optional<Block> latest = blockRepo.findTopByNetworkOrderByBlockHeightDesc(network);
            long expectedHeight = latest.map(b -> b.getBlockHeight() + 1).orElse(1L);
            if (blockHeight == null) {
                violations.add("MISSING_HEIGHT");
            } else if (blockHeight < expectedHeight - 1) {
                violations.add("HEIGHT_TOO_LOW");
            }

            // Check 2: Proposer must be active validator
            boolean proposerValid = proposerAddress != null && validatorRepo.findByAddressAndNetwork(proposerAddress, network)
                    .map(v -> v.getStatus() == com.m3rwallet.entity.Validator.ValidatorStatus.ACTIVE).orElse(false);
            if (!proposerValid) {
                violations.add("INVALID_PROPOSER");
            }

            // Check 3: Not already a block at this height
            if (blockHeight != null && blockRepo.existsById(blockHeight)) {
                violations.add("HEIGHT_ALREADY_EXISTS");
            }

            // Check 4: Timestamp sanity
            if (timestamp != null) {
                long diff = Math.abs(System.currentTimeMillis() - timestamp);
                if (diff > 30_000) violations.add("TIMESTAMP_SKEW");
            }

            boolean vote = violations.isEmpty();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("vote", vote);
            result.put("voterAddress", myAddress);
            result.put("blockHeight", blockHeight);
            result.put("blockHash", blockHash);
            result.put("violations", violations);
            result.put("reason", vote ? "VALID" : String.join(",", violations));

            log.info("[BLOCK VOTE] Block {} → vote: {} ({})", blockHeight, vote ? "YES" : "NO", vote ? "valid" : violations);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Vote error: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("vote", false, "reason", "INTERNAL_ERROR"));
        }
    }

    @PostMapping("/{network}/validator/register")
    @ResponseBody
    public ResponseEntity<?> registerValidator(
            @PathVariable String network,
            @RequestBody Map<String, String> body) {
        try {
            String address   = body.get("address");
            String stakeStr  = body.get("stakeAmount");

            if (address == null || stakeStr == null)
                return ResponseEntity.badRequest()
                        .body(Map.of("error",
                                "address and stakeAmount required"));

            if (validatorService == null)
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "ValidatorService unavailable"));

            BigDecimal stake = new BigDecimal(stakeStr);
            Validator v = validatorService
                    .registerValidator(address, network, stake);

            return ResponseEntity.ok(Map.of(
                    "message",      "Validator registered",
                    "address",      v.getAddress(),
                    "status",       v.getStatus(),
                    "stakedAmount", v.getStakedAmount()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{network}/validator/receive")
    @ResponseBody
    public ResponseEntity<?> receiveValidator(
            @PathVariable String network,
            @RequestBody Map<String, Object> body) {
        try {
            String source = String.valueOf(body.getOrDefault("source", ""));
            if (!"peer-sync".equals(source)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "invalid source"));
            }

            String address = body.get("address") == null ? null : String.valueOf(body.get("address"));
            String stakeStr = String.valueOf(body.getOrDefault("stakeAmount", "10000"));
            if (address == null || address.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "address required"));
            }

            java.util.Optional<Validator> existing = validatorRepo.findByAddressAndNetwork(address, network);
            if (existing.isPresent()) {
                log.debug("Validator {} already exists on {}", address, network);
                return ResponseEntity.ok(Map.of(
                        "status", "ALREADY_EXISTS",
                        "address", address,
                        "currentStatus", existing.get().getStatus().toString()
                ));
            }

            Validator v = new Validator();
            v.setAddress(address);
            v.setNetwork(network);
            v.setStakedAmount(new BigDecimal(stakeStr));
            v.setReliabilityScoreScaled(0L);
            v.setStatus(Validator.ValidatorStatus.ACTIVE);
            v.setRegisteredAt(System.currentTimeMillis());
            v.setTotalProposals(0L);
            v.setSuccessfulProposals(0L);
            v.setCorruptedProposals(0L);
            validatorRepo.save(v);

            log.info("[VALIDATOR SYNC] Received validator: {} on {}", address, network);
            return ResponseEntity.ok(Map.of("status", "REGISTERED", "address", address));
        } catch (Exception e) {
            log.error("Error receiving validator: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{network}/validator/{address}")
    @ResponseBody
    public ResponseEntity<?> getValidator(
            @PathVariable String network,
            @PathVariable String address) {
        try {
            return validatorRepo
                    .findByAddressAndNetwork(address, network)
                    .map(v -> ResponseEntity.ok(Map.of(
                            "address",             v.getAddress(),
                            "stakedAmount",        v.getStakedAmount(),
                            "reliabilityScore",    v.getReliabilityScore(),
                            "weight",              validatorService != null
                                                   ? validatorService.calculateWeight(v)
                                                   : 0.0,
                            "status",              v.getStatus(),
                            "totalProposals",      v.getTotalProposals(),
                            "successfulProposals", v.getSuccessfulProposals(),
                            "registeredAt",        v.getRegisteredAt()
                    )))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{network}/validators")
    @ResponseBody
    public ResponseEntity<?> getPublicValidators(@PathVariable String network) {
        try {
            List<Map<String, Object>> validators = validatorRepo
                    .findByNetworkAndStatus(network, ValidatorStatus.ACTIVE)
                    .stream()
                    .map(v -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("address", AddressUtil.toDisplayAddress(v.getAddress()));
                        m.put("stakedAmount", v.getStakedAmount());
                        m.put("status", v.getStatus());
                        m.put("weight", validatorService != null ? validatorService.calculateWeight(v) : 0.0d);
                        m.put("successfulProposals", v.getSuccessfulProposals());
                        m.put("totalProposals", v.getTotalProposals());
                        return m;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "network", network,
                    "validators", validators,
                    "total", validators.size()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{network}/mempool")
    @ResponseBody
    public ResponseEntity<?> getMempoolStatus(
            @PathVariable String network) {
        try {
            if (mempoolService == null)
                return ResponseEntity.ok(
                        Map.of("pendingCount", 0, "transactions", List.of()));

            int size = mempoolService.size(network);
            List<Map<String, Object>> txList =
                    mempoolService.getPendingTransactions(network, 50)
                            .stream()
                            .map(tx -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("txHash",     tx.txHash());
                                m.put("sender",     AddressUtil.toDisplayAddress(tx.senderAddress()));
                                m.put("recipient",  AddressUtil.toDisplayAddress(tx.recipientAddress()));
                                m.put("broadcasterAddress", AddressUtil.toDisplayAddress(tx.broadcasterAddress()));
                                m.put("fee",        tx.fee());
                                m.put("receivedAt", tx.receivedAt());
                                return m;
                            })
                            .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "pendingCount", size,
                    "transactions", txList
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{network}/stats")
    @ResponseBody
    public ResponseEntity<?> getNetworkStats(
            @PathVariable String network) {
        try {
            long totalBlocks = blockRepo
                    .countByNetworkAndIsFinalized(network, true);
            long totalValidators = validatorRepo
                    .findByNetworkAndStatus(network,
                            ValidatorStatus.ACTIVE).size();
            int mempoolSize = mempoolService != null
                    ? mempoolService.size(network) : 0;

            return ResponseEntity.ok(Map.of(
                    "network",         network,
                    "finalizedBlocks", totalBlocks,
                    "activeValidators",totalValidators,
                    "pendingTxs",      mempoolSize,
                    "totalSupply",     calculateTotalSupply(network).toPlainString()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{network}/node/status")
    @ResponseBody
    public ResponseEntity<?> getNodeStatus(@PathVariable String network) {
        try {
            int mempoolSize = mempoolService != null ? mempoolService.size(network) : 0;
            long latestHeight = blockRepo.findTopByNetworkOrderByBlockHeightDesc(network)
                    .map(b -> b.getBlockHeight()).orElse(0L);

            Map<String, Object> status = new LinkedHashMap<>();
            status.put("network", network);
            status.put("nodeAddress", nodeIdentityService.getAddressOrUnknown());
            status.put("publicKeyCompressed", nodeIdentityService.getPublicKeyCompressedHex());
            status.put("privateKeyBacked", nodeIdentityService.isPrivateKeyBacked());
            status.put("mempoolPending", mempoolSize);
            status.put("pendingTxs", mempoolSize);
            status.put("latestBlockHeight", latestHeight);
            status.put("status", "OK");

            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.warn("Failed to build node status: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // Helper
    private BigDecimal calculateTotalSupply(String network) {
        BigDecimal accountBalances = accountRepository.findByNetwork(network).stream()
                .map(a -> parseBigDecimalOrZero(a.getBalance()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lockedValidatorStake = validatorRepo.findByNetwork(network).stream()
                .map(v -> v.getStakedAmount() == null ? BigDecimal.ZERO : v.getStakedAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lockedEscrows = escrowRepository.findByNetwork(network).stream()
                .map(e -> parseBigDecimalOrZero(e.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pendingFeeReserve = BigDecimal.ZERO;
        if (mempoolService != null) {
            pendingFeeReserve = BigDecimal.valueOf(mempoolService.pendingFeeTotal(network,
                    txHash -> blockTxRepo.findByTxHash(txHash) != null));
        }
        return accountBalances.add(lockedValidatorStake).add(lockedEscrows).add(pendingFeeReserve);
    }

    private BigDecimal parseBigDecimalOrZero(String value) {
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(value);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private Long getLong(Map<String, Object> m, String key) {
        try {
            Object v = m.get(key);
            if (v == null) return null;
            if (v instanceof Number) return ((Number) v).longValue();
            return Long.parseLong(v.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
