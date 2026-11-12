# M3R — Post-Remediation Verification & Three-Node Recovery Audit

**Audit Date:** November 12, 2026  
**Auditor:** Independent Security & Consensus Audit  
**Target Repository:** `/home/noob_coder/Desktop/M3R_Coin`  
**Inspected Cluster:** Ports 3000, 4000, and 5000 (Localhost 3-Node Network)  
**Target Databases:** MySQL `m3rwallet_load_3000`, `m3rwallet_load_4000`, `m3rwallet_load_5000`  
**Scope:** Post-Remediation (F-01 through F-10) Verification, Live Process Audit, Consensus Durability, Validator Synchronization, Catch-Up Pathways, and Build Evidence Reconciliation.

---

## Executive Summary & Audit Posture

This audit was conducted strictly under read-only constraints: **no source code, configurations, database records, or active node processes were modified, restarted, or terminated**.

The objective of this inspection is to reconcile the codebase remediations (F-01 through F-10) with the **actual runtime state of the running 3-node cluster**, the **durability of consensus artifacts in MySQL**, and the **verifiable test evidence in the build artifacts**.

### Summary of Major Findings

1. **CRITICAL DISCREPANCY — Running Software vs. Repackaged Artifacts:**
   The three active Java processes on ports 3000, 4000, and 5000 are **NOT running the remediated software**. They were started at 12:01–12:02 UTC+6 using an older build (`SHA256: edc4acfca0cb2571...`). Their file descriptors hold deleted inode handles to that older binary. The newly remediated JAR (`SHA256: 09ae821ddaa3...`) was compiled and packaged at 15:38 UTC+6 and has **never been deployed or executed**.
2. **OPERATIONAL DURABILITY DEFECT (F-04 Only Partially Remediated in Practice):**
   Flyway migration `V002__Add_Historical_Snapshots_And_Certificates.sql` has **never been applied** to the running MySQL databases (`m3rwallet_load_3000`, `4000`, `5000`). Consequently, the columns `consensus_certificate` and `proposer_public_key` do not exist in the live `blocks` tables, and table `validator_snapshots` does not exist. While the source code implements relational persistence, **in the running deployment, consensus certificates exist only in an ephemeral in-memory cache and cannot survive a process restart**.
3. **BOOTSTRAP & VALIDATOR STATE ISOLATION:**
   In resolving finding F-03 (preventing unauthenticated peer stake manipulation), the node software completely rejects unknown peer validator announcements (`savePeerValidator` returns `false` and `/validator/receive` returns HTTP 403). Because the blockchain does not support on-chain staking transactions, a newly spawned node cannot discover or join the validator set dynamically via peer-to-peer sync; the validator set must be pre-seeded into MySQL or configured locally.
4. **SYNCHRONIZATION PATHWAYS HARDENED IN CODE:**
   Code auditing confirms that all externally reachable and scheduled block persistence pathways (`PeerSyncService`, `BlockSyncService`, `NodeSyncController`, `WalletController`) now delegate exclusively to `BlockBroadcastService.receiveBlock()`, eliminating direct unverified persistence. However, this safety invariant is only present in the repackaged JAR, not in the live running processes.
5. **TEST RECONCILIATION & DOCUMENTATION INCONSISTENCY:**
   The Surefire directory contains **22 XML report files** executing **160 tests** with **0 failures, 0 errors, and 0 skipped**. The build and packaging executed cleanly with Maven exit code 0. However, project documentation exhibits contradictory test metrics across documents (64 tests in `TEST_RESULTS.md`, 138 in `SECURITY_REMEDIATION_REPORT.md`, and 160 in `CRITICAL_SECURITY_REMEDIATION_REPORT.md`), reflecting separate historical audit milestones.

---

## 1. Verify the Actual Running Software

An inspection of active listeners, operating system process tables, open file descriptors, and HTTP endpoints was executed across ports 3000, 4000, and 5000.

### 1.1 Process Identity & Start Times

```
$ ss -tulpn | grep -E ':(3000|4000|5000)\b'
tcp   LISTEN 0   100   *:5000   *:*   users:(("java",pid=60564,fd=18))
tcp   LISTEN 0   100   *:3000   *:*   users:(("java",pid=60282,fd=16))
tcp   LISTEN 0   100   *:4000   *:*   users:(("java",pid=60462,fd=17))
```

