# M3R Consensus Synchronization Protocol (CSP-v1)

## 1. Protocol Architecture & Invariants

The M3R Consensus Synchronization Protocol defines how nodes synchronize blockchain state across peer networks without compromising local consensus finality, validator weights, or ledger integrity.

### 1.1 Fundamental Security Invariants

1. **Uniform Validation Perimeter**: Every externally received block—regardless of whether received via live peer broadcast (`POST /api/node/block/receive`) or background catch-up synchronization (`PeerSyncService` / `BlockSyncService`)—MUST pass through the identical shared validation pipeline in `BlockBroadcastService.receiveBlock()`.
2. **Untrusted Peer Claims**: External JSON flags (such as `isFinalized: true` or `latestBlockHeight`) are completely untrusted. Finality is established strictly by verifying an authentic dual-quorum `ConsensusCertificate`.
3. **Immutability of Finalized State**: A peer's advertised chain height or alternative chain can NEVER authorize the deletion or rollback of local finalized blocks (`isFinalized == true`).
4. **Authoritative Validator State**: Validator stake amounts, reliability scores, and proposal statistics are strictly authoritative and derived locally from on-chain registrations, balance locks, and canonical finalized blocks. Unauthenticated peer responses can never modify local consensus weights.
5. **Fail-Closed Verification**: If required cryptographic evidence (proposer signature, vote signatures, historical validator snapshots) is missing, malformed, or fails verification, the block is rejected, and synchronization halts immediately.

---

## 2. Synchronization Ingress Pathways

```
                                [ External Peer ]
                                        |
                 +----------------------+----------------------+
                 | (Live Broadcast)                            | (Catch-Up Polling)
                 v                                             v
        POST /api/node/block/receive                 GET /{net}/blocks/{height}
                 |                                             |
                 v                                             v
     NodeSyncController.receiveBlock()               PeerSyncService / BlockSyncService
                 |                                             |
                 +----------------------+----------------------+
                                        |
                                        v
                       BlockBroadcastService.receiveBlock()
                                        |
                 +----------------------+----------------------+
                 |                                             |
                 v                                             v
    BlockValidationService.validateReceivedBlock()    verifyConsensusCertificate()
    - 13-field canonical header digest                - Dual quorum check (W >= 2/3, V >= 2N/3)
    - Proposer secp256k1 low-s signature              - Voter secp256k1 low-s signatures
    - Proposer address derivation                     - Active validator verification
    - Slot leader election proof                      - Independent peer confirmation (> 0)
    - Merkle root & State root                        - Historical snapshot verification
    - Validator set commitment hash
                 |                                             |
                 +----------------------+----------------------+
                                        |
                         Is Block Valid & Certified?
                                /              \
                              YES               NO
                              /                  \
                             v                    v
                  Is Block Finalized?           REJECT
                    /             \
                  YES              NO
                  /                 \
                 v                   v
        Canonical Database      Memory Staging
        - blockRepo.save()      - unfinalizedProposals.put()
        - Confirm transactions  - Ledger untouched
        - Distribute fees       - Mempool untouched
        - Update PoR stats
```

---

## 3. Block Retrieval & Response Schema

To ensure honest nodes can catch up seamlessly without bypassing verification, block retrieval endpoints (`GET /{network}/blocks/{height}` and `GET /api/node/block/{height}`) transport complete consensus evidence.

### 3.1 Certified Block Payload Specification

