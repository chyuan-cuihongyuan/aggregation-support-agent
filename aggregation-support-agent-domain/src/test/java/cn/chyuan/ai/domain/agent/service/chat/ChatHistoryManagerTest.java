package cn.chyuan.ai.domain.agent.service.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatHistoryManager 滑动窗口契约测试（工单 1138）：
 * 多轮对话保留最近 6 对（12 条）消息、会话隔离、清除与快照语义。
 */
@DisplayName("ChatHistoryManager 滑动窗口契约")
class ChatHistoryManagerTest {

    private final ChatHistoryManager manager = new ChatHistoryManager();

    @Test
    @DisplayName("按时间顺序保留消息且角色/内容成对可读")
    void preservesOrderAndPairs() {
        manager.addMessage("s1", "user", "u1");
        manager.addMessage("s1", "assistant", "a1");

        List<ChatHistoryManager.ChatMessage> history = manager.getHistory("s1");

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getRole()).isEqualTo("user");
        assertThat(history.get(0).getContent()).isEqualTo("u1");
        assertThat(history.get(1).getRole()).isEqualTo("assistant");
        assertThat(manager.getSessionPairCount("s1")).isEqualTo(1);
    }

    @Test
    @DisplayName("超出 6 对时滑动窗口丢弃最早轮次，只保留最近 12 条")
    void slidingWindowKeepsMostRecentTwelve() {
        for (int i = 1; i <= 8; i++) {
            manager.addMessage("s1", "user", "u" + i);
            manager.addMessage("s1", "assistant", "a" + i);
        }

        List<ChatHistoryManager.ChatMessage> history = manager.getHistory("s1");

        assertThat(history).hasSize(12);
        assertThat(history.get(0).getContent()).isEqualTo("u3");
        assertThat(history.get(11).getContent()).isEqualTo("a8");
        assertThat(manager.getSessionPairCount("s1")).isEqualTo(6);
    }

    @Test
    @DisplayName("恰好 6 对时不裁剪")
    void exactlySixPairsUntouched() {
        for (int i = 1; i <= 6; i++) {
            manager.addMessage("s1", "user", "u" + i);
            manager.addMessage("s1", "assistant", "a" + i);
        }

        assertThat(manager.getHistory("s1")).hasSize(12);
        assertThat(manager.getHistory("s1").get(0).getContent()).isEqualTo("u1");
    }

    @Test
    @DisplayName("会话之间历史互相隔离")
    void sessionsAreIsolated() {
        manager.addMessage("s1", "user", "s1-msg");
        manager.addMessage("s2", "user", "s2-msg");

        assertThat(manager.getHistory("s1")).hasSize(1);
        assertThat(manager.getHistory("s1").get(0).getContent()).isEqualTo("s1-msg");
        assertThat(manager.getHistory("s2").get(0).getContent()).isEqualTo("s2-msg");
    }

    @Test
    @DisplayName("清除后历史为空，未知会话返回空列表且不抛异常")
    void clearAndUnknownSessions() {
        manager.addMessage("s1", "user", "hello");
        manager.clearHistory("s1");

        assertThat(manager.getHistory("s1")).isEmpty();
        assertThat(manager.getSessionPairCount("s1")).isZero();
        assertThat(manager.getHistory("no-such-session")).isEmpty();
        assertThat(manager.getSessionPairCount("no-such-session")).isZero();
    }

    @Test
    @DisplayName("getHistory 返回快照副本，外部修改不影响内部状态")
    void historySnapshotIsDefensiveCopy() {
        manager.addMessage("s1", "user", "keep");

        List<ChatHistoryManager.ChatMessage> snapshot = manager.getHistory("s1");
        snapshot.clear();

        assertThat(manager.getHistory("s1")).hasSize(1);
    }

    @Test
    @DisplayName("getHistoryAsMap 输出 DashScope 兼容的 role/content 结构")
    void historyAsMapShape() {
        manager.addMessage("s1", "user", "u1");
        manager.addMessage("s1", "assistant", "a1");

        List<Map<String, String>> asMap = manager.getHistoryAsMap("s1");

        assertThat(asMap).hasSize(2);
        assertThat(asMap.get(0)).containsEntry("role", "user").containsEntry("content", "u1");
        assertThat(asMap.get(1)).containsEntry("role", "assistant").containsEntry("content", "a1");
    }
}
