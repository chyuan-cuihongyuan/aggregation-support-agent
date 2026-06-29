package cn.chyuan.ai.domain.agent.service.chat;

import cn.chyuan.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import cn.chyuan.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.chyuan.ai.domain.agent.model.entity.ChatHistoryEntity;
import cn.chyuan.ai.domain.agent.model.entity.ChatSessionEntity;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.chyuan.ai.domain.agent.service.IChatService;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.chyuan.ai.domain.memory.model.enums.MemoryType;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryMatch;
import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import cn.chyuan.ai.domain.memory.model.valobj.RecallOptions;
import cn.chyuan.ai.domain.memory.service.AgentMemoryService;
import cn.chyuan.ai.domain.rag.adapter.port.IEmbeddingService;
import cn.chyuan.ai.domain.rag.support.RagSourceCollector;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatService implements IChatService {

    /** 会话 ID 不存在时的兜底 scope 路径 */
    private static final String SCOPE_UNKNOWN = "/conversation/__unknown__";

    /** 会话重建时最大回灌历史条数（避免超出 LLM 上下文窗口） */
    private static final int MAX_HISTORY_REPLAY = 6;

    /** 单条历史消息最大字符数，超长截断防止历史中的自问自答污染新会话 */
    private static final int MAX_HISTORY_CHAR_LENGTH = 2000;

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @Resource
    private AgentMemoryService agentMemoryService;

    @Resource
    private IEmbeddingService embeddingService;

    /**
     * 会话 ID 缓存 — 按 tenantId:ownerUserId:agentId 复用同一 session，保持对话上下文连续
     * <p>
     * 最大容量 10000 条，24 小时无访问自动淘汰。
     * Google ADK 的 InMemoryRunner 的 session 是内存对象，与 JVM 生命周期一致，
     * 因此 Guava Cache 和 InMemoryRunner 的 session 生命周期对齐。
     */
    private final Cache<String, String> userSessions = CacheBuilder.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(24, TimeUnit.HOURS)
            .build();

    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    @Override
    public String createSession(String agentId, String userId) {
        if (agentId == null || agentId.isBlank()) {
            throw new AppException(ResponseCode.E0001.getCode(), "agentId 不能为空");
        }

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        TenantScopeVO scope = currentScope(userId);

        String sessionKey = scope.getTenantId() + ":" + scope.getOwnerUserId() + ":" + agentId;
        try {
            return userSessions.get(sessionKey, () -> {
                Session session = runner.sessionService().createSession(appName, scope.getOwnerUserId())
                        .blockingGet();
                chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                        .sessionId(session.id())
                        .agentId(agentId)
                        .tenantId(scope.getTenantId())
                        .ownerUserId(scope.getOwnerUserId())
                        .traceId("")
                        .build());
                return session.id();
            });
        } catch (Exception e) {
            throw new AppException(ResponseCode.E0001.getCode(), "创建会话失败", e);
        }
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {
        if (agentId == null || agentId.isBlank()) {
            throw new AppException(ResponseCode.E0001.getCode(), "agentId 不能为空");
        }

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = createSession(agentId, userId);

        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        if (agentId == null || agentId.isBlank()) {
            throw new AppException(ResponseCode.E0001.getCode(), "agentId 不能为空");
        }
        ensureSessionOwner(sessionId, agentId, userId);

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        // 在当前线程捕获租户作用域快照，防止异步执行链中 ThreadLocal 丢失
        final TenantScopeVO scopeSnapshot = currentScope(userId);

        // Step 1: 检索相关记忆
        String memoryContext = buildMemoryContext(userId, agentId, sessionId, message);

        // Step 2: 增强消息（注入记忆上下文）
        String enhancedMessage = enhanceMessageWithMemory(message, memoryContext);

        // 开启 RAG 证据收集 — 收集器在 ThreadLocal 中，由调用方（Controller）在出口 drain 取走并清理
        RagSourceCollector.begin(scopeSnapshot);

        try {
            Content userMsg = Content.fromParts(Part.fromText(enhancedMessage));
            Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);

            List<String> outputs = new ArrayList<>();
            // 收集 Agent 的推理过程（Thought），排除最终答案
            List<String> thoughtParts = new ArrayList<>();
            // 确保 blockingForEach 执行期间租户作用域可用
            TenantScopeVO previousScope = RequestScopeContext.snapshot();
            try {
                RequestScopeContext.attach(scopeSnapshot);
                events.blockingForEach(event -> {
                    String content = event.stringifyContent();
                    outputs.add(content);

                    // 捕获 Agent Thought：提取事件中的纯文本内容（非函数调用/响应）
                    event.content().ifPresent(c ->
                        c.parts().ifPresent(parts ->
                            parts.forEach(part ->
                                part.text().ifPresent(text -> {
                                    if (!text.isEmpty() && !text.isBlank()) {
                                        thoughtParts.add(text.trim());
                                    }
                                })
                            )
                        )
                    );
                });
            } finally {
                RequestScopeContext.attach(previousScope);
            }

            // 写入 Agent Thought 到 Holder：取所有推理文本（排除最后一条即最终答案）
            RagSourceCollector.Holder holder = RagSourceCollector.currentHolder();
            if (holder != null && thoughtParts.size() > 1) {
                // 多轮推理：前面的文本是 Thought，最后一条是最终答案
                String thought = String.join(" → ", thoughtParts.subList(0, thoughtParts.size() - 1));
                holder.setAgentThought(thought);
            } else if (holder != null && thoughtParts.size() == 1 && !toolCallsDetected(outputs)) {
                // 单轮推理且无工具调用：Thought 就是推理过程
                holder.setAgentThought(thoughtParts.get(0));
            }

            // Step 3: 异步存储对话记忆
            String response = String.join("\n", outputs);
            storeConversationMemory(userId, agentId, sessionId, message, response);

            return outputs;
        } catch (RuntimeException e) {
            // 异常路径下兜底清理 ThreadLocal，避免 Tomcat 工作线程复用时残留旧数据
            RagSourceCollector.drain();
            throw e;
        }
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        if (agentId == null || agentId.isBlank()) {
            throw new AppException(ResponseCode.E0001.getCode(), "agentId 不能为空");
        }
        ensureSessionOwner(sessionId, agentId, userId);

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        // 在当前线程（HTTP 线程）捕获租户作用域快照，防止异步流中 ThreadLocal 丢失
        final TenantScopeVO scopeSnapshot = currentScope(userId);

        // Step 1: 检索相关记忆
        String memoryContext = buildMemoryContext(userId, agentId, sessionId, message);

        // Step 2: 增强消息（注入记忆上下文）
        String enhancedMessage = enhanceMessageWithMemory(message, memoryContext);

        // 开启 RAG 证据收集 — 由 Controller 在 SSE complete / error 回调中 drain 并追发 sources 事件
        RagSourceCollector.begin(scopeSnapshot);

        Content userMsg = Content.fromParts(Part.fromText(enhancedMessage));
        RunConfig runConfig = RunConfig.builder()
                .setStreamingMode(RunConfig.StreamingMode.SSE)
                .build();

        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg, runConfig);

        // 包装 Flowable：确保在整个流的生命周期内租户作用域可用
        // Google ADK 的 InMemoryRunner 可能使用自己的线程调度器，
        // 导致 MySpringAI 中的 RequestScopeContext.snapshot() 读取到 null
        return events.compose(upstream -> upstream
                .doOnSubscribe(s -> {
                    RequestScopeContext.attach(scopeSnapshot);
                    log.debug("流式对话恢复租户作用域: tenantId={}, userId={}",
                            scopeSnapshot.getTenantId(), scopeSnapshot.getOwnerUserId());
                })
                .doFinally(() -> {
                    RequestScopeContext.clear();
                })
        );
    }
    
    /**
     * 存储流式对话记忆
     * <p>
     * 在流式对话完成后，由 Controller 调用此方法存储对话记忆
     *
     * @param userId    用户ID
     * @param agentId   智能体ID
     * @param sessionId 会话ID
     * @param message   用户消息
     * @param response  助手响应（流式收集的完整内容）
     */
    public void storeStreamConversationMemory(String userId, String agentId, String sessionId, 
                                              String message, String response) {
        if (response == null || response.isEmpty()) {
            return;
        }
        storeConversationMemory(userId, agentId, sessionId, message, response);
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        String agentId = chatCommandEntity.getAgentId();
        if (agentId == null || agentId.isBlank()) {
            throw new AppException(ResponseCode.E0001.getCode(), "agentId 不能为空");
        }

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        List<Part> parts = new ArrayList<>();

        // 提取文本内容用于记忆
        StringBuilder textContent = new StringBuilder();
        
        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
                if (textContent.length() > 0) textContent.append("\n");
                textContent.append(text.getMessage());
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        // Step 1: 检索相关记忆
        String userId = chatCommandEntity.getUserId();
        String sessionId = chatCommandEntity.getSessionId();
        String message = textContent.toString();
        String memoryContext = buildMemoryContext(userId, agentId, sessionId, message);
        
        // Step 2: 增强消息（注入记忆上下文）
        if (memoryContext != null && !memoryContext.isEmpty()) {
            parts.add(Part.fromText(memoryContext));
        }

        Content content = Content.builder().role("user").parts(parts).build();

        // 获取运行体
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        // 捕获租户作用域快照，防止异步执行链中 ThreadLocal 丢失
        final TenantScopeVO scopeSnapshot = currentScope(userId);

        Flowable<Event> events = runner.runAsync(userId, sessionId, content);

        List<String> outputs = new ArrayList<>();
        // 确保 blockingForEach 执行期间租户作用域可用
        TenantScopeVO previousScope = RequestScopeContext.snapshot();
        try {
            RequestScopeContext.attach(scopeSnapshot);
            events.blockingForEach(event -> outputs.add(event.stringifyContent()));
        } finally {
            RequestScopeContext.attach(previousScope);
        }

        // Step 3: 异步存储对话记忆
        String response = String.join("\n", outputs);
        storeConversationMemory(userId, agentId, sessionId, message, response);

        return outputs;
    }

    private void ensureSessionOwner(String sessionId, String agentId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        ChatSessionEntity sessionEntity = chatHistoryRepository.querySession(sessionId, currentScope(userId));
        if (sessionEntity == null) {
            throw new AppException(ResponseCode.AUTH_PERMISSION_DENIED.getCode(), "会话不存在或无权访问");
        }
        if (sessionEntity.getAgentId() != null && !sessionEntity.getAgentId().equals(agentId)) {
            throw new AppException(ResponseCode.AUTH_PERMISSION_DENIED.getCode(), "会话不存在或无权访问");
        }
    }
    
    /**
     * 构建记忆上下文
     * <p>
     * 优先检索会话级记忆（scope=/conversation/{sessionId}），
     * 如果不足则回退到 agent 级记忆（scope=/agent/{agentId}），确保跨会话记忆可检索。
     * <p>
     * 优化：query 只嵌入一次，复用给两次 recall 调用，避免重复 API 调用。
     */
    private String buildMemoryContext(String userId, String agentId, String sessionId, String message) {
        long startTime = System.currentTimeMillis();
        try {
            TenantScopeVO scope = currentScope(userId);

            // 预计算 query embedding，两次 recall 共用（节省 1 次 API 调用）
            float[] queryEmbedding = embeddingService.embed(message);

            // 优先检索会话级记忆
            List<MemoryMatch> sessionMemories = agentMemoryService.recall(
                message,
                queryEmbedding,
                RecallOptions.builder()
                    .tenantId(scope.getTenantId())
                    .userId(scope.getOwnerUserId())
                    .agentId(agentId)
                    .scope(conversationScope(sessionId))
                    .limit(5)
                    .minScore(0.45)
                    .minSemanticScore(0.6)
                    .build()
            );

            List<MemoryMatch> agentMemories = new ArrayList<>();

            // 会话级记忆不足时，回退到 agent 级 scope（复用同一个 queryEmbedding）
            if (sessionMemories.size() < 3) {
                agentMemories = agentMemoryService.recall(
                    message,
                    queryEmbedding,
                    RecallOptions.builder()
                        .tenantId(scope.getTenantId())
                        .userId(scope.getOwnerUserId())
                        .agentId(agentId)
                        .scope("/agent/" + agentId)
                        .limit(5)
                        .minScore(0.45)
                        .minSemanticScore(0.6)
                        .build()
                );
                // 合并去重
                Set<String> existingIds = sessionMemories.stream()
                    .map(m -> m.getEntry().getMemoryId())
                    .collect(Collectors.toSet());
                for (MemoryMatch m : agentMemories) {
                    if (!existingIds.contains(m.getEntry().getMemoryId())) {
                        sessionMemories.add(m);
                    }
                    if (sessionMemories.size() >= 5) break;
                }
            }

            // 新增：记录记忆检索结果到 Holder
            long costTimeMs = System.currentTimeMillis() - startTime;
            RagSourceCollector.Holder holder = RagSourceCollector.currentHolder();
            if (holder != null) {
                Map<String, Object> memoryRecallResult = new LinkedHashMap<>();
                memoryRecallResult.put("queryText", message);
                memoryRecallResult.put("sessionMemoryCount", sessionMemories.size());
                memoryRecallResult.put("agentMemoryCount", agentMemories.size());
                memoryRecallResult.put("sessionMemoryScores", sessionMemories.stream()
                    .map(MemoryMatch::getScore).collect(Collectors.toList()));
                memoryRecallResult.put("agentMemoryScores", agentMemories.stream()
                    .map(MemoryMatch::getScore).collect(Collectors.toList()));
                memoryRecallResult.put("costTimeMs", (int) costTimeMs);
                holder.setMemoryRecallResult(memoryRecallResult);
            }

            if (sessionMemories.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n## 相关记忆\n");
            for (int i = 0; i < sessionMemories.size(); i++) {
                MemoryMatch match = sessionMemories.get(i);
                sb.append(String.format(
                    "%d. [%.0f%%相关] %s\n",
                    i + 1,
                    match.getScore() * 100,
                    match.getEntry().getContent()
                ));
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("检索记忆失败", e);
            return "";
        }
    }
    
    /**
     * 增强消息（添加记忆上下文）
     */
    private String enhanceMessageWithMemory(String message, String memoryContext) {
        if (memoryContext == null || memoryContext.isEmpty()) {
            return message;
        }
        
        return String.format("""
            %s
            
            ---
            
            %s
            """, message, memoryContext);
    }
    
    /**
     * 存储对话记忆
     * <p>
     * 同时写入会话级 scope（/conversation/{sessionId}）和 agent 级 scope（/agent/{agentId}），
     * 确保跨会话记忆可检索。agentMemoryService.remember() 内部有 contentHash 去重机制。
     */
    private void storeConversationMemory(String userId, String agentId, String sessionId,
                                         String question, String answer) {
        try {
            TenantScopeVO scope = currentScope(userId);
            String content = String.format("用户: %s\n助手: %s", question, answer);

            // 存储到会话级 scope
            agentMemoryService.remember(
                content,
                MemoryOptions.builder()
                    .tenantId(scope.getTenantId())
                    .userId(scope.getOwnerUserId())
                    .agentId(agentId)
                    .sessionId(sessionId)
                    .memoryType(MemoryType.EPISODE)
                    .scope(conversationScope(sessionId))
                    .source("chat")
                    .build()
            );

            // 同时存储到 agent 级 scope，确保跨会话可检索
            agentMemoryService.remember(
                content,
                MemoryOptions.builder()
                    .tenantId(scope.getTenantId())
                    .userId(scope.getOwnerUserId())
                    .agentId(agentId)
                    .sessionId(sessionId)
                    .memoryType(MemoryType.EPISODE)
                    .scope("/agent/" + agentId)
                    .source("chat")
                    .build()
            );
        } catch (Exception e) {
            log.warn("存储对话记忆失败", e);
        }
    }

    @Override
    public String ensureAdkSession(String agentId, String userId, String sessionId) {
        // sessionId 为空时直接创建新会话
        if (sessionId == null || sessionId.isBlank()) {
            return createSession(agentId, userId);
        }

        AiAgentRegisterVO register = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (register == null) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = register.getRunner();
        String appName = register.getAppName();
        TenantScopeVO scope = currentScope(userId);

        // 检查 ADK InMemorySessionService 中是否存在该会话
        try {
            Session session = runner.sessionService()
                    .getSession(appName, scope.getOwnerUserId(), sessionId, Optional.empty())
                    .blockingGet();
            if (session != null) {
                // 更新 Guava 缓存
                String sessionKey = scope.getTenantId() + ":" + scope.getOwnerUserId() + ":" + agentId;
                userSessions.put(sessionKey, sessionId);
                return sessionId;
            }
        } catch (java.util.NoSuchElementException e) {
            // ADK 内存中不存在该会话，需要重建
            log.info("ADK 会话不存在，尝试用原 sessionId 重建: sessionId={}, userId={}", sessionId, userId);
        } catch (Exception e) {
            log.warn("检查 ADK 会话异常，尝试重建: sessionId={}, userId={}", sessionId, userId, e);
        }

        // ADK 会话不存在 — 用原 sessionId 重建并回灌历史消息
        return rebuildSession(runner, appName, agentId, userId, sessionId, scope);
    }

    /**
     * 重建 ADK 会话 — 用原 sessionId 在 ADK 内存中恢复会话，并回灌历史消息。
     * <p>
     * 解决应用重启后 ADK InMemoryRunner 会话丢失的问题，
     * 确保切换到历史会话时 agent 能获取到之前的对话上下文。
     *
     * @param runner           ADK 运行器
     * @param appName          应用名称
     * @param agentId          智能体ID
     * @param userId           用户ID
     * @param originalSessionId 原始会话ID
     * @param scope            租户作用域
     * @return 恢复后的会话ID（与 originalSessionId 相同）
     */
    private String rebuildSession(InMemoryRunner runner, String appName,
                                  String agentId, String userId,
                                  String originalSessionId, TenantScopeVO scope) {
        // Step 1: 验证该 sessionId 在数据库中确实存在且属于该用户
        ChatSessionEntity sessionEntity = chatHistoryRepository.querySession(originalSessionId, scope);
        if (sessionEntity == null) {
            log.warn("会话在数据库中不存在，创建新会话: sessionId={}, userId={}", originalSessionId, userId);
            return createSession(agentId, userId);
        }

        // Step 2: 并发安全 — 再次检查是否已被其他线程重建
        try {
            Session existingSession = runner.sessionService()
                    .getSession(appName, scope.getOwnerUserId(), originalSessionId, Optional.empty())
                    .blockingGet();
            if (existingSession != null) {
                log.info("会话已被其他线程重建: sessionId={}", originalSessionId);
                String sessionKey = scope.getTenantId() + ":" + scope.getOwnerUserId() + ":" + agentId;
                userSessions.put(sessionKey, originalSessionId);
                return originalSessionId;
            }
        } catch (Exception ignored) {
            // 仍然不存在，继续重建
        }

        // Step 3: 用原 sessionId 在 ADK 内存中创建 session
        Session newSession = runner.sessionService()
                .createSession(appName, scope.getOwnerUserId(), new ConcurrentHashMap<>(), originalSessionId)
                .blockingGet();

        // Step 4: 从 chat_history 查出历史对话并回灌（限制条数 + 截断超长内容）
        List<ChatHistoryEntity> histories = chatHistoryRepository.queryBySessionId(originalSessionId, scope);
        int replayCount = Math.min(histories.size(), MAX_HISTORY_REPLAY);
        for (int i = 0; i < replayCount; i++) {
            ChatHistoryEntity history = histories.get(i);
            // 回灌用户消息（问题通常较短，不截断）
            appendHistoryEvent(runner, newSession, "user", history.getQuestion());
            // 回灌助手消息（截断超长回复，防止历史中的自问自答污染新会话）
            String answer = history.getAnswer();
            if (answer != null && answer.length() > MAX_HISTORY_CHAR_LENGTH) {
                answer = answer.substring(0, MAX_HISTORY_CHAR_LENGTH) + "...[历史回复已截断]";
            }
            appendHistoryEvent(runner, newSession, "model", answer);
        }

        // Step 5: 更新 Guava 缓存
        String sessionKey = scope.getTenantId() + ":" + scope.getOwnerUserId() + ":" + agentId;
        userSessions.put(sessionKey, originalSessionId);

        log.info("ADK 会话已重建: sessionId={}, 回灌历史消息 {} 条(共 {} 条记录), userId={}",
                originalSessionId, replayCount * 2, histories.size(), userId);

        return originalSessionId;
    }

    /**
     * 将一条历史消息作为 Event 追加到 ADK Session 中
     *
     * @param runner  ADK 运行器
     * @param session 目标会话
     * @param author  消息作者（"user" 或 "model"）
     * @param text    消息文本内容
     */
    private void appendHistoryEvent(InMemoryRunner runner, Session session,
                                    String author, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Content content = Content.builder()
                .role(author)
                .parts(List.of(Part.fromText(text)))
                .build();
        Event event = Event.builder()
                .author(author)
                .content(Optional.of(content))
                .build();
        runner.sessionService().appendEvent(session, event).blockingGet();
    }

    private TenantScopeVO currentScope(String fallbackUserId) {
        TenantScopeVO scope = RequestScopeContext.snapshot();
        if (scope == null || !scope.isValid()) {
            return TenantScopeVO.singleUser(fallbackUserId);
        }
        if (scope.getOwnerUserId() == null || scope.getOwnerUserId().isBlank()) {
            scope.setOwnerUserId(fallbackUserId);
        }
        return scope;
    }

    private String conversationScope(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return SCOPE_UNKNOWN;
        }
        return "/conversation/" + sessionId;
    }

    /**
     * 判断事件输出列表中是否包含工具调用事件
     * （通过 stringifyContent 结果的特征判断，函数调用事件的文本通常以特定模式开头）
     */
    private boolean toolCallsDetected(List<String> outputs) {
        // 如果 Holder 中有工具调用记录，说明确实有工具调用
        RagSourceCollector.Holder holder = RagSourceCollector.currentHolder();
        return holder != null && !holder.getToolCalls().isEmpty();
    }

}
