package cn.chyuan.ai.domain.agent.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话历史滑动窗口管理器 — 控制多轮对话的上下文长度，保留最近 6 轮对话
 * <p>
 * 策略：每个会话最多保留 MAX_MESSAGE_PAIRS 对（用户消息 + 助手回复 = 12 条消息），
 * 超出时丢弃最早的对话轮次，确保上下文不会超出模型 Token 限制。
 * <p>
 * 线程安全：使用 ConcurrentHashMap 存储各会话的历史记录。
 */
@Slf4j
@Component
public class ChatHistoryManager {

    /** 最大保留对话轮次（1 轮 = 用户消息 + 助手回复） */
    private static final int MAX_MESSAGE_PAIRS = 6;

    /** 会话历史存储：key=sessionId, value=消息列表 */
    private final Map<String, List<ChatMessage>> sessionHistories = new ConcurrentHashMap<>();

    /**
     * 添加用户消息到会话历史，并自动执行滑动窗口裁剪
     *
     * @param sessionId 会话 ID
     * @param role      消息角色（user / assistant）
     * @param content   消息内容
     */
    public void addMessage(String sessionId, String role, String content) {
        sessionHistories.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new ChatMessage(role, content));

        // 滑动窗口裁剪：超出最大轮次时移除最早的一对消息
        trimHistory(sessionId);
    }

    /**
     * 获取会话历史消息列表
     *
     * @param sessionId 会话 ID
     * @return 消息列表（按时间顺序）
     */
    public List<ChatMessage> getHistory(String sessionId) {
        List<ChatMessage> messages = sessionHistories.get(sessionId);
        return messages != null ? new ArrayList<>(messages) : Collections.emptyList();
    }

    /**
     * 获取格式化的历史消息（Map 列表，兼容 DashScope 格式）
     *
     * @param sessionId 会话 ID
     * @return 历史消息 Map 列表 [{"role": "user", "content": "..."}, ...]
     */
    public List<Map<String, String>> getHistoryAsMap(String sessionId) {
        List<ChatMessage> messages = getHistory(sessionId);
        List<Map<String, String>> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            Map<String, String> map = new HashMap<>();
            map.put("role", msg.getRole());
            map.put("content", msg.getContent());
            result.add(map);
        }
        return result;
    }

    /**
     * 清除指定会话的历史记录
     *
     * @param sessionId 会话 ID
     */
    public void clearHistory(String sessionId) {
        sessionHistories.remove(sessionId);
        log.info("会话历史已清除: sessionId={}", sessionId);
    }

    /**
     * 获取当前会话的对话轮次数
     */
    public int getSessionPairCount(String sessionId) {
        List<ChatMessage> messages = sessionHistories.get(sessionId);
        if (messages == null) return 0;
        return messages.size() / 2;
    }

    /**
     * 滑动窗口裁剪 — 保留最近 MAX_MESSAGE_PAIRS 对消息
     */
    private void trimHistory(String sessionId) {
        List<ChatMessage> messages = sessionHistories.get(sessionId);
        if (messages == null) return;

        int maxMessages = MAX_MESSAGE_PAIRS * 2;
        while (messages.size() > maxMessages) {
            // 移除最早的一对消息（用户 + 助手）
            if (messages.size() >= 2) {
                messages.remove(0);
                messages.remove(0);
            } else {
                messages.remove(0);
            }
        }
    }

    /**
     * 聊天消息内部类
     */
    public static class ChatMessage {
        private final String role;
        private final String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
    }

}
