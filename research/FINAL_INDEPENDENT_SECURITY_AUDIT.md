# Final Independent Security & Distributed Consistency Audit: M3R Node Software

**Target Repository:** `~/Desktop/M3R_Coin/Node_Software/`  
**Target Branch:** `research/consensus-audit-fixes`  
**Baseline Commit:** `21799ad`  
**Audit Date:** November 12, 2026  
**Audit Classification:** **A — NOT SAFE**  
**Audit Mode:** Read-Only Verification (No source code, configuration, or database mutations executed)

---

## Executive Summary

An exhaustive, read-only security and distributed consistency audit of the M3R node software was conducted across the source code, unit/adversarial test suites, Maven Surefire XML artifacts, running multi-node cluster (ports 3000, 4000, 5000), and research documentation.

While the engineering team successfully implemented significant cryptographic hardening in `BlockBroadcastService.receiveBlock` (enforcing authenticated dual quorum and proposer signature verification on direct block arrival) and defined a deterministic binary serialization standard (`M3R_BLOCK_HEADER_V1`), **critical consensus and distributed ledger vulnerabilities remain active in the codebase**:

1. **Unauthenticated Peer Sync Finalization Bypass (`PeerSyncService.java:274`)**: A scheduled background synchronization task periodically fetches raw `Block` entities from peers and commits them directly to `BlockRepository` via `blockRepository.save(fetched)` without verifying consensus certificates, proposer signatures, Merkle roots, or slot leadership. If a peer presents a chain with higher height, the node executes `rollbackToHeight()`, deleting locally finalized blocks and replacing them with unverified peer blocks.
2. **Consensus Weight & Identity Poisoning (`ValidatorSyncService.java:169-203`)**: A background synchronization task periodically pulls validator states from peers via `/api/validator/list` and overwrites local validator stakes (`v.setStakedAmount(...)`) and applies `Math.max(local, peer)` to reliability scores and proposal counters based on unauthenticated peer JSON responses. Any peer can forge stake amounts or proposal metrics to hijack slot leadership and consensus voting weights.
3. **Catch-Up Incompatibility (`WalletController.java:610-624` vs `BlockSyncService.java:82-84`)**: The standard block retrieval endpoint `GET /{network}/blocks/{height}` does not return consensus certificates or proposer public keys. Consequently, when `BlockSyncService` fetches blocks to catch up, the hardened `receiveBlock` fails closed on every block. The only reason nodes caught up in practice was the vulnerable `PeerSyncService` bypass.
4. **Specification & Cryptographic Discrepancies**:
   - `CONSENSUS_CERTIFICATE_SPEC.md` documents an 18-byte domain tag (`0..17`), while the source code implements a 20-byte tag (19 ASCII bytes + `0x00` null byte).
   - The spec specifies 4-byte `int32` network length; the code implements 2-byte `short`.
   - The spec specifies a 4-byte length-prefixed UTF-8 proposer address; the code writes a 20-byte decoded Base58 raw payload.
   - The spec claims canonical low-$s$ ($s \le n/2$) verification; the code in `CryptoUtil.verifyRawSecp256k1Signature` only checks $1 \le s < n$, permitting signature malleability.
   - `validatorSetHash` is computed via `SHA-256`, contradicting the spec's claim of `Keccak-256`.
5. **Runtime-Only Ephemeral Snapshots**: Validator snapshots are stored exclusively in in-memory `ConcurrentHashMap` caches. No database persistence exists. Cold restarts or newly joining nodes cannot validate historical certificates from previous epochs.

Consequently, the software is rated **A — NOT SAFE**.

---

## 1. Canonical Header Cryptographic Audit

### 1.1 Specification vs. Source Implementation Breakdown

The canonical binary serialization standard `M3R_BLOCK_HEADER_V1` is implemented in `Block.java` (`serializeCanonicalHeader`, lines 140–226). A rigorous comparison against `CONSENSUS_CERTIFICATE_SPEC.md` Section 2.1 reveals substantial structural discrepancies:

