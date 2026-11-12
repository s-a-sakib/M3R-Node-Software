# M3R Blockchain — Consensus Security Remediation Report

**Date:** November 2026  
**Target Branch:** `research/consensus-audit-fixes`  
**Baseline Commit:** `21799ad`  
**Status:** Remediated & Cryptographically Verified  
**Build Status:** 138 Tests Run, 0 Failures, 0 Errors, 0 Skipped  

---

## Executive Summary

Following an independent read-only security audit of the M3R consensus implementation, six security vulnerabilities were identified spanning critical unauthenticated block finalization, ECDSA signature digest truncation, unfinalized state pollution, lack of historical validator set tracking, zero-weight quorum inflation, and concurrent proposal race conditions.

All six vulnerabilities have been patched with defense-in-depth architectural remediations, verified through dedicated end-to-end integration and adversarial test suites using real secp256k1 key generation, canonical serialization, and HTTP controller execution paths.

---

## Detailed Vulnerability Remediation

### 1. M3R-SEC-01: Unauthenticated Block Finalization via Ingestion Endpoints (CRITICAL)

- **Vulnerability:** In `NodeSyncController.java`, the endpoint `POST /api/node/block/receive` directly executed:
  ```java
  block.setIsFinalized(true);
  blockRepository.save(block);
  ```
  allowing any unauthenticated caller to persist an arbitrary block as finalized without verifying proposer signatures or consensus certificates.
- **Root Cause:** Ingestion endpoints bypassed the hardened consensus pipeline implemented in `BlockBroadcastService`.
- **Remediation:**
  - Refactored `NodeSyncController.receiveBlock(Map<String, Object> payload)` to delegate block reception strictly to `BlockBroadcastService.receiveBlock(payload, network)`.
  - Enforced that any block claiming `finalized=true` or `isFinalized=true` MUST provide a cryptographically verified `ConsensusCertificate` satisfying dual quorum ($\ge 2/3$ stake weight, $\ge \lceil 2N/3 \rceil$ count, independent peer confirmation).
  - Blocks submitted without certificates or with invalid certificates fail closed with HTTP `400 Bad Request`.
  - Canonical database persistence (`blockRepo.save()`), transaction ledger commitment, and fee distribution are strictly guarded behind certificate verification.
- **Verification:** `BlockReceiveEndpointAdversarialTest.java` (7 adversarial tests verifying unauthenticated rejection, forged signatures, invalid certificates, in-memory staging, and wallet controller enforcement).

---

### 2. M3R-SEC-02: Proposer Signature Digest Truncation & Asymmetric Serialization (CRITICAL)

- **Vulnerability:**
  - Arbitrary-length serialized header bytes (~177 bytes in proposal signing vs ~129 bytes in verification) were passed directly to BouncyCastle's `ECDSASigner.generateSignature(byte[])`.
  - Because `ECDSASigner` expects a pre-hashed digest, it truncated arbitrary bytes to 256 bits (the curve bit-length), dropping trailing fields including `validatorSetHash`, `nonce`, and `txCount`.
  - Proposer proposal signing and verification used mismatched serializations, omitting weight and validator set hash during verification.
- **Remediation:**
  - Defined a single canonical binary serialization standard `M3R_BLOCK_HEADER_V1` in `Block.serializeCanonicalHeader()` using big-endian byte-order and deterministic field order:
    1. Domain Separation Tag (`"M3R_BLOCK_HEADER_V1"`)
    2. Version (`byte`)
    3. Network (`UTF-8` string, length-prefixed)
    4. Block Height (`int64`)
    5. Slot Number (`int64`)
    6. Parent Block Hash (`byte[32]` decoded from hex)
    7. Merkle Root (`byte[32]` decoded from hex)
    8. State Root (`byte[32]` decoded from hex)
    9. Timestamp (`int64`)
    10. Proposer Address (`UTF-8` string, length-prefixed)
    11. Proposer Weight (`double` IEEE 754 raw long bits)
    12. Validator-Set Hash (`byte[32]` decoded from hex)
    13. Nonce (`int64`)
    14. Transaction Count (`int32`)
  - Both signing (`BlockProposalService.buildBlock`) and verification (`BlockValidationService.validateReceivedBlock`) now compute the 32-byte cryptographic digest:
    $$\text{Digest} = \text{Keccak-256}(\text{M3R\_BLOCK\_HEADER\_V1})$$
  - Proposer signatures are strictly 64-byte raw secp256k1 signatures ($r \parallel s$) with canonical low-$s$ and curve range validation ($1 \le r < n, 1 \le s < n$).
- **Verification:** `CanonicalHeaderAuthenticationTest.java` (13 tests verifying authentic block validation, and detecting single-field tampering across all 10 consensus header fields).

---

### 3. M3R-SEC-03: Ledger State Pollution by Unfinalized Block Proposals (HIGH)

- **Vulnerability:** Unfinalized block proposals broadcasted across peers could be saved to `blockRepo`, prematurely evicting mempool transactions, triggering spurious fork conflicts, or distributing fees prior to consensus quorum.
- **Remediation:**
  - Added an in-memory staging table `ConcurrentHashMap<String, Map<String, Object>> unfinalizedProposals` in `BlockBroadcastService`.
  - Incoming blocks with `finalized=false` (pre-consensus proposals) are validated for structural correctness, slot leadership, and proposer signatures, then staged in memory with status `STAGED_UNFINALIZED` (HTTP 202 Accepted).
  - Unfinalized proposals NEVER touch `blockRepo`, NEVER clear mempool transactions, NEVER distribute fees, and NEVER mutate validator balances.
  - When the consensus certificate arrives for that height and hash, the block is finalized cleanly. If another proposal was staged, it does not trigger a database fork conflict.
