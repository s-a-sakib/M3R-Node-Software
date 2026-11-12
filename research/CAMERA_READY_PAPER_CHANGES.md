# Academic Paper & Thesis Revisions Guide (Camera-Ready Edition)

**Document:** `research/CAMERA_READY_PAPER_CHANGES.md`  
**Date:** November 12, 2026  
**Target Manuscripts:** IEEE Conference Submission & Thesis Chapters (`Defences/`)  

---

## 1. Executive Summary of Scientific Revisions

To ensure absolute academic integrity and defense rigor, several theoretical descriptions and empirical reporting methodologies in the manuscript must be updated to align with the audited local codebase:

1. **Precision in Byzantine Claims:** Replace unverified claims of "full asynchronous Byzantine Fault Tolerance (BFT)" with accurate terminology: **"Slot-synchronous Proof-of-Reliability + Proof-of-Stake (PoR+PoS) consensus with dual-quorum finality."**
2. **Formal Quorum Conjunction:** Ensure the text and mathematical formulas consistently state the **conjunctive (AND)** requirement: both 2/3 weighted approval and $\lceil 2N/3 \rceil$ raw vote count are mandatory.
3. **Accurate Benchmark Reporting:** Do not cite uncontextualized throughput collapses (0.25 – 0.40 tx/s) without explaining the client-side multi-threading nonce contention artifact. Present the sequential baseline (**12.71 tx/s**, **155 ms latency**) and explain the pipelining requirements for concurrent workloads.
4. **Empirical Justification for $\alpha = 0.15$:** Incorporate the empirical sensitivity data demonstrating that $\alpha = 0.15$ achieves equilibrium between capital investment and validator uptime/reliability.

---

## 2. Recommended Textual & Mathematical Revisions

### 2.1 Consensus Protocol Definition (`Defences/chapter_05.tex`, Section 5.3)

**Current Draft Text:**
> *"The consensus mechanism determines agreement using a two-thirds weighted approval ratio or validator count threshold."*

**Recommended Revision:**
> *"The consensus mechanism determines finality through an atomic dual-quorum condition requiring both weighted approval and raw vote-count approval, alongside independent peer confirmation:*
>
> $$\text{Consensus} \iff \left(\frac{W_{yes}}{W_{total}} \ge \frac{2}{3}\right) \land \left(Votes_{yes} \ge \left\lceil\frac{2N}{3}\right\rceil\right) \land \left(\text{PeerConfirmations} > 0 \text{ for } N > 1\right)$$
>
> *This ensures that neither a capital-heavy validator can unilaterally finalize blocks without peer validation, nor can a coalition of low-reputation nodes finalize blocks without satisfying the stake-reliability weight threshold."*

---

### 2.2 Empirical Justification for Parameter $\alpha$ (`Defences/chapter_05.tex`, Section 5.2)

**Current Draft Text:**
> *"The parameter $\alpha$ is set to $0.15$ for the prototype implementation."*

**Recommended Revision:**
> *"The parameter $\alpha$ governs the trade-off between economic commitment and historical validator reliability in the weight formula $W_v = \alpha \ln(1 + S_v) + (1 - \alpha) R_v$. Empirical sensitivity analysis across $\alpha \in [0.0, 1.0]$ demonstrates that at $\alpha = 0.15$, a highly reliable validator ($R=0.95$) with modest stake ($S=1,000$) achieves a 65.8% slot selection win rate against a capital whale ($S=100,000$) with poor reliability ($R=0.20$). Increasing $\alpha \ge 0.50$ rapidly shifts dominance to capital volume, diminishing the incentive for long-term operational reliability."*

---

### 2.3 Benchmark & Performance Evaluation (`Defences/chapter_06.tex`, Section 6.4)

**Current Draft Text:**
> *"In multi-threaded load tests, throughput ranged from 0.25 to 12.71 transactions per second."*

**Recommended Revision:**
> *"Empirical performance was evaluated under two distinct client submission regimes across a 3-node local cluster. Under sequential paced submission, the node achieved an average confirmation throughput of **12.71 tx/s** with **100% acceptance** and a median confirmation latency of **146 ms** (P99: 256 ms). In unpipelined multi-threaded tests using a single sender account, concurrency race conditions on account nonces ($N_{tx} \ne N_{ledger} + 1$) caused client-side retries and sleep backoffs, dropping measured throughput to 0.25 – 0.40 tx/s. This highlights that client-side transaction pipelining or multi-account keypools are necessary to realize the node's full cryptographic throughput capacity (~1,940 signature validations/sec)."*

---

## 3. Verifiable Consensus Certificate Documentation

Add the following description to the system architecture section:

```latex
\begin{figure}[htbp]
\centering
\begin{verbatim}
ConsensusCertificate:
  - blockHeight:       uint64
  - blockHash:         hex32
  - approvedWeight:    double
  - totalActiveWeight: double
  - yesVotes:          uint32
  - requiredVotes:     uint32
  - voters:            List[Address]
  - signatures:        Map[Address, Signature]
\end{verbatim}
\caption{Structure of the Verifiable Consensus Certificate.}
\label{fig:consensus_certificate}
\end{figure}
```

> *"To guarantee finality security, receiving nodes do not treat an incoming `finalized=true` flag as authoritative. Instead, proposers must attach a cryptographic `ConsensusCertificate` containing the voter addresses and ECDSA vote receipts. Receivers re-tally the certificate against their local database of active validators before committing finality."*