| Field Parameter | Documented in `CONSENSUS_CERTIFICATE_SPEC.md` | Actual Implementation in `Block.java` | Status / Discrepancy |
| :--- | :--- | :--- | :--- |
| **Domain Separation Tag** | `byte[18]` (`0..17`), ASCII `"M3R_BLOCK_HEADER_V1"` | `byte[20]`: 19 UTF-8 bytes (`"M3R_BLOCK_HEADER_V1"`) + `(byte) 0x00` | **Mismatch**: Length is 20 bytes, not 18. Null terminator omitted in spec. |
| **Protocol Version** | `int8` (`1 byte`) | `byte` (`1 byte`, default `(byte) 1`) | **Match** |
| **Network String Length** | `int32` (`4 bytes` big-endian) | `short` (`2 bytes` big-endian, `ByteBuffer.allocate(2).putShort(...)`) | **Mismatch**: 2 bytes in code vs. 4 bytes claimed in spec. |
| **Network Identifier** | `byte[]` (UTF-8 string) | `byte[]` (`network.trim().toLowerCase().getBytes(...)`) | **Match** |
| **Block Height** | `int64` (`8 bytes` big-endian) | `long` (`8 bytes` big-endian via `toLongBytes()`) | **Match** |
| **Slot Number** | `int64` (`8 bytes` big-endian) | `long` (`8 bytes` big-endian via `toLongBytes()`) | **Match** |
| **Parent Block Hash** | `byte[32]` | `byte[32]` via `hexToFixedBytes(parentBlockHash, 32)` | **Match** (Coerces on malformed hex) |
| **Merkle Root** | `byte[32]` | `byte[32]` via `hexToFixedBytes(merkleRoot, 32)` | **Match** (Coerces on malformed hex) |
| **State Root** | `byte[32]` | `byte[32]` via `hexToFixedBytes(stateRoot, 32)` | **Match** (Coerces on malformed hex) |
| **Timestamp** | `int64` (`8 bytes` big-endian) | `long` (`8 bytes` big-endian via `toLongBytes()`) | **Match** |
| **Proposer Address** | `int32` length + `byte[]` UTF-8 string | Exactly `byte[20]` raw payload decoded from Base58Check (`AddressUtil.decodeBase58ToHex20`) | **Mismatch**: Raw 20-byte address payload in code; length-prefixed string in spec. |
| **Proposer Weight** | `float64` (`8 bytes` IEEE 754 double) | `double` (`8 bytes` IEEE 754 big-endian via `ByteBuffer.putDouble`) | **Match** |
| **Validator-Set Hash** | `byte[32]` | `byte[32]` via `hexToFixedBytes(validatorSetHash, 32)` | **Match** (SHA-256 in code, not Keccak) |
| **Nonce** | `int64` (`8 bytes` big-endian) | `long` (`8 bytes` big-endian via `toLongBytes()`) | **Match** |
| **Transaction Count** | `int32` (`4 bytes` big-endian) | `int` (`4 bytes` big-endian via `ByteBuffer.putInt`) | **Match** |

### 1.2 Resolution of Field Count Discrepancy (10 vs 12 vs 13 Fields)

Research documents (`ADVERSARIAL_TEST_RESULTS.md`, `CAMERA_READY_SECURITY_CLAIMS.md`, `SECURITY_REMEDIATION_REPORT.md`) repeatedly assert *"10-field tampering resistance"* or *"all 10 consensus-critical fields"*. In contrast, `CONSENSUS_CERTIFICATE_SPEC.md` lists 12 data fields, and the source code serializes 13 distinct parameters:

1. **Root Cause of the "10 Fields" Claim**: In `CanonicalHeaderAuthenticationTest.java`, the author wrote unit tests exercising individual field mutations:
   - Test 2: `merkleRoot`
   - Test 3: `stateRoot`
   - Test 4: `timestamp`
   - Test 5: `proposerAddress`
   - Test 6: `validatorSetHash`
   - Test 7: `nonce`
   - Test 8: `proposerWeight`
   - Test 9: `parentBlockHash`
   - Test 10: `txCount`
   - Test 13: `version`  
   The claims of "10 fields" arose solely because exactly 10 fields had dedicated `@Test` tampering assertions in `CanonicalHeaderAuthenticationTest.java`.
2. **Actual Field Count**:
   - `Block.serializeCanonicalHeader()` serializes **13 distinct header fields**: `version`, `network`, `blockHeight`, `slotNumber`, `parentBlockHash`, `merkleRoot`, `stateRoot`, `timestamp`, `proposerAddress`, `proposerWeight`, `validatorSetHash`, `nonce`, and `txCount`.
   - `network` and `slotNumber` were omitted from explicit mutation tests in `CanonicalHeaderAuthenticationTest.java` (though `slotNumber` is checked by slot leader validation).

### 1.3 Signing & Verification Symmetry & Digest Handling

1. **Serialization Identicality**:
   - Proposer signing (`BlockProposalService.buildBlock`, line 93) invokes `block.computeHeaderSigningDigest()`.
   - Payload verification (`BlockValidationService.validateReceivedBlock`, line 168) invokes `Block.computePayloadHeaderSigningDigest(payload)`.
   - Both delegate directly to `Block.computeHeaderDigest(...)` and `Block.serializeCanonicalHeader(...)`.
