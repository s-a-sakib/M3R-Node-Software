# M3R Node Server - SRS And Guideline Book

`Node_Software` is the M3R Coin node server. It is responsible for the HTTP wallet API, MySQL persistence, account state, transaction decoding/validation/execution, escrow state, wallet history ledger, local peer consensus, mempool, block production, validator registry/sync, peer sync, fee accounting, and localhost admin dashboard.

Android wallet documentation is separate: `../M3R_Wallet_App/README.md`.

## 1. Product Scope

The node server provides a local-development blockchain-like backend for M3R Coin wallets. A wallet submits signed raw transactions to a node. The node verifies signatures, nonces, balances, escrow authorization, and transaction shape, then persists the result. When consensus is enabled, the receiving node asks peers to validate and execute the same transaction. The server also exposes explorer/admin endpoints for accounts, transactions, blocks, mempool, validators, and health.

This implementation is suitable for local testing and iterative development. The current peer quorum and block logic are not an audited public-chain BFT consensus protocol.

## 2. Stakeholders And Actors

| Actor | Responsibility |
| --- | --- |
| Wallet user | Uses the Android wallet to query balance/history and submit signed transactions. |
| Node operator | Runs the Spring Boot node, manages database, ports, keys, peers, and logs. |
| Local admin | Uses `/admin` pages to inspect node state on localhost. |
| Peer node | Receives consensus, block, validator, and peer-sync messages from other nodes. |
| Validator node | Registers an identity, participates in local validation/proposal flow, and receives proposal stats/slashing data. |
| Developer | Maintains controllers, services, DTOs, entities, templates, configuration, and test harnesses. |

## 3. Technology Stack

| Layer | Technology |
| --- | --- |
| Runtime | Java 17 |
| Framework | Spring Boot 3.2.0 |
| Build | Maven |
| Persistence | MySQL 8+, Spring Data JPA, Hibernate |
| Web | Spring MVC REST controllers |
| Admin UI | Thymeleaf templates |
| Crypto | BouncyCastle, bitcoinj Base58, Apache Commons Codec |
| JSON | Jackson |
| Scheduling | Spring `@Scheduled` |
| Logging | Spring Boot logging with Lombok `@Slf4j` |

## 4. Complete File Structure

```text
Node_Software/
|-- README.md
|-- pom.xml
|-- setup-mysql.sh
|-- setup-mysql-dev.sh
|-- start.sh
|-- src.zip
`-- src/main/
    |-- java/com/m3rwallet/
    |   |-- M3RWalletServerApplication.java
    |   |-- config/
    |   |   |-- BeanConfig.java
    |   |   |-- ConsensusProperties.java
    |   |   |-- LocalhostOnlyInterceptor.java
    |   |   |-- RestTemplateConfig.java
    |   |   `-- WebMvcConfig.java
    |   |-- controller/
    |   |   |-- AdminDashboardController.java
    |   |   |-- DashboardController.java
    |   |   |-- NodeSyncController.java
    |   |   |-- ValidatorController.java
    |   |   `-- WalletController.java
    |   |-- dto/
    |   |   |-- AccountInfoResponse.java
    |   |   |-- ArbiterResponse.java
    |   |   |-- FaucetRequest.java
    |   |   |-- FaucetResponse.java
    |   |   |-- FeeResponse.java
    |   |   |-- HealthResponse.java
    |   |   |-- TxHistoryResponse.java
    |   |   |-- TxResponse.java
    |   |   `-- TxSubmitRequest.java
    |   |-- entity/
    |   |   |-- Account.java
    |   |   |-- Block.java
    |   |   |-- BlockTransaction.java
    |   |   |-- BroadcasterEarning.java
    |   |   |-- Escrow.java
    |   |   |-- Peer.java
    |   |   |-- ProposerEarning.java
    |   |   |-- Receipt.java
    |   |   |-- SlashEvent.java
    |   |   |-- Transaction.java
    |   |   |-- TxLedger.java
    |   |   |-- Validator.java
    |   |   `-- ValidatorWeight.java
    |   |-- lifecycle/
    |   |   |-- NodeShutdownHandler.java
    |   |   `-- NodeStartupRecovery.java
    |   |-- repository/
    |   |   |-- AccountRepository.java
    |   |   |-- BlockRepository.java
    |   |   |-- BlockTransactionRepository.java
    |   |   |-- BroadcasterEarningRepository.java
    |   |   |-- EscrowRepository.java
    |   |   |-- PeerRepository.java
    |   |   |-- ProposerEarningRepository.java
    |   |   |-- ReceiptRepository.java
    |   |   |-- SlashEventRepository.java
    |   |   |-- TransactionRepository.java
    |   |   |-- TxLedgerRepository.java
    |   |   |-- ValidatorRepository.java
    |   |   `-- ValidatorWeightRepository.java
    |   |-- scheduler/
    |   |   `-- BlockScheduler.java
    |   |-- service/
    |   |   |-- AccountReconciliationService.java
    |   |   |-- AccountService.java
    |   |   |-- BlockBroadcastService.java
    |   |   |-- BlockConsensusVoteService.java
    |   |   |-- BlockProposalService.java
    |   |   |-- BlockSyncService.java
    |   |   |-- BlockValidationService.java
    |   |   |-- EscrowService.java
    |   |   |-- FeeDistributionService.java
    |   |   |-- MempoolService.java
    |   |   |-- NodeConsensusService.java
    |   |   |-- NodeIdentityService.java
    |   |   |-- PeerSyncService.java
    |   |   |-- SlashDetectionService.java
    |   |   |-- TransactionService.java
    |   |   |-- TxLedgerService.java
    |   |   |-- ValidatorService.java
    |   |   |-- ValidatorSyncService.java
    |   |   `-- WalletService.java
    |   `-- util/
    |       |-- AddressUtil.java
    |       |-- AdminViewUtil.java
    |       |-- CryptoUtil.java
    |       `-- TxDecoder.java
    `-- resources/
        |-- application.yml
        |-- db/migration/
        |   `-- V001__Create_Blockchain_Tables.sql
        `-- templates/
            |-- admin/
            |   |-- accounts.html
            |   |-- blocks.html
            |   |-- dashboard.html
            |   |-- escrows.html
            |   |-- transactions.html
            |   `-- validators.html
            `-- public/
                `-- index.html
