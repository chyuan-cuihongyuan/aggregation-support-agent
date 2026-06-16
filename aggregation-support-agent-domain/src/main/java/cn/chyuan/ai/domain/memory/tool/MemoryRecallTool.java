package cn.chyuan.ai.domain.memory.tool;

import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;
import cn.chyuan.ai.domain.memory.retrieval.IUnifiedMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆检索工具（供 Agent 在推理过程中按需调用）
 * 
 * 这是一个被动检索工具，Agent 在推理过程中判断需要历史知识时，
 * 可以主动调用此工具检索相关记忆。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryRecallTool {
    
    private final IUnifiedMemoryService memoryService;
    
    /**
     * 工具名称（注册到 Agent 时使用）
     */
    public static final String TOOL_NAME = "memory_recall";
    
    /**
     * 工具描述
     */
    public static final String TOOL_DESCRIPTION = """
        从长期记忆中检索与查询内容相关的历史记忆。
        当你需要回忆之前的对话、用户偏好、历史决策或专业知识时使用此工具。
        输入应该是一个描述你需要什么信息的查询语句。
        """;
    
    /**
     * 执行记忆检索
     *
     * @param query    查询内容
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @param limit    最大返回数量
     * @return 格式化的检索结果
     */
    public String execute(String query, String tenantId, String userId, int limit) {
        log.info("Agent 调用记忆检索工具: query='{}', limit={}", query, limit);
        
        List<MemoryMatch> results = memoryService.recall(
            query,
            RecallOptions.builder()
                .tenantId(tenantId)
                .userId(userId)
                .limit(limit)
                .minScore(0.3)
                .build()
        );
        
        if (results.isEmpty()) {
            return "未找到相关记忆。";
        }
        
        return results.stream()
            .map(match -> String.format(
                "- [%.0f%%相关/%s] %s",
                match.getScore() * 100,
                match.getEntry().getMemoryType().name(),
                match.getEntry().getContent()
            ))
            .collect(Collectors.joining("\n"));
    }
}
