# M3R Consensus Certificate & Canonical Header Specification

## 1. Specification Overview

The M3R Consensus Certificate serves as portable, cryptographic, and verifiable proof that a proposed block has attained dual quorum under the Proof-of-Reliability + Proof-of-Stake (PoR+PoS) consensus protocol.

Dual Quorum requires:
$$\left(\frac{W_{yes}}{W_{total}} \ge \frac{2}{3}\right) \quad \land \quad \left(Votes_{yes} \ge \left\lceil\frac{2N}{3}\right\rceil\right) \quad \land \quad (\text{PeerConfirmations} > 0 \text{ when } N > 1)$$

Where:
- $N$ is the count of eligible active validators holding stake-weight $w > 0.0$.
- Zero-weight or inactive validators cannot contribute to $Votes_{yes}$, $W_{yes}$, or $\text{PeerConfirmations}$.
- Floating point sums are checked with an IEEE 754 summation tolerance ($\epsilon = 10^{-9}$).

---

## 2. Canonical Block Header Specification (`M3R_BLOCK_HEADER_V1`)

To prevent truncation, ambiguous parsing, or length-extension vulnerabilities in secp256k1 signature verification, block headers are serialized into a deterministic binary format prior to hashing.

### 2.1 Deterministic Binary Serialization Layout

All integers are serialized in big-endian byte order (`java.nio.ByteOrder.BIG_ENDIAN`).

| Offset | Field Name | Data Type | Encoding / Format |
|---|---|---|---|
| 0..19 | Domain Separation Tag | `byte[20]` | 19-byte ASCII string `"M3R_BLOCK_HEADER_V1"` followed by `0x00` null byte |
| 20 | Protocol Version | `int8` (`byte`) | Value `1` |
| 21..22 | Network String Length | `int16` (`short`) | 2-byte big-endian length of network string |
| 23..X | Network Identifier | `byte[]` | UTF-8 encoded string (e.g., `"mainnet"`) |
| X+1..X+8 | Block Height | `int64` | Big-endian 64-bit integer |
| X+9..X+16 | Slot Number | `int64` | Big-endian 64-bit integer |
| X+17..X+48 | Parent Block Hash | `byte[32]` | 32-byte binary decoded from 64-character hex |
| X+49..X+80 | Merkle Root | `byte[32]` | 32-byte binary decoded from 64-character hex |
| X+81..X+112 | State Root | `byte[32]` | 32-byte binary decoded from 64-character hex |
| X+113..X+120 | Timestamp | `int64` | Milliseconds since Unix epoch |
| X+121..X+140 | Proposer Address | `byte[20]` | Raw 20-byte payload decoded from Base58Check address |
| X+141..X+148 | Proposer Weight | `float64` (`double`) | Big-endian IEEE 754 raw double bits |
| X+149..X+180 | Validator-Set Hash | `byte[32]` | 32-byte binary decoded from 64-character hex |
| X+181..X+188 | Nonce | `int64` | Big-endian 64-bit integer |
| X+189..X+192 | Transaction Count | `int32` | Big-endian 32-bit integer |

### 2.2 Proposer Signing & Verification Digest

BouncyCastle's `ECDSASigner.generateSignature(byte[])` expects a 32-byte cryptographic message digest.
The proposer signing digest is calculated as:

$$\text{SigningDigest} = \text{Keccak-256}(\text{CanonicalHeaderBytes})$$

Both proposal construction (`BlockProposalService.buildBlock`) and proposal verification (`BlockValidationService.validateReceivedBlock`) execute the identical canonical binary serialization and digest derivation.

