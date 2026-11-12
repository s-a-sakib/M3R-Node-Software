# M3R Node Software — Final Adversarial Test Results

**Project:** `~/Desktop/M3R_Coin/Node_Software/`  
**Branch:** `research/consensus-audit-fixes`  
**Baseline:** `21799ad`  
**Execution Date:** November 12, 2026  
**Environment:** OpenJDK 64-Bit Server VM (build 21.0.10+7), Spring Boot 3.2.0, BouncyCastle bcprov-jdk15on 1.70, Maven 3.6.3  
**Overall Verdict:** **160 PASSED, 0 FAILED, 0 ERRED, 0 SKIPPED**

---

## 1. Test Suite Summary Matrix

$$\text{Total Executed Tests: } \mathbf{160} \quad\mid\quad \text{Failures: } \mathbf{0} \quad\mid\quad \text{Errors: } \mathbf{0} \quad\mid\quad \text{Skipped: } \mathbf{0}$$

| Test Class | Category / Focus | Tests | Status |
|---|---|:---:|:---:|
| `com.m3rwallet.config.LocalhostOnlyInterceptorTest` | Admin Endpoint Localhost Isolation & Proxy Header Protection | 8 | **PASS** |
| `com.m3rwallet.service.BlockFinalizationSecurityTest` | Dual-Quorum Enforcement & Solo Mode Gating | 6 | **PASS** |
| `com.m3rwallet.service.BlockReceiveEndpointAdversarialTest` | REST Ingress Certificate Verification & Proposal Staging | 7 | **PASS** |
| `com.m3rwallet.service.BlockValidationHashTest` | Canonical Block Hash Determinism & Format | 1 | **PASS** |
| `com.m3rwallet.service.CanonicalHeaderAuthenticationTest` | 13-Field Header Tampering Resistance | 13 | **PASS** |
| `com.m3rwallet.service.ConsensusCertificateConcurrencyTest` | Concurrent Multi-Threaded Certificate Cache Stress (32 Threads) | 3 | **PASS** |
| `com.m3rwallet.service.ConsensusQuorumTest` | Dual Quorum Boundary & Weight Combinations | 19 | **PASS** |
| `com.m3rwallet.service.ConsensusSecurityAdversarialTest$CertificateAdversarialTests` | Forged Voter Lists, Mismatched Hashes, Invalid Signatures | 18 | **PASS** |
| `com.m3rwallet.service.ConsensusSecurityAdversarialTest$HistoricalValidatorSetTests` | Validator-Set Commitment Integrity & Mismatch Rejection | 2 | **PASS** |
| `com.m3rwallet.service.ConsensusSecurityAdversarialTest$ProposerSignatureTests` | Proposer secp256k1 Signature Verification & Slot Leadership | 12 | **PASS** |
| `com.m3rwallet.service.ConsensusSecurityAdversarialTest$SoloModeTests` | Solo Gating & Peer Multi-Node Verification | 4 | **PASS** |
| `com.m3rwallet.service.CryptographicCanonicalizationAdversarialTest` | Strict Low-$s$ secp256k1, Malformed Hex, Empty-Block Merkle Root | 7 | **PASS** |
| `com.m3rwallet.service.HistoricalSnapshotRestartAuditTest` | Relational Snapshot Persistence, Restart Recovery & Tamper Detection | 3 | **PASS** |
| `com.m3rwallet.service.HistoricalValidatorSnapshotTest` | Historical Snapshot Verification Across Stake and Roster Evolution | 6 | **PASS** |
| `com.m3rwallet.service.MultiNodeConsensusSimulationTest` | Multi-Validator Mesh Voting & Partition Simulations | 14 | **PASS** |
| `com.m3rwallet.service.PeerSyncSecurityAdversarialTest` | Peer Catch-Up Forged Block Injection, Rollback Attacks & Fork Resolution | 5 | **PASS** |
| `com.m3rwallet.service.PoRPoSWeightValidationTest` | PoR+PoS Weight Calculations, Alpha Blending & Slashing | 10 | **PASS** |
| `com.m3rwallet.service.ProposalStatisticsAuditTest` | Idempotent Proposal Counters & Reorganization Rollback | 2 | **PASS** |
| `com.m3rwallet.service.QuorumZeroWeightSecurityTest` | Inactive & Zero-Weight Validator Exclusion from Quorums | 9 | **PASS** |
| `com.m3rwallet.service.TransactionDoubleSpendingTest` | Strict Concurrency & Anti-Double-Spending Controls | 6 | **PASS** |
| `com.m3rwallet.service.ValidatorSyncSecurityAdversarialTest` | Unauthenticated Stake/Reliability/Proposal Poisoning Rejection | 5 | **PASS** |

