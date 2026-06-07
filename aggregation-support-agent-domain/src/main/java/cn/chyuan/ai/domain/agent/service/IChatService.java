package cn.chyuan.ai.domain.agent.service;

import cn.chyuan.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * 对话接口
 *
 * @author chyuan @chyuan
 * 2025/12/17 08:13
 */
public interface IChatService {

    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    String createSession(String agentId, String userId);

    List<String> handleMessage(String agentId, String userId, String message);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

    void storeStreamConversationMemory(String userId, String agentId, String sessionId, String message, String response);

    /**
     * 确保 ADK 会话有效 — 如果 InMemorySessionService 中不存在，自动创建新会话
     * <p>
     * 处理应用重启后 ADK 内存会话丢失但数据库中仍有记录的场景。
     *
     * @param agentId   智能体ID
     * @param userId    用户ID
     * @param sessionId 待验证的会话ID
     * @return 有效的 sessionId（可能与输入不同）
     */
    String ensureAdkSession(String agentId, String userId, String sessionId);

}
