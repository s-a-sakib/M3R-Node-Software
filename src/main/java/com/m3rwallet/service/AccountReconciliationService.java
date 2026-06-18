package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.entity.Account;
import com.m3rwallet.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountReconciliationService {

    private final AccountRepository accountRepository;
    private final ConsensusProperties consensusProperties;
    private final RestTemplate restTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.blockchain.network:mainnet}")
    private String defaultNetwork;

    @Scheduled(fixedRate = 10000)
    public void reconcileAccounts() {
        if (consensusProperties == null || !consensusProperties.isEnabled()) return;

        String network = defaultNetwork;
        List<String> peers = consensusProperties.getPeers();
        if (peers == null || peers.isEmpty()) return;

        List<Account> localAccounts = accountRepository.findByNetwork(network);
        for (Account acc : localAccounts) {
            reconcileAccount(network, acc.getAddress());
        }
    }

    public void reconcileAccount(String network, String address) {
        if (network == null || network.isBlank() || address == null || address.isBlank()) {
            return;
        }
        if (consensusProperties == null || !consensusProperties.isEnabled()) {
            return;
        }

        List<AccountSnapshot> snapshots = new ArrayList<>();
        accountRepository.findByNetworkAndAddress(network, address)
                .ifPresent(account -> snapshots.add(new AccountSnapshot(
                        account.getBalance(), account.getNonce() == null ? 0L : account.getNonce())));

        List<String> peers = consensusProperties.getPeers();
        if (peers == null) {
            peers = List.of();
        }

        for (String peer : peers) {
            try {
                String path = peer;
                if (!path.endsWith("/")) path += "/";
                String url = path + network + "/account?addr=" + address;
                Map response = restTemplate.getForObject(url, Map.class);
                AccountSnapshot snapshot = snapshotFromResponse(response);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            } catch (Exception e) {
                log.debug("[RECONCILE] Peer {} failed for {}: {}", peer, address, e.getMessage());
            }
        }

        chooseAuthoritativeSnapshot(snapshots).ifPresent(snapshot ->
                reconcileSingleAccount(network, address, snapshot.balance(), snapshot.nonce()));
    }

    private AccountSnapshot snapshotFromResponse(Map response) {
        if (response == null) {
            return null;
        }
        Object status = response.get("status");
        if (status == null || !"OK".equals(String.valueOf(status))) {
            return null;
        }
        Object balObj = response.get("balance");
        Object nonceObj = response.get("nonce");
        if (balObj == null) {
            return null;
        }
        String balance = String.valueOf(balObj);
        long nonce = nonceObj == null ? 0L : Long.parseLong(String.valueOf(nonceObj));
        return new AccountSnapshot(balance, nonce);
    }

    private Optional<AccountSnapshot> chooseAuthoritativeSnapshot(List<AccountSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return Optional.empty();
        }

        long highestNonce = snapshots.stream()
                .mapToLong(AccountSnapshot::nonce)
                .max()
                .orElse(0L);

        BigInteger chosenBalance = null;
        for (AccountSnapshot snapshot : snapshots) {
            if (snapshot.nonce() == highestNonce) {
                BigInteger balance = new BigInteger(snapshot.balance());
                if (chosenBalance == null || balance.compareTo(chosenBalance) > 0) {
                    chosenBalance = balance;
                }
            }
        }

        return chosenBalance == null
                ? Optional.empty()
                : Optional.of(new AccountSnapshot(chosenBalance.toString(), highestNonce));
    }

    private void reconcileSingleAccount(String network, String address, String authoritativeBalance, long authoritativeNonce) {
        transactionTemplate.executeWithoutResult(status ->
                reconcileSingleAccountLocked(network, address, authoritativeBalance, authoritativeNonce));
    }

    private void reconcileSingleAccountLocked(String network, String address, String authoritativeBalance, long authoritativeNonce) {
        try {
            Optional<Account> localOpt = accountRepository.findForUpdate(network, address);
            if (localOpt.isEmpty()) {
                Account created = new Account();
                created.setNetwork(network);
                created.setAddress(address);
                created.setBalance(authoritativeBalance == null ? "0" : authoritativeBalance);
                created.setNonce(authoritativeNonce);
                accountRepository.save(created);
                log.info("[RECONCILE] Created missing account {} on {} with balance {} nonce {}",
                        address, network, authoritativeBalance, authoritativeNonce);
                return;
            }

            Account local = localOpt.get();
            BigInteger localBal = new BigInteger(local.getBalance());
            BigInteger chosenBal = new BigInteger(authoritativeBalance == null ? "0" : authoritativeBalance);
            long localNonce = local.getNonce() == null ? 0L : local.getNonce();

            if (!localBal.equals(chosenBal) || localNonce != authoritativeNonce) {
                local.setBalance(chosenBal.toString());
                local.setNonce(authoritativeNonce);
                accountRepository.save(local);
                log.info("[RECONCILE] Account {} adjusted balance {} -> {}, nonce {} -> {}",
                        address, localBal, chosenBal, localNonce, authoritativeNonce);
            }
        } catch (Exception e) {
            log.debug("[RECONCILE] Failed reconciling {}: {}", address, e.getMessage());
        }
    }

    private record AccountSnapshot(String balance, long nonce) {}
}
