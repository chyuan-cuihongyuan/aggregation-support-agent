package cn.chyuan.ai.domain.agent.service.chat;

import cn.chyuan.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.chyuan.ai.domain.agent.service.IChatService;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
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

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String validSessionId = resolveValidSessionId(runner, aiAgentRegisterVO.getAppName(), agentId, userId, sessionId);

        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, validSessionId, userMsg);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        if (agentId == null || agentId.isBlank()) {
            throw new AppException(ResponseCode.E0001.getCode(), "agentId 不能为空");
        }

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String validSessionId = resolveValidSessionId(runner, aiAgentRegisterVO.getAppName(), agentId, userId, sessionId);

        Content userMsg = Content.fromParts(Part.fromText(message));
        RunConfig runConfig = RunConfig.builder()
                .setStreamingMode(RunConfig.StreamingMode.SSE)
                .build();
        return runner.runAsync(userId, validSessionId, userMsg, runConfig);
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

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
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

        Content content = Content.builder().role("user").parts(parts).build();

        // 获取运行体
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String validSessionId = resolveValidSessionId(runner, aiAgentRegisterVO.getAppName(),
                chatCommandEntity.getAgentId(), chatCommandEntity.getUserId(), chatCommandEntity.getSessionId());

        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), validSessionId, content);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

    /**
     * 验证会话是否有效，无效时自动创建新会话（防止应用重启后内存中 session 丢失）
     */
    private String resolveValidSessionId(InMemoryRunner runner, String appName, String agentId, String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return createSession(agentId, userId);
        }
        try {
            Session session = runner.sessionService()
                    .getSession(appName, userId, sessionId)
                    .blockingGet();
            if (session != null) {
                return sessionId;
            }
        } catch (Exception ignored) {
        }
        log.warn("会话不存在，自动创建新会话 userId:{} oldSessionId:{}", userId, sessionId);
        userSessions.invalidate(userId + ":" + agentId);
        return createSession(agentId, userId);
    }

}
