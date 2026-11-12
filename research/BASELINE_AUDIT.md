# M3R Local Codebase — Baseline Consensus & Architectural Audit

**Document:** `research/BASELINE_AUDIT.md`  
**Date:** November 12, 2026  
**Target Codebase:** `~/Desktop/M3R_Coin/Node_Software`  
**Branch:** `research/consensus-audit-fixes` (branched from commit `21799ad: Everything Synchronized`)  
**Auditor:** Antigravity Autonomous Security & Consensus Researcher  

---

## 1. Executive Summary & Repository State

This baseline audit evaluates the consensus, transaction validation, cryptographic integrity, and block finalization pipelines in the local M3R cryptocurrency node implementation (`Node_Software`). 

### Key Findings
1. **Active Consensus Mode:** The node is actively configured with `selection-mode: round-robin` in `application.yml` and operational scripts (`sources.txt`). The academic PoR+PoS formula ($W_v = 0.15\ln(1+S_v) + 0.85 R_v$) is present in source code but is deactivated by default configuration.
2. **Consensus Quorum Bug (OR vs. AND):** In the transaction consensus pipeline (`NodeConsensusService`), the legacy code implemented `if (!weightedConsensus && !countConsensus) { reject }`, which via De Morgan's laws evaluates to an **OR** condition (`weightedConsensus || countConsensus`), violating the formal specification ($R \ge \frac{2}{3} \land Votes_{yes} \ge \lceil\frac{2N}{3}\rceil$).
3. **Block Finalization Vulnerability:** A remote node receiving a block payload containing `finalized: true` or `isFinalized: true` immediately persisted the block as finalized and triggered fee distribution without requiring or verifying a cryptographic consensus certificate or quorum proof.
4. **Proposer Signature Omission:** `BlockValidationService` validated block height, timestamp skew, and Merkle root, but did not cryptographically verify the block proposer's secp256k1 signature against the registered validator identity.
5. **Database & Concurrency:** Transaction execution uses `Isolation.SERIALIZABLE` and `findForUpdate` pessimistic locking on sender accounts, but new accounts without existing rows in `accounts` table bypass row locks prior to insertion, requiring strict unique constraint handling.

---

## 2. Environment, Build System & Compilation Diagnostics

- **Operating System:** Linux x86_64 (`kernel 6.8.0-147-generic`, Ubuntu 24.04 LTS)
- **Java SDK:** OpenJDK 21.0.12.1 (build `21.0.12.1+1-1-24.04.4-Ubuntu`)
- **Build Tool:** Apache Maven 3.8.7
- **Database Engine:** MySQL 8.0+ running on `localhost:3306` with active schemas (`m3rwallet_load_3000`, `m3rwallet_load_4000`, `m3rwallet_load_5000`, `m3rwallet_db`)
- **Build Output:**
  ```bash
  mvn clean test-compile
  [INFO] Building M3R Wallet Server 1.0.0
  [INFO] BUILD SUCCESS
  ```
- **Compilation Diagnostics:** Zero compiler errors. Compiler warnings relate primarily to unchecked generic type casts in legacy controller payload maps (`Map<String, Object>`).

---

## 3. Existing Tests & Test Suite Execution

The existing unit tests in `src/test/` executed with zero failures:

| Test Class | Test Name / Scope | Results | Notes |
| :--- | :--- | :--- | :--- |
| `LocalhostOnlyInterceptorTest` | Admin endpoint security & reverse-proxy headers | 8 run, 0 fail | Verifies loopback enforcement |
| `ConsensusQuorumTest` | Quorum threshold evaluation (`ConsensusQuorum`) | 6 run, 0 fail | Verifies mathematical boundary conditions |
| `BlockValidationHashTest` | Block header hash computation & tampering detection | 1 run, 0 fail | Verifies header field commitment |
| **Total** | | **15 run, 0 fail** | Duration: 1.9s |

---

## 4. Active Consensus Configuration Inspection

Configuration loaded from `Node_Software/src/main/resources/application.yml`:

```yaml
app:
  consensus:
    enabled: true
    timeout-ms: 2500
    shared-secret: "local-test-secret"
    peers:
      - http://localhost:3000
      - http://localhost:4000
      - http://localhost:5000

  validator:
    enabled: true
    minimum-stake: 1000
    slot-duration-ms: 15000
    startup-proposal-delay-ms: 15000
    selection-mode: round-robin          # <--- ACTIVE CONFIGURATION
    sync-interval-ms: 30000
    sync-initial-delay-ms: 10000
    unbonding-period-blocks: 100
    reputation-decay-rate: 0.999
    initial-reputation: 0.0
    require-balance: false

  fee-policy:
    broadcast-fee-bps: 2000
    consensus-fee-bps: 8000
    base-fee: 100
    per-byte-fee: 1

  blockchain:
    network: mainnet
    slot-ms: 15000
    skip-empty-blocks: true
    finality-confirmations: 2
```

### Analysis of Consensus Settings
- **Proposer Election:** Set to `round-robin`. The PoR+PoS weighted algorithm is available in `ValidatorService.java` when `app.validator.selection-mode=weighted`.
- **Peer Topology:** Peer list includes the node itself (`localhost:3000`), requiring normalization to prevent self-voting when calculating external peer confirmations.
- **Stake Enforcement:** `app.validator.require-balance: false` allows validators to register nominal stake without locking actual account balances unless explicitly enabled.

---

## 5. Proposer Selection: Round-Robin vs. Weighted PoR+PoS

