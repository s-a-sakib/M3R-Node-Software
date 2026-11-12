# M3R Historical Validator Set Specification (HVSS-v1)

## 1. Specification Overview

In Proof-of-Reliability + Proof-of-Stake (PoR+PoS), validator eligibility, voting weights, and active rosters evolve over time due to stake delegation, unbonding, slashing, and reliability updates.

To ensure that consensus certificates signed in earlier epochs remain cryptographically auditable and verifiable across process restarts and by newly joining nodes, M3R implements **Durable Historical Validator-Set Snapshots**.

Every block header commits to a 32-byte `validatorSetHash`. Historical consensus certificates are validated against the exact validator weights and roster committed to at that height.

---

## 2. Relational Storage Schema (Flyway V002)

Snapshots are durably stored in MySQL using the `validator_snapshots` table defined in `V002__Add_Historical_Snapshots_And_Certificates.sql`:

```sql
CREATE TABLE IF NOT EXISTS `validator_snapshots` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `snapshot_hash` VARCHAR(64) NOT NULL,
  `network` VARCHAR(20) NOT NULL,
  `block_height` BIGINT NOT NULL,
  `captured_at` BIGINT NOT NULL,
  `snapshot_data_json` LONGTEXT NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_snapshot_hash` (`snapshot_hash`),
  KEY `idx_snap_net_height` (`network`, `block_height`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.1 Database Entities and Repositories

- **Entity**: `com.m3rwallet.entity.HistoricalValidatorSnapshot`
- **Repository**: `com.m3rwallet.repository.ValidatorSnapshotRepository`
  - `findBySnapshotHash(String snapshotHash)`
  - `findByNetworkAndBlockHeight(String network, Long blockHeight)`
  - `existsBySnapshotHash(String snapshotHash)`

---

## 3. Validator-Set Commitment Hash Algorithm

The `validatorSetHash` committed in block headers is computed deterministically as follows:

1. **Active Roster Filtering**:
   $$V_{active} = \{ v \in V \mid v.status = \text{ACTIVE} \land v.address \ne \text{null} \}$$
2. **Canonical Address Ordering**:
   Validators are sorted lexicographically by their canonical Base58Check address:
   $$v_1 < v_2 \iff v_1.address < v_2.address$$
3. **Deterministic Text Canonicalization**:
   For each validator $v_i$, trailing zeros of BigDecimal stake amounts are stripped:
   $$\text{Entry}_i = v_i.address + \text{":"} + \text{stripTrailingZeros}(v_i.stakedAmount) + \text{":"} + v_i.reliabilityScoreScaled + \text{";"}$$
4. **Cryptographic Hash Computation**:
   $$\text{ValidatorSetHash} = \text{Hex}(\text{SHA-256}(\text{UTF-8}(\sum_{i=1}^N \text{Entry}_i)))$$
   If $V_{active}$ is empty, the hash is defined as 64 zeros (`"0".repeat(64)`).

---

## 4. Snapshot Data Model & JSON Encoding

The complete validator roster is serialized as a JSON array of `ValidatorSnapshotItem` objects:

```json
[
  {
    "address": "NVHNEzEVZfLBKrbvTznp9dsAUC3Gb7eNmg",
    "stakedAmount": 10000,
    "reliabilityScoreScaled": 950000,
    "weight": 1.398566055
  },
  {
    "address": "NidXN8ZVWqqmN2vDt7UcJ6EAPJvDPYM6Qy",
    "stakedAmount": 10000,
    "reliabilityScoreScaled": 950000,
    "weight": 1.398566055
  }
]
```

---

## 5. Integrity & Tampering Protection

To prevent local database tampering from corrupting historical verification:

1. **Recomputation upon Deserialization**:
   When `deserializeSnapshotEntity()` reads an entity from the database, it reconstructs the active validator entities from `snapshot_data_json` and independently recomputes the SHA-256 validator set hash.
2. **Fail-Closed Tamper Rejection**:
   ```java
   String recomputedHash = BlockValidationService.computeValidatorSetHash(reconstructedValidators);
   if (!recomputedHash.equalsIgnoreCase(entity.getSnapshotHash())) {
       log.error("[SECURITY] Historical validator snapshot hash TAMPERING DETECTED for height {}! Expected: {} but recomputed: {}. Failing closed.",
               entity.getBlockHeight(), entity.getSnapshotHash(), recomputedHash);
       return null; // Fails closed!
   }
   ```
   If stored contents do not match `snapshot_hash`, the snapshot is rejected.
3. **Overwrite Prevention**:
   The database enforces a `UNIQUE KEY` on `snapshot_hash`. Any attempt to overwrite an existing snapshot with conflicting contents is blocked.

---

## 6. Cold Restart & Synchronization Lifecycle

1. **Startup Preload**:
   Upon node boot, `@PostConstruct initSnapshots()` queries `validatorSnapshotRepository.findAll()`, validates the hash of every stored snapshot, and preloads active snapshots into high-performance in-memory lookup caches:
   - `snapshotCacheByHash: Map<String, ValidatorSetSnapshot>`
   - `snapshotCacheByHeight: Map<String, ValidatorSetSnapshot>`
2. **On-Demand Loading**:
   If a historical block queries a hash not in memory, the node attempts an on-demand database lookup before failing closed.
3. **Missing Snapshot Behavior**:
   If a historical block commits to a validatorSetHash with no recorded snapshot in database or cache, `BlockBroadcastService.verifyConsensusCertificate()` emits a security alert and returns `false`, rejecting the block.
