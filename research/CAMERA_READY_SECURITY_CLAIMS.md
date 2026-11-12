# Camera-Ready Security & Consensus Claims

## Purpose

This document provides strict academic guidance for the research paper and conference publication describing M3R Coin. It demarcates claims that are mathematically and empirically demonstrated by the codebase and tests from claims that must NOT be made.

---

## 1. Supported Claims (Demonstrably Verified by Code and Tests)

The following claims are verified by automated tests, cryptographic implementations, and database persistence:

1. **Dual-Condition Conjunctive Quorum**:
   - The node enforces both stake-weighted reputation quorum ($\frac{W_{yes}}{W_{total}} \ge \frac{2}{3}$) AND raw validator-count quorum ($Votes_{yes} \ge \lceil\frac{2N}{3}\rceil$).
   - Consensus fails if either condition is not met.
   - Zero-weight or inactive validators are filtered out and cannot contribute to quorum or satisfy peer confirmations.

2. **Cryptographically Authenticated Consensus Certificates**:
   - Received blocks claiming `finalized=true` cannot finalize without a cryptographically verified `ConsensusCertificate`.
   - Each vote in the certificate contains a valid secp256k1 signature over a canonical digest committing to `network`, `blockHeight`, `blockHash`, and `voterAddress`.
   - Public keys are strictly validated and checked against the derived validator address.
   - Forged voter lists, missing signatures, tampered metadata, and duplicate votes are rejected.
   - All HTTP ingestion endpoints (`POST /api/node/block/receive`, `POST /{network}/blocks/receive`, and catch-up sync) enforce consensus certificates.

3. **Canonical Binary Header Serialization (`M3R_BLOCK_HEADER_V1`) & Digest Hashing**:
   - The block header is serialized using deterministic binary big-endian encoding incorporating a 20-byte domain separation tag (`"M3R_BLOCK_HEADER_V1"` + `0x00`) and 13 consensus parameters (`version`, `network`, `blockHeight`, `slotNumber`, `parentBlockHash`, `merkleRoot`, `stateRoot`, `timestamp`, `proposerAddress`, `proposerWeight`, `validatorSetHash`, `nonce`, `txCount`).
   - Proposer signing and verification execute over the exact 32-byte cryptographic digest $\text{Keccak-256}(\text{CanonicalHeaderBytes})$, preventing truncation or length-extension vulnerabilities.
   - Single-bit or field tampering across any header parameter invalidates the proposer signature.

4. **Strict Low-$s$ secp256k1 Signature Enforcement**:
   - Strict canonical low-$s$ verification ($1 \le r < n, 1 \le s \le n/2$) is enforced for all proposer and vote signatures.
   - High-$s$ signatures ($s > n/2$) are strictly rejected during verification, eliminating ECDSA signature malleability.
   - Signer normalizes $s \to n - s$ when $s > n/2$.

5. **Uniform Validation Perimeter Across Peer Sync & Catch-Up**:
   - Background peer catch-up synchronization routes every externally fetched block through the identical shared validation pipeline.
   - Direct persistence without validation is completely eliminated.
   - Blocks missing certificates or carrying invalid signatures fail closed without entering canonical storage, distributing fees, confirming transactions, or clearing mempools.

6. **Finality Invariance & Rollback Prevention**:
   - A peer's reported height or alternative chain can never authorize the rollback or deletion of local finalized blocks.
   - Any attempt to roll back finalized state throws an `IllegalStateException` and aborts.
   - Fork conflicts at finalized heights halt synchronization and preserve local canonical history.

7. **Authoritative Validator State & Metric Integrity**:
   - Local validator stake, reliability scores, and proposal statistics are authoritative and immune to unauthenticated peer JSON updates.
   - `Math.max(local, peer)` heuristics are prohibited.
   - Proposal counters are derived deterministically and idempotently from canonical finalized blocks.

8. **Durable Relational Historical Snapshots Across Cold Restarts**:
   - Validator set snapshots are durably stored in MySQL (`validator_snapshots` table via Flyway `V002`).
   - Upon deserialization, stored snapshots recompute their SHA-256 hash from stored contents, failing closed if tampered.
   - On cold process restarts, snapshots are preloaded into cache, enabling newly booted nodes to verify historical blocks.

9. **Strict Slot Leadership and Proposer Authentication**:
   - Proposers must be registered, active validators and the deterministic leader elected by the PoR+PoS slot selection algorithm.
   - Proposer signatures cannot be skipped, blank, or set to placeholder strings.

10. **In-Memory Staging of Unfinalized Proposals**:
    - Pre-consensus proposals are staged strictly in-memory (`STAGED_UNFINALIZED`).
    - Unfinalized proposals never touch canonical storage, never evict mempool transactions, and never distribute fees.

11. **Anti-Double-Spending Protection**:
    - Simultaneous conflicting transactions with identical nonces are strictly serialized; only the first commits, while subsequent attempts are rejected.

---

## 2. Unsupported Claims (Must NOT Be Made in Publication)

To maintain scientific rigor and academic integrity, the following claims must be avoided:

1. **Do NOT Claim Full Asynchronous Byzantine Fault Tolerance (BFT)**:
   - M3R operates on synchronized 15-second block slots and assumes weak synchrony for slot leadership. It does not provide the formal asynchronous liveness guarantees of protocols like PBFT, HotStuff, or Tendermint.

2. **Do NOT Claim Production Readiness**:
   - The current software is a research prototype. It does not implement peer-to-peer gossip networking, production state pruning, or formal Byzantine failure recovery harnesses.

3. **Do NOT Claim Static Public Key Pinning in SQL Schema**:
   - The `Validator` entity persists validator addresses, stakes, and reliability, but not raw secp256k1 public keys. Public keys are provided in proposals and certificates and verified via cryptographic address derivation.

4. **Do NOT Claim Distributed Finality in Solo Mode**:
   - Solo mode is an explicit developer fallback without peer verification or Byzantine resistance.

5. **Do NOT Claim Optimal Alpha ($\alpha = 0.15$) Without Empirical Proof**:
   - The prototype parameter $\alpha = 0.15$ is a configurable weighting factor, not a mathematically proven optimum.

6. **Do NOT Claim Physical Multi-Machine Distributed Benchmarks**:
   - The test suite includes comprehensive in-process protocol simulations and local multi-process tests, but these must not be conflated with wide-area network (WAN) or multi-datacenter physical deployments.
