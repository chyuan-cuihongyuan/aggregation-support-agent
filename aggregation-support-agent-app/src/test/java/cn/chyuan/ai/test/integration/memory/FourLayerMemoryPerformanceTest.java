package cn.chyuan.ai.test.integration.memory;

import cn.chyuan.ai.domain.agent.model.valobj.memory.MemoryMessage;
import cn.chyuan.ai.domain.agent.service.IShortTermMemoryService;
import cn.chyuan.ai.domain.agent.service.IUnifiedMemoryService;
import cn.chyuan.ai.domain.agent.service.memory.IKnowledgeGraphService;
import cn.chyuan.ai.domain.agent.service.memory.ISensoryMemoryService;
import cn.chyuan.ai.infrastructure.metrics.MemoryMetrics;
import cn.chyuan.ai.test.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 四层记忆架构性能测试
 * <p>
 * 测试各层在高并发场景下的性能表现。
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("四层记忆架构性能测试")
public class FourLayerMemoryPerformanceTest extends BaseIntegrationTest {

    @Resource
    private ISensoryMemoryService sensoryMemoryService;

    @Resource
    private IShortTermMemoryService shortTermMemoryService;

    @Resource
    private IUnifiedMemoryService unifiedMemoryService;

    @Resource
    private IKnowledgeGraphService knowledgeGraphService;

    @Resource
    private MemoryMetrics memoryMetrics;

    private String perfUserId;
    private String perfAgentId;

    @BeforeEach
    void setUp() {
        perfUserId = "perf-test-user-" + System.currentTimeMillis();
        perfAgentId = "perf-test-agent";
    }

    @Test
    @DisplayName("感知记忆层性能测试：1000 次并发预处理")
    void testSensoryMemoryPerformance() throws InterruptedException {
        int threadCount = 10;
        int requestsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount * requestsPerThread);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount * requestsPerThread; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    String input = "测试输入 " + index;
                    String result = sensoryMemoryService.processInput(input);
                    if (result != null && !result.isEmpty()) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("感知记忆层处理失败", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;
        executor.shutdown();

        double qps = (threadCount * requestsPerThread) / (duration / 1000.0);
        log.info("感知记忆层性能测试完成: {} 次请求, 耗时 {}ms, QPS: {:.2f}, 成功: {}, 失败: {}",
                threadCount * requestsPerThread, duration, qps, successCount.get(), failCount.get());

        assertEquals(threadCount * requestsPerThread, successCount.get() + failCount.get(),
                "所有请求都应该完成");
        assertTrue(successCount.get() > threadCount * requestsPerThread * 0.95,
                "成功率应该超过 95%");
    }

