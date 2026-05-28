package cn.chyuan.ai.domain.agent.service.chat;

import cn.chyuan.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import cn.chyuan.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.chyuan.ai.domain.agent.model.entity.ChatSessionEntity;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IChatHistoryRepository chatHistoryRepository;
    
    @Resource
    private AgentMemoryService agentMemoryService;

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

        String sessionKey = userId + ":" + agentId;
        try {
            return userSessions.get(sessionKey, () -> {
                Session session = runner.sessionService().createSession(appName, userId)
                        .blockingGet();
                chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                        .sessionId(session.id())
                        .agentId(agentId)
                        .tenantId(userId)
                        .ownerUserId(userId)
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

        // Step 1: 检索相关记忆
        String memoryContext = buildMemoryContext(userId, agentId, message);
        
        // Step 2: 增强消息（注入记忆上下文）
        String enhancedMessage = enhanceMessageWithMemory(message, memoryContext);

        // 开启 RAG 证据收集 — 收集器在 ThreadLocal 中，由调用方（Controller）在出口 drain 取走并清理
        RagSourceCollector.begin();

        try {
            Content userMsg = Content.fromParts(Part.fromText(enhancedMessage));
            Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);

            List<String> outputs = new ArrayList<>();
            events.blockingForEach(event -> outputs.add(event.stringifyContent()));
            
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

        // Step 1: 检索相关记忆
        String memoryContext = buildMemoryContext(userId, agentId, message);
        
        // Step 2: 增强消息（注入记忆上下文）
        String enhancedMessage = enhanceMessageWithMemory(message, memoryContext);

        // 开启 RAG 证据收集 — 由 Controller 在 SSE complete / error 回调中 drain 并追发 sources 事件
        RagSourceCollector.begin();

        Content userMsg = Content.fromParts(Part.fromText(enhancedMessage));
        RunConfig runConfig = RunConfig.builder()
                .setStreamingMode(RunConfig.StreamingMode.SSE)
                .build();
        
        return runner.runAsync(userId, sessionId, userMsg, runConfig);
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
        String memoryContext = buildMemoryContext(userId, agentId, message);
        
        // Step 2: 增强消息（注入记忆上下文）
        if (memoryContext != null && !memoryContext.isEmpty()) {
            parts.add(Part.fromText(memoryContext));
        }

        Content content = Content.builder().role("user").parts(parts).build();

        // 获取运行体
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Flowable<Event> events = runner.runAsync(userId, sessionId, content);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));
        
        // Step 3: 异步存储对话记忆
        String response = String.join("\n", outputs);
        storeConversationMemory(userId, agentId, sessionId, message, response);

        return outputs;
    }

    private void ensureSessionOwner(String sessionId, String agentId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        ChatSessionEntity sessionEntity = chatHistoryRepository.querySession(sessionId, TenantScopeVO.singleUser(userId));
        if (sessionEntity == null) {
            throw new AppException(ResponseCode.AUTH_PERMISSION_DENIED.getCode(), "会话不存在或无权访问");
        }
        // 只检查用户权限，不检查智能体匹配
        // 允许用户在任何智能体的会话中切换到其他智能体，保持上下文连续性
    }
    
    /**
     * 构建记忆上下文
     */
    private String buildMemoryContext(String userId, String agentId, String message) {
        try {
            List<MemoryMatch> memories = agentMemoryService.recall(
                message,
                RecallOptions.builder()
                    .tenantId(userId)
                    .userId(userId)
                    .agentId(agentId)
                    .limit(5)
                    .minScore(0.3)
                    .build()
            );
            
            if (memories.isEmpty()) {
                return "";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("\n## 相关记忆\n");
            for (int i = 0; i < memories.size(); i++) {
                MemoryMatch match = memories.get(i);
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
     */
    private void storeConversationMemory(String userId, String agentId, String sessionId, 
                                         String question, String answer) {
        try {
            String content = String.format("用户: %s\n助手: %s", question, answer);
            agentMemoryService.remember(
                content,
                MemoryOptions.builder()
                    .tenantId(userId)
                    .userId(userId)
                    .agentId(agentId)
                    .sessionId(sessionId)
                    .memoryType(MemoryType.EPISODE)
                    .scope("/conversation/" + sessionId)
                    .source("chat")
                    .build()
            );
        } catch (Exception e) {
            log.warn("存储对话记忆失败", e);
        }
    }

}