2. **32-Byte Digest Verification**:
   - `Block.computeHeaderDigest` terminates with `CryptoUtil.keccak256(bytes)`, returning exactly 32 bytes (`byte[32]`).
   - The signer (`NodeIdentityService.signData`) passes this 32-byte array to BouncyCastle's `ECDSASigner.generateSignature(data)`.
   - The verifier independently recomputes this 32-byte Keccak-256 digest from the incoming payload and passes it to `CryptoUtil.verifyVoteSignature(digest, sigBytes, pubKeyBytes)`.
3. **Block Hash vs. Header Signing Digest Divergence**:
   - An architectural quirk exists: `block.blockHash` is **NOT** the Keccak-256 canonical header digest.
   - `BlockValidationService.computeBlockHash()` computes the `SHA-256` digest over an 8-field pipe-delimited string (`version|height|slot|parent|merkle|timestamp|proposer|nonce`).
   - Consensus certificate votes sign `blockHash` (`"BLOCK_VOTE|" + net + "|" + height + "|" + blockHash + "|" + voter`), which commits to the SHA-256 block hash, whereas the proposer signature signs the Keccak-256 canonical binary header digest.

### 1.4 Cryptographic Rigor & Malformed Value Handling

1. **Missing Canonical Low-$s$ Enforcement**:
   - `CONSENSUS_CERTIFICATE_SPEC.md` Section 2.2 claims: *"with strict canonical low-$s$ ($s \le n/2$)"*.
   - Verification in `CryptoUtil.verifyRawSecp256k1Signature` (lines 95–99) only verifies:
     ```java
     BigInteger n = domain.getN();
     if (r.compareTo(BigInteger.ONE) < 0 || r.compareTo(n) >= 0
             || s.compareTo(BigInteger.ONE) < 0 || s.compareTo(n) >= 0) {
         return false;
     }
     ```
   - It **does not check** `s.compareTo(n.shiftRight(1)) <= 0`. Signatures with high $s$ ($s > n/2$) are accepted, permitting signature malleability.
2. **Silent Coercion in Serialization**:
   - In `Block.hexToFixedBytes()`, malformed hex strings, invalid characters, or null values do not throw an exception; they are silently coerced to `new byte[32]` (all zeros) or truncated.
   - `toLongBytes(Long v)` silently coerces null to `0L`.
   - `proposerWeight` silently coerces null to `0.0d`.
3. **Validation Holes in `BlockValidationService.validateReceivedBlock`**:
   - `stateRoot` is never validated or required to be non-blank.
   - `validatorSetHash` validation is optional: line 184 checks `if (claimedValSetHash != null && !claimedValSetHash.isBlank())`. If a proposer sends a payload omitting `validatorSetHash`, the check is bypassed.
   - `proposerWeight` in the block payload is never checked against the validator's actual registered stake weight in `ValidatorRepository` or `ValidatorWeightRepository`.
   - When `txCount == 0` and `transactions` is empty, line 119 (`if (txHashes != null && !txHashes.isEmpty())`) skips the Merkle root check completely, allowing any arbitrary Merkle string to pass if $txCount = 0$.

---

## 2. Complete Block Finalization Audit

Every occurrence of block finalization, block repository persistence, fee distribution, transaction confirmation, and mempool clearing across the entire codebase was audited:

```
Search Target                      Source Location
setIsFinalized(true)               BlockProposalService.java:216
                                   BlockBroadcastService.java:332
blockRepository.save()             BlockProposalService.java:206
                                   BlockProposalService.java:226
                                   BlockBroadcastService.java:336
                                   BlockBroadcastService.java:355
                                   NodeStartupRecovery.java:68
                                   PeerSyncService.java:274 [CRITICAL BYPASS]
distributeBlockFees()              BlockScheduler.java:166
                                   BlockBroadcastService.java:354
Transaction CONFIRMED              BlockProposalService.java:222
                                   BlockBroadcastService.java:390, 445
                                   WalletService.java:585
mempoolService.removeTransactions  BlockScheduler.java:196
                                   BlockBroadcastService.java:367 (via clearConfirmedTransactions)
```

### 2.1 Audited Execution Pathways

