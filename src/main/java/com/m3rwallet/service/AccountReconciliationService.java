package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.entity.Account;
import com.m3rwallet.repository.AccountRepository;
import com.m3rwallet.util.PeerUrlUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    @Value("${app.node.self-url:}")
    private String selfUrl;

    @Value("${app.reconciliation.apply-peer-snapshots:false}")
    private boolean applyPeerSnapshots;

    @Scheduled(fixedRate = 10000)
    public void reconcileAccounts() {
        if (consensusProperties == null || !consensusProperties.isEnabled()) return;

        String network = defaultNetwork;
        List<String> peers = remotePeers();
        if (peers.isEmpty()) return;

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

        for (String peer : remotePeers()) {
            try {
                String url = PeerUrlUtil.normalize(peer) + "/" + network + "/account?addr=" + address;
                Map response = restTemplate.getForObject(url, Map.class);
                AccountSnapshot snapshot = snapshotFromResponse(response);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            } catch (Exception e) {
                log.debug("[RECONCILE] Peer {} failed for {}: {}", peer, address, e.getMessage());
            }
        }

        chooseAuthoritativeSnapshot(snapshots).ifPresent(snapshot -> {
            if (!applyPeerSnapshots) {
                return;
            }
            Optional<Account> localOpt = accountRepository.findByNetworkAndAddress(network, address);
            long localNonce = localOpt.map(Account::getNonce).orElse(0L);
            if (localNonce > snapshot.nonce()) {
                log.debug("[RECONCILE] Skipping {} — local nonce {} ahead of authoritative {}",
                        address, localNonce, snapshot.nonce());
                return;
            }
            if (localNonce == snapshot.nonce()) {
                String localBalance = localOpt.map(Account::getBalance).orElse("0");
                if (localBalance.equals(snapshot.balance())) {
                    return;
                }
                BigInteger localBal = new BigInteger(localBalance);
                BigInteger chosenBal = new BigInteger(snapshot.balance());
                if (chosenBal.compareTo(localBal) < 0) {
                    log.debug("[RECONCILE] Skipping {} at nonce {} — refusing same-nonce balance downgrade {} -> {}",
                            address, snapshot.nonce(), localBalance, snapshot.balance());
                    return;
                }
                long agreeingNodes = snapshots.stream()
                        .filter(entry -> entry.nonce() == snapshot.nonce())
                        .filter(entry -> entry.balance().equals(snapshot.balance()))
                        .count();
                if (agreeingNodes < 2) {
                    log.debug("[RECONCILE] Skipping {} at nonce {} — no peer majority for balance {}",
                            address, snapshot.nonce(), snapshot.balance());
                    return;
                }
            }
            reconcileSingleAccount(network, address, snapshot.balance(), snapshot.nonce());
        });
    }

    private List<String> remotePeers() {
        return PeerUrlUtil.remotePeers(
                consensusProperties == null ? List.of() : consensusProperties.getPeers(),
                selfUrl);
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

    /**
     * Pick the state at the highest observed nonce, then choose the balance value
     * with the most votes among nodes at that nonce. This avoids inflating balances
     * by taking the maximum when nodes temporarily diverge.
     */
    private Optional<AccountSnapshot> chooseAuthoritativeSnapshot(List<AccountSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return Optional.empty();
        }

        long highestNonce = snapshots.stream()
                .mapToLong(AccountSnapshot::nonce)
                .max()
                .orElse(0L);

        List<AccountSnapshot> atHighestNonce = snapshots.stream()
                .filter(snapshot -> snapshot.nonce() == highestNonce)
                .toList();
        if (atHighestNonce.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Integer> votes = new HashMap<>();
        for (AccountSnapshot snapshot : atHighestNonce) {
            votes.merge(snapshot.balance(), 1, Integer::sum);
        }

        int requiredVotes = (atHighestNonce.size() + 1) / 2;
        return votes.entrySet().stream()
                .filter(entry -> entry.getValue() >= requiredVotes)
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(entry -> new BigInteger(entry.getKey())))
                .map(entry -> new AccountSnapshot(entry.getKey(), highestNonce));
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

            if (localNonce > authoritativeNonce) {
                return;
            }

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
