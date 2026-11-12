# M3R Consensus Security Hardening Report

## Executive Summary

This security audit and hardening report documents the critical vulnerabilities identified and rectified in the M3R Node Software (`research/consensus-audit-fixes` branch, baseline commit `21799ad`). The modifications strictly address certificate forgery risks, unauthenticated peer votes, missing/bypassed proposer signatures, implicit solo-mode finalization bypasses, and historical validator-set commitment integrity without mutating database schemas or altering transaction/wallet compatibility.

---

## 1. Vulnerability Assessment & Root Causes

### Vulnerability A: Consensus Certificate Signature Verification Bypass
- **Severity**: Critical (High Impact, Low Complexity)
- **Original Code**: `BlockBroadcastService.verifyConsensusCertificate()` verified that the voter addresses in the certificate could numerically satisfy the dual quorum calculation against the active validator repository. However, it completely omitted cryptographic signature verification for individual votes in the certificate.
- **Root Cause**: The node trusted the voter addresses present in the JSON certificate payload without verifying that the corresponding private key holders had signed a canonical vote payload.
- **Attack Vector**: Any malicious node or MITM could fabricate a certificate containing the addresses of legitimate validators and claim finalization, bypassing consensus entirely.

### Vulnerability B: Implicit Solo Finalization Bypass
- **Severity**: High (Medium Complexity)
- **Original Code**: `if (peerUrls == null || peerUrls.isEmpty()) return true;` in `BlockBroadcastService.verifyConsensusCertificate()` and unconditional auto-finalization in `BlockConsensusVoteService.collectVotes()`.
- **Root Cause**: The consensus protocol conflated a temporary network partition or an empty peer list with authorized solo development operation.
- **Attack Vector**: A node whose peer connections dropped or was launched with missing peer configurations would silently finalize arbitrary unauthenticated blocks into its local ledger without BFT quorum.

### Vulnerability C: Unchecked Proposer Signatures and Slot Leadership
- **Severity**: High (Medium Complexity)
- **Original Code**: `BlockValidationService.validateReceivedBlock()` checked proposer signatures only inside a conditional block that skipped validation when `proposerSignature` was missing, blank, `"UNSIGNED"`, or `"PEER_BROADCAST"`. Furthermore, slot leader lookup failures were silently caught and ignored.
- **Root Cause**: Incomplete validation logic intended for debugging/local testing remained in production block intake pipelines.
- **Attack Vector**: An attacker could inject blocks with `proposerSignature: "UNSIGNED"` or propose out-of-turn slots, and the node would persist them as valid blocks.

### Vulnerability D: Unauthenticated Peer Confirmation
- **Severity**: High (Medium Complexity)
- **Original Code**: Quorum calculation checked `peerConfirmations > 0` based solely on the count of distinct addresses in the incoming vote list that differed from the proposer address.
- **Root Cause**: Peer confirmation was inferred from string matching rather than authenticated cryptographic signatures.
- **Attack Vector**: A proposer could append any peer address to the certificate voter list without a signature to satisfy the independent peer confirmation constraint.

### Vulnerability E: Ephemeral Validator-Set Roster Mismatch
- **Severity**: Medium (Architectural Limitation)
- **Original Code**: `BlockProposalService.computeValidatorSetHash()` returned a static placeholder: `"validators:" + network`.
- **Root Cause**: Block headers committed to a hardcoded string rather than the cryptographic state of the active validator set.
- **Attack Vector**: Nodes could not detect whether a proposed block was minted under a divergent or manipulated validator set.

---

## 2. Implemented Security Fixes

### Fix A: Cryptographic Consensus Certificate Verification
1. **Registered Identity & Public Key Derivation**: For each voter listed in the certificate:
   - The voter address must exist in `validatorRepository.findByNetworkAndStatus(network, ACTIVE)`.
   - The certificate provides `{ "publicKey": hex, "signature": hex }`.
   - The compressed secp256k1 public key is validated for curve membership ($q \neq \infty$, $q$ is valid).
   - The voter address is derived directly from the public key:
     $$\text{address} = \text{Base58Check}_{0x35}(\text{Keccak-256}(pubKeyBytes)[12..31])$$
     If derived address $\neq$ voter address, the certificate is rejected immediately (`PROPOSER_PUBKEY_MISMATCH`).
2. **Canonical Vote Digest Verification**:
   The vote payload is reconstructed canonically:
   $$\text{payload} = \text{UTF-8}("BLOCK\_VOTE|" + \text{network} + "|" + \text{blockHeight} + "|" + \text{blockHash} + "|" + \text{voterAddress})$$
   $$\text{digest} = \text{Keccak-256}(\text{payload})$$
   The 64-byte $(r \parallel s)$ or DER signature is verified using BouncyCastle's `ECDSASigner`.
