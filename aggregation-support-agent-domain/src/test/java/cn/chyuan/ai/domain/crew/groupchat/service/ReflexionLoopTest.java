package cn.chyuan.ai.domain.crew.groupchat.service;

import cn.chyuan.ai.domain.crew.groupchat.model.ReflexionResultVO;
import cn.chyuan.ai.domain.crew.service.Blackboard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反思循环单测（工单 0318 AN4）：早停/上限出口/经验入黑板/端口兜底。
 */
class ReflexionLoopTest {

    @Test
    void 一次达标早停() {
        ReflexionLoop loop = new ReflexionLoop(3, 60);
        ReflexionResultVO result = loop.run("任务甲",
                (task, lessons) -> "第一次输出",
                (task, output) -> 80,
                (task, output, score) -> "不应反思",
                null);
        assertTrue(result.isSuccess());
        assertEquals(1, result.getIterations());
        assertEquals(80, result.getScore());
        assertTrue(result.getLessons().isEmpty());
        assertEquals("iter1:score=80", result.getTrace().get(0));
    }

    @Test
    void 经验入黑板且重试携带经验() {
        Blackboard blackboard = new Blackboard();
        ReflexionLoop loop = new ReflexionLoop(3, 80);
        List<List<String>> seenLessons = new java.util.ArrayList<>();
        ReflexionResultVO result = loop.run("任务乙",
                (task, lessons) -> {
                    seenLessons.add(List.copyOf(lessons));
                    return lessons.isEmpty() ? "粗糙初稿" : "带经验改进稿";
                },
                (task, output) -> output.contains("改进") ? 90 : 40,
                (task, output, score) -> "上稿太粗糙，需补充细节",
                blackboard);
        assertTrue(result.isSuccess());
        assertEquals(2, result.getIterations());
        // 第一轮无经验，第二轮带上了第一轮反思
        assertTrue(seenLessons.get(0).isEmpty());
        assertEquals("上稿太粗糙，需补充细节", seenLessons.get(1).get(0));
        // 经验入黑板可查
        assertEquals("上稿太粗糙，需补充细节", blackboard.get(ReflexionLoop.LESSON_KEY_PREFIX + 1));
    }

    @Test
    void 迭代上限出口与端口异常兜底() {
        Blackboard blackboard = new Blackboard();
        ReflexionLoop loop = new ReflexionLoop(2, 70);
        ReflexionResultVO result = loop.run("任务丙",
                (task, lessons) -> "总是失败输出",
                (task, output) -> {
                    throw new IllegalStateException("评分模型挂");
                },
                (task, output, score) -> {
                    throw new IllegalStateException("反思模型挂");
                },
                blackboard);
        assertFalse(result.isSuccess());
        assertEquals(2, result.getIterations());
        // 评分异常按 0 分、反思异常走模板兜底
        assertEquals(2, result.getLessons().size());
        assertTrue(result.getLessons().get(0).contains("未达标"));
        assertTrue(result.getLessons().get(0).contains("任务丙"));
        assertEquals(0, result.getScore());
        assertEquals("总是失败输出", result.getOutput());
    }

    @Test
    void 反思返回空走模板与非法配置() {
        ReflexionLoop loop = new ReflexionLoop(1, 100);
        ReflexionResultVO result = loop.run("任务丁",
                (task, lessons) -> "输出",
                (task, output) -> 10,
                (task, output, score) -> "   ",
                null);
        assertTrue(result.getLessons().get(0).contains("第1次执行未达标"));
        assertThrows(IllegalArgumentException.class, () -> new ReflexionLoop(0, 50));
        assertThrows(IllegalArgumentException.class, () -> new ReflexionLoop(1, 101));
    }
}
