# M3R Node Software — Security Findings & Threat Analysis

**Document:** `research/SECURITY_FINDINGS.md`  
**Date:** November 12, 2026  
**Target Codebase:** `Node_Software`  
**Classification:** Security Vulnerability & Hardening Audit  

---

## 1. Threat Model & Trust Assumptions

The M3R cryptocurrency network operates in a permissioned-to-semi-permissioned validator model with self-custodial Android wallet clients. The security analysis considers the following adversarial threats:
1. **Malicious / Byzantine Validator:** A node that attempts to propose blocks outside its designated slot, propose conflicting blocks at the same height, vote multiple times, or finalize blocks without sufficient peer approval.
2. **Crash-Fault / Offline Peer:** Nodes that drop offline or suffer network delays.
3. **Malicious Client / Double-Spender:** A client attempting to spend the same funds twice across concurrent requests or exploit race conditions in database transaction isolation.
4. **Network Partition (Split-Brain):** An adversary that splits the peer-to-peer overlay into two isolated partitions.

---

## 2. Vulnerability Catalog & Remediation

### SEC-01: Disjunctive Consensus Quorum (Critical — Fixed)
- **Vulnerability:** The legacy transaction consensus rule implemented:
  $$\text{Pass} \iff \text{WeightedQuorum} \lor \text{CountQuorum}$$
- **Impact:** A single validator possessing $\ge 66.7\%$ of network weight could unilaterally approve transactions without peer confirmation. Alternatively, small nodes possessing $< 66.7\%$ of weight could pass transactions if raw vote count was satisfied, breaking weighted PoR+PoS guarantees.
- **Remediation:** Enforced strict atomic conjunction in `ConsensusQuorum`:
  $$\text{Consensus} = \left(\frac{W_{yes}}{W_{total}} \ge \frac{2}{3}\right) \land \left(Votes_{yes} \ge \left\lceil\frac{2N}{3}\right\rceil\right) \land \left(\text{PeerConfirmations} > 0 \text{ when } N > 1\right)$$
- **Verification:** Tested in `ConsensusQuorumTest` (19 tests).

---

### SEC-02: Unauthenticated Block Finalization (Critical — Fixed)
- **Vulnerability:** In `BlockBroadcastService`, incoming blocks with `isFinalized: true` were immediately accepted as finalized and fees were distributed without verifying peer consensus.
- **Impact:** A single rogue node could broadcast a fabricated block claiming `isFinalized: true`, forcing receiving nodes to prematurely finalize the block and credit fees to the attacker.
- **Remediation:** Implemented verifiable `ConsensusCertificate` verification. In a multi-node network, a received block without an authentic certificate containing $\ge 2/3$ weight and $\ge \lceil 2N/3 \rceil$ active validator votes is saved as **UNFINALIZED**, and fee distribution is withheld.
- **Verification:** Tested in `BlockFinalizationSecurityTest`.

---

### SEC-03: Block Header Proposer Impersonation (High — Fixed)
- **Vulnerability:** `BlockValidationService` checked whether `proposerAddress` was active, but failed to verify the secp256k1 cryptographic signature of the block proposer.
- **Impact:** Any entity could forge a block header attributing proposal to an active validator without holding their private key.
- **Remediation:** Implemented secp256k1 signature verification in `BlockValidationService` over canonical block header bytes using `CryptoUtil.verifyRawSecp256k1Signature`. Added check that `proposerAddress` matches the recovered public key address.
- **Verification:** Tested in `BlockFinalizationSecurityTest`.

---

### SEC-04: Unauthorized Slot Leadership (High — Fixed)
- **Vulnerability:** The node did not verify whether the proposer was the elected leader for the block's `slotNumber`.
- **Impact:** Any active validator could propose blocks in slots assigned to other validators, disrupting deterministic round-robin or PoR+PoS scheduling.
- **Remediation:** Added `PROPOSER_NOT_SLOT_LEADER` enforcement in `BlockValidationService` using `ValidatorService.selectValidatorForSlot`.
- **Verification:** Tested in `BlockFinalizationSecurityTest`.

---

### SEC-05: Sybil Vote Inflation via Unregistered Peers (Medium — Fixed)
- **Vulnerability:** When collecting peer votes, votes from unregistered IP addresses or unknown voter strings received a default weight of `1.0d`, and `totalWeight` defaulted to `peerCount`.
- **Impact:** Attackers could register arbitrary peers and manipulate quorum calculations.
- **Remediation:** Unregistered or inactive nodes contribute `0.0d` weight. Strict deduplication is enforced on voter addresses against the active validator database.
- **Verification:** Tested in `ConsensusQuorumTest` and `MultiNodeConsensusSimulationTest`.

---

### SEC-06: Concurrency & Double-Spending Race Conditions (Verified Safe)
- **Investigation:** Explored race conditions when submitting conflicting transactions with the same nonce concurrently.
- **Findings:** `WalletService.executeTransaction` operates under `Isolation.SERIALIZABLE` and acquires pessimistic locks via `SELECT * FROM accounts WHERE network = :network AND address = :address FOR UPDATE`.
- **Outcome:** The first transaction acquires the row lock, executes, increments the nonce, and updates the balance. The second transaction blocks, then re-evaluates the expected nonce, detects a nonce mismatch (`Invalid nonce`), and rolls back cleanly without partial state corruption.
- **Verification:** Validated under multi-threaded concurrency in `TransactionDoubleSpendingTest`.

---

## 3. Known Limitations & Architectural Boundaries

1. **No Asynchronous Byzantine Fault Tolerant (BFT) View-Change:** The current prototype relies on slot-based timeouts (15s slot duration). If an elected slot leader is offline, the network advances to the next slot without a multi-round PBFT view-change protocol.
2. **Certificate Aggregation Overhead:** Consensus certificates carry list of voter addresses and signatures in JSON format. In large validator sets ($N > 100$), this approach scales linearly; transition to aggregate BLS signatures (Boneh-Lynn-Shacham) is recommended for production.
3. **Database Concurrency on New Accounts:** For new accounts that do not yet have a row in `accounts` table, pessimistic row locking (`FOR UPDATE`) cannot lock a non-existent row. Unique constraint `(network, address)` prevents duplicate inserts, but atomic mempool reservation is recommended for production.