3. **Independent Quorum Re-Evaluation**:
   All supplied fields (`approvedWeight`, `totalWeight`, `yesVotes`, etc.) in the certificate are ignored. The quorum is recomputed strictly from cryptographically authenticated voters.

### Fix B: Strict Operating Mode Gating for Solo Finalization
1. Added property `@Value("${app.consensus.allow-solo-finalization:false}")`.
2. Secure default is `false`. Solo finalization is completely disallowed unless explicitly configured.
3. If `allow-solo-finalization=false`, empty peer lists fail closed with security rejections.
4. When `allow-solo-finalization=true` is set for single-node development, prominent security audit warnings are emitted to standard logs.

### Fix C: Mandatory Proposer Signatures & Strict Slot Leadership
1. Any block without a valid proposer signature, with blank signature, or marked `UNSIGNED` or `PEER_BROADCAST` is rejected with `MISSING_PROPOSER_SIGNATURE` or `PROPOSER_SIGNATURE_UNSIGNED`.
2. The proposer public key must derive the proposer address.
3. The proposer must be registered and `ACTIVE`.
4. The proposer must be the deterministic slot leader computed by `ValidatorService.selectValidatorForSlot()`. If leader lookup fails or times out, the block is rejected with `SLOT_LEADER_LOOKUP_FAILED`.

### Fix D: Authenticated Independent Peer Confirmation
- In `ConsensusQuorum.evaluateVotes()`, `peerConfirmations` is incremented only for authenticated votes from registered active validators where `!proposerAddress.equals(voter)`.
- If `totalValidators > 1` and `peerConfirmations == 0`, consensus is rejected. Proposer self-votes cannot satisfy peer confirmation.

### Fix E: Deterministic Validator-Set Commitment Hash
- Implemented `BlockValidationService.computeValidatorSetHash(List<Validator> active)`:
  Deterministically sorts active validators by address, serializes `address:stake:reliability;`, and hashes with SHA-256.
- Updated `BlockProposalService.computeValidatorSetHash(network)` to use this real commitment.
- Blocks presenting a mismatched `validatorSetHash` are rejected with `VALIDATOR_SET_HASH_MISMATCH`.

---

## 3. Files Modified and Created

| File | Change Nature | Purpose |
|---|---|---|
| `CryptoUtil.java` | Modified | Added `formatRawSignature`, `isValidSecp256k1PublicKey`, `verifyVoteSignature` |
| `AddressUtil.java` | Modified | Added `deriveAddressFromPublicKey` (byte[] and hex overloads) |
| `NodeIdentityService.java` | Modified | Fixed 64-byte signature formatting bug in `signData()` |
| `BlockValidationService.java` | Modified | Hardened proposer signature validation, slot leader checks, and validator-set hash verification |
| `BlockBroadcastService.java` | Modified | Implemented genuine cryptographic certificate verification and solo-mode gating |
| `BlockConsensusVoteService.java` | Modified | Added proposer self-signing, peer signature storage, and solo-mode gating |
| `WalletController.java` | Modified | Generates secp256k1 signature over canonical vote payload in `/{network}/blocks/vote` |
| `ValidatorService.java` | Modified | Added `getActiveValidators(network)` helper |
| `BlockProposalService.java` | Modified | Computes genuine active validator set hash commitment |
| `BlockFinalizationSecurityTest.java` | Modified | Updated test fixture with authentic secp256k1 signatures |
| `ConsensusSecurityAdversarialTest.java` | **New File** | 36 comprehensive adversarial tests covering all attack scenarios |

---

## 4. Historical Validator-Set Handling & Limitations

### Current Database Architecture
- The database schema (`validators` and `validator_weights` tables) records the live state and point-in-time calculation snapshots (`is_stale`).
- There is currently no historical epoch snapshot table (e.g. `validator_epoch_roster`) that records which set of validators was active at an arbitrary historical block height $H \ll H_{current}$.

### Backward-Compatible Handling
- As required by change-control guidelines, no database migrations or schema alterations were performed without explicit authorization.
- Real-time consensus validation validates incoming blocks at the chain tip against the active validator set.
- A proposed migration schema is documented in `research/CONSENSUS_CERTIFICATE_SPEC.md` for a future hard fork or database migration.

---

## 5. Verification & Build Results
- `mvn clean test`: **100 tests executed, 0 failures, 0 errors, 0 skipped**.
- `mvn clean package`: **BUILD SUCCESS**, packaged executable jar: `target/m3r-wallet-server-1.0.0.jar`.
