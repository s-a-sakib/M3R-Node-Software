package com.m3rwallet.service;

import com.m3rwallet.entity.Account;
import com.m3rwallet.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigInteger;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {
    private final AccountRepository accountRepository;

    /**
     * Get account by network and address
     */
    public Account getAccount(String network, String address) {
        Optional<Account> acc = accountRepository.findByNetworkAndAddress(network, address);
        return acc.orElse(null);
    }

    /**
     * Create or update account
     */
    @Transactional
    public void setAccount(String network, String address, BigInteger balance, Long nonce) {
        Account account = accountRepository.findByNetworkAndAddress(network, address)
                .orElse(new Account());
        account.setNetwork(network);
        account.setAddress(address);
        account.setBalance(balance.toString());
        account.setNonce(nonce);
        accountRepository.save(account);
    }

    /**
     * Get account for update (for transaction locking)
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Account getAccountForUpdate(String network, String address) {
        Optional<Account> acc = accountRepository.findForUpdate(network, address);
        return acc.orElse(null);
    }

    /**
     * Update and save an already-locked Account instance in-place.
     * This avoids re-querying which would release a pessimistic lock.
     */
    @Transactional
    public void updateAccountInPlace(Account lockedAccount, String network, String address, BigInteger newBalance, long newNonce) {
        if (lockedAccount == null) {
            // create new account if none provided
            lockedAccount = new Account();
            lockedAccount.setNetwork(network);
            lockedAccount.setAddress(address);
        }
        lockedAccount.setNetwork(network);
        lockedAccount.setAddress(address);
        lockedAccount.setBalance(newBalance.toString());
        lockedAccount.setNonce(newNonce);
        accountRepository.save(lockedAccount);
    }

    /**
     * Initialize genesis account
     */
    @Transactional
    public void initializeGenesis(String network, String genesisAddress, BigInteger initialBalance) {
        Optional<Account> existing = accountRepository.findByNetworkAndAddress(network, genesisAddress);
        if (existing.isEmpty()) {
            Account genesisAccount = new Account();
            genesisAccount.setNetwork(network);
            genesisAccount.setAddress(genesisAddress);
            genesisAccount.setBalance(initialBalance.toString());
            genesisAccount.setNonce(0L);
            accountRepository.save(genesisAccount);
            log.info("[{} GENESIS] Initialized: {} => {}", network.toUpperCase(), genesisAddress, initialBalance);
        } else {
            log.info("[{} GENESIS] Already exists", network.toUpperCase());
        }
    }

    /**
     * Get total accounts count for a network
     */
    public Long getNetworkAccountCount(String network) {
        return accountRepository.findByNetwork(network).stream().count();
    }

    /**
     * Get all accounts for a network
     */
    public java.util.List<Account> getAccountsByNetwork(String network) {
        return accountRepository.findByNetwork(network);
    }
}

