# M3R Blockchain — Remaining Security & Consensus Limitations

## Purpose

In accordance with strict security audit standards and academic integrity, this document explicitly details the known remaining architectural, protocol, and engineering limitations of the M3R node software following the consensus security hardening and vulnerability remediation (F-01 through F-10).

---

## 1. Network Synchrony Model & Absence of Asynchronous BFT

### Current Architecture
M3R utilizes fixed 15-second block slots with deterministic round-robin slot leader selection weighted by Proof-of-Reliability and Proof-of-Stake (PoR+PoS). Dual-quorum consensus collection is executed synchronously via HTTP REST calls (`/blocks/vote`) within the active slot.

### Remaining Limitations
- **Weak Synchrony Assumption**: The protocol assumes weak network synchrony: validator clocks must be roughly synchronized (within acceptable slot tolerance), and message propagation delay must be bounded below the slot interval.
- **Liveness Halts under Partitions**: If a network partition isolates $\ge 1/3$ of the active voting weight, block finalization safely halts (fails closed), preventing split-brain double-finalization. However, the system does not include an automatic view-change or asynchronous consensus pacemaker to resume finalization during ongoing partitions.
- **No Asynchronous Finality Proofs**: M3R is not an asynchronous Byzantine Fault Tolerant protocol like PBFT, Tendermint, or HotStuff. It does not implement multi-round commit/prepare pipelines or formal asynchronous state machine replication.
- **Academic Scope Boundary**: Do NOT claim full asynchronous BFT, proven Byzantine finality under arbitrary network delay, or production readiness.

---

## 2. Fork Conflict Resolution Strategy

### Current Architecture
Under the remediated `PeerSyncService`, when a peer presents a competing chain that conflicts with an already-finalized local block (`isFinalized == true`), the node reports a fork conflict (`[SECURITY][FORK]`) and terminates synchronization with that peer.

### Remaining Limitations
- **Fail-Closed Fork Rejection**: The protocol prioritizes safety over liveness: it strictly refuses to overwrite local finalized history. It does not implement automated heaviest-observed-subtree or longest-chain reorganization across finalized checkpoints.
- **Manual Intervention Requirement**: If an adversarial network split causes two partitions to independently finalize competing blocks (e.g., in a compromised dual-quorum partition scenario), nodes in opposing partitions will permanently reject each other's chains until manual operator intervention or hard-fork reconciliation occurs.

---

## 3. Networking Transport: HTTP REST Polling vs. P2P Gossip

### Current Architecture
Node communication operates over point-to-point HTTP REST endpoints (`RestTemplate`) configured through static peer lists or dynamic database peering tables (`peers`).

### Remaining Limitations
- **Transport Scalability**: The network lacks an authenticated peer-to-peer (P2P) gossip layer (such as libp2p, DevP2P, or GossipSub). Broadcasting blocks and votes requires direct $O(N)$ HTTP calls to each known peer.
- **Sybil and Eclipse Vulnerability**: Although unauthenticated peer data cannot poison validator state or finalized blocks, an attacker controlling network routing or DNS could attempt an eclipse attack by isolating a node's REST endpoints from honest peers, causing the node to stall.
- **Production Scope Boundary**: A production deployment requires migrating from synchronous HTTP REST to an authenticated, encrypted, multiplexed P2P gossip protocol with peer scoring and rate limiting.

---

## 4. Validator Public Key Storage & Identity Architecture

### Current Architecture
The `Validator` entity persists validator Base58Check addresses (`String address`), status, stake, and reliability metrics. It does not store the raw 33-byte secp256k1 public key in the database table. Public keys are provided in block proposals and votes, and validated via cryptographic address derivation (`AddressUtil.deriveAddressFromPublicKey`).

### Remaining Limitations
- **Cryptographic Preimage Assumption**: The security of validator authentication relies on the one-way preimage resistance of Keccak-256 and RIPEMD-160 / SHA-256 address derivation.
- **Future Post-Quantum Migration**: If quantum algorithms break ECDSA or if alternative signature algorithms (e.g., Ed25519 or BLS12-381) are introduced, explicit on-chain public key registration at validator onboarding time is preferable to address-based derivation.

---

## 5. Storage Pruning & Historical State Growth

### Current Architecture
Historical validator set snapshots are durably persisted in the `validator_snapshots` table, and consensus certificates are stored in the `blocks` table.

### Remaining Limitations
- **Unbounded Snapshot Growth**: The `validator_snapshots` table grows append-only over time. Currently, there is no automatic epoch-based snapshot pruning or compaction mechanism.
- **Database Scalability**: As the blockchain height increases to millions of blocks, table indexing and query performance in MySQL will require partitioning, cold-storage archiving, or state trie commitments (e.g., Sparse Merkle Trees or Verkle Trees).

---

## 6. Developer Solo Mode Boundary

### Current Architecture
For local unit testing and development without a multi-node cluster, the node supports an explicit solo finalization toggle:
```properties
app.consensus.allow-solo-finalization=false # production default
```

### Remaining Limitations
- Solo certificates are explicitly tagged with `isSoloCertificate: true`.
- If an external peer or production node receives a block carrying `isSoloCertificate: true`, it immediately rejects it unless that peer also explicitly has `allow-solo-finalization=true` with an empty peer list.
- Solo mode provides zero Byzantine resistance and is solely intended for developer test environments.
