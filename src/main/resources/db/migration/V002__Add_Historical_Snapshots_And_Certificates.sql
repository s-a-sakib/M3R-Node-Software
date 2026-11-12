-- Flyway migration V002: Add historical snapshots and certificate persistence
-- Preserves existing data and wallet compatibility

ALTER TABLE `blocks` ADD COLUMN IF NOT EXISTS `proposer_public_key` VARCHAR(66) DEFAULT NULL;
ALTER TABLE `blocks` ADD COLUMN IF NOT EXISTS `consensus_certificate` LONGTEXT DEFAULT NULL;

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
