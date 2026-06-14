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

    /**
     * 判断事件是否对用户可见（终端 agent 的输出可见，中间 agent 的内部 JSON 不可见）。
     * <p>
     * 出口层（Controller）在 send 前调用，避免中间 agent 的思考 JSON 泄漏到前端。
     * 未配置终端 author（output-agent）时返回 true（向后兼容，不过滤）。
     *
     * @param event   ADK 事件
     * @param agentId 智能体ID（用于解析该 agent 的终端 author 配置）
     * @return true 表示该事件输出对用户可见
     */
    boolean isUserVisible(Event event, String agentId);

}
