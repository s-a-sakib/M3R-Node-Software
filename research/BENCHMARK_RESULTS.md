# M3R Node Software — Empirical Benchmark & Performance Results

**Document:** `research/BENCHMARK_RESULTS.md`  
**Date:** November 12, 2026  
**Benchmarking Harness:** `M3RLoadTest.java` & JMH Micro-benchmarks  
**Testbed Environment:** Linux x86_64, AMD/Intel 8-Core Host, NVMe SSD, MySQL 8.0, OpenJDK 21  

---

## 1. Experimental Setup & Topologies

Benchmarks were evaluated on a local cluster configuration comprising three independent Spring Boot instances communicating over loopback HTTP REST interfaces with distinct MySQL schemas:

```
Topology:
Node 1 (Port 3000) <---> Node 2 (Port 4000)
       ^                        ^
       |                        |
       +--------> Node 3 <------+
               (Port 5000)
```

- **Node 1 (Port 3000):** DB `m3rwallet_load_3000`, Address `NQWWb4huPaqUncdJHFm2FHFjTo1qqLskuq`
- **Node 2 (Port 4000):** DB `m3rwallet_load_4000`, Address `Ndq8JTY3oX8D3gDqogzsWFy9uzTWhuN51E`
- **Node 3 (Port 5000):** DB `m3rwallet_load_5000`, Address `NVHNEzEVZfLBKrbvTznp9dsAUC3Gb7eNmg`

---

## 2. Empirical Benchmark Measurements

### 2.1 Macro Benchmark Summary (Node Consensus Pipeline)

| Workload Configuration | Threads | Total TXs | Success Rate | Measured TPS | Median Latency (P50) | P99 Latency | Failure Analysis |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| Sequential Paced | 1 | 100 | **100.0%** | **12.71 tx/s** | 146 ms | 256 ms | Zero rejections |
| Sequential Paced (Batch 2)| 1 | 50 | **100.0%** | **7.91 tx/s** | 249 ms | 415 ms | Zero rejections |
| Unpipelined Multi-Threaded| 2 | 50 | 56.0% (init) | 0.40 tx/s | 117 ms (acc) | 15,024 ms | Nonce out-of-order contention |
| Unpipelined Multi-Threaded| 2 | 50 | 51.0% (init) | 0.25 tx/s | 88 ms (acc) | 15,037 ms | Nonce out-of-order contention |
| Micro-benchmark (Crypto) | 1 | 10,000 | 100.0% | **1,940 tx/s** | 0.51 ms | 1.12 ms | Pure secp256k1 verify + Keccak-256 |
| Micro-benchmark (DB Lock) | 1 | 1,000 | 100.0% | **245 tx/s** | 4.08 ms | 8.92 ms | MySQL `SELECT FOR UPDATE` commit |

---

## 3. Bottleneck Breakdown & Component Latency Profiles

Profiling a single transaction lifecycle through consensus identifies the following latency breakdown:

```
Transaction Confirmation Latency Decomposition (~155 ms total):
├── 1. Cryptographic Verification (secp256k1 + Keccak-256) : ~0.5 ms  (0.3%)
├── 2. Database Read & Pessimistic Lock (findForUpdate)   : ~4.1 ms  (2.6%)
├── 3. Peer Validation HTTP Round-Trips (Node 2 & Node 3) : ~70.2 ms (45.3%)
├── 4. Peer Execution HTTP Round-Trips                    : ~74.5 ms (48.1%)
└── 5. Local Database Write & Commit                     : ~5.7 ms  (3.7%)
```

### Key Analytical Insights
1. **Network Overhead Dominates:** HTTP/1.1 REST round-trips to peers account for over **93%** of confirmation latency (~145 ms of 155 ms). Replacing synchronous HTTP POST calls with persistent HTTP/2, gRPC, or WebSocket streaming would reduce round-trip latency to $< 20\text{ ms}$, increasing node throughput to $> 50\text{ tx/s}$.
2. **Database Commit Overhead:** Each transaction issues multiple database queries inside `@Transactional(isolation = Isolation.SERIALIZABLE)`. MySQL InnoDB `fsync` operations on write limit single-account execution to ~245 tx/s.
3. **Mempool Batching Potential:** When transactions are packaged into blocks via `BlockProposalService` (with `maxBlockSize = 500`), the consensus cost is amortized across up to 500 transactions per block, allowing the system to achieve theoretical throughput of $> 33\text{ tx/s}$ at 15-second block intervals.

---

## 4. PoR+PoS Alpha Sensitivity Benchmark

Simulated slot leader election frequency across 500 slots comparing a high-stake node ($S=100,000, R=0.20$) versus a reputable node ($S=1,000, R=0.95$):

| Alpha Value ($\alpha$) | Stake Influence | Reputation Influence | High-Stake Win Rate | High-Reputation Win Rate | Characteristic |
| :---: | :---: | :---: | :---: | :---: | :--- |
| **0.00** | 0.0% | 100.0% | 17.4% | **82.6%** | Pure Proof-of-Reliability |
| **0.15** | 15.0% | 85.0% | 34.2% | **65.8%** | Prototype Default (Balanced) |
| **0.30** | 30.0% | 70.0% | 46.8% | **53.2%** | Near Parity |
| **0.50** | 50.0% | 50.0% | 58.4% | 41.6% | Stake Dominant |
| **0.75** | 75.0% | 25.0% | 66.8% | 33.2% | High Stake Dominant |
| **1.00** | 100.0% | 0.0% | **73.2%** | 26.8% | Pure Logarithmic PoS |

### Scientific Conclusion on $\alpha = 0.15$
The parameter $\alpha = 0.15$ provides a well-calibrated equilibrium where small validators with exemplary uptime and honest proposal records ($R=0.95$) win **65.8%** of slots despite having $100\times$ less capital than a whale node ($S=100,000$). At $\alpha \ge 0.50$, capital concentration begins to suppress validator reliability incentives.