---

## 2. In-Depth Breakdown of New Security Test Suites

### 2.1 PeerSyncSecurityAdversarialTest (F-01, F-02, F-04)
Exercises the background peer block synchronization pathways in `PeerSyncService`:

1. `testPeerSyncRejectsForgedBlockInjection`:
   - **Scenario**: A connected peer advertises a higher block height and serves a block payload containing a forged proposer signature (`"f".repeat(128)`).
   - **Verification**: Routed to `blockBroadcastService.receiveBlock()`, validation fails closed with `PROPOSER_SIGNATURE_INVALID`. The block is **never saved** to `BlockRepository`.
2. `testPeerSyncRejectsMissingCertificateBlock`:
   - **Scenario**: A peer serves a block marked `isFinalized: true` but omits the `consensusCertificate`.
   - **Verification**: `receiveBlock()` rejects the block as uncertified; synchronization halts; canonical database remains untouched.
3. `testRollbackAttackOnFinalizedBlocksDenied`:
   - **Scenario**: `rollbackToHeight(4L)` is invoked when block 5 is already marked `isFinalized = true`.
   - **Verification**: Throws `IllegalStateException("Cannot rollback finalized block at height 5")`; finalized block is **never deleted**.
4. `testPeerSyncForkConflictAtFinalizedHeightHaltsSync`:
   - **Scenario**: A malicious peer reports a higher chain height (height 3) and provides a block at height 2 whose hash conflicts with local finalized block 2.
   - **Verification**: Fork conflict detected; peer chain is rejected; local finalized history is preserved.
5. `testPeerSyncSuccessfullyFinalizesValidCertifiedBlock`:
   - **Scenario**: An honest peer delivers a valid block with authentic dual-quorum consensus certificate and valid proposer signature.
   - **Verification**: Passes validation pipeline; saved to `BlockRepository` with status `isFinalized = true`.

### 2.2 ValidatorSyncSecurityAdversarialTest (F-03)
Exercises validator state synchronization pathways in `ValidatorSyncService`:

1. `testPeerCannotForgeStake`:
   - **Scenario**: Peer JSON response reports an inflated stake of `999,999,999` for an existing validator.
   - **Verification**: Authoritative local stake (5,000) is preserved. No database update occurs.
2. `testPeerCannotInflateReliabilityScore`:
   - **Scenario**: Peer JSON response reports an inflated reliability score of `1,000,000` (1.0).
   - **Verification**: Local authoritative score (0.50) is preserved. Peer values are disregarded.
3. `testPeerCannotInflateProposalCounters`:
   - **Scenario**: Peer JSON response reports `successfulProposals: 1,000,000`.
   - **Verification**: Local counter remains at 3.
4. `testUnknownPeerValidatorCannotBeInjected`:
   - **Scenario**: Peer reports an unknown address claiming 500,000 stake and `ACTIVE` status.
   - **Verification**: Rejected; unknown validator is not added to active voting sets.
5. `testFullSyncPassRejectsPoisonedPeerRoster`:
   - **Scenario**: Executes `syncValidatorSet()` against a mock peer returning completely forged stakes, scores, and proposal counts.
   - **Verification**: Entire local validator set remains identical to authoritative state.

### 2.3 CryptographicCanonicalizationAdversarialTest (F-06, F-07, F-09)
Exercises cryptographic edge cases, low-$s$ validation, and malformed inputs:

1. `testHighSSignatureRejected`:
   - **Scenario**: Signs 32-byte Keccak-256 digest using real secp256k1 key; forces $s > n/2$ ($s = n - s$).
   - **Verification**: `CryptoUtil.verifyVoteSignature` returns `false` for high-$s$ signature, but `true` for canonical low-$s$ signature.
