package com.m3rwallet.controller;

import com.m3rwallet.service.AccountService;
import com.m3rwallet.service.EscrowService;
import com.m3rwallet.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class DashboardController {
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final EscrowService escrowService;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("stats", stats());
        return "admin/dashboard";
    }

    private Map<String, Object> stats() {
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
        return stats;
    }
}
