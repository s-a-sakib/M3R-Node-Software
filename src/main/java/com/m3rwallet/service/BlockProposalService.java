package com.m3rwallet.service;

import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.BlockTransaction;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.BlockRepository;
import com.m3rwallet.repository.BlockTransactionRepository;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class BlockProposalService {
    private static final Logger log = LoggerFactory.getLogger(BlockProposalService.class);

    private final MempoolService mempoolService;
    private final ValidatorService validatorService;
    private final NodeIdentityService nodeIdentityService;
    private final BlockRepository blockRepository;
    private final BlockTransactionRepository blockTransactionRepository;
    private final String defaultNetwork;
    private final int maxBlockSize;

    public BlockProposalService(MempoolService mempoolService,
                                ValidatorService validatorService,
                                NodeIdentityService nodeIdentityService,
                                BlockRepository blockRepository,
                                BlockTransactionRepository blockTransactionRepository,
                                @Value("${app.blockchain.network:mainnet}") String defaultNetwork,
                                @Value("${app.blockchain.max-block-size:5000}") int maxBlockSize) {
        this.mempoolService = mempoolService;
        this.validatorService = validatorService;
        this.nodeIdentityService = nodeIdentityService;
        this.blockRepository = blockRepository;
        this.blockTransactionRepository = blockTransactionRepository;
        this.defaultNetwork = defaultNetwork;
        this.maxBlockSize = maxBlockSize;
    }

    public Block buildBlock(long slotNumber, Validator proposer, List<MempoolService.PendingTx> pendingTxs, String network) {
        if (network == null) network = defaultNetwork;
        Block block = new Block();
        block.setVersion((byte)1);
        block.setSlotNumber(slotNumber);
        long now = System.currentTimeMillis();
        block.setTimestamp(now);
        block.setProposerAddress(proposer.getAddress());
        block.setProposerWeight(validatorService.calculateWeight(proposer));

        var lastOpt = blockRepository.findTopByNetworkOrderByBlockHeightDesc(network);

        block.setNetwork(network);
        if (lastOpt != null && lastOpt.isPresent()) {
            Block last = lastOpt.get();
            block.setBlockHeight(last.getBlockHeight() + 1);
            block.setParentBlockHash(last.getBlockHash());
        } else {
            block.setBlockHeight(1L);
            block.setParentBlockHash("0000000000000000000000000000000000000000000000000000000000000000");
        }

        List<String> txHashes = (pendingTxs == null) ? List.of() : pendingTxs.stream().map(MempoolService.PendingTx::txHash).sorted().collect(Collectors.toList());
        block.setTxCount(txHashes.size());

        // create tx list (not persisted yet)
        List<BlockTransaction> txs = createBlockTransactions(pendingTxs, block);
        // deterministically sort txs by txHash
        Collections.sort(txs, (a, b) -> a.getTxHash().compareTo(b.getTxHash()));

        List<String> sortedHashes = txs.stream().map(BlockTransaction::getTxHash).collect(Collectors.toList());
        String merkleRoot = computeMerkleRoot(sortedHashes);
        block.setMerkleRoot(merkleRoot);

        block.setStateRoot(computeStateRoot(network));
        block.setValidatorSetHash(computeValidatorSetHash(network));

        long nonce = Math.abs(System.nanoTime() % Long.MAX_VALUE);
        block.setNonce(nonce);
        block.setIsFinalized(false);
        block.setReceivedAt(System.currentTimeMillis());
        try {
            byte[] headerBytes = block.serializeForSigning();
            byte[] sig = nodeIdentityService.signData(headerBytes);
            block.setProposerSignature(bytesToHex(sig));
        } catch (Exception e) {
            log.warn("Could not sign block: {}", e.getMessage());
            block.setProposerSignature("UNSIGNED");
        }

        String blockHash = computeBlockHash(block);
        block.setBlockHash(blockHash);

        return block;
    }

    public List<BlockTransaction> createBlockTransactions(List<MempoolService.PendingTx> pendingTxs, Block block) {
        List<BlockTransaction> list = new ArrayList<>();
        if (pendingTxs == null || pendingTxs.isEmpty()) return list;
        List<MempoolService.PendingTx> sorted = pendingTxs.stream().sorted((a, b) -> a.txHash().compareTo(b.txHash())).collect(Collectors.toList());
        int idx = 0;
        for (MempoolService.PendingTx p : sorted) {
            BlockTransaction bt = new BlockTransaction();
            bt.setBlockHeight(block.getBlockHeight());
            bt.setTxHash(p.txHash());
            bt.setTxIndex(idx);
            bt.setSenderAddress(p.senderAddress());
            bt.setRecipientAddress(p.recipientAddress());
            try {
                bt.setValue(new BigDecimal(p.value()));
            } catch (Exception e) {
                bt.setValue(BigDecimal.ZERO);
            }
            bt.setTotalFee(p.fee());
            bt.setBroadcastFee(p.broadcastFee());
            bt.setConsensusFee(p.consensusFee());
            bt.setBroadcasterAddress(p.broadcasterAddress());
            bt.setNonce(0L);
            bt.setTimestamp(p.receivedAt());
            bt.setStatus(BlockTransaction.TxStatus.PENDING);
            list.add(bt);
            idx++;
        }
        return list;
    }

    public String computeMerkleRoot(List<String> txHashes) {
        try {
            if (txHashes == null || txHashes.isEmpty()) {
                Keccak.Digest256 k = new Keccak.Digest256();
                byte[] d = k.digest("empty".getBytes(StandardCharsets.UTF_8));
                return bytesToHex(d);
            }

            List<byte[]> level = txHashes.stream().map(h -> hexStringToBytes(h)).collect(Collectors.toList());
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            while (level.size() > 1) {
                List<byte[]> next = new ArrayList<>();
                for (int i = 0; i < level.size(); i += 2) {
                    byte[] left = level.get(i);
                    byte[] right = (i + 1 < level.size()) ? level.get(i + 1) : level.get(i);
                    byte[] combined = new byte[left.length + right.length];
                    System.arraycopy(left, 0, combined, 0, left.length);
                    System.arraycopy(right, 0, combined, left.length, right.length);
                    next.add(sha256.digest(combined));
                }
                level = next;
            }
            return bytesToHex(level.get(0));
        } catch (Exception e) {
            log.error("computeMerkleRoot failed", e);
            return "";
        }
    }

    public String computeBlockHash(Block block) {
        try {
            String base = block.getVersion() + "|" + block.getBlockHeight() + "|" + block.getSlotNumber() + "|"
                    + block.getParentBlockHash() + "|" + block.getMerkleRoot() + "|" + block.getTimestamp()
                    + "|" + block.getProposerAddress() + "|" + block.getNonce();
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] d = sha256.digest(base.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(d);
        } catch (Exception e) {
            log.error("computeBlockHash failed", e);
            return "";
        }
    }

    public String computeStateRoot(String network) {
        try {
            String base = "state:" + network + ":" + (System.currentTimeMillis() / 60000L);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return bytesToHex(sha256.digest(base.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("computeStateRoot failed", e);
            return "";
        }
    }

    public String computeValidatorSetHash(String network) {
        try {
            // STUB: deterministic placeholder until validator set fetching implemented
            String base = "validators:" + network;
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return bytesToHex(sha256.digest(base.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("computeValidatorSetHash failed", e);
            return "";
        }
    }

    @Transactional
    public Block saveBlock(Block block, List<BlockTransaction> txs) {
        Block saved = blockRepository.save(block);
        if (txs != null && !txs.isEmpty()) {
            for (BlockTransaction t : txs) t.setBlockHeight(saved.getBlockHeight());
            blockTransactionRepository.saveAll(txs);
        }
        return saved;
    }

    @Transactional
    public Block finalizeBlock(Block block, String network) {
        block.setIsFinalized(true);
        block.setFinalizedAt(System.currentTimeMillis());
        // update tx statuses
        List<BlockTransaction> txs = blockTransactionRepository.findByBlockHeight(block.getBlockHeight());
        if (txs != null) {
            for (BlockTransaction t : txs) {
                t.setStatus(BlockTransaction.TxStatus.CONFIRMED);
            }
            blockTransactionRepository.saveAll(txs);
        }
        Block saved = blockRepository.save(block);
        validatorService.recordSuccessfulProposal(block.getProposerAddress(), network);
        log.info("Block {} finalized. Proposer: {}. Txs: {}", saved.getBlockHeight(), saved.getProposerAddress(), saved.getTxCount());
        return saved;
    }

    @Transactional
    public void deleteBlock(Block block) {
        try {
            blockTransactionRepository.deleteByBlock(block);
            blockRepository.delete(block);
            log.info("Deleted unfinalized block {}", block.getBlockHeight());
        } catch (Exception e) {
            log.error("Could not delete block {}: {}", block.getBlockHeight(), e.getMessage());
        }
    }

    // --- helpers ---
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static byte[] hexStringToBytes(String s) {
        if (s == null) return new byte[0];
        String hex = s.length() % 2 == 0 ? s : "0" + s;
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        return data;
    }
}
