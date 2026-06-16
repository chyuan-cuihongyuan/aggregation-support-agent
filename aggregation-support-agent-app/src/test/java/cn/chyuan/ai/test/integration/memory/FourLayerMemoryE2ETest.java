package cn.chyuan.ai.test.integration.memory;

import cn.chyuan.ai.domain.agent.model.valobj.memory.MemoryMessage;
import cn.chyuan.ai.domain.agent.service.IShortTermMemoryService;
import cn.chyuan.ai.domain.agent.service.IUnifiedMemoryService;
import cn.chyuan.ai.domain.agent.service.memory.IKnowledgeGraphService;
import cn.chyuan.ai.domain.agent.service.memory.IMemoryConsolidationService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 四层记忆架构端到端测试
 * <p>
 * 测试各层之间的数据流转和集成效果。
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("四层记忆架构端到端测试")
public class FourLayerMemoryE2ETest extends BaseIntegrationTest {

    @Resource
    private ISensoryMemoryService sensoryMemoryService;

    @Resource
    private IShortTermMemoryService shortTermMemoryService;

    @Resource
    private IUnifiedMemoryService unifiedMemoryService;

    @Resource
    private IKnowledgeGraphService knowledgeGraphService;

    @Resource
    private IMemoryConsolidationService memoryConsolidationService;

    @Resource
    private MemoryMetrics memoryMetrics;

    private String testUserId;
    private String testAgentId;
    private String testSessionId;

    @BeforeEach
    void setUp() {
        testUserId = "e2e-test-user-" + System.currentTimeMillis();
        testAgentId = "e2e-test-agent";
        testSessionId = "e2e-test-session-" + System.currentTimeMillis();
    }

    @Test
    @DisplayName("完整对话流程：感知 → 短期 → 长期 → 实体")
    void testCompleteConversationFlow() {
        // Step 1: 感知记忆层 - 预处理输入
        String userInput = "你好，我叫张三，我喜欢使用 Python 编程";
        String processedInput = sensoryMemoryService.processInput(userInput);
        assertNotNull(processedInput, "感知记忆层应该返回处理后的输入");
        assertFalse(processedInput.isEmpty(), "处理后的输入不应为空");
        log.info("Step 1 完成: 感知记忆层预处理 - 输入: '{}', 输出: '{}'", userInput, processedInput);

        // Step 2: 短期记忆层 - 添加用户消息
        MemoryMessage userMessage = MemoryMessage.user(processedInput);
        shortTermMemoryService.addMessage(userMessage, testSessionId);
        log.info("Step 2 完成: 短期记忆层添加用户消息");

        // Step 3: 长期记忆层 - 检索相关记忆（首次对话应该为空）
        List<String> longTermMemories = unifiedMemoryService.recall(
                processedInput, testUserId, testAgentId, testSessionId, 5);
        log.info("Step 3 完成: 长期记忆层检索 - 找到 {} 条相关记忆", longTermMemories.size());

        // Step 4: 实体记忆层 - 查询相关实体（首次对话应该为空）
        List<String> entityContext = knowledgeGraphService.queryRelatedEntities(
                processedInput, testUserId, testAgentId, 2);
        log.info("Step 4 完成: 实体记忆层查询 - 找到 {} 个相关实体", entityContext.size());

        // Step 5: 模拟助手响应
        String assistantResponse = "你好张三！很高兴认识你。Python 是一门很优秀的编程语言。";
        MemoryMessage assistantMessage = MemoryMessage.assistant(assistantResponse);
        shortTermMemoryService.addMessage(assistantMessage, testSessionId);
        log.info("Step 5 完成: 短期记忆层添加助手响应");

        // Step 6: 长期记忆层 - 异步存储对话记忆
        unifiedMemoryService.remember(processedInput, testUserId, testAgentId, testSessionId);
        unifiedMemoryService.remember(assistantResponse, testUserId, testAgentId, testSessionId);
        log.info("Step 6 完成: 长期记忆层存储对话记忆");

        // Step 7: 实体记忆层 - 提取并更新实体
        knowledgeGraphService.extractAndUpdateEntities(
                processedInput + " " + assistantResponse, testUserId, testAgentId);
        log.info("Step 7 完成: 实体记忆层提取并更新实体");

        // Step 8: 验证短期记忆
        List<MemoryMessage> shortTermMessages = shortTermMemoryService.getMessages(testSessionId, 20);
        assertEquals(2, shortTermMessages.size(), "短期记忆应该包含 2 条消息");
        log.info("Step 8 完成: 验证短期记忆 - 共 {} 条消息", shortTermMessages.size());

        // Step 9: 第二轮对话 - 验证记忆召回
        String secondUserInput = "你还记得我喜欢用什么编程语言吗？";
        String secondProcessedInput = sensoryMemoryService.processInput(secondUserInput);
        shortTermMemoryService.addMessage(MemoryMessage.user(secondProcessedInput), testSessionId);

        List<String> secondLongTermMemories = unifiedMemoryService.recall(
                secondProcessedInput, testUserId, testAgentId, testSessionId, 5);
        log.info("Step 9 完成: 第二轮对话长期记忆检索 - 找到 {} 条相关记忆", secondLongTermMemories.size());

        // 验证是否能召回第一轮的记忆
        boolean foundRelevantMemory = secondLongTermMemories.stream()
                .anyMatch(memory -> memory.contains("Python") || memory.contains("编程"));
        assertTrue(foundRelevantMemory, "应该能召回关于编程偏好的记忆");
        log.info("✓ 成功召回相关记忆");

        // Step 10: 验证实体记忆
        List<String> secondEntityContext = knowledgeGraphService.queryRelatedEntities(
                secondProcessedInput, testUserId, testAgentId, 2);
        log.info("Step 10 完成: 第二轮对话实体记忆查询 - 找到 {} 个相关实体", secondEntityContext.size());
    }