In `ValidatorService.java` (`selectValidatorForSlot`):
```java
validators.sort(Comparator.comparing(Validator::getAddress));

if (!"weighted".equalsIgnoreCase(selectionMode)) {
    int idx = Math.floorMod(slotNumber, validators.size());
    return validators.get(idx);
}
```

### Weighted PoR+PoS Formulation
When configured to `selectionMode: weighted`:
1. Each active validator's weight is calculated:
   $$W_v = \alpha \cdot \ln(1 + S_v) + (1 - \alpha) \cdot R_v$$
   where $\alpha = 0.15$ is hardcoded in `calculateWeight()`:
   ```java
   double stakeComponent = 0.15d * Math.log(1.0d + S_v);
   double reputationComponent = 0.85d * R_v;
   double weight = stakeComponent + reputationComponent;
   return Math.max(0.0d, weight);
   ```
2. A deterministic seed is derived from `SHA-256("slot:" + slotNumber + ":network:" + network)`.
3. Cumulative weight sampling (roulette wheel selection) deterministically selects the slot leader.
4. **Finding:** Alpha is hardcoded to $0.15$ in source code rather than being parameterised via Spring configuration (`@Value("${app.validator.alpha:0.15}")`).

---

## 6. Transaction Consensus Audit (`NodeConsensusService.java`)

### Legacy Quorum Evaluation Flaw
In the original unpatched implementation:
```java
// Legacy code in NodeConsensusService.java:
boolean weightedConsensus = hasActiveValidators(network) && weightRatio >= (2.0 / 3.0);
boolean countConsensus = yesVotes >= quorum;

if (!weightedConsensus && !countConsensus) {
    // REJECT
}
```
- **Consequence:** De Morgan's Law yields acceptance when `weightedConsensus || countConsensus`. A transaction with only 1 vote from a high-stake validator could pass, or a transaction with 2 of 3 votes but $< 66.7\%$ weight could pass.
- **Remediation:** Enforce atomic conjunction:
  $$Consensus = \left(\frac{W_{yes}}{W_{total}} \ge \frac{2}{3}\right) \land \left(Votes_{yes} \ge \left\lceil\frac{2N}{3}\right\rceil\right)$$
  Implemented centrally in `ConsensusQuorum.isReached(...)`.

### Voter Eligibility and Duplicate Handling
- **Legacy:** Non-registered voters defaulted to weight `1.0d`, and `getTotalNetworkWeight()` defaulted to `peerCount`.
- **Finding:** Malicious or unregistered peers could dilute or fabricate weight.
- **Remediation:** Strict validation requiring voters to exist in `validators` table with `status == ACTIVE`. Unregistered nodes contribute `0.0d` weight. Duplicate votes from the same validator address must be deduplicated before tallying.

---

## 7. Block Consensus & Finalization Audit

### Flow in `BlockConsensusVoteService`
1. Proposer constructs candidate block header payload.
2. Proposer queries configured peers at `POST /{network}/blocks/vote`.
3. Peers run sanity checks in `WalletController.voteOnBlock()`:
   - Check height ($H_{incoming} == H_{latest} + 1$).
   - Check proposer registration (`status == ACTIVE`).
   - Check timestamp drift ($\le 30\text{s}$).
4. Proposer tallies votes using `approvedWeight` and `yesVotes`.
5. Requires `yesVotes > 1` (at least one external peer confirmation).

### Critical Vulnerabilities in Block Receiving (`BlockBroadcastService`)
1. **Unverified Finalization:** When a peer receives a payload with `isFinalized: true` at `POST /{network}/blocks/receive`, the receiver marks `block.setIsFinalized(true)` and immediately invokes `feeDistributionService.distributeBlockFees(block)`. No threshold signatures, vote receipts, or Merkle proofs are verified.
2. **Proposer Signature Bypass:** `BlockValidationService` did not verify `proposerSignature`. Any node could forge a block header claiming to be any active validator.
3. **Slot Leadership Bypass:** The receiving node did not check whether the proposer was actually elected for the claimed `slotNumber`.

---

## 8. Local Code vs. GitHub Repository (`origin`) Comparison

Comparing local HEAD (`21799ad: Everything Synchronized`) with remote branches:
- **Remote `origin/main` (`938ead5`):** Contains commits `922761e` (PR #3 from `updating_018`) and `938ead5` ("Minor Issue Fixed").
- **Remote `origin/updating_019` (`6c48eb3`):** Contains "Admin Panel, peer-to-peer authentication gap fixed", introducing `PeerAuthService.java` and `PeerUrlUtil.java`, while renaming `RestTemplateConfig.java` to `RestTemplateConfig.txt` (which breaks Spring context if RestTemplate is not configured elsewhere).
- **Local Workspace:** Preserves working `RestTemplateConfig.java`, has intact Thymeleaf templates, includes complete local load-test datasets, and maintains active local MySQL test databases.
- **Source of Truth Rule:** The local `Node_Software/` directory is treated as authoritative per user specification.

---

## 9. Baseline Audit Conclusion & Roadmap

The codebase provides a functional foundation for transaction execution and block production, but requires rigorous hardening in consensus evaluation and block validation:
1. **Quorum Logic:** Standardize `ConsensusQuorum` across both transaction and block pathways.
2. **Block Security:** Require cryptographic signature verification and verifiable consensus evidence before finalization.
3. **PoR+PoS Parameterization:** Expose $\alpha$ to configuration to enable scientific evaluation across $\alpha \in \{0.0, 0.15, 0.30, 0.50, 0.75, 1.0\}$.
4. **Documentation:** Populate all research reports in `Node_Software/research/`.
