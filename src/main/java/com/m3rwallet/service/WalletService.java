package com.m3rwallet.service;

import com.m3rwallet.entity.Account;
import com.m3rwallet.entity.Escrow;
import com.m3rwallet.entity.Transaction;
import com.m3rwallet.util.AddressUtil;
import com.m3rwallet.util.CryptoUtil;
import com.m3rwallet.util.TxDecoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
@Slf4j
public class WalletService {
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final EscrowService escrowService;
    private final TxLedgerService txLedgerService;

    @Value("${app.broadcast-fee:100}")
    private long broadcastFee;

    @Value("${app.percent-fee-bps:100}")
    private int percentFeeBps;

    @Value("${app.faucet.max-amount:100000}")
    private long maxFaucetAmount;

    @Value("${app.genesis-address}")
    private String genesisAddress;

    @Transactional
    public void initializeNetworks() {
        String genesisHex = AddressUtil.decodeBase58ToHex20(genesisAddress);
        if (genesisHex != null) {
            BigInteger genesisBalance = new BigInteger("100000000"); // 1,000,000 BDT
            for (String network : new String[]{"mainnet", "testnet", "legacy"}) {
                accountService.initializeGenesis(network, genesisHex, genesisBalance);
            }
        } else {
            log.error("[GENESIS] Failed to resolve genesis address");
        }
    }

    public Account getAccountInfo(String network, String addr) {
        String normalizedAddr = AddressUtil.resolveToHex20(addr);
        if (normalizedAddr == null || normalizedAddr.isEmpty()) {
            return null;
        }

        Account account = accountService.getAccount(network, normalizedAddr);
        log.info("[ACCOUNT][{}] {} => balance={} nonce={}", network, normalizedAddr,
                account != null ? account.getBalance() : "0",
                account != null ? account.getNonce() : "0");
        return account;
    }

    public long getBroadcastFee() {
        return broadcastFee;
    }

    public int getPercentFeeBps() {
        return percentFeeBps;
    }

    @Transactional
    public void executeFaucet(String network, String addr, BigInteger requestedAmount) {
        String normalizedAddr = AddressUtil.resolveToHex20(addr);
        if (normalizedAddr == null || normalizedAddr.isBlank()) {
            throw new IllegalArgumentException("Invalid address");
        }

        BigInteger maxAmount = BigInteger.valueOf(maxFaucetAmount);
        BigInteger amount = requestedAmount.compareTo(maxAmount) > 0 ? maxAmount : requestedAmount;

        Account state = accountService.getAccountForUpdate(network, normalizedAddr);
        if (state == null) {
            state = new Account();
            state.setBalance("0");
            state.setNonce(0L);
        }
        BigInteger currentBal = new BigInteger(state.getBalance());
        BigInteger newBal = currentBal.add(amount);
        accountService.setAccount(network, normalizedAddr, newBal, state.getNonce());
        log.info("[FAUCET][{}] {} += {} => {}", network, normalizedAddr, amount, newBal);
    }

    @Transactional
    public String submitTransaction(String network, String rawTxHex, String pubKeyCompressedHex) {
        return executeTransaction(network, rawTxHex, pubKeyCompressedHex);
    }

    @Transactional(readOnly = true)
    public String validateTransaction(String network, String rawTxHex, String pubKeyCompressedHex) {
        TxDecoder.ParsedTx tx = parseAndVerifyTransaction(rawTxHex, pubKeyCompressedHex);
        String txHash = computeTxHash(rawTxHex);
        validateTransactionState(network, tx, txHash);
        return txHash;
    }

    @Transactional
    public String executeTransaction(String network, String rawTxHex, String pubKeyCompressedHex) {
        TxDecoder.ParsedTx tx = parseAndVerifyTransaction(rawTxHex, pubKeyCompressedHex);
        String txHash = computeTxHash(rawTxHex);

        // ---- Duplicate check ----
        Transaction existingTx = transactionService.getTx(network, txHash);
        if (existingTx != null) {
            return txHash; // Already known — idempotent
        }

        // ---- Load sender account ----
        Account fromState = accountService.getAccountForUpdate(network, tx.getFromAddr20());
        if (fromState == null) {
            fromState = new Account();
            fromState.setBalance("0");
            fromState.setNonce(0L);
        }

        BigInteger nonceFromUser = tx.getNonce();
        if (nonceFromUser.compareTo(BigInteger.valueOf(fromState.getNonce())) <= 0) {
            throw new IllegalArgumentException(
                    "Nonce too low (tx=" + nonceFromUser + ", ledger=" + fromState.getNonce() + ")");
        }

        BigInteger currentBal = new BigInteger(fromState.getBalance());
        long now = System.currentTimeMillis();

        return executeParsedTransaction(network, tx, txHash, fromState, currentBal, nonceFromUser, now);
    }