Proposer signatures are formatted as 64-byte raw secp256k1 signatures ($r \parallel s$) with strict canonical low-$s$ ($s \le n/2$) and range verification ($1 \le r < n, 1 \le s \le n/2$). Signatures with $s > n/2$ are normalized during signing ($s' = n - s$) and strictly rejected during verification.

---

## 3. Validator-Set Commitment & Historical Snapshots

### 3.1 Validator-Set Hash Algorithm

Every block header commits to the hash of the active validator set eligible to vote on that block:

1. Active validators are retrieved: $V_{active} = \{ v \in V \mid v.status = \text{ACTIVE} \land v.address \ne \text{null} \}$.
2. If $V_{active}$ is empty, the commitment hash is 64 zeros (`"0".repeat(64)`).
3. Validators are sorted deterministically by canonical address (trimmed, lowercase).
4. Each entry is serialized as:
   $$\text{Entry}_i = \text{address}_i + \text{":"} + \text{stripTrailingZeros}(\text{stakedAmount}_i) + \text{":"} + \text{reliabilityScoreScaled}_i + \text{";"}$$
5. The commitment hash is computed via SHA-256:
   $$\text{ValidatorSetHash} = \text{Hex}(\text{SHA-256}(\text{UTF-8}(\sum \text{Entry}_i)))$$

### 3.2 Historical Validator-Set Snapshots

When a validator set evolves due to stake delegation, unbonding, or slashing:
1. `ValidatorService.recordSnapshot()` records a `ValidatorSetSnapshot` containing the active validator addresses, weights, and snapshot timestamp, indexed by `validatorSetHash`.
2. When evaluating a received block, if `claimedValSetHash != currentHash`:
   - The node queries `validatorService.getSnapshotByHash(claimedValSetHash)`.
   - If found, certificate voting weights are evaluated against the historical snapshot.
   - If no snapshot is recorded, the node fails closed and rejects the block.

---

## 4. Certificate Data Structure

A valid `ConsensusCertificate` JSON object is encapsulated within the block payload:

```json
{
  "consensusCertificate": {
    "blockHeight": 42,
    "blockHash": "5f0ca6ca54290f8dfd91ee9a72e7b87b405edbfe02cffd2fac495ee3a4a582c8",
    "network": "mainnet",
    "proposerAddress": "NQWWb4huPaqUncdJHFm2FHFjTo1qqLskuq",
    "yesVotes": 3,
    "requiredVotes": 2,
    "totalValidators": 3,
    "approvedWeight": 4.3657,
    "totalWeight": 4.3657,
    "weightRatio": 1.0,
    "peerConfirmations": 2,
    "voters": [
      "NQWWb4huPaqUncdJHFm2FHFjTo1qqLskuq",
      "Ndq8JTY3oX8D3gDqogzsWFy9uzTWhuN51E",
      "NVHN8c6Y1X3Z7fK2m9pB4wD5tQ8vR1jS6A"
    ],
    "signatures": {
      "NQWWb4huPaqUncdJHFm2FHFjTo1qqLskuq": {
        "publicKey": "031fb6beb658298e282aee75ca5dcb5dba64deb41664e12c91bd6fb8325f5ea84d",
        "signature": "7a3f8c2b1e0d4a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e9d8c7b6a5f4e3d2c1b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e9d8c7b6a5f4e3d"
      },
      "Ndq8JTY3oX8D3gDqogzsWFy9uzTWhuN51E": {
        "publicKey": "02a4b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8",
        "signature": "9b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e"
      }
    },
    "finalizedAt": 1740000000000
  }
}
```

---

## 5. Vote Signing & Verification Digest

Each validator produces a vote signature over a canonical message string:

$$\text{VoteString} = \text{"BLOCK\_VOTE|" } + \text{network} + \text{"|" } + \text{blockHeight} + \text{"|" } + \text{blockHash} + \text{"|" } + \text{voterAddress}$$
$$\text{VoteDigest} = \text{Keccak-256}(\text{UTF-8}(\text{VoteString}))$$

Verification requires:
1. Decoding the 33-byte compressed secp256k1 public key.
2. Deriving the base58check address via `AddressUtil.deriveAddressFromPublicKey(pubKeyBytes)` and asserting $\text{DerivedAddress} = \text{voterAddress}$.
3. Verifying the 64-byte raw secp256k1 signature against $\text{VoteDigest}$.