```

## 5. Package Responsibilities

| Package | Responsibility |
| --- | --- |
| `config` | Application beans, `RestTemplate`, consensus config binding, MVC interceptors, localhost-only admin protection. |
| `controller` | REST and MVC entry points for wallet API, admin dashboard, validator utility API, node sync, and root dashboard. |
| `dto` | Request/response models returned to wallet and API clients. |
| `entity` | JPA table models for accounts, transactions, escrows, blocks, validators, peers, receipts, and fee earnings. |
| `repository` | Spring Data repositories used by services and controllers. |
| `service` | Business logic: validation, state updates, consensus, mempool, block proposal, peer sync, validator logic, fee distribution. |
| `scheduler` | Timed block production and cleanup jobs. |
| `lifecycle` | Startup recovery and shutdown handling. |
| `util` | Address conversion, admin display formatting, crypto helpers, raw transaction decoding. |
| `resources/templates` | Thymeleaf admin and public HTML pages. |

## 6. Functional Requirements

| ID | Requirement | Implementation |
| --- | --- | --- |
| NS-FR-01 | Expose network-scoped wallet APIs. | `WalletController` under `/{network}/...`. |
| NS-FR-02 | Return fee policy. | `GET /{network}/fee`, `WalletService#getBroadcastFee`, `getPercentFeeBps`. |
| NS-FR-03 | Return account balance and nonce. | `GET /{network}/account`, `AccountService`, `AddressUtil`. |
| NS-FR-04 | Accept signed raw transactions. | `POST /{network}/tx/submit`, `TxSubmitRequest`. |
| NS-FR-05 | Decode transaction wire format. | `TxDecoder`. |
| NS-FR-06 | Verify sender from compressed public key. | `WalletService#parseAndVerifyTransaction`, `CryptoUtil`. |
| NS-FR-07 | Enforce nonce replay protection. | `WalletService#executeTransaction`, hash checks, account nonce checks. |
| NS-FR-08 | Execute transfers. | Transaction type `0`, account debit/credit, ledger entries. |
| NS-FR-09 | Execute escrow create. | Transaction type `1`, `EscrowService`, ledger roles. |
| NS-FR-10 | Execute escrow release. | Transaction type `2`, seller credit, authorization checks. |
| NS-FR-11 | Execute escrow refund. | Transaction type `3`, buyer credit, authorization checks. |
| NS-FR-12 | Maintain participant ledger history. | `TxLedgerService`, `tx_ledger`, `/tx/history`. |
| NS-FR-13 | Provide test faucet only outside mainnet. | `POST /{network}/faucet`, blocked on `mainnet`. |
| NS-FR-14 | Provide transaction status lookup. | `GET /{network}/tx/status`. |
| NS-FR-15 | Provide mock arbiter assignment/listing. | `/arbiter/request`, `/arbiter/list`. |
| NS-FR-16 | Support optional transaction consensus. | `NodeConsensusService`, `/node/tx/validate`, `/node/tx/execute`. |
| NS-FR-17 | Maintain mempool. | `MempoolService`, `/mempool`. |
| NS-FR-18 | Produce blocks on schedule. | `BlockScheduler`, `BlockProposalService`. |
| NS-FR-19 | Receive and vote on blocks. | `/blocks/receive`, `/blocks/vote`, block services. |
| NS-FR-20 | Store block transactions and receipts. | `BlockTransaction`, `Receipt`, repositories. |
| NS-FR-21 | Register validators. | `/validator/register`, `/api/validator/register`, `ValidatorService`. |
| NS-FR-22 | Sync validators across peers. | `ValidatorSyncService`, `/validator/receive`. |
| NS-FR-23 | Track validator slash events. | `SlashDetectionService`, `SlashEvent`. |
| NS-FR-24 | Sync peers and blocks. | `PeerSyncService`, `BlockSyncService`, `NodeSyncController`. |
| NS-FR-25 | Expose explorer-style state APIs. | `/blocks`, `/accounts`, `/transactions`, `/validators`, `/stats`. |
| NS-FR-26 | Provide admin dashboard pages. | `AdminDashboardController`, Thymeleaf templates. |
| NS-FR-27 | Restrict admin to localhost. | `LocalhostOnlyInterceptor`, `WebMvcConfig`. |