#### Pathway 1: Local Proposal Production (`BlockScheduler.java:148-181`)
- **Pre-condition**: Selected validator is slot leader.
- **Certificate Verification**: Calls `blockConsensusVoteService.collectVotes(candidate, network)`. Gathers proposer self-vote and queries peers via `POST /{network}/blocks/vote`. Only if dual quorum ($W \ge 2/3$, $V \ge \lceil 2N/3 \rceil$, $PeerConf > 0$) is reached does it invoke `blockProposalService.saveBlock()` and `blockProposalService.finalizeBlock()`.
- **Side Effects**: Sets `isFinalized = true`, marks transactions `CONFIRMED`, distributes fees via `feeDistributionService`, evicts mempool transactions, and broadcasts to peers.
- **Evaluation**: **Protected**.

#### Pathway 2: Direct Peer Broadcast Reception (`NodeSyncController` / `WalletController` -> `BlockBroadcastService.receiveBlock`)
- **Pre-condition**: External peer calls `/api/node/sync/receive-block` or `/{network}/blocks/receive`.
- **Certificate Verification**: Invokes `BlockValidationService.validateReceivedBlock()` followed by `verifyConsensusCertificate()`.
- **Behavior**: If `claimedFinalized = true` without a valid certificate, fails closed (`REJECTED`). If `claimedFinalized = false`, stages in `unfinalizedProposals` memory map without touching the canonical database or evicting mempool transactions. Only if an authentic dual-quorum certificate with valid ECDSA signatures is present does it call `blockRepo.save()`, distribute fees, and confirm transactions.
- **Evaluation**: **Protected**.

#### Pathway 3: Consensus Catch-Up Sync (`BlockSyncService.java:30-89`)
- **Pre-condition**: Background task (`@Scheduled` every 8s) queries `consensusProperties.getPeers()`.
- **Implementation**: Fetches `GET peer/{network}/blocks/{height}` and calls `blockBroadcastService.receiveBlock(payload, network)`.
- **Fatal Defect**: As established in Section 2.2, `GET /{network}/blocks/{height}` does not return certificates. Thus, this pathway continuously fails closed and cannot synchronize blocks.
- **Evaluation**: **Blocked / Inoperable**.

#### Pathway 4: Periodic Peer Catch-Up Sync (`PeerSyncService.java:240-285`) — [CRITICAL VULNERABILITY]
- **Pre-condition**: Background task (`@Scheduled` every 10s) and startup hook (`NodeStartupRecovery.java:47`) query `peerRepository.findByNetwork(network)`.
- **Implementation**:
  ```java
  // PeerSyncService.java:247-281
  String g = peer.getPeerUrl() + "/api/node/block/" + ht;
  ResponseEntity<Block> be = restTemplate.getForEntity(g, Block.class);
  Block fetched = be.getBody();
  if (fetched != null) {
      ...
      if (!hasValidLocalParent(fetched)) {
          continue;
      }
      Optional<Block> already = blockRepository.findByBlockHashAndNetwork(fetched.getBlockHash(), network);
      if (already.isEmpty()) {
          blockRepository.save(fetched); // <--- UNGUARDED CANONICAL PERSISTENCE
          if (validatorService != null && fetched.getProposerAddress() != null) {
              validatorService.recordSuccessfulProposal(fetched.getProposerAddress(), network);
          }
      }
  }
  ```
- **Vulnerability Analysis**:
  1. `fetched` is deserialized as `Block.class`. The `Block` class does not even contain a consensus certificate field.
  2. `PeerSyncService` **never invokes** `BlockBroadcastService.verifyConsensusCertificate()`.
  3. `PeerSyncService` **never invokes** `BlockValidationService.validateReceivedBlock()`.
  4. It does not verify proposer signatures, Merkle roots, transaction validity, or slot leadership.
  5. If `peerHeight > localHeight`, it executes `rollbackToHeight(ht - 1, network)`, deleting local canonical blocks and replacing them with unauthenticated peer blocks.
  6. If the incoming peer block has `isFinalized = true` in its JSON payload, it is saved directly to the database as finalized.
- **Evaluation**: **CRITICAL CONSENSUS BYPASS (VULNERABLE)**.

---

## 3. Historical Validator Snapshot Audit

### 3.1 Persistence Classification
**Classification: Runtime-only in-memory snapshots.**

Validator snapshots are managed in `ValidatorService.java` (lines 83–84):
```java
private final Map<String, ValidatorSetSnapshot> snapshotCacheByHash = new ConcurrentHashMap<>();
private final Map<String, ValidatorSetSnapshot> snapshotCacheByHeight = new ConcurrentHashMap<>();
```
- There is **no database table**, entity, or Flyway migration for validator snapshots.
- `V001__Create_Blockchain_Tables.sql` contains no tables for validator snapshots.
- Snapshots exist solely in volatile heap memory.

### 3.2 Evaluation of Historical Verification Guarantees

