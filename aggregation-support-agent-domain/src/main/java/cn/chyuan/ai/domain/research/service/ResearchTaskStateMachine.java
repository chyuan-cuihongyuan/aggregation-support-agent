package cn.chyuan.ai.domain.research.service;

import cn.chyuan.ai.domain.research.model.valobj.OutlineVO;
import cn.chyuan.ai.domain.research.model.valobj.ResearchReportVO;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研究任务状态机（工单 0352 AR6）。
 * CREATED→PLANNING→SEARCHING→DRAFTING→CITING→DONE|FAILED|CANCELLED 转移
 * 合法性矩阵 + 每阶段检查点（大纲/证据/草稿/引用快照）→断点续跑；
 * 运行态任意可取消；非法转移拒绝并留痕。domain 纯函数。
 */
public class ResearchTaskStateMachine {

    /** 状态常量 */
    public static final String CREATED = "CREATED";
    public static final String PLANNING = "PLANNING";
    public static final String SEARCHING = "SEARCHING";
    public static final String DRAFTING = "DRAFTING";
    public static final String CITING = "CITING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    /** 合法后继 */
    private static final Map<String, List<String>> NEXT = Map.of(
            CREATED, List.of(PLANNING, CANCELLED),
            PLANNING, List.of(SEARCHING, FAILED, CANCELLED),
            SEARCHING, List.of(DRAFTING, FAILED, CANCELLED),
            DRAFTING, List.of(CITING, FAILED, CANCELLED),
            CITING, List.of(DONE, FAILED, CANCELLED),
            DONE, List.of(), FAILED, List.of(), CANCELLED, List.of());

    private String status = CREATED;
    private final Deque<String> trail = new ArrayDeque<>();
    private final Map<String, Object> checkpoints = new LinkedHashMap<>();

    public synchronized String getStatus() {
        return status;
    }

    /** 转移（合法性校验，非法拒绝留痕） */
    public synchronized void transition(String target) {
        if (!NEXT.get(status).contains(target)) {
            throw new IllegalStateException("非法状态转移: " + status + " → " + target);
        }
        trail.add(status);
        status = target;
    }

    /** 保存检查点（阶段 → 快照对象） */
    public synchronized void checkpoint(String stage, Object snapshot) {
        checkpoints.put(stage, snapshot);
    }

    /** 恢复：取最近检查点阶段（无检查点从 PLANNING 起） */
    public synchronized String resumeFrom() {
        String[] order = {PLANNING, SEARCHING, DRAFTING, CITING};
        String latest = null;
        for (String stage : order) {
            if (checkpoints.containsKey(stage)) {
                latest = stage;
            }
        }
        if (latest == null) {
            return PLANNING;
        }
        // 检查点保存时该阶段已完成，恢复应进入其后继执行阶段
        return switch (latest) {
            case PLANNING -> SEARCHING;
            case SEARCHING -> DRAFTING;
            case DRAFTING -> CITING;
            default -> DONE;
        };
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> T checkpointOf(String stage) {
        return (T) checkpoints.get(stage);
    }

    /** 转移轨迹（来源状态序列） */
    public synchronized List<String> trail() {
        return List.copyOf(trail);
    }
}
