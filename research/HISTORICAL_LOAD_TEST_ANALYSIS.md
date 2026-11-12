# Analysis of Historical M3R Load-Testing Data

**Document:** `research/HISTORICAL_LOAD_TEST_ANALYSIS.md`  
**Date:** November 12, 2026  
**Source Utility:** `M3RLoadTest.java`  
**Dataset:** Normalized log files (`m3r_loadtest_*.txt`)  
**Normalized CSV:** `Node_Software/research/historical_loadtest_normalized.csv` (323 transactions)  

---

## 1. Executive Summary & Experimental Context

Historical load tests conducted on June 18, 2026, measured the throughput and latency of the M3R node implementation running locally on port 3000 connected to consensus peers on ports 4000 and 5000. 

A rigorous analysis of all 6 log files reveals a critical divergence between sequential transaction benchmarks and multi-threaded benchmarks:
- **Sequential Pipeline (`THREADS = 1` or paced dispatch):** Achieved **12.71 tx/sec** with **100% success rate** (100/100 accepted) and **155 ms average confirmation latency**.
- **Concurrent Dispatch (`THREADS = 2` without pipelining):** Reported throughput plummeted to **0.25 – 0.40 tx/sec** with high rejection rates (44% to 49% initial rejections) and average latency inflated to **4,669 – 7,484 ms**.

The investigation uncovers that this performance degradation was **not** caused by node consensus or cryptographic bottlenecks, but by a client-side benchmarking flaw: concurrent threads dispatched pre-signed sequential nonces ($N, N+1$) simultaneously before prior transactions had committed to the database ledger, triggering the node's strict anti-double-spending nonce rejection and a 15-second client backoff loop.

---

## 2. Comprehensive Historical Test Results

| Log File | Node Endpoint | Total TXs | Accepted | Rejected | Errors | Reported TPS | Avg Latency (ms) | Min Lat (ms) | Max Lat (ms) | Primary Failure Mode |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| `213557.txt` | `localhost:3000/mainnet` | 100 | 100 | 0 | 0 | **12.71** | **155** | 113 | 271 | None (Sequential Success) |
| `214008.txt` | `localhost:3000/mainnet` | 50 | 50 | 22 | 0 | **0.40** | **4,669** | 57 | 15,024 | Nonce Out-of-Order Race |
| `214853.txt` | `localhost:3000/mainnet` | 50 | 50 | 49 | 0 | **0.25** | **7,484** | 59 | 15,037 | Nonce Out-of-Order Race |
| `215714.txt` | `localhost:3000/mainnet` | 1 | 1 | 0 | 0 | 0.73 | 1,359 | 1,359 | 1,359 | Cold-start / JVM warmup |
| `215806.txt` | `localhost:3000/mainnet` | 1 | 1 | 0 | 0 | 6.54 | 149 | 149 | 149 | Single transaction probe |
| `215949.txt` | `localhost:3000/mainnet` | 50 | 50 | 0 | 0 | **7.91** | **250** | 143 | 425 | None (Sequential Success) |

---

## 3. Root Cause Investigation of Transaction Rejections

In `m3r_loadtest_20260618_214008.txt` and `214853.txt`, dozens of transaction lines display:
```
❌ TX#002 | nonce=38 | 15011ms | REJECTED | hash=message | Invalid nonce (tx=38, expected=37, ledger=36)
❌ TX#003 | nonce=39 | 15021ms | REJECTED | hash=message | Invalid nonce (tx=39, expected=38, ledger=37)
```
Followed seconds later by successful retries:
```
✅ TX#002 | nonce=38 | 97ms | ACCEPTED | hash=f2a5081e5c2c3363… | Consensus accepted: yesVotes=3/3
```

### Analysis of the Concurrency Mechanism
1. **Pre-signed Nonce Array:** `M3RLoadTest.java` pre-signed all transactions upfront with strictly incremental nonces ($nonce = startNonce + i$) under a single sender account (`NQWWb4huPaqUncdJHFm2FHFjTo1qqLskuq`).
2. **Multi-Threaded Pool (`THREADS = 2`):**
   ```java
   // In M3RLoadTest.java:
   static final int THREADS = 2;
   ExecutorService pool = Executors.newFixedThreadPool(THREADS);
   ```
   Thread 1 took `TX#001` (nonce=37) and posted to `http://localhost:3000/mainnet/tx/submit`.
   Simultaneously, Thread 2 took `TX#002` (nonce=38) and posted to the same endpoint.
3. **Database Ledger Nonce Verification:**
   `WalletService.executeTransaction` locked the sender account and checked:
   $$Nonce_{expected} = Nonce_{ledger} + 1$$
   Since Thread 1's transaction had not yet completed MySQL transaction commit, $Nonce_{ledger}$ was still 36 ($Nonce_{expected} = 37$).
   Thread 2's transaction ($Nonce_{tx} = 38$) was immediately rejected with:
   $$\text{"Invalid nonce (tx=38, expected=37, ledger=36)"}$$
4. **The 15-Second Latency Inflation:**
   In `M3RLoadTest.java`, when a rejection occurred, the client entered a sleep-retry loop:
   ```java
   if (!ok && msg != null && msg.contains("Invalid nonce")) {
       int retries = 0;
       while (retries < maxRetries && !ok) {
           retries++;
           Thread.sleep(1000); // 1-second backoff
           ...
       }
   }
   ```
   Furthermore, Spring Boot HTTP connection timeouts or slot waits caused the measured round-trip time for rejected attempts to reach **15,011 – 15,037 ms**.
5. **Impact on Metrics:** When averaging latencies across accepted and rejected transactions, the mathematical mean was distorted from ~120 ms up to 7,484 ms, and the measured throughput dropped by **98%** (from 12.71 to 0.25 tx/sec).

---

## 4. Latency Distribution Analysis (Normalized Dataset)

Filtering the 323 recorded transactions in `historical_loadtest_normalized.csv`:

```
Accepted Latency Distribution (Paced / Sequential):
  Min Latency     : 57 ms
  Median (P50)    : 146 ms
  P90             : 256 ms
  P99             : 415 ms
  Max Accepted    : 425 ms

Rejected Out-of-Order Latency (Client Backoff / Slot Timeout):
  Min Latency     : 15,011 ms
  Max Latency     : 15,037 ms
  Mean Duration   : 15,023 ms
```

When evaluated strictly on valid sequential submissions, the M3R node demonstrates consistent confirmation times of **100 – 250 ms** across 3 consensus peers.

---

## 5. Methodological Recommendations for Future Benchmarks

To obtain scientifically rigorous and reproducible benchmark results for publication:
1. **Multi-Account Load Generation:** Benchmarks testing concurrent throughput must allocate distinct sender accounts to each worker thread to prevent artificial account nonce contention.
2. **Mempool Nonce Pipelining:** The node's `MempoolService` should track pending nonces per sender account so that valid incremental nonces ($N, N+1, N+2$) can be queued in mempool before the previous block is committed to MySQL.
3. **Paced Arrival Rates:** Use open-loop load injection with fixed target rates rather than closed-loop thread pools.
