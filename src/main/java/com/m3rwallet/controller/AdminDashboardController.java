package com.m3rwallet.controller;

import com.m3rwallet.entity.Account;
import com.m3rwallet.entity.Transaction;
import com.m3rwallet.entity.Escrow;
import com.m3rwallet.service.AccountService;
import com.m3rwallet.service.TransactionService;
import com.m3rwallet.service.EscrowService;
import com.m3rwallet.util.AddressUtil;
import com.m3rwallet.util.AdminViewUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminDashboardController {
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final EscrowService escrowService;
    private final AdminViewUtil adminViewUtil;

    @GetMapping("")
    public String dashboard(Model model) {
        // Add statistics to model
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
        List<Account> accounts = accountService.getAccountsByNetwork(network).stream()
                .filter(account -> matchesAccount(account, q))
                .toList();
        model.addAttribute("network", network);
        model.addAttribute("q", q);
        model.addAttribute("accounts", accounts);
        model.addAttribute("networks", new String[]{"mainnet", "testnet", "legacy"});
        return "admin/accounts";
    }

    @GetMapping("/transactions")
    public String transactions(@RequestParam(required = false, defaultValue = "mainnet") String network,
                               @RequestParam(required = false, defaultValue = "") String q,
                               Model model) {
        List<Transaction> transactions = transactionService.getTransactionsByNetwork(network).stream()
                .filter(transaction -> matchesTransaction(transaction, q))
                .toList();
        model.addAttribute("network", network);
        model.addAttribute("q", q);
        model.addAttribute("transactions", transactions);
        model.addAttribute("networks", new String[]{"mainnet", "testnet", "legacy"});
        return "admin/transactions";
    }

    @GetMapping("/escrows")
    public String escrows(@RequestParam(required = false, defaultValue = "mainnet") String network,
                          @RequestParam(required = false, defaultValue = "") String q,
                          Model model) {
        List<Escrow> escrows = escrowService.getEscrowsByNetwork(network).stream()
                .filter(escrow -> matchesEscrow(escrow, q))
                .toList();
        model.addAttribute("network", network);
        model.addAttribute("q", q);
        model.addAttribute("escrows", escrows);
        model.addAttribute("networks", new String[]{"mainnet", "testnet", "legacy"});
        return "admin/escrows";
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