    private TxDecoder.ParsedTx parseAndVerifyTransaction(String rawTxHex, String pubKeyCompressedHex) {
        TxDecoder.ParsedTx tx = TxDecoder.parseTx(rawTxHex);

        if (!tx.isValid()) {
            throw new IllegalArgumentException("Decode failed: " + tx.getError());
        }

        try {
            byte[] pubKeyBuffer = Hex.decodeHex(pubKeyCompressedHex);
            byte[] pubKeyHash = CryptoUtil.keccak256(pubKeyBuffer);
            byte[] derivedFromAddrBytes = Arrays.copyOfRange(pubKeyHash, 12, 32);
            String derivedFromAddr = Hex.encodeHexString(derivedFromAddrBytes);

            if (!derivedFromAddr.equals(tx.getFromAddr20())) {
                throw new IllegalArgumentException("Invalid public key (derived address mismatch).");
            }

            byte[] msgHashBytes = CryptoUtil.keccak256(tx.getEncodeForSigning());
            boolean validSig = CryptoUtil.verifySignature(msgHashBytes, tx.getSignature(), pubKeyBuffer);

            if (!validSig) {
                throw new IllegalArgumentException("Digital signature verification failed.");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Cryptographic verification failed: " + e.getMessage());
        }

        return tx;
    }

    private String computeTxHash(String rawTxHex) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(Hex.decodeHex(rawTxHex));
            return Hex.encodeHexString(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException("Hashing failed.");
        }
    }

    private void validateTransactionState(String network, TxDecoder.ParsedTx tx, String txHash) {
        Transaction existingTx = transactionService.getTx(network, txHash);
        if (existingTx != null) {
            return;
        }

        Account fromState = accountService.getAccount(network, tx.getFromAddr20());
        if (fromState == null) {
            fromState = new Account();
            fromState.setBalance("0");
            fromState.setNonce(0L);
        }

        BigInteger nonceFromUser = tx.getNonce();
        if (nonceFromUser.compareTo(BigInteger.valueOf(fromState.getNonce())) <= 0) {
            throw new IllegalArgumentException(
                    "Nonce too low (tx=" + nonceFromUser + ", ledger=" + fromState.getNonce() + ")");
        }

        BigInteger currentBal = new BigInteger(fromState.getBalance());

        validateParsedTransactionState(network, tx, currentBal);
    }

    private void validateParsedTransactionState(String network, TxDecoder.ParsedTx tx, BigInteger currentBal) {
        if (tx.getType() == 0 || tx.getType() == 1) {
            BigInteger totalCost = tx.getParsedPayload().getAmount().add(tx.getFee());
            if (currentBal.compareTo(totalCost) < 0) {
                throw new IllegalArgumentException("Insufficient funds");
            }
        }

        if (tx.getType() == 1) {
            Escrow existingEscrow = escrowService.getEscrow(network, tx.getParsedPayload().getEscrowId());
            if (existingEscrow != null) {
                throw new IllegalArgumentException("Escrow ID exists");
            }
            return;
        }

        if (tx.getType() == 2 || tx.getType() == 3) {
            String escrowId = tx.getParsedPayload().getEscrowId();
            Escrow escrow = escrowService.getEscrow(network, escrowId);
            if (escrow == null) {
                throw new IllegalArgumentException("Escrow not found");
            }

            if (currentBal.compareTo(tx.getFee()) < 0) {
                throw new IllegalArgumentException("Insufficient fee");
            }

            if (tx.getType() == 2) {
                if (!tx.getFromAddr20().equals(escrow.getBuyer())
                        && !tx.getFromAddr20().equals(escrow.getArbiter())) {
                    throw new IllegalArgumentException("Unauthorized");
                }
                if (!tx.getParsedPayload().getToAddr().equals(escrow.getSeller())
                        || !tx.getParsedPayload().getAmount().toString().equals(escrow.getAmount())) {
                    throw new IllegalArgumentException("Release data mismatch");
                }
            } else {
                if (!tx.getFromAddr20().equals(escrow.getSeller())
                        && !tx.getFromAddr20().equals(escrow.getArbiter())) {
                    throw new IllegalArgumentException("Unauthorized");
                }
                if (!tx.getParsedPayload().getToAddr().equals(escrow.getBuyer())
                        || !tx.getParsedPayload().getAmount().toString().equals(escrow.getAmount())) {
                    throw new IllegalArgumentException("Refund data mismatch");
                }
            }
            return;
        }

        if (tx.getType() != 0) {
            throw new IllegalArgumentException("Unknown TxType: " + tx.getType());
        }
    }