- **Verification:** `BlockReceiveEndpointAdversarialTest.testUnfinalizedProposalStagedWithoutPollutingLedger` and `testFinalizedBlockSucceedsAfterPreviousUnfinalizedProposal`.

---

### 4. M3R-SEC-04: Historical Validator-Set Verification Gap (HIGH)

- **Vulnerability:** Historical blocks received during catch-up or re-sync were validated against current active validators rather than the validator set that signed the block. Stake changes or validator churn broke past block verification.
- **Remediation:**
  - Introduced `ValidatorSetSnapshot` in `ValidatorService` recording deterministic snapshots indexed by canonical `validatorSetHash`.
  - Every block header commits to `validatorSetHash`.
  - Canonical validator set hash algorithm:
    $$\text{Hex}(\text{Keccak-256}(\text{SortedActiveValidators}(\text{address} : \text{stake} : \text{reliability})))$$
  - During certificate verification in `BlockBroadcastService`, if `claimedValSetHash != currentHash`, the node queries `validatorService.getSnapshotByHash(claimedValSetHash)`.
  - If a snapshot exists, certificate votes are evaluated against historical validator weights.
  - If no snapshot is found for an unfamiliar validator set hash, validation fails closed (`REJECTED`).
- **Verification:** `HistoricalValidatorSnapshotTest.java` (6 tests verifying stake changes, deactivations, missing snapshot fail-closed, and cross-node hash determinism).

---

### 5. M3R-SEC-05: Quorum Inflation by Inactive / Zero-Weight Validators (MEDIUM)

- **Vulnerability:** In `ConsensusQuorum.evaluateVotes()`, zero-weight or inactive validators in `activeValidatorWeights` were included in total validator count $N$, allowing zero-weight validators to satisfy count quorum ($\ge \lceil 2N/3 \rceil$) and independent peer confirmation ($N > 1$).
- **Remediation:**
  - Filtered `activeValidatorWeights` in `ConsensusQuorum.evaluateVotes()` to strictly eligible validators holding weight $w > 0.0$ and non-blank address.
  - Zero-weight validators cannot contribute to `yesVotes`, `approvedWeight`, or `peerConfirmations`.
  - Total validator count $N$ strictly represents eligible staked validators.
  - Added IEEE 754 summation rounding tolerance ($10^{-9}$) in `isReached` to prevent supermajority rejection due to floating-point summation discrepancies across identical-weight validators.
- **Verification:** `QuorumZeroWeightSecurityTest.java` (9 tests verifying dominant proposer without peer, zero-weight peer rejection, multiple zero-weight peers, and count vs weight threshold mismatches).

---

### 6. M3R-SEC-06: Mutable Shared State Race Condition in Consensus Votes (LOW)

- **Vulnerability:** `BlockConsensusVoteService` stored consensus certificates in a shared `private volatile Map<String, Object> lastCertificate`. Concurrent block proposals on different heights or networks could overwrite each other's certificates, leading to cross-talk.
- **Remediation:**
  - Replaced mutable shared state with `certificateCache = new ConcurrentHashMap<String, Map<String, Object>>()`, keyed by `network + ":" + height + ":" + hash`.
  - Refactored `collectVotes` to return an immutable `VoteCollectionResult` record encapsulating `consensusReached`, `blockHeight`, `blockHash`, `network`, `certificate`, `tally`, and `failureReason`.
  - Cached certificates are strictly isolated by block height, hash, and network.
- **Verification:** `ConsensusCertificateConcurrencyTest.java` (3 multi-threaded concurrency tests verifying no cross-contamination under concurrent proposals across distinct threads and heights).

---

## Summary of Verification Suites

| Test Suite File | Tests | Focus Area | Result |
|---|---|---|---|
| `CanonicalHeaderAuthenticationTest.java` | 13 | Canonical header serialization, 32-byte Keccak-256 digest, 10-field tampering | **PASSED** |
| `BlockReceiveEndpointAdversarialTest.java` | 7 | HTTP endpoint ingestion, certificate enforcement, unauthenticated rejection, in-memory staging | **PASSED** |
| `HistoricalValidatorSnapshotTest.java` | 6 | Historical snapshot lookup, validator churn, missing snapshot fail-closed, cross-node determinism | **PASSED** |
| `QuorumZeroWeightSecurityTest.java` | 9 | Zero-weight peer rejection, Sybil peer resistance, count/weight dual quorum | **PASSED** |
| `ConsensusCertificateConcurrencyTest.java` | 3 | Thread-safe certificate cache, isolation across heights and networks | **PASSED** |
| `ConsensusSecurityAdversarialTest.java` | 36 | Consensus certificate structure, solo-mode gating, peer confirmation | **PASSED** |
| `ConsensusQuorumTest.java` | 19 | Dual-quorum edge cases, threshold boundaries | **PASSED** |
| `LocalhostOnlyInterceptorTest.java` | 8 | Localhost IP spoofing, header injection defenses | **PASSED** |
| `BlockFinalizationSecurityTest.java` | 11 | Block finalization guarantees, rollback protection | **PASSED** |
| `MultiNodeConsensusSimulationTest.java` | 4 | Multi-node consensus simulation | **PASSED** |
| `TransactionDoubleSpendingTest.java` | 4 | Nonce serialization, anti-double-spending | **PASSED** |
| `PoRPoSWeightValidationTest.java` | 13 | Stake & reliability weight calculation | **PASSED** |
| `BlockValidationHashTest.java` | 5 | Block hash computation and validation | **PASSED** |
| **Total Project Test Suite** | **138** | **Full System Regression & Security Verification** | **ALL PASSED** |