1. **How `validatorSetHash` is Computed**:
   - `BlockValidationService.computeValidatorSetHash()` deterministically sorts active validators by canonical address and concatenates `address:stake:reliability;`.
   - **Spec Discrepancy**: The spec specifies `Keccak-256`, but the code executes `MessageDigest.getInstance("SHA-256")`.
2. **Deterministic Hash Reproduction**:
   - Given the identical set of active validators, stake amounts, and reliability scores, `computeValidatorSetHash` deterministically reproduces the exact 64-character hex hash.
3. **Historical Block Validation Against Past Sets**:
   - In `BlockBroadcastService.verifyConsensusCertificate` (lines 671–679):
     If `claimedValSetHash` does not match the current validator set hash, the node looks up `validatorService.getSnapshotByHash(claimedValSetHash)`. If present in the cache, it validates vote weights against the cached historical set.
4. **Behavior When a Snapshot is Missing**:
   - If the snapshot is not in `snapshotCacheByHash`, `verifyConsensusCertificate` emits a security warning (`[SECURITY] Block references historical validator set hash ... but no snapshot exists. Failing closed.`) and returns `false`. The block is rejected.
5. **Newly Joining Nodes**:
   - A newly joining node boots with empty `ConcurrentHashMap` caches. It possesses zero historical snapshots.
   - When attempting to validate historical blocks from earlier epochs (where the validator set differed from genesis or the current set), it **fails closed** and rejects the historical certificates.
6. **Complete Node Restart Guarantees**:
   - Upon process termination, all cached snapshots are lost.
   - Because snapshots are not reconstructed from genesis upon restart, the restarted node cannot validate certificates signed under historical validator sets.
   - Runtime in-memory caching provides zero durable historical auditability.

---

## 4. Proposal Statistics Consistency Audit

### 4.1 Analysis of Synchronized Counters vs. Canonical State

The system tracks validator proposal performance across two distinct mechanisms:
1. Local increment upon block finalization via `validatorService.recordSuccessfulProposal(address, network)`.
2. Remote synchronization in `ValidatorSyncService.java` and `PeerSyncService.java`.

### 4.2 Audit Findings on Proposal Statistics

1. **Peer Counter Inflation (`ValidatorSyncService.java:184-203`)**:
   ```java
   // ValidatorSyncService.java:186-203
   Object spObj = validatorMap.get("successfulProposals");
   if (spObj != null) {
       long peerSp = Long.parseLong(String.valueOf(spObj));
       long localSp = v.getSuccessfulProposals() == null ? 0L : v.getSuccessfulProposals();
       if (peerSp > localSp) {
           v.setSuccessfulProposals(peerSp);
       }
   }
   ```
   - `ValidatorSyncService` polls peers via `/api/validator/list` every 30 seconds.
   - It executes `Math.max(local, peer)` on unauthenticated, unsigned JSON values provided by peers.
   - **Vulnerability**: Any peer can report arbitrary values (e.g., `successfulProposals: 1000000`, `reliabilityScoreScaled: 1000000`). Local nodes immediately adopt the inflated values.
   - Even worse, lines 167–170 unconditionally overwrite validator stake:
     ```java
     var parsedStake = parseStake(validatorMap.get("stakedAmount"));
     if (parsedStake != null) v.setStakedAmount(parsedStake);
     ```
   - An attacker can hijack consensus weighting and slot leader selection across all connected nodes simply by returning forged stake or reliability numbers via `/api/validator/list`.
2. **Duplicate Counting on Catch-Up**:
   - `recordSuccessfulProposal()` is invoked unconditionally in `BlockProposalService.finalizeBlock` (proposer), `BlockBroadcastService.receiveBlock` (live broadcast receiver), AND `PeerSyncService.syncMissingBlocksFromPeers` (catch-up sync).
   - `recordSuccessfulProposal()` does not inspect whether the block height was already credited; it executes `v.setSuccessfulProposals(v.getSuccessfulProposals() + 1)`.
   - If a node re-receives or synchronizes a block, or synchronizes from multiple peers, counters can be double-incremented.
3. **Reorganization and Rollback Inconsistency**:
   - In `PeerSyncService.rollbackToHeight()`, when a fork is resolved by rolling back local blocks, `BlockRepository.delete(b)` deletes the blocks.
   - **Crucial Defect**: `rollbackToHeight()` **never decrements** `successfulProposals` or rolls back `reliabilityScoreScaled`. The validator retains credit for orphaned or malicious blocks that were rolled back, permanently corrupting PoR metrics.