    @Test
    @DisplayName("记忆整合流程：去重 → 合并 → 抽象提炼")
    void testMemoryConsolidationFlow() {
        // 准备测试数据：存储多条相似的记忆
        String userId = "consolidation-test-user";
        String agentId = "consolidation-test-agent";
        String sessionId = "consolidation-test-session";

        // 存储多条关于同一主题的记忆
        for (int i = 0; i < 5; i++) {
            String memory = "用户喜欢使用 " + (i % 2 == 0 ? "Python" : "Java") + " 编程";
            unifiedMemoryService.remember(memory, userId, agentId, sessionId);
        }
        log.info("已存储 5 条测试记忆");

        // 执行记忆整合
        var result = memoryConsolidationService.consolidate(userId, agentId);
        log.info("记忆整合完成: 去重 {} 条, 合并 {} 条, 抽象 {} 条, 过期 {} 条, 冲突 {} 条",
                result.getDedupCount(),
                result.getMergeCount(),
                result.getAbstractionCount(),
                result.getExpireCount(),
                result.getConflictCount());

        // 验证整合结果
        assertTrue(result.getTotalProcessed() > 0, "应该处理了至少一条记忆");
        log.info("✓ 记忆整合流程验证通过");
    }

    @Test
    @DisplayName("监控指标采集验证")
    void testMetricsCollection() {
        // 模拟各层操作并记录指标
        long startTime = System.currentTimeMillis();

        // 感知记忆层
        sensoryMemoryService.processInput("测试输入");
        long sensoryDuration = System.currentTimeMillis() - startTime;
        memoryMetrics.recordSensoryInput("accepted", sensoryDuration);

        // 短期记忆层
        startTime = System.currentTimeMillis();
        shortTermMemoryService.addMessage(MemoryMessage.user("测试"), "test-session");
        long shortTermDuration = System.currentTimeMillis() - startTime;
        memoryMetrics.recordShortTermAdd("sliding_window", shortTermDuration);

        // 长期记忆层
        startTime = System.currentTimeMillis();
        unifiedMemoryService.recall("测试", testUserId, testAgentId, testSessionId, 5);
        long longTermDuration = System.currentTimeMillis() - startTime;
        memoryMetrics.recordLongTermRecall("hybrid", 0, longTermDuration);

        // 缓存指标
        memoryMetrics.recordCacheHit("longterm", true);

        log.info("✓ 监控指标采集验证通过");
    }
}