    private String executeParsedTransaction(String network, TxDecoder.ParsedTx tx, String txHash,
                                            Account fromState, BigInteger currentBal,
                                            BigInteger nonceFromUser, long now) {

        // ====================================================================
        // TRANSFER (type = 0)
        // ====================================================================
        if (tx.getType() == 0) {
            BigInteger amount = tx.getParsedPayload().getAmount();
            BigInteger fee = tx.getFee();
            BigInteger totalCost = amount.add(fee);

            if (currentBal.compareTo(totalCost) < 0) {
                throw new IllegalArgumentException("Insufficient funds");
            }

            // Update sender
            fromState.setBalance(currentBal.subtract(totalCost).toString());
            fromState.setNonce(nonceFromUser.longValue());
            accountService.setAccount(network, tx.getFromAddr20(),
                    new BigInteger(fromState.getBalance()), fromState.getNonce());

            // Update recipient
            String toAddr = tx.getParsedPayload().getToAddr();
            Account toState = accountService.getAccountForUpdate(network, toAddr);
            if (toState == null) {
                toState = new Account();
                toState.setBalance("0");
                toState.setNonce(0L);
            }
            BigInteger toCurBal = new BigInteger(toState.getBalance());
            accountService.setAccount(network, toAddr, toCurBal.add(amount), toState.getNonce());

            // Ledger: sender sees SEND, recipient sees RECEIVE
            String amtStr = amount.toString();
            String feeStr = fee.toString();
            txLedgerService.recordEntry(network, txHash,
                    tx.getFromAddr20(), "SEND",
                    amtStr, feeStr, tx.getFromAddr20(), toAddr, null, now);
            txLedgerService.recordEntry(network, txHash,
                    toAddr, "RECEIVE",
                    amtStr, "0", tx.getFromAddr20(), toAddr, null, now);

        // ====================================================================
        // ESCROW_CREATE (type = 1)
        // ====================================================================
        } else if (tx.getType() == 1) {
            BigInteger amount = tx.getParsedPayload().getAmount();
            BigInteger fee = tx.getFee();
            BigInteger totalCost = amount.add(fee);

            if (currentBal.compareTo(totalCost) < 0) {
                throw new IllegalArgumentException("Insufficient funds");
            }

            Escrow existingEscrow = escrowService.getEscrowForUpdate(
                    network, tx.getParsedPayload().getEscrowId());
            if (existingEscrow != null) {
                throw new IllegalArgumentException("Escrow ID exists");
            }

            // Deduct from buyer
            fromState.setBalance(currentBal.subtract(totalCost).toString());
            fromState.setNonce(nonceFromUser.longValue());
            accountService.setAccount(network, tx.getFromAddr20(),
                    new BigInteger(fromState.getBalance()), fromState.getNonce());

            String buyer  = tx.getParsedPayload().getBuyer();
            String seller = tx.getParsedPayload().getSeller();
            String arbiter = tx.getParsedPayload().getArbiter();
            String escrowId = tx.getParsedPayload().getEscrowId();
            String amtStr = amount.toString();
            String feeStr = fee.toString();

            escrowService.setEscrow(
                    network, escrowId,
                    buyer, seller, arbiter,
                    amtStr,
                    java.util.Map.of(
                            "amount",  amtStr,
                            "buyer",   buyer,
                            "seller",  seller,
                            "arbiter", arbiter
                    )
            );

            // Ledger: buyer initiated the escrow
            txLedgerService.recordEntry(network, txHash,
                    buyer, "ESCROW_CREATE",
                    amtStr, feeStr, buyer, seller, escrowId, now);

            // Ledger: seller is informed that funds are now held in escrow for them
            txLedgerService.recordEntry(network, txHash,
                    seller, "ESCROW_RECEIVE",
                    amtStr, "0", buyer, seller, escrowId, now);

            // Ledger: arbiter is notified (zero-address guard is inside recordEntry)
            txLedgerService.recordEntry(network, txHash,
                    arbiter, "ESCROW_ARBITER",
                    amtStr, "0", buyer, seller, escrowId, now);

        // ====================================================================
        // ESCROW_RELEASE (type = 2)  — buyer or arbiter releases to seller
        // ====================================================================
        } else if (tx.getType() == 2) {
            String escrowId = tx.getParsedPayload().getEscrowId();
            Escrow escrow = escrowService.getEscrowForUpdate(network, escrowId);
            if (escrow == null) {
                throw new IllegalArgumentException("Escrow not found");
            }
            if (!tx.getFromAddr20().equals(escrow.getBuyer())
                    && !tx.getFromAddr20().equals(escrow.getArbiter())) {
                throw new IllegalArgumentException("Unauthorized");
            }
            if (!tx.getParsedPayload().getToAddr().equals(escrow.getSeller())
                    || !tx.getParsedPayload().getAmount().toString().equals(escrow.getAmount())) {
                throw new IllegalArgumentException("Release data mismatch");
            }

            BigInteger fee = tx.getFee();
            if (currentBal.compareTo(fee) < 0) {
                throw new IllegalArgumentException("Insufficient fee");
            }

            // Deduct fee from releasor
            fromState.setBalance(currentBal.subtract(fee).toString());
            fromState.setNonce(nonceFromUser.longValue());
            accountService.setAccount(network, tx.getFromAddr20(),
                    new BigInteger(fromState.getBalance()), fromState.getNonce());

            // Credit seller
            String sellerAddr = tx.getParsedPayload().getToAddr();
            Account toState = accountService.getAccountForUpdate(network, sellerAddr);
            if (toState == null) {
                toState = new Account();
                toState.setBalance("0");
                toState.setNonce(0L);
            }
            BigInteger toCurBal = new BigInteger(toState.getBalance());
            accountService.setAccount(network, sellerAddr,
                    toCurBal.add(new BigInteger(escrow.getAmount())), toState.getNonce());

            escrowService.deleteEscrow(network, escrowId);

            String amtStr = escrow.getAmount();
            String feeStr = fee.toString();

            // Ledger: the releasor (buyer or arbiter) sees ESCROW_RELEASE
            txLedgerService.recordEntry(network, txHash,
                    tx.getFromAddr20(), "ESCROW_RELEASE",
                    amtStr, feeStr, tx.getFromAddr20(), sellerAddr, escrowId, now);

            // Ledger: seller sees the incoming payment
            txLedgerService.recordEntry(network, txHash,
                    sellerAddr, "ESCROW_RELEASE_RECEIVED",
                    amtStr, "0", tx.getFromAddr20(), sellerAddr, escrowId, now);

        // ====================================================================
        // ESCROW_REFUND (type = 3)  — seller or arbiter refunds to buyer
        // ====================================================================
        } else if (tx.getType() == 3) {
            String escrowId = tx.getParsedPayload().getEscrowId();
            Escrow escrow = escrowService.getEscrowForUpdate(network, escrowId);
            if (escrow == null) {
                throw new IllegalArgumentException("Escrow not found");
            }
            if (!tx.getFromAddr20().equals(escrow.getSeller())
                    && !tx.getFromAddr20().equals(escrow.getArbiter())) {
                throw new IllegalArgumentException("Unauthorized");
            }
            if (!tx.getParsedPayload().getToAddr().equals(escrow.getBuyer())
                    || !tx.getParsedPayload().getAmount().toString().equals(escrow.getAmount())) {
                throw new IllegalArgumentException("Refund data mismatch");
            }

            BigInteger fee = tx.getFee();
            if (currentBal.compareTo(fee) < 0) {
                throw new IllegalArgumentException("Insufficient fee");
            }

            // Deduct fee from refunder
            fromState.setBalance(currentBal.subtract(fee).toString());
            fromState.setNonce(nonceFromUser.longValue());
            accountService.setAccount(network, tx.getFromAddr20(),
                    new BigInteger(fromState.getBalance()), fromState.getNonce());

            // Credit buyer
            String buyerAddr = tx.getParsedPayload().getToAddr();
            Account toState = accountService.getAccountForUpdate(network, buyerAddr);
            if (toState == null) {
                toState = new Account();
                toState.setBalance("0");
                toState.setNonce(0L);
            }
            BigInteger toCurBal = new BigInteger(toState.getBalance());
            accountService.setAccount(network, buyerAddr,
                    toCurBal.add(new BigInteger(escrow.getAmount())), toState.getNonce());

            escrowService.deleteEscrow(network, escrowId);

            String amtStr = escrow.getAmount();
            String feeStr = fee.toString();

            // Ledger: the refunder (seller or arbiter) sees ESCROW_REFUND
            txLedgerService.recordEntry(network, txHash,
                    tx.getFromAddr20(), "ESCROW_REFUND",
                    amtStr, feeStr, tx.getFromAddr20(), buyerAddr, escrowId, now);

            // Ledger: buyer sees the incoming refund
            txLedgerService.recordEntry(network, txHash,
                    buyerAddr, "ESCROW_REFUND_RECEIVED",
                    amtStr, "0", tx.getFromAddr20(), buyerAddr, escrowId, now);

        } else {
            throw new IllegalArgumentException("Unknown TxType: " + tx.getType());
        }

        // ---- Persist transaction status ----
        transactionService.setTx(network, txHash, "CONFIRMED");
        log.info("[SUBMIT][{}] Accepted: {}", network, txHash);
        return txHash;
    }

    public String getTxStatus(String network, String txHash) {
        Transaction tx = transactionService.getTx(network, txHash);
        return tx != null ? tx.getStatus() : "UNKNOWN";
    }
}
