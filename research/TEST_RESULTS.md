# M3R Test Suite — Execution Results & Empirical Validation

**Document:** `research/TEST_RESULTS.md`  
**Date:** November 12, 2026  
**Build System:** Apache Maven 3.8.7 (OpenJDK 21)  
**Total Tests Executed:** 64  
**Passing Tests:** 64 (100%)  
**Failing Tests:** 0 (0%)  
**Skipped Tests:** 0 (0%)  

---

## 1. Test Suite Summary Table

| Test Suite / Class Name | Primary Scope | Tests Run | Pass | Fail | Skip | Duration |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| `LocalhostOnlyInterceptorTest` | Admin API & reverse proxy header validation | 8 | 8 | 0 | 0 | 0.34s |
| `ConsensusQuorumTest` | Mathematical dual-quorum & vote tallying rules | 19 | 19 | 0 | 0 | 0.22s |
| `BlockValidationHashTest` | Block header hash commitment & tampering detection | 1 | 1 | 0 | 0 | 0.04s |
| `BlockFinalizationSecurityTest` | Block finalization security & consensus certificates | 6 | 6 | 0 | 0 | 1.68s |
| `TransactionDoubleSpendingTest` | secp256k1 crypto, nonces & concurrent double spending | 6 | 6 | 0 | 0 | 0.60s |
| `PoRPoSWeightValidationTest` | Weight formula, alpha parameterization & simulation | 10 | 10 | 0 | 0 | 0.31s |
| `MultiNodeConsensusSimulationTest` | 3, 4, and 7-node network failure & partition modeling | 14 | 14 | 0 | 0 | 0.11s |
| **Total** | | **64** | **64** | **0** | **0** | **3.30s** |

---

## 2. Detailed Test Case Verification

### 2.1 `ConsensusQuorumTest` (19 Tests)
- `acceptsWhenBothThresholdsAreMet`: Verified affirmative consensus when $W_{yes}/W_{total} = 70\% \ge 66.7\%$ and $Votes_{yes} = 3 \ge 3$ for $N=4$.
- `rejectsWhenWeightedThresholdFailsEvenWhenCountPasses`: Verified rejection when raw count passes ($3 \ge 3$) but weight ratio is $60\% < 66.7\%$.
- `rejectsWhenCountThresholdFailsEvenWhenWeightPasses`: Verified rejection when weight passes ($90\% \ge 66.7\%$) but raw count fails ($2 < 3$).
- `rejectsWhenNeitherConditionPasses`: Verified rejection when both thresholds fail.
- `acceptsExactlyTwoThirdsForThreeValidators`: Verified boundary condition where weight is exactly $2.0 / 3.0$ and votes are 2 of 3.
- `rejectsJustBelowTwoThirds`: Verified rejection when weight is $1.9999 / 3.0000$ ($66.663\% < 66.667\%$).
- `safelyRejectsZeroTotalWeightAndInvalidVoteCounts`: Verified rejection when $W_{total} = 0$, $W_{yes} < 0$, or $Votes_{yes} > N$.
- `verifiesRequiredVoteThresholds` (8 parameterized test cases): Verified $\lceil 2N/3 \rceil$ formula across $N \in \{1, 2, 3, 4, 5, 6, 7, 10\}$.
- `requiresIndependentPeerConfirmationForMultiNode`: Verified that a proposer cannot self-finalize when $N > 1$ without peer confirmation.
- `allowsSelfConfirmationInSoloMode`: Verified that single-node development mode ($N=1$) allows local finalization.
- `evaluateVotesDeduplicatesAndFilters`: Verified deduplication of repeated votes and exclusion of unregistered/inactive voters.
- `evaluateVotesRejectsOnMissingPeers`: Verified rejection when peers are unreachable or silent.

---

