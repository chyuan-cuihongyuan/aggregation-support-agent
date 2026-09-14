package cn.chyuan.ai.domain.crew.groupchat.service;

import cn.chyuan.ai.domain.crew.groupchat.model.ReflexionResultVO;
import cn.chyuan.ai.domain.crew.service.Blackboard;

import java.util.ArrayList;
import java.util.List;

/**
 * 反思循环内核（工单 0318 AN4，Reflexion 思想）。
 * 执行（任务端口，上下文携带历史经验）→自评（评分端口，异常按失败兜底）→
 * 达标早停；未达标则反思（反思端口+模板兜底）经验写入共享黑板后重试，
 * 迭代上限出口。复用五期 Blackboard（AC5）。domain 纯函数编排。
 */
public class ReflexionLoop {

    /** 任务执行端口：输入任务与历史经验，产出输出 */
    public interface TaskPort {
        String execute(String task, List<String> lessons);
    }

    /** 自评端口：0-100 分（异常按 0 分失败兜底） */
    public interface SelfEvaluatePort {
        int score(String task, String output);
    }

    /** 反思端口：失败原因归纳（异常走模板兜底） */
    public interface ReflectionPort {
        String reflect(String task, String output, int score);
    }

    /** 黑板经验键前缀 */
    public static final String LESSON_KEY_PREFIX = "reflexion:lesson:";

    private final int maxIterations;
    private final int scoreThreshold;

    public ReflexionLoop(int maxIterations, int scoreThreshold) {
        if (maxIterations <= 0 || scoreThreshold < 0 || scoreThreshold > 100) {
            throw new IllegalArgumentException("迭代上限须为正数且阈值在 [0,100]");
        }
        this.maxIterations = maxIterations;
        this.scoreThreshold = scoreThreshold;
    }

    /** 运行反思循环（黑板可空：不留痕黑板仅本地轨迹） */
    public ReflexionResultVO run(String task, TaskPort taskPort,
                                 SelfEvaluatePort evaluatePort,
                                 ReflectionPort reflectionPort,
                                 Blackboard blackboard) {
        List<String> lessons = new ArrayList<>();
        List<String> trace = new ArrayList<>();
        String output = null;
        int score = 0;
        int iterations = 0;
        for (int i = 1; i <= maxIterations; i++) {
            iterations = i;
            output = taskPort.execute(task, List.copyOf(lessons));
            score = safeScore(evaluatePort, task, output);
            trace.add("iter" + i + ":score=" + score);
            if (score >= scoreThreshold) {
                return ReflexionResultVO.builder()
                        .success(true)
                        .output(output)
                        .score(score)
                        .iterations(i)
                        .lessons(List.copyOf(lessons))
                        .trace(trace)
                        .build();
            }
            String lesson = safeReflect(reflectionPort, task, output, score, i);
            lessons.add(lesson);
            if (blackboard != null) {
                blackboard.put(LESSON_KEY_PREFIX + i, lesson);
            }
        }
        return ReflexionResultVO.builder()
                .success(false)
                .output(output)
                .score(score)
                .iterations(iterations)
                .lessons(List.copyOf(lessons))
                .trace(trace)
                .build();
    }

    private int safeScore(SelfEvaluatePort port, String task, String output) {
        try {
            return Math.max(0, Math.min(100, port.score(task, output)));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private String safeReflect(ReflectionPort port, String task, String output, int score, int iteration) {
        try {
            String lesson = port.reflect(task, output, score);
            if (lesson != null && !lesson.isBlank()) {
                return lesson;
            }
        } catch (RuntimeException ignored) {
            // 走模板兜底
        }
        return "第" + iteration + "次执行未达标（得分 " + score + "），需针对任务「" + task + "」改进输出。";
    }
}
