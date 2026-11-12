# M3R Consensus Protocol Specification

**Document:** `research/CONSENSUS_PROTOCOL.md`  
**Date:** November 12, 2026  
**Protocol Version:** 1.1 (Conjunctive Dual-Quorum with Cryptographic Certificates)  

---

## 1. Formal Mathematical Specification

The M3R blockchain protocol combines **Proof-of-Stake (PoS)** and **Proof-of-Reliability (PoR)** to determine validator influence during leader election and consensus voting.

### 1.1 Validator Weight Formulation
For any active validator $v \in \mathcal{V}_{active}$:

$$W_v = \alpha \cdot \ln(1 + S_v) + (1 - \alpha) \cdot R_v$$

where:
- $S_v \ge 0$ is the validator's locked stake in Poisha (smallest currency unit, 1 BDT = 100 Poisha).
- $R_v \in [0.0, 1.0]$ is the empirical reliability score of validator $v$.
- $\alpha \in [0.0, 1.0]$ is the governance weight parameter (prototype default $\alpha = 0.15$).
- For any inactive, jailed, or slashed validator $v \notin \mathcal{V}_{active}$, $W_v = 0.0$.

### 1.2 Dual-Quorum Consensus Rule
A proposed block or transaction is finalized if and only if both the weighted threshold AND the raw vote-count threshold are satisfied, alongside at least one independent peer confirmation in multi-node networks:

$$\text{Consensus} \iff \left(\frac{W_{yes}}{W_{total}} \ge \frac{2}{3}\right) \land \left(Votes_{yes} \ge \left\lceil\frac{2N}{3}\right\rceil\right) \land \left(\text{PeerConfirmations} > 0 \text{ for } N > 1\right)$$

where:
- $W_{total} = \sum_{v \in \mathcal{V}_{active}} W_v$ is the sum of weights of all active registered validators.
- $W_{yes} = \sum_{v \in \mathcal{V}_{yes}} W_v$ is the sum of weights of unique active validators who voted affirmative.
- $N = |\mathcal{V}_{active}|$ is the cardinality of the active validator set.
- $Votes_{yes} = |\mathcal{V}_{yes}|$ is the count of distinct affirmative votes.
- $\lceil 2N/3 \rceil$ is the ceiling of two-thirds of the validator set.

---

## 2. Slot Leader Selection Mechanism

The protocol operates on discrete time slots $s \in \mathbb{N}$ of duration $T_{slot} = 15\text{ seconds}$.

```
      Slot s                  Slot s+1                Slot s+2
|<--- 15s --->|       |<--- 15s --->|       |<--- 15s --->|
Leader: Val_A         Leader: Val_B         Leader: Val_C
```

### 2.1 Round-Robin Mode (`app.validator.selection-mode: round-robin`)
Validators are sorted lexicographically by address:
$$\mathcal{V}_{sorted} = [v_0, v_1, \dots, v_{N-1}]$$
The leader for slot $s$ is chosen deterministically:
$$\text{Leader}(s) = \mathcal{V}_{sorted}\left[s \pmod N\right]$$

### 2.2 Weighted PoR+PoS Mode (`app.validator.selection-mode: weighted`)
When weighted mode is active, the slot leader is chosen via pseudo-random cumulative weight sampling seeded by slot index and network identifier:

$$\text{Seed}(s) = \text{SHA-256}(\text{"slot:"} \parallel s \parallel \text{":network:"} \parallel \text{network})$$
$$\text{Random}(s) = \frac{\text{Seed}_{first\_8\_bytes}(s) \pmod{10^6}}{10^6} \in [0.0, 1.0)$$

The leader is the validator $v_k$ satisfying:
$$\sum_{i=0}^{k-1} \frac{W_i}{W_{total}} \le \text{Random}(s) < \sum_{i=0}^{k} \frac{W_i}{W_{total}}$$

---

## 3. Protocol Message Exchange & Sequence

```mermaid
sequenceDiagram
    autonumber
    participant L as Slot Leader (Proposer)
    participant P1 as Peer Validator 1
    participant P2 as Peer Validator 2
    participant DB as Leader DB

    Note over L: Slot s begins; Leader builds block
    L->>L: buildBlock(slot, txs, header, signature)
    
    par Collect Votes
        L->>P1: POST /{network}/blocks/vote (Block Header)
        P1->>P1: Validate Header, Slot Leader, Timestamp
        P1-->>L: Vote Response {vote: true, signature: Sig_P1}
    and
        L->>P2: POST /{network}/blocks/vote (Block Header)
        P2->>P2: Validate Header, Slot Leader, Timestamp
        P2-->>L: Vote Response {vote: true, signature: Sig_P2}
    end

    Note over L: Tally votes via ConsensusQuorum
    L->>L: Verify W_yes/W_total >= 2/3 AND Votes_yes >= ceil(2N/3)
    L->>L: Generate ConsensusCertificate
    L->>DB: finalizeBlock() & distributeBlockFees()

    par Propagate Finalized Block
        L->>P1: POST /{network}/blocks/receive (Block + ConsensusCertificate)
        P1->>P1: verifyConsensusCertificate()
        P1->>P1: Mark isFinalized=true, distributeFees()
    and
        L->>P2: POST /{network}/blocks/receive (Block + ConsensusCertificate)
        P2->>P2: verifyConsensusCertificate()
        P2->>P2: Mark isFinalized=true, distributeFees()
    end
```

---

## 4. Verifiable Consensus Certificate Specification

When consensus is reached, the proposer compiles a cryptographic consensus certificate embedded within the block payload:

```json
{
  "consensusCertificate": {
    "blockHeight": 42,
    "blockHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
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
      "NVHNEzEVZfLBKrbvTznp9dsAUC3Gb7eNmg"
    ],
    "signatures": {
      "Ndq8JTY3oX8D3gDqogzsWFy9uzTWhuN51E": "3045022100...",
      "NVHNEzEVZfLBKrbvTznp9dsAUC3Gb7eNmg": "3044022071..."
    },
    "finalizedAt": 1762918800000
  }
}
```

### Verification Criteria for Receiving Peers
Any node receiving a block with `isFinalized: true` checks:
1. `certificate.blockHeight == payload.blockHeight`
2. `certificate.blockHash == payload.blockHash`
3. All addresses in `certificate.voters` are distinct and registered as `ACTIVE` validators in the node's local database.
4. Quorum satisfied: $\frac{W_{yes}}{W_{total}} \ge \frac{2}{3}$ and $Votes_{yes} \ge \lceil \frac{2N}{3} \rceil$.
5. `peerConfirmations > 0` when $N > 1$.
Failure to satisfy any condition forces the receiving node to save the block as **UNFINALIZED**, preventing unauthorized state transitions or premature fee distribution.
