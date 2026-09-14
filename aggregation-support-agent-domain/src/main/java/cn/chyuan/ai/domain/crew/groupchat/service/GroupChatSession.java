package cn.chyuan.ai.domain.crew.groupchat.service;

import cn.chyuan.ai.domain.crew.groupchat.model.GroupChatMessageVO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 群聊状态机（工单 0315 AN1，autogen group chat 思想）。
 * 参与者注册（IDLE 阶段）→ start 进入 RUNNING → 逐轮发言 →
 * 终态转移 CONSENSUS/TERMINATED/EXCEEDED（仅 RUNNING 可出）。
 * 非法转移/非注册成员发言/超轮次推进一律拒绝。domain 纯函数内核。
 */
public class GroupChatSession {

    /** 会话状态 */
    public enum Status { IDLE, RUNNING, CONSENSUS, TERMINATED, EXCEEDED }

    private final int maxRounds;
    private final Set<String> participants = new LinkedHashSet<>();
    private final List<GroupChatMessageVO> messages = new ArrayList<>();
    private final Deque<Status> trail = new ArrayDeque<>();
    private Status status = Status.IDLE;
    private int currentRound = 0;
    private String endReason;

    public GroupChatSession(int maxRounds) {
        if (maxRounds <= 0) {
            throw new IllegalArgumentException("轮次上限必须为正数");
        }
        this.maxRounds = maxRounds;
    }

    /** 注册参与者（仅 IDLE；空名/重复拒绝） */
    public GroupChatSession register(String role) {
        if (status != Status.IDLE) {
            throw new IllegalStateException("仅 IDLE 阶段可注册参与者");
        }
        if (role == null || role.trim().isEmpty()) {
            throw new IllegalArgumentException("角色名不能为空");
        }
        if (!participants.add(role.trim())) {
            throw new IllegalArgumentException("角色重复注册: " + role);
        }
        return this;
    }

    /** 启动群聊：IDLE → RUNNING（至少两名参与者），轮次从 1 起 */
    public void start() {
        transition(Status.RUNNING);
        if (participants.size() < 2) {
            throw new IllegalStateException("群聊至少需要两名参与者");
        }
        currentRound = 1;
    }

    /** 记录发言（仅 RUNNING；发言者必须已注册；轮次为当前轮） */
    public void recordSpeech(String speaker, String content, String triggerStrategy) {
        requireRunning();
        if (!participants.contains(speaker)) {
            throw new IllegalArgumentException("未注册成员不能发言: " + speaker);
        }
        messages.add(GroupChatMessageVO.builder()
                .round(currentRound)
                .speaker(speaker)
                .content(content == null ? "" : content)
                .triggerStrategy(triggerStrategy == null ? "manual" : triggerStrategy)
                .tokenEstimate(content == null ? 0 : content.length())
                .build());
    }

    /** 推进到下一轮（当前轮内无发言拒绝；超出上限拒绝需转 EXCEEDED） */
    public void nextRound() {
        requireRunning();
        int roundSpeeches = (int) messages.stream().filter(m -> m.getRound() == currentRound).count();
        if (roundSpeeches == 0) {
            throw new IllegalStateException("当前轮尚无发言，不能推进");
        }
        if (currentRound >= maxRounds) {
            throw new IllegalStateException("已达轮次上限，应转 EXCEEDED");
        }
        currentRound++;
    }

    /** 终态转移（仅 RUNNING 可出）：CONSENSUS / TERMINATED / EXCEEDED */
    public void finish(Status terminal, String reason) {
        if (terminal != Status.CONSENSUS && terminal != Status.TERMINATED && terminal != Status.EXCEEDED) {
            throw new IllegalArgumentException("非法终态: " + terminal);
        }
        transition(terminal);
        this.endReason = reason;
    }

    /** 转移合法性：IDLE→RUNNING；RUNNING→三终态；终态无出边 */
    private void transition(Status target) {
        boolean legal = (status == Status.IDLE && target == Status.RUNNING)
                || (status == Status.RUNNING && target != Status.IDLE && target != Status.RUNNING);
        if (!legal) {
            throw new IllegalStateException("非法状态转移: " + status + " → " + target);
        }
        trail.push(status);
        status = target;
    }

    private void requireRunning() {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("仅 RUNNING 状态可执行该操作，当前: " + status);
        }
    }

    /** 发言历史（只读副本） */
    public List<GroupChatMessageVO> getMessages() {
        return List.copyOf(messages);
    }

    /** 参与者（注册序只读） */
    public Set<String> getParticipants() {
        return Set.copyOf(participants);
    }

    public Status getStatus() {
        return status;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public String getEndReason() {
        return endReason;
    }

    /** 状态转移轨迹（用于审计） */
    public List<Status> statusTrail() {
        List<Status> out = new ArrayList<>(trail);
        java.util.Collections.reverse(out);
        return out;
    }
}