4. **Architectural Derivation Recommendation**:
   - Validator proposal counts and reliability metrics must **never** be synchronized via peer-supplied counters or incremented state variables.
   - Proposal statistics must be computed deterministically from canonical, finalized block records:
     $$\text{successfulProposals}(v) = \text{COUNT}(\text{blocks where } proposer = v \land isFinalized = \text{true})$$

---

## 5. Three-Node Evidence Audit (Ports 3000, 4000, 5000)

Direct inspection of the live running cluster on localhost was executed via non-mutating system calls and read-only HTTP queries:

```
Port    Process PID    Database Target             Node Address                           Public Key (Compressed)
3000    60282          m3rwallet_load_3000         NidXN8ZVWqqmN2vDt7UcJ6EAPJvDPYM6Qy     02931fc921b50c612abbf756a1bfcc33cafd5c8c4d9f999d9749615ddb692731cb
4000    60462          m3rwallet_load_4000         NVHNEzEVZfLBKrbvTznp9dsAUC3Gb7eNmg     0318f08dca70367b8a04e9f74feae237f699fbfaae380cc9264f144b95c364dfe7
5000    60564          m3rwallet_load_5000         NNjGdDKJnDejkAhTpTvGzDdW9UmGMwN547     02185d8c60a330c02fdfaf7e6b0337265f9e19382b49f799719cb6814995787a15
```

### 5.1 Verification Matrix: Verified Facts vs. Operator Claims

| Audit Item | Verified Evidence from Inspection | Operator Claim / Spec Claim | Discrepancy / Assessment |
| :--- | :--- | :--- | :--- |
| **Processes & Isolation** | Verified: 3 distinct JVM processes with separate MySQL databases (`m3rwallet_load_3000`, `4000`, `5000`). | Claims separate node processes. | **Verified**. |
| **Node Identities** | Verified: 3 distinct secp256k1 keypairs, compressed public keys, and Base58Check addresses. | Claims distinct validator identities. | **Verified**. |
| **Peer Connectivity** | Verified: `GET /api/node/peer/list` shows mutual bidirectional alive peering across all 3 nodes. | Claims connected mesh topology. | **Verified**. |
| **Finalized Block Heights** | Verified: All 3 nodes report exactly 15 finalized blocks (`totalElements: 15`). | Claims synchronized ledger height. | **Verified**. |
| **Block Hashes & State** | Verified: All 15 block hashes, parent hashes, timestamps, and tx lists match 100% across all 3 nodes. | Claims consistent canonical ledger. | **Verified**. |
| **Block 15 Details** | Height 15: `4cd133058...`, Proposer `NVHNEzEVZf...`, Parent `167eac4fac...` (Height 14), 1 Tx. | Claims deterministic chain. | **Verified**. |
| **Consensus Certificates** | **Not verifiable via API**: `GET /mainnet/blocks/15` strips certificates; no endpoint exposes cached certificates. | Claims verified consensus certificates on chain. | **Unverified via API**: Certificates are ephemeral in-memory maps only. |
| **Proposal Statistics Consistency** | Verified in DB: Node 3000 (proposer 5 times): `sp=5`; Node 4000 (proposer 3 times): `sp=3`; Node 5000 (proposer 7 times): `sp=7`. Total = 15. | Claims proposal counts match blocks. | **Conditionally consistent** in this quiet run; vulnerable under peer sync. |

---

## 6. Test Suite Audit

### 6.1 Maven Surefire XML Execution Reconciliation

A complete audit of all 17 Surefire XML reports in `target/surefire-reports/` was performed:

$$\text{Total Executed: } \mathbf{138} \quad\mid\quad \text{Failures: } \mathbf{0} \quad\mid\quad \text{Errors: } \mathbf{0} \quad\mid\quad \text{Skipped: } \mathbf{0}$$