## 7. Non-Functional Requirements

| Area | Requirement |
| --- | --- |
| Runtime | Must run on Java 17+. |
| Persistence | Must persist state in MySQL and tolerate application restart. |
| Consistency | Account updates that mutate balances/nonces must be transactional. |
| Security | Admin must be localhost-only; consensus tokens should be configured for peer calls. |
| Addressing | APIs should accept M3R Base58Check or hex-20 where implemented; internal state uses canonical address strings. |
| Amounts | Store amounts in base units, not floating point BDT values. |
| Observability | Log important validation, consensus, block, validator, and sync events. |
| Operability | Most environment-specific settings must be overrideable through Spring Boot CLI args or env vars. |
| Local testing | Multiple nodes must be runnable from one jar with separate ports/databases/keys. |

## 8. Data Model

| Table/entity | Key fields | Purpose |
| --- | --- | --- |
| `accounts` | network, address, balance, nonce | Canonical account state. |
| `transactions` | network, hash, status, createdAt | Submitted/executed transaction registry. |
| `tx_ledger` | network, txHash, participantAddr, type, amount, fee, fromAddr, toAddr, escrowId, status | Wallet history by participant. |
| `escrows` | network, escrowId, buyer, seller, arbiter, amount, status, metadata, createdAt | Locked-fund escrow state. |
| `blocks` | blockHeight, blockHash, parentBlockHash, slotNumber, proposerAddress, txCount, roots, finality | Chain/block metadata. |
| `block_transactions` | blockHeight, txHash, txIndex, sender, recipient, value, fees, broadcaster, status | Transactions included in blocks. |
| `receipts` | blockHeight, txHash, txIndex, status, gasUsed, output, errorMessage | Execution result metadata. |
| `validators` | address, network, stakedAmount, reliability, proposals, status | Validator registry and stats. |
| `validator_weights` | validatorAddress, network, weight, reliability snapshot, stake snapshot | Validator selection/weight snapshots. |
| `slash_events` | validatorAddress, network, reason, severity, evidence | Misbehavior evidence and penalties. |
| `peers` | peerUrl, network, active, lastSeenAt, failCount | Peer discovery and health. |
| `broadcaster_earnings` | broadcasterAddress, txHash, broadcastFee | Transaction broadcaster rewards. |
| `proposer_earnings` | proposerAddress, blockHeight, consensusFee | Block proposer rewards. |

## 9. Transaction Contract

The node decodes `rawTxHex` using `TxDecoder`.

Common transaction header:

```text
version: u16
chainId: u32
type: u8
nonce: u64
fee: u64
timestamp: u64
fromAddr20: 20 bytes
payloadLength: u32
payload: variable
memoLength: u32
memo: variable, max 1024 bytes
sigScheme: u8
signatureLength: u32
signature: variable, max 512 bytes
```

Supported transaction types:

| Code | Type | Payload |
| --- | --- | --- |
| `0` | `TRANSFER` | `toAddr20`, `amount`. |
| `1` | `ESCROW_CREATE` | `escrowId32`, `buyer20`, `seller20`, `arbiter20`, `amount`, `expiryTs`, `releaseMode`, `disputeMode`, `metaHash32`. |
| `2` | `ESCROW_RELEASE` | `escrowId32`, `toAddr20`, `amount`. |
| `3` | `ESCROW_REFUND` | `escrowId32`, `toAddr20`, `amount`. |

Validation rules include decode limits, derived sender address from compressed public key, signature verification, nonce equality, sufficient balance, escrow state, and escrow authorization.

## 10. HTTP API

Supported path networks:

```text
mainnet
testnet
legacy
```

Wallet API:

```text
GET  /{network}/fee
GET  /{network}/account?addr=<hex20-or-M3R-address>
GET  /{network}/account?address=<hex20-or-M3R-address>
POST /{network}/tx/submit
GET  /{network}/tx/status?hash=<txHash>
GET  /{network}/tx/history?addr=<hex20-or-M3R-address>
POST /{network}/arbiter/request
GET  /{network}/arbiter/list
POST /{network}/faucet
GET  /{network}/health
```

Submit request:

```json
{
  "rawTxHex": "...",
  "pubKeyCompressedHex": "..."
}
```

Explorer/public node API:

```text
GET  /{network}/blocks?page=0&size=20
GET  /{network}/blocks/{height}
GET  /{network}/accounts?page=0&size=20
GET  /{network}/transactions?page=0&size=20
GET  /{network}/validators
GET  /{network}/validator/{address}
GET  /{network}/mempool
GET  /{network}/stats
GET  /{network}/node/status
```

Peer and consensus API:

```text
POST /{network}/node/tx/validate
POST /{network}/node/tx/execute
POST /{network}/blocks/receive
POST /{network}/blocks/vote
POST /{network}/validator/register
POST /{network}/validator/receive
```

Node sync API:

```text
POST /api/node/block/receive
GET  /api/node/block/latest
GET  /api/node/block/{height}
GET  /api/node/health
POST /api/node/peer/announce
GET  /api/node/peer/list
```

Validator utility API:

```text
POST /api/validator/register
GET  /api/validator/{address}
GET  /api/validator/list
GET  /api/validator/{address}/slashes
POST /api/validator/{address}/release
```

## 11. Admin Dashboard

Admin routes:

```text
GET  /admin
GET  /admin/accounts
GET  /admin/transactions
GET  /admin/escrows
GET  /admin/blocks
GET  /admin/validators
POST /admin/accounts/search
POST /admin/transactions/search
POST /admin/escrows/search
GET  /admin/api/stats
GET  /admin/api/blockchain-stats
GET  /admin/api/validators
GET  /admin/api/accounts
GET  /admin/api/transactions
GET  /admin/api/escrows
```

Guidelines:

- Admin pages are for local node operators.
- Do not expose `/admin` publicly.
- Override default admin credentials before any shared environment.
- Keep admin display formatting in `AdminViewUtil`; do not change persisted values just for display.

## 12. Configuration Reference

Main file:

```text
src/main/resources/application.yml
```

Important keys:

```yaml
server:
  port: 3000

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/m3rwallet_db?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC
    username: root
    password: ${DB_PASSWORD:rootpass}
  jpa:
    hibernate:
      ddl-auto: update

app:
  admin:
    username: ${M3R_ADMIN_USERNAME:hululuadmin}
    password: ${M3R_ADMIN_PASSWORD:puripuri saitama}
  broadcast-fee: 100
  percent-fee-bps: 100
  genesis-address: NQWWb4huPaqUncdJHFm2FHFjTo1qqLskuq
  consensus:
    enabled: true
    timeout-ms: 2500
    shared-secret: local-test-secret
    peers: []
  fee-policy:
    broadcast-fee-bps: 2000
    consensus-fee-bps: 8000
    base-fee: 100
    per-byte-fee: 1
  validator:
    enabled: true
    minimum-stake: 1000
    stake: 10000
    slot-duration-ms: 15000
    selection-mode: round-robin
    require-balance: false
  blockchain:
    network: mainnet
    slot-ms: 15000
    max-block-size: 500
    skip-empty-blocks: true
    finality-confirmations: 2
  node:
    self-url: http://localhost:${server.port:3000}
    bootstrap-peers: ${NODE_BOOTSTRAP_PEERS:}
    private-key: ${M3R_NODE_PRIVATE_KEY:}
```

## 13. Build, Run, And Local Cluster

Build:

