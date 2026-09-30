package cn.chyuan.ai.domain.durablekernel.service;

import java.util.List;

/**
 * 确定性重放（工单 1067 EP2，temporal 思想）。
 * 重放历史重建终态：首事件必须 WORKFLOW_STARTED，工作流代码逐步重执行并与记录事件
 * （类型 + 载荷）比对；不一致抛 NonDeterminism；外部事件（SIGNAL_RECEIVED/TIMER_FIRED）
 * 非工作流确定性产物，重放时按序跳过；重放幂等且与在线结果一致。
 */
public final class Replayer {

    /** 非确定性：重放生成的事件与历史记录不一致 */
    public static final class NonDeterminismException extends RuntimeException {
        public NonDeterminismException(String message) {
            super(message);
        }
    }

    /** 工作流代码：确定性步进函数；返回 DONE:xxx 表示终态输出 */
    public interface WorkflowCode {
        String act(String input);
    }

    private Replayer() {
    }

    /** 在线执行：驱动代码向历史追加事件至终态或步数上限；返回终态输出或 null（执行中） */
    public static String runOnline(EventHistory history, WorkflowCode code, int maxSteps) {
        String current = history.events().get(0).payload();
        for (int step = 0; step < maxSteps; step++) {
            String result = code.act(current);
            if (result.startsWith("DONE:")) {
                history.append(EventHistory.WORKFLOW_COMPLETED, result.substring("DONE:".length()));
                return result.substring("DONE:".length());
            }
            history.append(EventHistory.ACTIVITY_COMPLETED, result);
            current = result;
        }
        return null;
    }

    /** 重放：校验历史与代码重执行一致；返回终态输出（未终态返回 null 执行中） */
    public static String replay(List<EventHistory.Event> events, WorkflowCode code, int maxSteps) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("空历史拒绝重放");
        }
        EventHistory.Event first = events.get(0);
        if (!EventHistory.WORKFLOW_STARTED.equals(first.type())) {
            throw new NonDeterminismException("首事件必须 WORKFLOW_STARTED: " + first.type());
        }
        String current = first.payload();
        int steps = 0;
        boolean terminated = false;
        String output = null;
        for (int index = 1; index < events.size(); index++) {
            EventHistory.Event recorded = events.get(index);
            if (EventHistory.SIGNAL_RECEIVED.equals(recorded.type())
                    || EventHistory.TIMER_FIRED.equals(recorded.type())) {
                continue;
            }
            if (terminated) {
                throw new NonDeterminismException("终态后仍有事件: id=" + recorded.id());
            }
            if (steps >= maxSteps) {
                throw new IllegalStateException("重放步数超限: " + maxSteps);
            }
            steps++;
            String result = code.act(current);
            if (result.startsWith("DONE:")) {
                String expected = result.substring("DONE:".length());
                if (!EventHistory.WORKFLOW_COMPLETED.equals(recorded.type())
                        || !expected.equals(recorded.payload())) {
                    throw new NonDeterminismException(
                            "终态不一致: 记录 " + recorded.type() + "/" + recorded.payload()
                                    + " 期望 WORKFLOW_COMPLETED/" + expected);
                }
                terminated = true;
                output = expected;
                continue;
            }
            if (!EventHistory.ACTIVITY_COMPLETED.equals(recorded.type())
                    || !result.equals(recorded.payload())) {
                throw new NonDeterminismException(
                        "第 " + steps + " 步不一致: 记录 " + recorded.type() + "/" + recorded.payload()
                                + " 期望 ACTIVITY_COMPLETED/" + result);
            }
            current = result;
        }
        return output;
    }
}
