package com.m3rwallet.service;

import com.m3rwallet.config.ConsensusProperties;
import com.m3rwallet.entity.Block;
import com.m3rwallet.entity.Validator;
import com.m3rwallet.repository.ValidatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Concurrency test for BlockConsensusVoteService.
 *
 * Verifies M3R-SEC-06 remediation:
 * - Thread-safe certificate caching keyed by (network, height, hash).
 * - No crosstalk or cross-contamination between concurrent block proposals.
 * - Return of immutable VoteCollectionResult instead of reliance on shared mutable volatile lastCertificate.
 */
public class ConsensusCertificateConcurrencyTest {

    private BlockConsensusVoteService voteService;
    private ValidatorRepository validatorRepository;
    private ValidatorService validatorService;
    private NodeIdentityService nodeIdentityService;
    private RestTemplate restTemplate;
    private ConsensusProperties consensusProperties;

    @BeforeEach
    void setUp() {
        validatorRepository = Mockito.mock(ValidatorRepository.class);
        validatorService = Mockito.mock(ValidatorService.class);
        nodeIdentityService = Mockito.mock(NodeIdentityService.class);
        restTemplate = Mockito.mock(RestTemplate.class);
        consensusProperties = new ConsensusProperties();

        voteService = new BlockConsensusVoteService();
        ReflectionTestUtils.setField(voteService, "validatorRepository", validatorRepository);
        ReflectionTestUtils.setField(voteService, "validatorService", validatorService);
        ReflectionTestUtils.setField(voteService, "nodeIdentityService", nodeIdentityService);
        ReflectionTestUtils.setField(voteService, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(voteService, "consensusProperties", consensusProperties);
        ReflectionTestUtils.setField(voteService, "network", "testnet");
    }

    @Test
    @DisplayName("Concurrent store and retrieve across distinct block hashes never cross-contaminates")
    void testConcurrentStoreAndRetrieveNoCrossContamination() throws Exception {
        int threadCount = 32;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ConcurrentMap<String, Map<String, Object>> expectedCertificates = new ConcurrentHashMap<>();
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        long height = 1000L + j;
                        String hash = "hash_" + threadId + "_" + j;
                        String network = (threadId % 2 == 0) ? "mainnet" : "testnet";

                        Map<String, Object> cert = new LinkedHashMap<>();
                        cert.put("blockHeight", height);
                        cert.put("blockHash", hash);
                        cert.put("network", network);
                        cert.put("threadId", threadId);
                        cert.put("opId", j);

                        String key = voteService.certificateKey(network, height, hash);
                        expectedCertificates.put(key, cert);

                        voteService.storeCertificate(network, height, hash, cert);

                        // Immediate check
                        Map<String, Object> retrieved = voteService.getCertificate(network, height, hash);
                        if (retrieved == null || !hash.equals(retrieved.get("blockHash")) || !Integer.valueOf(threadId).equals(retrieved.get("threadId"))) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All concurrent tasks should complete in time");
        assertEquals(0, failures.get(), "No certificate retrieval failures or cross-contamination during concurrent writes");

        // Comprehensive post-verification
        for (Map.Entry<String, Map<String, Object>> entry : expectedCertificates.entrySet()) {
            Map<String, Object> expected = entry.getValue();
            String net = (String) expected.get("network");
            Long height = (Long) expected.get("blockHeight");
            String hash = (String) expected.get("blockHash");

            Map<String, Object> actual = voteService.getCertificate(net, height, hash);
            assertNotNull(actual, "Certificate must exist for key " + entry.getKey());
            assertEquals(expected.get("blockHash"), actual.get("blockHash"), "Block hash must match exactly");
            assertEquals(expected.get("threadId"), actual.get("threadId"), "Thread ID must match exactly");
        }
    }

    @Test
    @DisplayName("Different networks and block hashes at the exact same height are strictly isolated")
    void testSameHeightDifferentNetworkAndHashIsolation() {
        Long height = 500L;
        String hashMainnetA = "0xaaaa1111";
        String hashMainnetB = "0xaaaa2222";
        String hashTestnetA = "0xbbbb1111";

        Map<String, Object> certMainA = Map.of("net", "mainnet", "branch", "A", "blockHash", hashMainnetA);
        Map<String, Object> certMainB = Map.of("net", "mainnet", "branch", "B", "blockHash", hashMainnetB);
        Map<String, Object> certTestA = Map.of("net", "testnet", "branch", "A", "blockHash", hashTestnetA);

        voteService.storeCertificate("mainnet", height, hashMainnetA, certMainA);
        voteService.storeCertificate("mainnet", height, hashMainnetB, certMainB);
        voteService.storeCertificate("testnet", height, hashTestnetA, certTestA);

        assertEquals("A", voteService.getCertificate("mainnet", height, hashMainnetA).get("branch"));
        assertEquals("B", voteService.getCertificate("mainnet", height, hashMainnetB).get("branch"));
        assertEquals("A", voteService.getCertificate("testnet", height, hashTestnetA).get("branch"));

        // Querying non-existent hash returns null even if same height is present
        assertNull(voteService.getCertificate("mainnet", height, "0xunknown"));
        assertNull(voteService.getCertificate("testnet", height, hashMainnetA));
    }

    @Test
    @DisplayName("Concurrent collectConsensusVotes returns independent VoteCollectionResult without lastCertificate race")
    void testConcurrentCollectConsensusVotesIndependentResults() throws Exception {
        voteService.setAllowSoloFinalization(true);

        when(nodeIdentityService.getNodeAddress()).thenReturn("0xproposer");
        when(nodeIdentityService.isPrivateKeyBacked()).thenReturn(false);

        Validator val = new Validator();
        val.setAddress("0xproposer");
        val.setStatus(Validator.ValidatorStatus.ACTIVE);
        when(validatorRepository.findByNetworkAndStatus(anyString(), any()))
                .thenReturn(List.of(val));
        when(validatorService.calculateWeight(any())).thenReturn(10.0);

        int count = 20;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        List<Future<BlockConsensusVoteService.VoteCollectionResult>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                Block b = new Block();
                b.setBlockHeight((long) (2000 + index));
                b.setBlockHash("0xhash_" + index);
                b.setProposerAddress("0xproposer");
                return voteService.collectConsensusVotes(b, "testnet");
            }));
        }

        for (int i = 0; i < count; i++) {
            BlockConsensusVoteService.VoteCollectionResult res = futures.get(i).get(5, TimeUnit.SECONDS);
            assertTrue(res.consensusReached(), "Solo consensus reached for block " + i);
            assertEquals((long) (2000 + i), res.blockHeight());
            assertEquals("0xhash_" + i, res.blockHash());
            assertNotNull(res.certificate());
            assertEquals("0xhash_" + i, res.certificate().get("blockHash"));

            // Verify stored in cache independently
            Map<String, Object> cached = voteService.getCertificate("testnet", (long) (2000 + i), "0xhash_" + i);
            assertNotNull(cached);
            assertEquals("0xhash_" + i, cached.get("blockHash"));
        }

        executor.shutdown();
    }
}