```bash
cd /M3R-Node-Software
mvn clean package -DskipTests
```

Run tests:

```bash
mvn test
```

Create databases:

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS m3rwallet_db;"
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS m3rwallet_node_3000;"
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS m3rwallet_node_4000;"
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS m3rwallet_node_5000;"
```

Generate local node keys:

```bash
KEY1=$(openssl rand -hex 32)
KEY2=$(openssl rand -hex 32)
KEY3=$(openssl rand -hex 32)
```

Run one node in port 5000:

```bash
java -jar target/m3r-wallet-server-1.0.0.jar \
  --server.port=5000 \
  '--spring.datasource.url=jdbc:mysql://localhost:3306/m3rwallet_load_5000?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC' \
  --spring.datasource.username=root \
  --spring.datasource.password="$MYSQL_ROOT_PASSWORD" \
  --app.node.private-key="KEY1" \
  --app.node.self-url=http://localhost:5000 \
  --app.validator.enabled=true \
  --app.validator.stake=10000 \
  --app.validator.selection-mode=round-robin \
  --app.validator.startup-proposal-delay-ms=25000 \
  --app.blockchain.network=mainnet \
  --app.node.block-sync-interval-ms=10000 \
  --app.consensus.enabled=true \
  --app.consensus.peers[0]=$PEER[0]_URL \
  --app.consensus.peers[1]=$PEER[1]_URL \
  --app.consensus.shared-secret=local-test-secret
```

For three nodes, use separate ports/databases/keys and configure each node's `app.consensus.peers` to point to the other two nodes.

## 14. Consensus And Block Guidelines

Transaction consensus flow:

1. Submitter validates transaction locally.
2. Submitter asks peer `/node/tx/validate` endpoints.
3. Submitter counts local yes vote plus peer yes votes.
4. If active validators exist, validator weight can approve by two-thirds.
5. Count fallback requires `ceil(2/3 of total nodes)`.
6. Submitter broadcasts `/node/tx/execute` to peers.
7. Submitter executes locally and returns the final response.

Block flow:

1. `BlockScheduler` runs periodically by `app.blockchain.slot-ms`.
2. `BlockProposalService` builds block metadata from pending mempool transactions.
3. Block services broadcast, receive, validate, vote, persist, and finalize blocks.
4. Finalization can update block transaction statuses and validator proposal counters.

Do not treat this as production BFT consensus without major redesign and audit.

## 15. Security Guidelines

- Keep private node keys out of source files.
- Use `M3R_NODE_PRIVATE_KEY` or CLI args in local scripts only.
- Use a non-empty `app.consensus.shared-secret` for peer transaction consensus.
- Do not expose `/admin`, `/api/node/*`, `/{network}/node/*`, `/{network}/blocks/receive`, or `/{network}/blocks/vote` publicly.
- Replace default admin credentials in any shared environment.
- Keep wallet private keys client-side; the node should only receive public keys and signed transactions.
- Keep amount math integer/base-unit based.

## 16. Development Guidelines

- Put endpoint logic in controllers and business rules in services.
- Use repositories only from services/controllers where existing patterns already do.
- Keep mutation methods transactional.
- Do not bypass `WalletService` for transaction execution.
- Add DTO fields with backward compatibility for the Android wallet client.
- Use `AddressUtil` for address normalization/display.
- Use `TxLedgerService` whenever a user-visible transaction history entry is needed.
- Keep admin formatting in view utilities/templates, not in database state.
- Avoid changing transaction binary format without updating both node `TxDecoder` and Android `TxBuilder`/`TxV1`.

## 17. Troubleshooting

MySQL fails:

```bash
mysql -u root -p -e "SELECT 1;"
mysql -u root -p -e "SHOW DATABASES LIKE 'm3rwallet%';"
```

Port in use:

```bash
ps -ef | grep m3r-wallet-server
```

Consensus rejects valid transaction:

- Check peer URLs.
- Check shared secret.
- Check each node uses the same network.
- Check databases started from expected state.
- Check node private keys are unique.
- Check nonce sequence.

Nodes diverge:

- Rebuild with `mvn clean package -DskipTests`.
- Use the same `app.blockchain.network`.
- Clean local node databases if already forked.
- Confirm validator sync and block receive logs.

## 18. Known Implementation Boundaries

- Arbiter endpoints currently return mock arbiter data.
- The local quorum system is not production BFT consensus.
- Flyway migration files exist as DDL documentation, but Flyway is not configured in Maven.
- Rate-limit configuration exists; verify enforcement before relying on it as a security boundary.