| Port | PID | Process Start Time (Local) | Process Working Directory (`cwd`) | Command-Line Loaded JAR Argument |
|:---:|:---:|:---:|:---:|:---|
| **3000** | `60282` | `Thu Nov 12 12:01:56 2026` | `/home/noob_coder/Desktop/M3R_Coin/Node_Software` | `target/m3r-wallet-server-1.0.0.jar` |
| **4000** | `60462` | `Thu Nov 12 12:02:26 2026` | `/home/noob_coder/Desktop/M3R_Coin/Node_Software` | `target/m3r-wallet-server-1.0.0.jar` |
| **5000** | `60564` | `Thu Nov 12 12:02:41 2026` | `/home/noob_coder/Desktop/M3R_Coin/Node_Software` | `target/m3r-wallet-server-1.0.0.jar` |

### 1.2 Binary File Descriptors and Cryptographic Checksums

Inspecting the open file descriptors in `/proc/<PID>/fd/` revealed that each process maintains open file handles to an unlinked inode:
```
lr-x------ 1 noob_coder noob_coder 64 Nov 12 16:40 /proc/60282/fd/5 -> /home/noob_coder/Desktop/M3R_Coin/Node_Software/target/m3r-wallet-server-1.0.0.jar (deleted)
lr-x------ 1 noob_coder noob_coder 64 Nov 12 16:40 /proc/60462/fd/5 -> /home/noob_coder/Desktop/M3R_Coin/Node_Software/target/m3r-wallet-server-1.0.0.jar (deleted)
lr-x------ 1 noob_coder noob_coder 64 Nov 12 16:40 /proc/60564/fd/5 -> /home/noob_coder/Desktop/M3R_Coin/Node_Software/target/m3r-wallet-server-1.0.0.jar (deleted)
```

Direct cryptographic hashes were computed across the active file descriptors and the newly packaged target JAR:
```
SHA-256 Hashes:
edc4acfca0cb25710244891970a37ef2e5fad202d662dfc0d3b96f155e87106a  /proc/60282/fd/5 (Active Node 3000)
edc4acfca0cb25710244891970a37ef2e5fad202d662dfc0d3b96f155e87106a  /proc/60462/fd/5 (Active Node 4000)
edc4acfca0cb25710244891970a37ef2e5fad202d662dfc0d3b96f155e87106a  /proc/60564/fd/5 (Active Node 5000)
09ae821ddaa343747b9ea14445c44f44c607ff5f1db4f7dacc8165d705b32804  Node_Software/target/m3r-wallet-server-1.0.0.jar (Disk Artifact)
```

Bytecode structure analysis confirms:
- **Running Processes:** Built at `2026-11-12 12:01`. Class `PeerSyncService.class` size is 17,977 bytes. Classes `HistoricalValidatorSnapshot.class` and migration `V002__Add_Historical_Snapshots_And_Certificates.sql` are **completely absent**.
- **On-Disk Target JAR:** Built at `2026-11-12 15:38:48`. Class `PeerSyncService.class` size is 20,295 bytes. Contains `HistoricalValidatorSnapshot.class` (6,342 bytes) and `V002` Flyway migration (851 bytes).

### 1.3 Application Version & Runtime Exposure

Testing the active HTTP endpoints on port 3000:
- `GET /actuator/info` $\to$ HTTP 404 (Spring Actuator not enabled).
- `GET /api/version` $\to$ HTTP 404.
- `GET /api/node/status` $\to$ HTTP 200:
  ```json
  {"network":"api","nodeAddress":"NidXN8ZVWqqmN2vDt7UcJ6EAPJvDPYM6Qy","publicKeyCompressed":"02931fc921b50c612abbf756a1bfcc33cafd5c8c4d9f999d9749615ddb692731cb","privateKeyBacked":true,"mempoolPending":0,"pendingTxs":0,"latestBlockHeight":0,"status":"OK"}
  ```
  *(Note: Does not expose build commit hash or packaging timestamp).*
- `META-INF/MANIFEST.MF` declares: `Implementation-Title: M3R Wallet Server`, `Implementation-Version: 1.0.0`.