#### Breakdown by Test Class:
1. `com.m3rwallet.config.LocalhostOnlyInterceptorTest`: **8 tests** (0 fail)
2. `com.m3rwallet.service.BlockFinalizationSecurityTest`: **6 tests** (0 fail)
3. `com.m3rwallet.service.BlockReceiveEndpointAdversarialTest`: **7 tests** (0 fail)
4. `com.m3rwallet.service.BlockValidationHashTest`: **1 test** (0 fail)
5. `com.m3rwallet.service.CanonicalHeaderAuthenticationTest`: **13 tests** (0 fail)
6. `com.m3rwallet.service.ConsensusCertificateConcurrencyTest`: **3 tests** (0 fail)
7. `com.m3rwallet.service.ConsensusQuorumTest`: **19 tests** (0 fail)
8. `com.m3rwallet.service.ConsensusSecurityAdversarialTest$CertificateAdversarialTests`: **18 tests** (0 fail)
9. `com.m3rwallet.service.ConsensusSecurityAdversarialTest$HistoricalValidatorSetTests`: **2 tests** (0 fail)
10. `com.m3rwallet.service.ConsensusSecurityAdversarialTest$ProposerSignatureTests`: **12 tests** (0 fail)
11. `com.m3rwallet.service.ConsensusSecurityAdversarialTest$SoloModeTests`: **4 tests** (0 fail)
12. `com.m3rwallet.service.HistoricalValidatorSnapshotTest`: **6 tests** (0 fail)
13. `com.m3rwallet.service.MultiNodeConsensusSimulationTest`: **14 tests** (0 fail)
14. `com.m3rwallet.service.PoRPoSWeightValidationTest`: **10 tests** (0 fail)
15. `com.m3rwallet.service.QuorumZeroWeightSecurityTest`: **9 tests** (0 fail)
16. `com.m3rwallet.service.TransactionDoubleSpendingTest`: **6 tests** (0 fail)  
*(Note: Nested classes of `ConsensusSecurityAdversarialTest` sum to $18 + 2 + 12 + 4 = 36$ tests).*

### 6.2 Documentation Reconciliations

In `research/ADVERSARIAL_TEST_RESULTS.md`, a markdown summary table reported 138 tests, but misstated the individual class counts:
- Claimed `BlockFinalizationSecurityTest` had 11 tests (actual: 6).
- Claimed `MultiNodeConsensusSimulationTest` had 4 tests (actual: 14).
- Claimed `BlockValidationHashTest` had 5 tests (actual: 1).
- Claimed `PoRPoSWeightValidationTest` had 13 tests (actual: 10).
- Claimed `TransactionDoubleSpendingTest` had 4 tests (actual: 6).  
The discrepancy was caused by human error when transcribing individual suite counts into markdown. The underlying Surefire XML test count is verified at 138.

In `research/TEST_RESULTS.md`, only 64 tests are documented. That file reflects the historical pre-remediation baseline before the 74 new adversarial security tests were added.

### 6.3 Test Quality & Boundary Analysis

1. **Cryptographic Realism**:
   - The adversarial tests (`CanonicalHeaderAuthenticationTest`, `ConsensusSecurityAdversarialTest`, `BlockReceiveEndpointAdversarialTest`) **do exercise real cryptographic primitives**.
   - They instantiate real secp256k1 curves using BouncyCastle (`CustomNamedCurves.getByName("secp256k1")`), generate legitimate key pairs, sign real 32-byte digests, and perform genuine signature and address derivation checks.
2. **Controller Testing**:
   - `BlockReceiveEndpointAdversarialTest` directly invokes `NodeSyncController.receiveBlock()` and `WalletController.receiveBlock()`.
   - However, repository dependencies (`BlockRepository`, `BlockTransactionRepository`, `FeeDistributionService`) are mocked via Mockito. The tests verify mock interactions (`verify(mockBlockRepo, never()).save(...)`), but do not verify live database constraints or SQL rollback behaviors.
3. **Concurrency Testing**:
   - `ConsensusCertificateConcurrencyTest` genuinely exercises concurrent execution using 32 worker threads, a fixed thread pool, and dual `CountDownLatch` barriers to test race conditions in `BlockConsensusVoteService.certificateCache`.
4. **Testing Blind Spots**:
   - **`PeerSyncService` has zero tests**. The critical bypass at line 274 is completely unexercised by the test suite.
   - **`ValidatorSyncService` is never tested** (only mocked in `PoRPoSWeightValidationTest`). Counter inflation and peer-driven stake overwrites are unexercised.
   - **`BlockSyncService` has zero tests**. The failure of catch-up sync due to missing certificates in `getBlock` was never caught because no integration test attempted catch-up between two real nodes.

---

## 7. Comprehensive Audit Findings & Verdict

### Final Classification: A — NOT SAFE

**Justification:** While the direct block proposal and live broadcast endpoints have received robust cryptographic controls, the node remains vulnerable to **complete consensus bypass and ledger poisoning** via background synchronization tasks (`PeerSyncService` and `ValidatorSyncService`). In any adversarial distributed deployment, an unauthenticated peer can inject invalid blocks that override local canonical history and manipulate consensus weights.

### Detailed Findings Table

