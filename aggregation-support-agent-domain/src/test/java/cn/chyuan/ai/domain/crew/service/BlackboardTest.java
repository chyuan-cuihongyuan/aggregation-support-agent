package cn.chyuan.ai.domain.crew.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享黑板单测（工单 0217 AC5）：读写/版本递增/旧版本写拒绝/快照不可变。
 */
class BlackboardTest {

    @Test
    void 读写与版本递增() {
        Blackboard board = new Blackboard();
        assertEquals(1, board.put("k", "v1"));
        assertEquals(2, board.put("k", "v2"));
        assertEquals("v2", board.get("k"));
        assertEquals(2, board.versionOf("k"));
        assertEquals(0, board.versionOf("ghost"));
        assertNull(board.get("ghost"));
        assertEquals(2, board.totalWrites());
    }

    @Test
    void 乐观写入版本冲突拒绝() {
        Blackboard board = new Blackboard();
        long v1 = board.put("fact", "初版");
        // 期望 v1 写入成功 → v2
        assertEquals(2, board.putIfVersion("fact", "更新A", v1));
        // 另一方仍持 v1 → 拒绝（-1），事实不被旧写覆盖
        assertEquals(-1, board.putIfVersion("fact", "过期B", v1));
        assertEquals("更新A", board.get("fact"));
        // 新键期望版本 0 可直接写
        assertEquals(1, board.putIfVersion("new", "x", 0));
    }

    @Test
    void 快照不可变与键序() {
        Blackboard board = new Blackboard();
        board.put("b", 2);
        board.put("a", 1);
        Map<String, Object> snapshot = board.snapshot();
        assertEquals("{a=1, b=2}", snapshot.toString());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("c", 3));
        // 快照后写原板不影响快照（a 版本 v1 → v2）
        board.put("a", 9);
        assertEquals(1, snapshot.get("a"));
        assertEquals(2, board.versionOf("a"));
    }

    @Test
    void 非法键拒绝() {
        Blackboard board = new Blackboard();
        assertThrows(IllegalArgumentException.class, () -> board.put("", 1));
        assertThrows(IllegalArgumentException.class, () -> board.put(null, 1));
        assertThrows(IllegalArgumentException.class, () -> board.putIfVersion(" ", 1, 0));
        assertTrue(board.keys().isEmpty());
    }
}