2. `testMalformedHexFieldsThrowIllegalArgumentException`:
   - **Scenario**: Passes short hex, non-hex characters, and null required fields to `Block.serializeCanonicalHeader()`.
   - **Verification**: Throws `IllegalArgumentException`; silent zero-padding/truncation eliminated.
3. `testNullValidatorSetHashThrowsException`:
   - **Scenario**: Passes null `validatorSetHash` to canonical serialization.
   - **Verification**: Throws `IllegalArgumentException("Canonical header validatorSetHash cannot be null or blank")`.
4. `testValidationRejectsMissingValidatorSetHash`:
   - **Scenario**: Submits block payload omitting `validatorSetHash` to `validateReceivedBlock()`.
   - **Verification**: Returns `valid = false` with violation `MISSING_VALIDATOR_SET_HASH`.
5. `testValidationRejectsMalformedStateRoot`:
   - **Scenario**: Submits block payload with malformed `stateRoot`.
   - **Verification**: Returns `valid = false` with violation `MALFORMED_STATE_ROOT`.
6. `testEmptyBlockMerkleRootEnforcement`:
   - **Scenario**: Evaluates empty block (`txCount = 0`, empty transaction list) with arbitrary Merkle root vs. canonical empty root.
   - **Verification**: Arbitrary root rejected with `MERKLE_ROOT_MISMATCH`; canonical empty root (`Keccak-256("empty")`) accepted.
7. `testBlockHashHeaderDigestBinding`:
   - **Scenario**: Block payload claims a `blockHash` inconsistent with header fields.
   - **Verification**: Rejected with `BLOCK_HASH_MISMATCH`.

### 2.4 ProposalStatisticsAuditTest (F-08)
Exercises proposal metric accounting and idempotency:

1. `testProposalAccountingIdempotence`:
   - **Scenario**: Proposer finalizes block; `recordSuccessfulProposal` is called 3 consecutive times (simulating delivery from multiple peers).
   - **Verification**: Counter increments exactly once (to 11); subsequent calls detect identical canonical block count and leave counter and reliability unchanged.
2. `testReorganizationRollsBackProposalMetrics`:
   - **Scenario**: Chain reorganization rolls back 2 orphaned blocks (canonical count drops from 20 to 18).
   - **Verification**: Reconciling validator statistics reduces `successfulProposals` from 20 to 18 and decrements reliability by 0.02.

### 2.5 HistoricalSnapshotRestartAuditTest (F-05)
Exercises relational database snapshot persistence and process restarts:

1. `testSnapshotDurablePersistence`:
   - **Scenario**: `ValidatorService.recordSnapshot()` records active validator set for height 10.
   - **Verification**: Persisted into `ValidatorSnapshotRepository` entity with full JSON details.
2. `testPostRestartSnapshotReload`:
   - **Scenario**: A fresh `ValidatorService` instance is instantiated and initialized via `@PostConstruct initSnapshots()`.
   - **Verification**: Restores snapshot from repository; hash matches; retrieved snapshot contains all original validators and weights.
3. `testTamperedSnapshotInDatabaseFailsClosed`:
   - **Scenario**: Modifies stored snapshot JSON in database so that stored contents do not hash to `snapshot_hash`.
   - **Verification**: `deserializeSnapshotEntity()` detects tampering and returns `null` (fails closed).

---

## 3. Cryptographic Realism & Boundary Guarantees

All tests adhere strictly to real cryptographic standards:
- **Elliptic Curve Primitives**: Real secp256k1 curve parameters instantiated via BouncyCastle's `CustomNamedCurves.getByName("secp256k1")`.
- **Key Pairs**: Real 256-bit private keys and 33-byte compressed public keys generated via `ECKeyPairGenerator`.
- **Signatures**: Formatted as 64-byte raw $r \parallel s$ byte arrays with strict canonical low-$s$ ($s \le n/2$).
- **Digests**: 32-byte Keccak-256 digests computed over deterministic binary headers and canonical vote strings.
- **Address Derivation**: 20-byte Base58Check addresses derived deterministically from compressed public keys.