| # | Vulnerability Finding | Severity | Exact Source File & Method | Evidence / Root Cause | Security & Research Impact | Verification Status |
| :---: | :--- | :---: | :--- | :--- | :--- | :---: |
| **F-01** | **Consensus Certificate Bypass in Peer Sync** | **CRITICAL** | `PeerSyncService.java`<br>`syncMissingBlocksFromPeers()` (L240–285) | `blockRepository.save(fetched)` called directly at L274 without calling `verifyConsensusCertificate` or `validateReceivedBlock`. | Any peer can deliver forged, unauthenticated blocks that are committed to the local database as finalized. | **Verified** |
| **F-02** | **Arbitrary Local Chain Rollback by Peers** | **CRITICAL** | `PeerSyncService.java`<br>`syncMissingBlocksFromPeers()` (L257–265) | `if (peerHeight > localHeight) rollbackToHeight(ht - 1, network)` unconditionally deletes local finalized blocks. | A malicious peer advertising a longer unverified chain forces the node to delete its canonical ledger. | **Verified** |
| **F-03** | **Unauthenticated Validator Stake & Metric Poisoning** | **CRITICAL** | `ValidatorSyncService.java`<br>`savePeerValidator()` (L167–203) | `v.setStakedAmount(...)` overwritten unconditionally; `Math.max(local, peer)` applied to proposal stats and reliability from untrusted peer JSON. | Peers can forge stake amounts and proposal counts, hijacking PoR+PoS slot leadership and consensus quorum. | **Verified** |
| **F-04** | **Broken Catch-Up Sync Due to Missing Certificates** | **HIGH** | `WalletController.java`<br>`getBlock()` (L610–624)<br>`BlockSyncService.java` (L82–85) | `getBlock` omits `consensusCertificate` from response; `receiveBlock` in `BlockSyncService` fails closed on every synced block. | Honest nodes cannot synchronize historical blocks via the hardened `BlockSyncService` pathway. | **Verified** |
| **F-05** | **Lack of Durable Historical Snapshot Persistence** | **HIGH** | `ValidatorService.java`<br>`snapshotCacheByHash` (L83–84) | Snapshots stored only in in-memory `ConcurrentHashMap`. No database persistence exists. | Restarted or newly joining nodes cannot validate historical certificates from past validator sets. | **Verified** |
| **F-06** | **Signature Malleability via High-$s$ Acceptance** | **MEDIUM** | `CryptoUtil.java`<br>`verifyRawSecp256k1Signature()` (L95–99) | Verification checks $1 \le s < n$, but fails to check $s \le n/2$. `formatRawSignature` does not normalize $s$. | Signatures can be malleated ($s \to n - s$), violating strict consensus specification claims. | **Verified** |
| **F-07** | **Silent Hex Coercion Instead of Parsing Rejection** | **MEDIUM** | `Block.java`<br>`hexToFixedBytes()` (L294–313) | Invalid, short, or long hex strings are silently zero-filled, truncated, or coerced instead of throwing errors. | Allows non-canonical representations to pass through binary header serialization without fail-closed rejection. | **Verified** |
| **F-08** | **Missing Proposal Count Rollback on Reorganization** | **MEDIUM** | `PeerSyncService.java`<br>`rollbackToHeight()` (L315–336) | Blocks are deleted from DB, but `successfulProposals` and `reliabilityScoreScaled` are never decremented. | Orphaned or rolled-back blocks permanently leave validator reputation and proposal metrics inflated. | **Verified** |
| **F-09** | **Specification Discrepancies in Header Tag & Fields** | **LOW** | `CONSENSUS_CERTIFICATE_SPEC.md`<br>vs `Block.java` (L157–204) | Domain tag is 20 bytes (spec: 18); network len is 2 bytes (spec: 4); address is 20 raw bytes (spec: string); hash is SHA-256 (spec: Keccak). | Academic and formal specification documents contradict the actual bytecode implementation. | **Verified** |
| **F-10** | **Untested Sync Services & Peer Catch-Up** | **LOW** | `src/test/java/` (All test suites) | `PeerSyncService`, `ValidatorSyncService`, and `BlockSyncService` have zero unit or integration tests. | Critical consensus bypasses remained undetected despite 138 passing tests. | **Verified** |

---

## 8. Conclusion

The M3R codebase exhibits a common security anti-pattern in distributed systems: **hardened front door, wide-open back door**.

The engineering team focused security hardening on the primary REST ingress endpoint (`receiveBlock`), creating the illusion of security that enabled 138 unit tests to pass. However, secondary background synchronization services (`PeerSyncService` and `ValidatorSyncService`) completely circumvented this hardened perimeter, allowing unauthenticated blocks and poisoned validator metrics to enter the canonical database.

Until `PeerSyncService` is refactored to require full consensus certificate and proposer signature validation, `ValidatorSyncService` is stripped of untrusted peer state updates, and validator snapshots are durably persisted to disk, the M3R node software cannot be certified as safe for distributed Byzantine operation.