### 2.2 `BlockFinalizationSecurityTest` (6 Tests)
- `detectsHeaderTampering`: Verified `BLOCK_HASH_MISMATCH` when header fields (e.g. nonce, height) are altered.
- `detectsMerkleRootMismatch`: Verified `MERKLE_ROOT_MISMATCH` when transactions do not hash to claimed Merkle root.
- `detectsTxCountMismatch`: Verified `TX_COUNT_MISMATCH` when transaction list length contradicts `txCount`.
- `detectsUnauthorizedProposerForSlot`: Verified `PROPOSER_NOT_SLOT_LEADER` when an active validator proposes out of turn.
- `detectsInvalidProposerSignature`: Verified `PROPOSER_PUBKEY_MISMATCH` and `PROPOSER_SIGNATURE_INVALID` when an invalid secp256k1 signature is attached.
- `rejectsFinalizationWithoutValidCertificate`: Verified that `BlockBroadcastService` rejects `isFinalized: true` unless accompanied by an authentic consensus certificate satisfying the dual-quorum threshold.

---

### 2.3 `TransactionDoubleSpendingTest` (6 Tests)
- `validTransactionPasses`: Verified that an authentic transaction signed with secp256k1 over Keccak-256 payload passes validation.
- `rejectsInvalidSignature`: Verified that transactions signed with incorrect keys fail signature verification.
- `rejectsMismatchedSenderAddress`: Verified that forged sender addresses differing from derived public key bytes are rejected.
- `rejectsInvalidNonce`: Verified that transactions with out-of-order nonces ($N_{tx} \ne N_{ledger} + 1$) are rejected.
- `rejectsInsufficientBalance`: Verified that transactions exceeding account balance are rejected with `Insufficient funds`.
- `concurrentTransactionsOnlyOneConsumesNonce`: Verified that two conflicting transactions submitted simultaneously with identical nonce ($nonce = 1$) under multi-threading execute with strict mutual exclusion. Exactly one transaction succeeds, the conflicting transaction fails, and the account nonce increments exactly once.

---

### 2.4 `PoRPoSWeightValidationTest` (10 Tests)
- `nonActiveValidatorsReceiveZeroWeight`: Verified that INACTIVE, JAILED, and SLASHED validators contribute $0.0$ weight.
- `evaluatesWeightAcrossAlphaValues` (6 parameterized cases): Verified weight calculation across $\alpha \in \{0.0, 0.15, 0.30, 0.50, 0.75, 1.0\}$.
- `validatorSelectionFrequencySimulation`: Simulated 500 slot leader elections comparing a high-stake/low-reputation node vs a low-stake/high-reputation node. Demonstrated that at $\alpha=0.0$ the reputable node won $>70\%$ of slots, while at $\alpha=1.0$ the rich node won $>55\%$ of slots.
- `reliabilityLifecycleTest`: Verified $+0.01$ increment on proposal, $0.999$ reputation decay, and complete reputation reset to $0.0$ with 1-unit stake burn on slashing.
- `handlesZeroWeightValidatorsSafely`: Verified that zero-weight validator sets fall back safely without division-by-zero exceptions.

---

### 2.5 `MultiNodeConsensusSimulationTest` (14 Tests)
- `testConsensusAndTolerance` (9 parameterized cases):
  - 3 nodes: 3/3 passes; 2/3 passes (1 unavailable); 1/3 fails.
  - 4 nodes: 4/4 passes; 3/4 passes (1 unavailable); 2/4 fails.
  - 7 nodes: 7/7 passes; 5/7 passes (2 unavailable); 4/7 fails.
- `maliciousPeerToleratedWhenHonestQuorumMet`: Verified that 1 malicious/Byzantine node in a 4-node network cannot prevent consensus when 3 honest nodes approve.
- `duplicateBroadcastsDoNotInflateTally`: Verified that 10 duplicate votes from a single peer are deduplicated to 1 vote.
- `networkPartitionPreventsSplitBrain`: Verified that a 2-vs-2 partition in a 4-node network prevents either partition from finalizing blocks.
- `asymmetricPartitionMajorityProgressesMinorityHalts`: Verified that in a 5-vs-2 partition in a 7-node network, the 5-node majority continues block production while the 2-node minority halts.
- `weightedAsymmetryPreventsLowWeightFinalization`: Verified that even if 2 of 3 nodes vote YES (meeting count quorum), consensus is rejected if their combined weight is only $16.7\% < 66.7\%$.
