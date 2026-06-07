package com.m3rwallet.controller;

import com.m3rwallet.entity.Account;
import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.Escrow;
import com.m3rwallet.entity.Transaction;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.ValidatorRepository;
import com.m3rwallet.service.AccountService;
import com.m3rwallet.service.EscrowService;
import com.m3rwallet.service.TransactionService;
import com.m3rwallet.service.ValidatorService;
import com.m3rwallet.util.AddressUtil;
import com.m3rwallet.util.AdminViewUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminDashboardController {

    private final AccountService accountService;
    private final TransactionService transactionService;
    private final EscrowService escrowService;
    private final AdminViewUtil adminViewUtil;

    private final BlockRepository blockRepo;
    private final ValidatorRepository validatorRepo;
    private final ValidatorService validatorService;

    @GetMapping("")
    public String dashboard(Model model) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("mainnetAccounts", accountService.getNetworkAccountCount("mainnet"));
        stats.put("testnetAccounts", accountService.getNetworkAccountCount("testnet"));
        stats.put("legacyAccounts", accountService.getNetworkAccountCount("legacy"));
        stats.put("mainnetTransactions", transactionService.getNetworkTransactionCount("mainnet"));
        stats.put("testnetTransactions", transactionService.getNetworkTransactionCount("testnet"));
        stats.put("legacyTransactions", transactionService.getNetworkTransactionCount("legacy"));
        stats.put("mainnetEscrows", escrowService.getNetworkEscrowCount("mainnet"));
        stats.put("testnetEscrows", escrowService.getNetworkEscrowCount("testnet"));
        stats.put("legacyEscrows", escrowService.getNetworkEscrowCount("legacy"));

        model.addAttribute("stats", stats);
        return "admin/dashboard";
    }

    @GetMapping("/accounts")
    public String accounts(@RequestParam(required = false, defaultValue = "mainnet") String network,
                           @RequestParam(required = false, defaultValue = "") String q,
                           Model model) {
        return dashboard(model);
    }

    @GetMapping("/transactions")
    public String transactions(@RequestParam(required = false, defaultValue = "mainnet") String network,
                               @RequestParam(required = false, defaultValue = "") String q,
                               Model model) {
        return dashboard(model);
    }

    @GetMapping("/escrows")
    public String escrows(@RequestParam(required = false, defaultValue = "mainnet") String network,
                          @RequestParam(required = false, defaultValue = "") String q,
                          Model model) {
        return dashboard(model);
    }

    @GetMapping("/blocks")
    public String blocks(@RequestParam(required = false, defaultValue = "mainnet") String network,
                         Model model) {
        return dashboard(model);
    }

    @GetMapping("/validators")
    public String validators(@RequestParam(required = false, defaultValue = "mainnet") String network,
                             Model model) {
        return dashboard(model);
    }

    @PostMapping("/accounts/search")
    public String searchAccount(@RequestParam String network, @RequestParam String address, Model model) {
        String normalizedAddress = AddressUtil.resolveToHex20(address);
        Account account = accountService.getAccount(network, normalizedAddress);
        model.addAttribute("network", network);
        model.addAttribute("account", account);
        model.addAttribute("networks", new String[]{"mainnet", "testnet", "legacy"});
        return "admin/account-detail";
    }

    @PostMapping("/transactions/search")
    public String searchTransaction(@RequestParam String network, @RequestParam String hash, Model model) {
        Transaction tx = transactionService.getTx(network, hash);
        model.addAttribute("network", network);
        model.addAttribute("transaction", tx);
        model.addAttribute("networks", new String[]{"mainnet", "testnet", "legacy"});
        return "admin/transaction-detail";
    }

    @PostMapping("/escrows/search")
    public String searchEscrow(@RequestParam String network, @RequestParam String escrowId, Model model) {
        Escrow escrow = escrowService.getEscrow(network, escrowId);
        model.addAttribute("network", network);
        model.addAttribute("escrow", escrow);
        model.addAttribute("networks", new String[]{"mainnet", "testnet", "legacy"});
        return "admin/escrow-detail";
    }

    @GetMapping("/api/stats")
    @ResponseBody
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("timestamp", System.currentTimeMillis());
        stats.put("mainnetAccounts", accountService.getNetworkAccountCount("mainnet"));
        stats.put("testnetAccounts", accountService.getNetworkAccountCount("testnet"));
        stats.put("legacyAccounts", accountService.getNetworkAccountCount("legacy"));
        stats.put("mainnetTransactions", transactionService.getNetworkTransactionCount("mainnet"));
        stats.put("testnetTransactions", transactionService.getNetworkTransactionCount("testnet"));
        stats.put("legacyTransactions", transactionService.getNetworkTransactionCount("legacy"));
        return stats;
    }

    // --- Admin API endpoints ---
    @GetMapping("/api/blockchain-stats")
    @ResponseBody
    public ResponseEntity<?> getBlockchainStats() {
        try {
            long totalBlocks = blockRepo.count();
            long finalizedBlocks = blockRepo.countByNetworkAndIsFinalized("mainnet", true);
            long activeValidators = validatorRepo.findByStatus(Validator.ValidatorStatus.ACTIVE).size();

            Optional<Block> latest = blockRepo.findTopByNetworkOrderByBlockHeightDesc("mainnet");

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("totalBlocks", totalBlocks);
            stats.put("finalizedBlocks", finalizedBlocks);
            stats.put("activeValidators", activeValidators);
            stats.put("latestBlockHeight", latest.map(Block::getBlockHeight).orElse(0L));
            stats.put("latestBlockHash", latest.map(Block::getBlockHash).orElse("none"));
            stats.put("latestBlockTime", latest.map(Block::getTimestamp).orElse(0L));

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/validators")
    @ResponseBody
    public ResponseEntity<?> getAllValidators() {
        try {
            List<Validator> validators = validatorRepo.findAll();
            List<Map<String, Object>> result = validators.stream()
                    .map(v -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("address", v.getAddress());
                        m.put("network", v.getNetwork());
                        m.put("stakedAmount", v.getStakedAmount());
                        m.put("reliabilityScore", v.getReliabilityScore());
                        m.put("weight", validatorService != null ? validatorService.calculateWeight(v) : 0.0);
                        m.put("status", v.getStatus());
                        m.put("totalProposals", v.getTotalProposals());
                        m.put("successfulProposals", v.getSuccessfulProposals());
                        m.put("registeredAt", v.getRegisteredAt());
                        return m;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of("validators", result, "total", result.size()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/accounts")
    @ResponseBody
    public ResponseEntity<?> getAccountsApi(@RequestParam(required = false, defaultValue = "mainnet") String network,
                                            @RequestParam(required = false, defaultValue = "") String q,
                                            @RequestParam(required = false, defaultValue = "50") int limit) {
        try {
            int cappedLimit = Math.max(1, Math.min(limit, 200));
            List<Map<String, Object>> accounts = accountService.getAccountsByNetwork(network).stream()
                    .filter(account -> matchesAccount(account, q))
                    .limit(cappedLimit)
                    .map(account -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("network", account.getNetwork());
                        m.put("address", account.getAddress());
                        m.put("displayAddress", adminViewUtil.address(account.getAddress()));
                        m.put("balance", account.getBalance());
                        m.put("displayBalance", adminViewUtil.amount(account.getBalance()));
                        m.put("nonce", account.getNonce());
                        return m;
                    })
                    .toList();
            return ResponseEntity.ok(Map.of("network", network, "accounts", accounts, "total", accounts.size()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/transactions")
    @ResponseBody
    public ResponseEntity<?> getTransactionsApi(@RequestParam(required = false, defaultValue = "mainnet") String network,
                                                @RequestParam(required = false, defaultValue = "") String q,
                                                @RequestParam(required = false, defaultValue = "50") int limit) {
        try {
            int cappedLimit = Math.max(1, Math.min(limit, 200));
            List<Map<String, Object>> transactions = transactionService.getTransactionsByNetwork(network).stream()
                    .filter(transaction -> matchesTransaction(transaction, q))
                    .sorted(Comparator.comparing(Transaction::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(cappedLimit)
                    .map(transaction -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("network", transaction.getNetwork());
                        m.put("hash", transaction.getHash());
                        m.put("status", transaction.getStatus());
                        m.put("createdAt", transaction.getCreatedAt());
                        return m;
                    })
                    .toList();
            return ResponseEntity.ok(Map.of("network", network, "transactions", transactions, "total", transactions.size()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/escrows")
    @ResponseBody
    public ResponseEntity<?> getEscrowsApi(@RequestParam(required = false, defaultValue = "mainnet") String network,
                                           @RequestParam(required = false, defaultValue = "") String q,
                                           @RequestParam(required = false, defaultValue = "50") int limit) {
        try {
            int cappedLimit = Math.max(1, Math.min(limit, 200));
            List<Map<String, Object>> escrows = escrowService.getEscrowsByNetwork(network).stream()
                    .filter(escrow -> matchesEscrow(escrow, q))
                    .sorted(Comparator.comparing(Escrow::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(cappedLimit)
                    .map(escrow -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("network", escrow.getNetwork());
                        m.put("escrowId", escrow.getEscrowId());
                        m.put("buyer", escrow.getBuyer());
                        m.put("buyerDisplay", adminViewUtil.address(escrow.getBuyer()));
                        m.put("seller", escrow.getSeller());
                        m.put("sellerDisplay", adminViewUtil.address(escrow.getSeller()));
                        m.put("arbiter", escrow.getArbiter());
                        m.put("arbiterDisplay", adminViewUtil.address(escrow.getArbiter()));
                        m.put("amount", escrow.getAmount());
                        m.put("displayAmount", adminViewUtil.amount(escrow.getAmount()));
                        m.put("createdAt", escrow.getCreatedAt());
                        return m;
                    })
                    .toList();
            return ResponseEntity.ok(Map.of("network", network, "escrows", escrows, "total", escrows.size()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // --- helpers ---
    private boolean matchesAccount(Account account, String query) {
        return adminViewUtil.contains(account.getAddress(), query)
                || adminViewUtil.contains(adminViewUtil.address(account.getAddress()), query)
                || adminViewUtil.contains(adminViewUtil.amount(account.getBalance()), query)
                || adminViewUtil.contains(account.getNonce().toString(), query);
    }

    private boolean matchesTransaction(Transaction transaction, String query) {
        return adminViewUtil.contains(transaction.getHash(), query)
                || adminViewUtil.contains(transaction.getStatus(), query)
                || adminViewUtil.contains(transaction.getNetwork(), query);
    }

    private boolean matchesEscrow(Escrow escrow, String query) {
        return adminViewUtil.contains(escrow.getEscrowId(), query)
                || adminViewUtil.contains(escrow.getBuyer(), query)
                || adminViewUtil.contains(adminViewUtil.address(escrow.getBuyer()), query)
                || adminViewUtil.contains(escrow.getSeller(), query)
                || adminViewUtil.contains(adminViewUtil.address(escrow.getSeller()), query)
                || adminViewUtil.contains(escrow.getArbiter(), query)
                || adminViewUtil.contains(adminViewUtil.address(escrow.getArbiter()), query)
                || adminViewUtil.contains(adminViewUtil.amount(escrow.getAmount()), query);
    }
}