```json
{
  "blockHeight": 15,
  "blockHash": "4cd133058fc6a8b86389eefe839fd381d5bc4b1c097ddfb75a72b2e4f1188ad0",
  "parentBlockHash": "167eac4fac851464d4b0ad2a457573b58842fc5fa9be89ae186876aacd6298e9",
  "slotNumber": 119630896,
  "version": 1,
  "network": "mainnet",
  "timestamp": 1794463453726,
  "proposerAddress": "NVHNEzEVZfLBKrbvTznp9dsAUC3Gb7eNmg",
  "proposerPublicKey": "0318f08dca70367b8a04e9f74feae237f699fbfaae380cc9264f144b95c364dfe7",
  "proposerWeight": 1.398566055,
  "proposerSignature": "3045022100... (64-byte raw hex or DER with low-s)",
  "merkleRoot": "48ce15bda30eadf4d2306a0e0ccc98df559052953608069833a65f1e48fcfb71",
  "stateRoot": "0000000000000000000000000000000000000000000000000000000000000000",
  "validatorSetHash": "d0647bccccfab280cc9e0dc3610b8903b8630dac2a253e8a792fd239d17528af",
  "nonce": 1001,
  "txCount": 1,
  "isFinalized": true,
  "transactions": [
    {
      "txHash": "48ce15bda30eadf4d2306a0e0ccc98df559052953608069833a65f1e48fcfb71",
      "sender": "NQWWb4huPaqUncdJHFm2FHFjTo1qqLskuq",
      "recipient": "Ndq8JTY3oX8D3gDqogzsWFy9uzTWhuN51E",
      "value": 1000,
      "totalFee": 100,
      "broadcastFee": 20,
      "consensusFee": 80,
      "broadcasterAddress": "NidXN8ZVWqqmN2vDt7UcJ6EAPJvDPYM6Qy",
      "status": "CONFIRMED"
    }
  ],
  "consensusCertificate": {
    "blockHeight": 15,
    "blockHash": "4cd133058fc6a8b86389eefe839fd381d5bc4b1c097ddfb75a72b2e4f1188ad0",
    "network": "mainnet",
    "proposerAddress": "NVHNEzEVZfLBKrbvTznp9dsAUC3Gb7eNmg",
    "yesVotes": 2,
    "requiredVotes": 2,
    "totalValidators": 3,
    "approvedWeight": 2.797,
    "totalWeight": 4.195,
    "weightRatio": 0.6667,
    "peerConfirmations": 1,
    "voters": [
      "NVHNEzEVZfLBKrbvTznp9dsAUC3Gb7eNmg",
      "NidXN8ZVWqqmN2vDt7UcJ6EAPJvDPYM6Qy"
    ],
    "signatures": {
      "NVHNEzEVZfLBKrbvTznp9dsAUC3Gb7eNmg": {
        "publicKey": "0318f08dca70367b8a04e9f74feae237f699fbfaae380cc9264f144b95c364dfe7",
        "signature": "..."
      },
      "NidXN8ZVWqqmN2vDt7UcJ6EAPJvDPYM6Qy": {
        "publicKey": "02931fc921b50c612abbf756a1bfcc33cafd5c8c4d9f999d9749615ddb692731cb",
        "signature": "..."
      }
    },
    "finalizedAt": 1794463454120
  }
}
```

---

## 4. Fork Resolution & Rollback Protection

When a node synchronizes from a peer advertising a longer chain:

1. **Pre-Validation Existence Check**: The node checks if it already stores a block at the candidate height:
   ```java
   Optional<Block> existing = blockRepository.findByBlockHeightAndNetwork(ht, network);
   ```
2. **Deterministic Hash Agreement**:
   - If `existing.isPresent()` and `existing.get().getBlockHash().equalsIgnoreCase(incomingHash)`, the block is already synchronized; the node advances to the next height.
3. **Fork Conflict on Finalized State**:
   - If `existing.isPresent()`, hashes conflict, and `Boolean.TRUE.equals(existing.get().getIsFinalized())`:
   - The node reports an immediate security fork alert:
     `[SECURITY][FORK] Fork conflict at finalized height H. Local finalized block cannot be rolled back by peer. Rejecting peer chain.`
   - Synchronization is terminated immediately. Local canonical history is strictly preserved.
4. **Rollback Guard**:
   - If a rollback of unfinalized state is requested via `rollbackToHeight(targetHeight, network)`:
   - Every block with `height > targetHeight` is evaluated:
     ```java
     if (Boolean.TRUE.equals(b.getIsFinalized())) {
         throw new IllegalStateException("Cannot rollback finalized block at height " + b.getBlockHeight());
     }
     ```
   - No finalized block can be deleted from canonical storage under any condition.