### Section 1 Verdict: **FAILED**
The three running processes are executing the pre-remediation build from 12:01. None of the F-01 through F-10 fixes are running in memory.

---

## 2. Verify Durable Consensus Certificates

A line-by-line inspection of `Block.java`, Flyway migration scripts, certificate repositories, and consensus vote services was performed.

### 2.1 Answers to Mandatory Questions

#### 1. Are certificates persisted in MySQL?
- **In Source Code:** **YES.** In [Block.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/entity/Block.java#L85-L87), field `consensusCertificate` is mapped as `@Column(name = "consensus_certificate", columnDefinition = "LONGTEXT")`. When proposed locally ([BlockScheduler.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/scheduler/BlockScheduler.java#L160-L165)) or received over P2P ([BlockBroadcastService.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/service/BlockBroadcastService.java#L338-L348)), the JSON string is assigned to the entity and saved via `blockRepo.save(block)`.
- **In Live Databases:** **NO (FAILED).** In `m3rwallet_load_3000`, `load_4000`, and `load_5000`, querying `DESCRIBE blocks;` confirms that the column `consensus_certificate` **does not exist**. Flyway migration `V002` was never executed against the running cluster. In the running nodes, certificates exist only in `BlockConsensusVoteService.certificateCache` (`ConcurrentHashMap`).

#### 2. Are certificates linked to immutable block hashes?
- **VERIFIED.** In [BlockConsensusVoteService.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/service/BlockConsensusVoteService.java#L198-L203), the certificate structure explicitly records `blockHeight` and `blockHash`. Furthermore, each validator's cryptographic vote is computed over:
  $$\text{Payload} = \texttt{"BLOCK\_VOTE|" } \parallel \text{network} \parallel \texttt{"|" } \parallel \text{blockHeight} \parallel \texttt{"|" } \parallel \text{blockHash} \parallel \texttt{"|" } \parallel \text{voterAddr}$$
  $$\text{Digest} = \text{Keccak-256}(\text{Payload})$$
  In [BlockBroadcastService.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/service/BlockBroadcastService.java#L630-L640), `verifyConsensusCertificate` strictly enforces `certHash.equalsIgnoreCase(blockHash)`. If any attempt is made to link a certificate to a differing block hash, secp256k1 vote signature verification fails.

#### 3. Are proposer public keys and signatures persisted?
- **Proposer Signatures:** **VERIFIED.** Column `proposer_signature VARCHAR(128)` exists in the database schema and is populated with 64-byte raw secp256k1 signatures formatted as 128 hex characters.
- **Proposer Public Keys:** **PARTIALLY VERIFIED.** In source code, `@Column(name = "proposer_public_key", length = 66)` exists on `Block.java`. In the live databases, this column is **absent** due to unapplied migration `V002`.

#### 4. Can a certificate be retrieved after a complete application restart?
- **In Source Code:** **YES.** [WalletController.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/controller/WalletController.java#L637-L643) and [NodeSyncController.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/controller/NodeSyncController.java#L105-L112) check `b.getConsensusCertificate()` from the database entity if the in-memory cache misses.
- **In Live Cluster:** **NO (FAILED).** Because certificates are stored only in memory in the running processes and the MySQL schema lacks the certificate column, restarting the application completely purges all consensus certificates.

#### 5. Can a new node validate an old block without the original node's in-memory cache?
- **In Source Code:** **YES.** [PeerSyncService.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/service/PeerSyncService.java#L254-L270) retrieves blocks via `GET /{network}/blocks/{height}`, which serializes the persisted certificate. [BlockBroadcastService.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/service/BlockBroadcastService.java#L680-L705) reconstructs the historical validator set from historical snapshots or fetches the snapshot via `GET /{network}/validators/snapshot/{hash}`.
- **In Live Cluster:** **NO (FAILED).** Querying the running node on port 3000 for block 15 (`GET /api/node/block/15` and `GET /mainnet/blocks/15`) confirms that `consensusCertificate` is omitted from the JSON response. Furthermore, `GET /mainnet/validators/snapshot/{hash}` returns HTTP 404. A new node syncing against the active cluster cannot validate historical blocks.

#### 6. Can a database record be modified to substitute a certificate for another block?
- **Database Manipulation:** Any actor with root MySQL access can directly execute `UPDATE blocks SET consensus_certificate = ...`.
- **Validation Rejection:** If a substituted certificate is served to any verifying node, [BlockBroadcastService.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/service/BlockBroadcastService.java#L775-L785) recomputes the Keccak-256 vote digest over the target block hash and checks the secp256k1 signature. Because the voter signed the original block hash, the cryptographic signature check fails (`CryptoUtil.verifyVoteSignature == false`).
- **Startup Re-Verification Gap:** Local nodes trust existing blocks in their own database marked `is_finalized = true` on startup without re-verifying certificates ([NodeStartupRecovery.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/lifecycle/NodeStartupRecovery.java#L58-L68)).

### Explicit Remediation Assessment: **F-04 IS ONLY PARTIALLY REMEDIATED**
> **CRITICAL NOTICE:** Finding F-04 ("Broken Catch-Up Sync Due to Missing Certificates") is **ONLY PARTIALLY REMEDIATED**. Although the Java code and SQL migration file exist in the source repository, the running cluster has not executed migration `V002`, the live MySQL schemas lack `consensus_certificate`, and active running processes do not persist certificates durably. In the deployed environment, consensus certificates remain volatile.

---

## 3. Verify Deterministic Validator-State Synchronization

A read-only comparison was executed across the three active databases, and the validator synchronization logic was traced.

### 3.1 Read-Only Comparison Across MySQL Databases

```sql
SELECT id, address, network, staked_amount, status, reliability_score_scaled, 
       successful_proposals, total_proposals, corrupted_proposals 
FROM validators ORDER BY address ASC;
```

#### Results Across `m3rwallet_load_3000`, `load_4000`, and `load_5000`:

| Validator Address | Network | Stake Amount | Status | Reliability ($R \times 10^5$) | Successful Proposals | Total Proposals | Corrupted Proposals |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `NidXN8ZVWqqmN2vDt7UcJ6EAPJvDPYM6Qy` (Port 3000) | `mainnet` | `10000` | `ACTIVE` | `50000` ($R=0.50$) | `5` | `5` | `0` |
| `NNjGdDKJnDejkAhTpTvGzDdW9UmGMwN547` (Port 4000) | `mainnet` | `10000` | `ACTIVE` | `70000` ($R=0.70$) | `7` | `7` | `0` |
| `NVHNEzEVZfLBKrbvTznp9dsAUC3Gb7eNmg` (Port 5000) | `mainnet` | `10000` | `ACTIVE` | `30000` ($R=0.30$) | `3` | `3` | `0` |

- **Block Proposal Sum:** $5 + 7 + 3 = 15$ blocks, exactly matching the 15 finalized blocks in each database.
- **Account Ledger Balances:**
  - `3268dbda...`: Balance `99836100` on all 3 nodes (Nonce: 20).
  - Proposers: Node 4000 and 5000 show `1000` for `Nid...`, while Node 3000 shows `980` (a 20-unit broadcast fee reconciliation drift).

### 3.2 Evaluation of Validator Synchronization Questions

1. **How a new node obtains the initial validator set:**
   - **NOT VERIFIED / RESTRICTED:** A new node **cannot** obtain the validator set from peers dynamically. In [ValidatorSyncService.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/service/ValidatorSyncService.java#L135-L150) (`savePeerValidator`), unknown peer validator records are unconditionally rejected:
     ```java
     log.warn("[SECURITY][VALIDATOR SYNC] Rejected unauthenticated peer validator registration for {}", address);
     return false;
     ```
     Similarly, `POST /{network}/validator/receive` in [WalletController.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/controller/WalletController.java#L870-L874) returns `403 FORBIDDEN`. A new node only auto-registers itself via `--app.validator.enabled=true`. All other validators must be pre-seeded into MySQL.
2. **How stake changes are propagated:**
   - **NOT VERIFIED / ABSENT:** There is no protocol mechanism to propagate stake changes. Staking transactions do not exist in the block specification. `savePeerValidator` ignores peer stake to prevent poisoning.
3. **How validator activation and deactivation are propagated:**
   - **PARTIALLY VERIFIED:** Slashing is evaluated locally upon detecting double-proposals ([SlashDetectionService.java](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/service/SlashDetectionService.java#L120)). No peer message can activate or deactivate validators.
4. **Whether validator state is derived from verified transactions or another authenticated mechanism:**
   - Stake is derived from local configuration/balance locking, **not** from verified ledger transactions.
   - Proposal counts and reliability scores ($R_v$) **are derived deterministically** from canonical finalized blocks via `recordSuccessfulProposal()` calling `blockRepository.countByProposerAddressAndNetworkAndIsFinalized(address, network, true)`.
5. **Whether independent nodes compute identical validator-set hashes:**
   - **VERIFIED.** [BlockValidationService.computeValidatorSetHash](file:///home/noob_coder/Desktop/M3R_Coin/Node_Software/src/main/java/com/m3rwallet/service/BlockValidationService.java#L297-L315) sorts validators lexicographically by lowercase address and computes:
     $$\text{ValSetHash} = \text{SHA-256}\left(\sum_{v \in \text{sorted}} \text{addr}_v \parallel \texttt{":"} \parallel \text{stake}_v \parallel \texttt{":"} \parallel \text{rel}_v \parallel \texttt{";"}\right)$$
   - **Empirical Check on Block 15:**
     $$\text{Input: } \texttt{Nid...:10000:50000;NNj...:10000:70000;NVH...:10000:20000;}$$
     $$\text{Computed SHA-256: } \texttt{df033f4df8e0b0fe547dbb39a5cff692e9cd77efe842c4fa5859d1dacdee0807}$$
     This matches the exact `validatorSetHash` recorded in block 15 across all three databases.
6. **Whether peer JSON can influence voting weights or leadership:**
   - **VERIFIED (IMMUNE).** Peer JSON is strictly ignored for existing validators and rejected for new validators in `ValidatorSyncService.java`.
7. **Whether a restart reconstructs the same validator set:**
   - **VERIFIED.** On restart, `ValidatorRepository` loads existing records from MySQL, and `reconcileAllValidatorProposalStatistics()` recomputes proposal metrics from finalized blocks.

### Section 3 Verdict: **PARTIALLY VERIFIED**
Validator set hashing and immunity to peer JSON poisoning are verified. However, dynamic propagation of validator registrations and stake updates is absent; bootstrapping a new node requires database pre-seeding.

---

## 4. Verify the Actual Synchronization Implementation

Every catch-up pathway, persistence call, and state transition was audited in the codebase.

```
       [ P2P / HTTP Ingress ]
                  │
        ┌─────────┴─────────┐
        ▼                   ▼
  NodeSyncController   WalletController
  /block/receive       /blocks/receive
        │                   │
        └─────────┬─────────┘
                  ▼
       [ BlockBroadcastService.receiveBlock() ]
                  │
      ├── 1. Structural Header Validation
      ├── 2. Proposer secp256k1 Signature Verification
      ├── 3. Validator Set Commitment Hash Check
      ├── 4. Dual-Quorum Consensus Certificate Check (>= 2/3 weight & count)
      │
      ├── [ If Valid Finalized Block ]
      │         │
      │         ├── a. blockRepo.save(block)
      │         ├── b. blockTxRepo.saveAll(txs) [Status: CONFIRMED]
      │         ├── c. feeDistributionService.distributeBlockFees()
      │         └── d. mempoolService.removeTransactions(confirmedTxHashes)
      │
      └── [ If Unfinalized Proposal ]
                │
                └── Staged in-memory ONLY (unfinalizedProposals map)
```

### 4.1 Audit of Block Ingress & Persistence Pathways

| Component / File | Inspected Method | Validation & Security Controls | Direct Unverified Persistence? |
|:---|:---|:---|:---:|
| `PeerSyncService.java` | `syncMissingBlocks()` | Routes all fetched blocks through `blockBroadcastService.receiveBlock()`. Halts immediately on validation failure. Rejects rollbacks of finalized blocks. | **NONE** |
| `BlockSyncService.java` | `syncMissingBlocks()` | Routes all blocks through `receiveBlockFromPeer()` $\to$ `blockBroadcastService.receiveBlock()`. | **NONE** |
| `NodeStartupRecovery.java` | `onStartup()` | Reconciles fee rewards only for already finalized blocks (`findByNetworkAndIsFinalized(true)`). | **NONE** |
| `BlockBroadcastService.java` | `receiveBlock()` | Validates parent hash, slot leadership, timestamp, state root, Merkle root, proposer signature, and dual-quorum consensus certificate. | **NONE** |
| `NodeSyncController.java` | `receiveBlock()` | Delegates strictly to `blockBroadcastService.receiveBlock()`. Returns HTTP 400 on rejection. | **NONE** |
| `WalletController.java` | `receiveBlock()` | Delegates strictly to `blockBroadcastService.receiveBlock()`. | **NONE** |

### 4.2 Comprehensive Audit of State Transitions

1. **`blockRepository.save()` Calls:**
   - `BlockProposalService.java:209`: Proposed block saved only after `blockConsensusVoteService.collectVotes()` confirms 2/3 consensus.
   - `BlockProposalService.java:229`: Finalized block saved.
   - `BlockBroadcastService.java:348`: Persists received block **only after** `verifyConsensusCertificate()` returns true.
   - `NodeStartupRecovery.java:68`: Startup fee flag persistence on existing finalized blocks.
2. **Finalized State Assignment (`setIsFinalized(true)`):**
   - Strictly restricted to `BlockProposalService.finalizeBlock()` (after consensus) and `BlockBroadcastService.receiveBlock()` (after certificate verification).
3. **Transaction Status Confirmation (`CONFIRMED`):**
   - Strictly executed inside `saveBlockTransactions()` in `BlockBroadcastService` and `finalizeBlock()` in `BlockProposalService`.
4. **Fee Distribution (`distributeBlockFees`):**
   - Executed exclusively after consensus certificate verification.
5. **Mempool Deletion (`removeTransactions`):**
   - Eviction occurs strictly upon saving confirmed block transactions.

### Section 4 Verdict: **VERIFIED (IN CODEBASE)**
There is no remaining pathway in the codebase that directly persists unverified blocks or assigns finalized state without consensus verification.

---

## 5. Reconcile Test and Build Evidence

The Maven Surefire directory at `Node_Software/target/surefire-reports` was audited directly.

### 5.1 Surefire XML Report Inventory & Test Counts

- **Total XML Report Files Generated:** **22 XML files**
- **Total Test Cases Executed:** **160 tests**
- **Total Failures:** **0**
- **Total Errors:** **0**
- **Total Skipped:** **0**
- **Total Execution Duration:** **12.558 seconds**

```
================================================================================
Surefire XML Test Execution Breakdown
================================================================================
Report XML File Name                                              | Tests | Fail | Err | Skip
--------------------------------------------------------------------------------
TEST-com.m3rwallet.config.LocalhostOnlyInterceptorTest.xml        | 8     | 0    | 0   | 0   
TEST-com.m3rwallet.service.BlockFinalizationSecurityTest.xml      | 6     | 0    | 0   | 0   
TEST-com.m3rwallet.service.BlockReceiveEndpointAdversarialTest.xml | 7     | 0    | 0   | 0   
TEST-com.m3rwallet.service.BlockValidationHashTest.xml            | 1     | 0    | 0   | 0   
TEST-com.m3rwallet.service.CanonicalHeaderAuthenticationTest.xml  | 13    | 0    | 0   | 0   
TEST-com.m3rwallet.service.ConsensusCertificateConcurrencyTest.xml | 3     | 0    | 0   | 0   
TEST-com.m3rwallet.service.ConsensusQuorumTest.xml                | 19    | 0    | 0   | 0   
TEST-com.m3rwallet.service.ConsensusSecurityAdversarialTest$CertificateAdversarialTests.xml | 18    | 0    | 0   | 0   
TEST-com.m3rwallet.service.ConsensusSecurityAdversarialTest$HistoricalValidatorSetTests.xml | 2     | 0    | 0   | 0   
TEST-com.m3rwallet.service.ConsensusSecurityAdversarialTest$ProposerSignatureTests.xml | 12    | 0    | 0   | 0   
TEST-com.m3rwallet.service.ConsensusSecurityAdversarialTest$SoloModeTests.xml | 4     | 0    | 0   | 0   
TEST-com.m3rwallet.service.ConsensusSecurityAdversarialTest.xml   | 0     | 0    | 0   | 0   
TEST-com.m3rwallet.service.CryptographicCanonicalizationAdversarialTest.xml | 7     | 0    | 0   | 0   
TEST-com.m3rwallet.service.HistoricalSnapshotRestartAuditTest.xml | 3     | 0    | 0   | 0   
TEST-com.m3rwallet.service.HistoricalValidatorSnapshotTest.xml    | 6     | 0    | 0   | 0   
TEST-com.m3rwallet.service.MultiNodeConsensusSimulationTest.xml   | 14    | 0    | 0   | 0   
TEST-com.m3rwallet.service.PeerSyncSecurityAdversarialTest.xml    | 5     | 0    | 0   | 0   
TEST-com.m3rwallet.service.PoRPoSWeightValidationTest.xml         | 10    | 0    | 0   | 0   
TEST-com.m3rwallet.service.ProposalStatisticsAuditTest.xml        | 2     | 0    | 0   | 0   
TEST-com.m3rwallet.service.QuorumZeroWeightSecurityTest.xml       | 9     | 0    | 0   | 0   
TEST-com.m3rwallet.service.TransactionDoubleSpendingTest.xml      | 6     | 0    | 0   | 0   
TEST-com.m3rwallet.service.ValidatorSyncSecurityAdversarialTest.xml | 5     | 0    | 0   | 0   
--------------------------------------------------------------------------------
TOTALS: 22 files | 160 tests | 0 failures | 0 errors | 0 skipped
================================================================================
```

### 5.2 Build & Packaging Execution Verification

- **Maven Exit Codes:**
  - `mvn clean test` exited with code `0`.
  - `mvn clean package` exited with code `0`.
- **Target Artifact Timestamp:** `target/m3r-wallet-server-1.0.0.jar` was created at `2026-11-12 15:38:48.042 +0600`, immediately following the test suite execution (which completed between `15:38:43` and `15:38:47`). Because packaging succeeded, all tests passed without failure.

### 5.3 Inconsistencies in Documentation Test Counts

Auditing historical documents in `research/` revealed three differing test counts:
1. **`research/TEST_RESULTS.md`:** Reports **64 tests** across 7 test classes. This documents the pre-audit baseline before security hardening.
2. **`research/SECURITY_REMEDIATION_REPORT.md`:** Reports **138 tests** across 16 test classes. This documents the intermediate remediation of vulnerabilities M3R-SEC-01 through M3R-SEC-06.
3. **`Node_Software/research/CRITICAL_SECURITY_REMEDIATION_REPORT.md`:** Reports **160 tests**. The text states *"Total Test Classes: 21"*, whereas the Surefire directory contains **22 XML files**.
   - **Reason for 21 vs. 22 Discrepancy:** The file `TEST-com.m3rwallet.service.ConsensusSecurityAdversarialTest.xml` represents the outer enclosing class and records 0 tests, while its 4 `@Nested` inner classes generate 4 separate XML report files totaling 36 tests. Adding the 17 other top-level test classes yields 21 test-bearing classes, but 22 XML files on disk.

### Section 5 Verdict: **VERIFIED**
160 tests ran cleanly with 0 failures and exit code 0. Documentation discrepancies represent progressive testing milestones.

---

## 6. Audit Classification Matrix

Each audited item from the post-remediation scope is classified below:

| Audit Item | Scope & Criteria | Classification | Primary Evidence & Root Cause |
|:---|:---|:---:|:---|
| **Running Software Identity** | Ports 3000, 4000, 5000 running newly packaged JAR | **FAILED** | Processes started at 12:01 running unlinked older build (`edc4acfca0cb...`). Newly packaged JAR (`09ae821ddaa3...`) built at 15:38 was never deployed. |
| **Relational Certificate Schema** | MySQL schema supports durable certificate persistence | **FAILED** | Running databases (`load_3000`, `4000`, `5000`) lack column `consensus_certificate`. Flyway migration `V002` was not executed. |
| **Certificate Hash Binding** | Certificates bound to immutable block hash and signatures | **VERIFIED** | Enforced by Keccak-256 digest `BLOCK_VOTE\|...` and secp256k1 signature validation in `BlockBroadcastService`. |
| **Proposer Key Persistence** | Proposer public keys and signatures persisted | **PARTIALLY VERIFIED** | Signatures persisted in `proposer_signature`. Public key column is absent from running MySQL databases. |
| **Certificate Recovery on Restart** | Certificates retrievable after application restart | **FAILED** | In live nodes, certificates exist solely in memory. Application restart destroys all certificate data. |
| **Catch-Up Sync Old Block Validation** | New node can validate old blocks without in-memory cache | **FAILED** | Live nodes return 404 on validator snapshots and omit certificates from block retrieval endpoints. |
| **Certificate Substitution Tampering** | Database record modification can substitute certificate | **VERIFIED (PROTECTED)** | Substituting certificates on differing blocks fails cryptographic signature verification upon ingress. |
| **Dynamic Validator Discovery** | New node dynamically obtains validator set via P2P | **NOT VERIFIED** | P2P peer validator registration is blocked (`savePeerValidator` returns `false`, endpoint returns 403). Requires manual DB seed. |
| **Stake Change Propagation** | Stake transitions propagated across nodes | **NOT VERIFIED** | No on-chain staking transaction type exists. Peer stake updates are intentionally discarded. |
| **Deterministic ValSet Hashing** | Independent nodes compute identical validator-set hashes | **VERIFIED** | Lexicographical address sorting and SHA-256 hash verified against block 15 (`df033f4d...`). |
| **Peer JSON Stake Immunity** | Peer JSON cannot manipulate voting weights or leadership | **VERIFIED** | `ValidatorSyncService` strictly ignores peer-supplied stake and reliability values. |
| **Validator Set Restart Consistency** | Restart reconstructs identical validator set | **VERIFIED** | Reconstructed reliably from local database records. |
| **Catch-Up Verification Invariant** | No pathway directly persists unverified blocks | **VERIFIED (CODE)** | All sync pathways (`PeerSyncService`, `BlockSyncService`, controllers) route through `BlockBroadcastService.receiveBlock()`. |
| **Finalized Block Rollback Protection** | Finalized blocks cannot be deleted or rolled back | **VERIFIED (CODE)** | `rollbackToHeight()` and `deleteBlock()` throw `IllegalStateException` on finalized blocks. |
| **Surefire Test Suite Reconciliation** | 160 tests executed with 0 failures, exit code 0 | **VERIFIED** | 22 XML reports, 160 passing tests, clean Maven package execution verified from Surefire XML artifacts. |

---

## 7. Critical Operational & Architectural Risks

> [!CAUTION]
> **DO NOT ASSUME THE SYSTEM IS SAFE MERELY BECAUSE 160 TESTS PASSED.**
> Passing 160 unit and integration tests verifies only that the isolated Java classes and mocked integration flows behave as programmed in memory. It provides **NO guarantee of production readiness or operational Byzantine Fault Tolerance (BFT)**.

The following critical risks remain unresolved in the operational environment:

1. **Stale Running Cluster:**
   The three active node processes continue to execute code that lacks all F-01 through F-10 remediations. They remain susceptible to unauthenticated block injection, peer-driven rollbacks, and memory-only certificate loss until they are restarted with the new JAR and database migrations are applied.
2. **Absence of On-Chain Governance & Staking Transactions:**
   Because validator registration and stake changes cannot be submitted as signed transactions into blocks, the validator set cannot evolve dynamically. The system behaves as a permissioned, manually configured federation rather than an open, decentralized network.
3. **Historical Sync Catch-22:**
   Because historical validator snapshots change whenever a validator's reliability score updates ($R_v = R_v + 0.01$), validating historical blocks requires historical validator set snapshots for every single block height. If snapshots are lost or not seeded, historical verification fails closed, permanently halting node catch-up sync.
4. **Local Startup Trust Assumption:**
   `NodeStartupRecovery` assumes all blocks in MySQL with `is_finalized = 1` are valid. If a local database is compromised or corrupted offline, the node will resume on top of invalid blocks without re-verifying certificates on boot.
