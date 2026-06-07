-- Flyway migration: Create blockchain tables for blocks, block_transactions and receipts
-- Charset and collation chosen for broad compatibility

CREATE TABLE IF NOT EXISTS `blocks` (
  `block_height` BIGINT NOT NULL,
  `block_hash` VARCHAR(64) NOT NULL,
  `parent_block_hash` VARCHAR(64) DEFAULT NULL,
  `slot_number` BIGINT NOT NULL,
  `version` TINYINT NOT NULL DEFAULT 1,
  `timestamp` BIGINT NOT NULL,
  `nonce` BIGINT NOT NULL,
  `proposer_address` VARCHAR(64) DEFAULT NULL,
  `proposer_weight` DOUBLE NOT NULL DEFAULT 0,
  `proposer_signature` VARCHAR(128) DEFAULT NULL,
  `tx_count` INT NOT NULL DEFAULT 0,
  `merkle_root` VARCHAR(64) DEFAULT NULL,
  `state_root` VARCHAR(64) DEFAULT NULL,
  `validator_set_hash` VARCHAR(64) DEFAULT NULL,
  `is_finalized` BOOLEAN NOT NULL DEFAULT FALSE,
  `finalized_at` BIGINT DEFAULT NULL,
  `received_at` BIGINT DEFAULT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`block_height`),
  UNIQUE KEY `uk_block_hash` (`block_hash`),
  KEY `idx_parent` (`parent_block_hash`),
  KEY `idx_slot` (`slot_number`),
  KEY `idx_proposer` (`proposer_address`),
  KEY `idx_finalized` (`is_finalized`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `block_transactions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `block_height` BIGINT NOT NULL,
  `tx_hash` VARCHAR(64) NOT NULL,
  `tx_index` INT NOT NULL,
  `sender_address` VARCHAR(64) DEFAULT NULL,
  `recipient_address` VARCHAR(64) DEFAULT NULL,
  `value` DECIMAL(38,0) DEFAULT NULL,
  `total_fee` BIGINT DEFAULT NULL,
  `broadcast_fee` BIGINT DEFAULT NULL,
  `consensus_fee` BIGINT DEFAULT NULL,
  `broadcaster_address` VARCHAR(64) DEFAULT NULL,
  `nonce` BIGINT DEFAULT NULL,
  `timestamp` BIGINT DEFAULT NULL,
  `tx_signature` VARCHAR(128) DEFAULT NULL,
  `pub_key_compressed` VARCHAR(66) DEFAULT NULL,
  `status` VARCHAR(20) DEFAULT 'PENDING',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_block_txindex` (`block_height`,`tx_index`),
  UNIQUE KEY `uk_tx_hash` (`tx_hash`),
  KEY `idx_tx_hash` (`tx_hash`),
  KEY `idx_sender` (`sender_address`),
  KEY `idx_recipient` (`recipient_address`),
  KEY `idx_block` (`block_height`),
  CONSTRAINT `fk_bt_block` FOREIGN KEY (`block_height`) REFERENCES `blocks` (`block_height`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `receipts` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `block_height` BIGINT NOT NULL,
  `tx_hash` VARCHAR(64) NOT NULL,
  `tx_index` INT NOT NULL,
  `status` VARCHAR(20) NOT NULL,
  `gas_used` BIGINT DEFAULT NULL,
  `output` VARCHAR(512) DEFAULT NULL,
  `error_message` VARCHAR(512) DEFAULT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_block_tx` (`block_height`,`tx_index`),
  KEY `idx_tx_hash` (`tx_hash`),
  CONSTRAINT `fk_receipt_block` FOREIGN KEY (`block_height`) REFERENCES `blocks` (`block_height`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