    @Test
    @DisplayName("短期记忆层性能测试：100 个会话并发读写")
    void testShortTermMemoryPerformance() throws InterruptedException {
        int sessionCount = 100;
        int messagesPerSession = 20;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(sessionCount);
        AtomicInteger successCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < sessionCount; i++) {
            final int sessionIndex = i;
            executor.submit(() -> {
                try {
                    String sessionId = "perf-session-" + sessionIndex;
                    for (int j = 0; j < messagesPerSession; j++) {
                        MemoryMessage msg = MemoryMessage.user("消息 " + j);
                        shortTermMemoryService.addMessage(msg, sessionId);
                    }

                    List<MemoryMessage> messages = shortTermMemoryService.getMessages(sessionId, 20);
                    if (messages.size() == messagesPerSession) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("短期记忆层操作失败", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;
        executor.shutdown();

        double qps = (sessionCount * messagesPerSession * 2) / (duration / 1000.0); // 读写各一次
        log.info("短期记忆层性能测试完成: {} 个会话, 每个 {} 条消息, 耗时 {}ms, QPS: {:.2f}, 成功: {}",
                sessionCount, messagesPerSession, duration, qps, successCount.get());

        assertTrue(successCount.get() > sessionCount * 0.95, "成功率应该超过 95%");
    }

    @Test
    @DisplayName("长期记忆层性能测试：并发检索延迟")
    void testLongTermMemoryRetrievalPerformance() throws InterruptedException {
        // 准备测试数据
        String sessionId = "perf-retrieval-session";
        for (int i = 0; i < 50; i++) {
            unifiedMemoryService.remember("测试记忆 " + i, perfUserId, perfAgentId, sessionId);
        }

        int threadCount = 10;
        int requestsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount * requestsPerThread);
        AtomicInteger successCount = new AtomicInteger(0);
        long[] latencies = new long[threadCount * requestsPerThread];

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount * requestsPerThread; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    long reqStart = System.currentTimeMillis();
                    List<String> results = unifiedMemoryService.recall(
                            "测试查询 " + index, perfUserId, perfAgentId, sessionId, 5);
                    long reqDuration = System.currentTimeMillis() - reqStart;
                    latencies[index] = reqDuration;

                    if (results != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("长期记忆层检索失败", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(120, TimeUnit.SECONDS);
        long totalDuration = System.currentTimeMillis() - startTime;
        executor.shutdown();

        // 计算 P50、P95、P99
        java.util.Arrays.sort(latencies);
        long p50 = latencies[latencies.length / 2];
        long p95 = latencies[(int) (latencies.length * 0.95)];
        long p99 = latencies[(int) (latencies.length * 0.99)];

        double qps = (threadCount * requestsPerThread) / (totalDuration / 1000.0);
        log.info("长期记忆层检索性能测试完成:");
        log.info("  - 总请求数: {}", threadCount * requestsPerThread);
        log.info("  - 总耗时: {}ms", totalDuration);
        log.info("  - QPS: {:.2f}", qps);
        log.info("  - P50 延迟: {}ms", p50);
        log.info("  - P95 延迟: {}ms", p95);
        log.info("  - P99 延迟: {}ms", p99);
        log.info("  - 成功数: {}", successCount.get());

        assertEquals(threadCount * requestsPerThread, successCount.get(), "所有请求都应该成功");
        assertTrue(p95 < 2000, "P95 延迟应该小于 2000ms");
    }

    @Test
    @DisplayName("实体记忆层性能测试：并发实体查询")
    void testEntityMemoryPerformance() throws InterruptedException {
        // 准备测试数据
        String sessionId = "perf-entity-session";
        knowledgeGraphService.extractAndUpdateEntities(
                "用户张三喜欢 Python 编程，使用 VS Code 编辑器", perfUserId, perfAgentId);

        int threadCount = 10;
        int requestsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount * requestsPerThread);
        AtomicInteger successCount = new AtomicInteger(0);
        long[] latencies = new long[threadCount * requestsPerThread];

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount * requestsPerThread; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    long reqStart = System.currentTimeMillis();
                    List<String> entities = knowledgeGraphService.queryRelatedEntities(
                            "Python 编程", perfUserId, perfAgentId, 2);
                    long reqDuration = System.currentTimeMillis() - reqStart;
                    latencies[index] = reqDuration;

                    if (entities != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("实体记忆层查询失败", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(120, TimeUnit.SECONDS);
        long totalDuration = System.currentTimeMillis() - startTime;
        executor.shutdown();

        // 计算 P50、P95、P99
        java.util.Arrays.sort(latencies);
        long p50 = latencies[latencies.length / 2];
        long p95 = latencies[(int) (latencies.length * 0.95)];
        long p99 = latencies[(int) (latencies.length * 0.99)];

        double qps = (threadCount * requestsPerThread) / (totalDuration / 1000.0);
        log.info("实体记忆层查询性能测试完成:");
        log.info("  - 总请求数: {}", threadCount * requestsPerThread);
        log.info("  - 总耗时: {}ms", totalDuration);
        log.info("  - QPS: {:.2f}", qps);
        log.info("  - P50 延迟: {}ms", p50);
        log.info("  - P95 延迟: {}ms", p95);
        log.info("  - P99 延迟: {}ms", p99);
        log.info("  - 成功数: {}", successCount.get());

        assertTrue(successCount.get() > threadCount * requestsPerThread * 0.95, "成功率应该超过 95%");
        assertTrue(p95 < 1000, "P95 延迟应该小于 1000ms");
    }
}
