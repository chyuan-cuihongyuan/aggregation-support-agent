package cn.chyuan.ai.domain.crew.groupchat.service;

import cn.chyuan.ai.domain.crew.groupchat.model.GroupChatMessageVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 群聊终止条件（工单 0317 AN3）。
 * 四类条件任一命中即终止并全量留痕原因：终止令牌（整词检测）/
 * 轮次上限/共识判定（全部存活成员最近发言含同意令牌）/外部中断。
 * domain 纯函数。
 */
public class TerminationPolicy {

    /** 终止原因码 */
    public enum Reason { TOKEN, ROUND_LIMIT, CONSENSUS, EXTERNAL }

    private final String terminationToken;
    private final String consentToken;
    private boolean externalInterrupt;

    public TerminationPolicy(String terminationToken, String consentToken) {
        this.terminationToken = terminationToken == null ? "[DONE]" : terminationToken;
        this.consentToken = consentToken == null || consentToken.isBlank() ? "[同意]" : consentToken;
    }

    /** 外部中断请求（编排侧设置） */
    public void requestExternalInterrupt() {
        this.externalInterrupt = true;
    }

    /** 评估：任一命中即终止，原因全量留痕 */
    public Result evaluate(GroupChatSession session) {
        List<Reason> reasons = new ArrayList<>();
        if (session.getCurrentRound() >= session.getMaxRounds()
                && hasSpeechInRound(session, session.getCurrentRound())) {
            reasons.add(Reason.ROUND_LIMIT);
        }
        if (containsToken(session, terminationToken, true)) {
            reasons.add(Reason.TOKEN);
        }
        if (allAgree(session)) {
            reasons.add(Reason.CONSENSUS);
        }
        if (externalInterrupt) {
            reasons.add(Reason.EXTERNAL);
        }
        return new Result(!reasons.isEmpty(), reasons);
    }

    /** 终止令牌整词检测：任一发言内容含令牌（按空白分词精确匹配，非子串误伤） */
    private boolean containsToken(GroupChatSession session, String token, boolean wholeWord) {
        for (GroupChatMessageVO message : session.getMessages()) {
            if (wholeWord ? wholeWordContains(message.getContent(), token)
                    : message.getContent().contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 整词匹配：内容按非中英文数字切分后精确比对 */
    static boolean wholeWordContains(String content, String token) {
        if (content == null || token == null) {
            return false;
        }
        for (String part : content.split("[^\\p{IsHan}A-Za-z0-9_\\[\\]]+")) {
            if (part.equals(token)) {
                return true;
            }
        }
        return false;
    }

    /** 共识：全部参与者的最近一条发言都含同意令牌（尚无发言者不满足） */
    private boolean allAgree(GroupChatSession session) {
        for (String participant : session.getParticipants()) {
            GroupChatMessageVO latest = latestOf(session, participant);
            if (latest == null || !wholeWordContains(latest.getContent(), consentToken)) {
                return false;
            }
        }
        return !session.getParticipants().isEmpty();
    }

    private GroupChatMessageVO latestOf(GroupChatSession session, String speaker) {
        GroupChatMessageVO latest = null;
        for (GroupChatMessageVO message : session.getMessages()) {
            if (message.getSpeaker().equals(speaker)) {
                latest = message;
            }
        }
        return latest;
    }

    private boolean hasSpeechInRound(GroupChatSession session, int round) {
        return session.getMessages().stream().anyMatch(m -> m.getRound() == round);
    }

    /** 评估结果值对象 */
    public record Result(boolean shouldTerminate, List<Reason> reasons) {
    }
}
