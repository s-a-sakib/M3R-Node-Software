package com.m3rwallet.service;

import com.m3rwallet.entity.Account;
import com.m3rwallet.entity.Transaction;
import com.m3rwallet.repository.AccountRepository;
import com.m3rwallet.repository.TransactionRepository;
import com.m3rwallet.util.AddressUtil;
import com.m3rwallet.util.CryptoUtil;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransactionDoubleSpendingTest {

    private WalletService walletService;
    private AccountService mockAccountService;
    private TransactionService mockTxService;
    private EscrowService mockEscrowService;
    private TxLedgerService mockTxLedgerService;
    private NodeIdentityService mockNodeIdentityService;
    private AccountRepository mockAccountRepo;
    private TransactionRepository mockTxRepo;

    private byte[] testPrivKey;
    private byte[] testPubKeyCompressed;
    private byte[] testFromAddr20;
    private String testFromAddrHex;

    @BeforeEach
    void setup() {
        mockAccountService = Mockito.mock(AccountService.class);
        mockTxService = Mockito.mock(TransactionService.class);
        mockEscrowService = Mockito.mock(EscrowService.class);
        mockTxLedgerService = Mockito.mock(TxLedgerService.class);
        mockNodeIdentityService = Mockito.mock(NodeIdentityService.class);
        mockAccountRepo = Mockito.mock(AccountRepository.class);
        mockTxRepo = Mockito.mock(TransactionRepository.class);

        when(mockNodeIdentityService.getAddressOrUnknown()).thenReturn("node_broadcaster_addr");

        walletService = new WalletService(
                mockAccountService,
                mockTxService,
                mockEscrowService,
                mockTxLedgerService,
                mockNodeIdentityService
        );

        ReflectionTestUtils.setField(walletService, "transactionRepository", mockTxRepo);
        ReflectionTestUtils.setField(walletService, "broadcastFee", 100L);
        ReflectionTestUtils.setField(walletService, "percentFeeBps", 100);

        // Generate secp256k1 test keypair
        ECParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        testPrivKey = new byte[32];
        new SecureRandom().nextBytes(testPrivKey);
        BigInteger d = new BigInteger(1, testPrivKey);
        org.bouncycastle.math.ec.ECPoint q = spec.getG().multiply(d).normalize();
        testPubKeyCompressed = q.getEncoded(true);

        // Derive 20-byte address from keccak256
        byte[] pubHash = CryptoUtil.keccak256(testPubKeyCompressed);
        testFromAddr20 = Arrays.copyOfRange(pubHash, 12, 32);
        testFromAddrHex = Hex.encodeHexString(testFromAddr20);
    }

    private String buildRawTx(byte[] privKey, byte[] pubKey, byte[] fromAddr20, byte[] toAddr20,
                              long amount, long fee, long nonce, int chainId) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            // U16 version = 1
            body.write(new byte[]{0x00, 0x01});
            // U32 chainId
            body.write(java.nio.ByteBuffer.allocate(4).putInt(chainId).array());
            // txType = 0 (TRANSFER)
            body.write(0x00);
            // U64 nonce
            body.write(java.nio.ByteBuffer.allocate(8).putLong(nonce).array());
            // U64 fee
            body.write(java.nio.ByteBuffer.allocate(8).putLong(fee).array());
            // U64 timestamp
            body.write(java.nio.ByteBuffer.allocate(8).putLong(System.currentTimeMillis() / 1000L).array());
            // fromAddr20
            body.write(fromAddr20);

            // payload: toAddr20 + U64 amount
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(toAddr20);
            payload.write(java.nio.ByteBuffer.allocate(8).putLong(amount).array());
            byte[] payloadBytes = payload.toByteArray();

            // VarBytes payload
            body.write(java.nio.ByteBuffer.allocate(4).putInt(payloadBytes.length).array());
            body.write(payloadBytes);

            // memo: empty VarBytes (length 0)
            body.write(java.nio.ByteBuffer.allocate(4).putInt(0).array());
            // sigScheme: 1 byte (0x01 = ECDSA secp256k1)
            body.write(0x01);

            byte[] encodeForSigning = body.toByteArray();

            // Sign encodeForSigning with secp256k1
            byte[] msgHash = CryptoUtil.keccak256(encodeForSigning);
            ECParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
            ECDomainParameters domain = new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH());
            ECDSASigner signer = new ECDSASigner();
            signer.init(true, new ECPrivateKeyParameters(new BigInteger(1, privKey), domain));
            BigInteger[] sig = signer.generateSignature(msgHash);

            ASN1EncodableVector v = new ASN1EncodableVector();
            v.add(new ASN1Integer(sig[0]));
            v.add(new ASN1Integer(sig[1]));
            byte[] derSig = new DERSequence(v).getEncoded();

            // rawTx = body + VarBytes(derSig)
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            raw.write(encodeForSigning);
            raw.write(java.nio.ByteBuffer.allocate(4).putInt(derSig.length).array());
            raw.write(derSig);

            return Hex.encodeHexString(raw.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Valid secp256k1 signed transaction parses and passes state validation")
    void validTransactionPasses() {
        byte[] toAddr = new byte[20];
        Arrays.fill(toAddr, (byte) 0x02);

        String rawTxHex = buildRawTx(testPrivKey, testPubKeyCompressed, testFromAddr20, toAddr,
                1000L, 100L, 1L, 1);
        String pubKeyHex = Hex.encodeHexString(testPubKeyCompressed);

        Account acc = new Account();
        acc.setNetwork("mainnet");
        acc.setAddress(testFromAddrHex);
        acc.setBalance("50000");
        acc.setNonce(0L);

        when(mockAccountService.getAccount(eq("mainnet"), eq(testFromAddrHex))).thenReturn(acc);
        when(mockAccountService.getAccountForUpdate(eq("mainnet"), eq(testFromAddrHex))).thenReturn(acc);

        String txHash = walletService.validateTransaction("mainnet", rawTxHex, pubKeyHex);
        assertNotNull(txHash);
        assertFalse(txHash.isBlank());
    }

    @Test
    @DisplayName("Rejects transaction with invalid secp256k1 signature")
    void rejectsInvalidSignature() {
        byte[] toAddr = new byte[20];
        byte[] wrongPrivKey = new byte[32];
        Arrays.fill(wrongPrivKey, (byte) 0x99);

        // Sign with wrong private key
        String rawTxHex = buildRawTx(wrongPrivKey, testPubKeyCompressed, testFromAddr20, toAddr,
                1000L, 100L, 1L, 1);
        String pubKeyHex = Hex.encodeHexString(testPubKeyCompressed);

        assertThrows(IllegalArgumentException.class, () ->
                walletService.validateTransaction("mainnet", rawTxHex, pubKeyHex));
    }

    @Test
    @DisplayName("Rejects transaction when derived public key address mismatches fromAddr")
    void rejectsMismatchedSenderAddress() {
        byte[] toAddr = new byte[20];
        byte[] spoofedFrom = new byte[20];
        Arrays.fill(spoofedFrom, (byte) 0x77);

        String rawTxHex = buildRawTx(testPrivKey, testPubKeyCompressed, spoofedFrom, toAddr,
                1000L, 100L, 1L, 1);
        String pubKeyHex = Hex.encodeHexString(testPubKeyCompressed);

        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                walletService.validateTransaction("mainnet", rawTxHex, pubKeyHex));
        assertTrue(ex.getMessage().contains("derived address mismatch"));
    }

    @Test
    @DisplayName("Rejects transaction when nonce does not equal ledger nonce + 1")
    void rejectsInvalidNonce() {
        byte[] toAddr = new byte[20];
        // Nonce 5 when ledger nonce is 0 -> should expect 1
        String rawTxHex = buildRawTx(testPrivKey, testPubKeyCompressed, testFromAddr20, toAddr,
                1000L, 100L, 5L, 1);
        String pubKeyHex = Hex.encodeHexString(testPubKeyCompressed);

        Account acc = new Account();
        acc.setNetwork("mainnet");
        acc.setAddress(testFromAddrHex);
        acc.setBalance("50000");
        acc.setNonce(0L);

        when(mockAccountService.getAccount(eq("mainnet"), eq(testFromAddrHex))).thenReturn(acc);
        when(mockAccountService.getAccountForUpdate(eq("mainnet"), eq(testFromAddrHex))).thenReturn(acc);

        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                walletService.executeTransaction("mainnet", rawTxHex, pubKeyHex));
        assertTrue(ex.getMessage().contains("Invalid nonce"));
    }

    @Test
    @DisplayName("Rejects transaction when balance is insufficient to cover amount + fee")
    void rejectsInsufficientBalance() {
        byte[] toAddr = new byte[20];
        String rawTxHex = buildRawTx(testPrivKey, testPubKeyCompressed, testFromAddr20, toAddr,
                10000L, 500L, 1L, 1);
        String pubKeyHex = Hex.encodeHexString(testPubKeyCompressed);

        Account acc = new Account();
        acc.setNetwork("mainnet");
        acc.setAddress(testFromAddrHex);
        acc.setBalance("5000"); // Only 5000 available, requires 10500
        acc.setNonce(0L);

        when(mockAccountService.getAccount(eq("mainnet"), eq(testFromAddrHex))).thenReturn(acc);
        when(mockAccountService.getAccountForUpdate(eq("mainnet"), eq(testFromAddrHex))).thenReturn(acc);

        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                walletService.executeTransaction("mainnet", rawTxHex, pubKeyHex));
        assertTrue(ex.getMessage().contains("Insufficient funds"));
    }

    @Test
    @DisplayName("Concurrent execution: two conflicting transactions with identical nonce allow only ONE to succeed")
    void concurrentTransactionsOnlyOneConsumesNonce() throws Exception {
        byte[] toAddr1 = new byte[20];
        Arrays.fill(toAddr1, (byte) 0x11);
        byte[] toAddr2 = new byte[20];
        Arrays.fill(toAddr2, (byte) 0x22);

        // Two distinct transactions, both claiming nonce = 1
        String tx1 = buildRawTx(testPrivKey, testPubKeyCompressed, testFromAddr20, toAddr1, 1000L, 100L, 1L, 1);
        String tx2 = buildRawTx(testPrivKey, testPubKeyCompressed, testFromAddr20, toAddr2, 2000L, 100L, 1L, 1);
        String pubKeyHex = Hex.encodeHexString(testPubKeyCompressed);

        // Thread-safe in-memory account simulating row lock
        Account realAccount = new Account();
        realAccount.setNetwork("mainnet");
        realAccount.setAddress(testFromAddrHex);
        realAccount.setBalance("50000");
        realAccount.setNonce(0L);

        java.util.concurrent.locks.ReentrantLock dbPessimisticRowLock = new java.util.concurrent.locks.ReentrantLock(true);

        when(mockAccountService.getAccountForUpdate(eq("mainnet"), eq(testFromAddrHex))).thenAnswer(inv -> {
            dbPessimisticRowLock.lock();
            Account snapshot = new Account();
            snapshot.setNetwork(realAccount.getNetwork());
            snapshot.setAddress(realAccount.getAddress());
            snapshot.setBalance(realAccount.getBalance());
            snapshot.setNonce(realAccount.getNonce());
            return snapshot;
        });

        doAnswer(inv -> {
            try {
                String addr = inv.getArgument(2);
                BigInteger newBal = inv.getArgument(3);
                long newNonce = inv.getArgument(4);
                if (testFromAddrHex.equalsIgnoreCase(addr)) {
                    realAccount.setBalance(newBal.toString());
                    realAccount.setNonce(newNonce);
                }
                return null;
            } finally {
                if (dbPessimisticRowLock.isHeldByCurrentThread()) {
                    dbPessimisticRowLock.unlock();
                }
            }
        }).when(mockAccountService).updateAccountInPlace(any(), anyString(), anyString(), any(), anyLong());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<?> f1 = pool.submit(() -> {
            try {
                barrier.await();
                walletService.executeTransaction("mainnet", tx1, pubKeyHex);
                successCount.incrementAndGet();
            } catch (Exception e) {
                rejectedCount.incrementAndGet();
            } finally {
                if (dbPessimisticRowLock.isHeldByCurrentThread()) {
                    dbPessimisticRowLock.unlock();
                }
            }
        });

        Future<?> f2 = pool.submit(() -> {
            try {
                barrier.await();
                walletService.executeTransaction("mainnet", tx2, pubKeyHex);
                successCount.incrementAndGet();
            } catch (Exception e) {
                rejectedCount.incrementAndGet();
            } finally {
                if (dbPessimisticRowLock.isHeldByCurrentThread()) {
                    dbPessimisticRowLock.unlock();
                }
            }
        });

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        // Exactly one should succeed, exactly one should fail!
        assertEquals(1, successCount.get(), "Exactly one concurrent transaction should succeed");
        assertEquals(1, rejectedCount.get(), "Conflicting transaction must be rejected");
        assertEquals(1L, realAccount.getNonce(), "Nonce must only increment once");
    }
}
