package cn.chyuan.ai.domain.crew.groupchat.service;

import java.util.List;

/**
 * 发言者选择策略（工单 0316 AN2，autogen speaker selection 思想）。
 * 策略族统一接口：轮转 / 权重 / 自动选人端口，可配置切换。
 */
public interface SpeakerSelector {

    /** 选出下一位发言者（无可选者返回 null） */
    String nextSpeaker(GroupChatSession session);

    /** 策略名（发言留痕 triggerStrategy） */
    String strategyName();

    /** 轮转策略：按注册序轮转 */
    class RoundRobin implements SpeakerSelector {
        @Override
        public String nextSpeaker(GroupChatSession session) {
            List<String> order = new java.util.ArrayList<>(session.getParticipants());
            if (order.isEmpty()) {
                return null;
            }
            java.util.Collections.sort(order);
            return order.get(session.getMessages().size() % order.size());
        }

        @Override
        public String strategyName() {
            return "round-robin";
        }
    }

    /** 权重策略：权重随机（零权不选中），随机源可注入保证确定性 */
    class Weighted implements SpeakerSelector {

        private final java.util.Map<String, Double> weights;
        private final java.util.random.RandomGenerator random;

        public Weighted(java.util.Map<String, Double> weights, java.util.random.RandomGenerator random) {
            if (weights == null || weights.isEmpty()) {
                throw new IllegalArgumentException("权重表不能为空");
            }
            this.weights = java.util.Map.copyOf(weights);
            this.random = random;
        }

        @Override
        public String nextSpeaker(GroupChatSession session) {
            List<String> eligible = new java.util.ArrayList<>(session.getParticipants());
            eligible.retainAll(weights.keySet());
            eligible.removeIf(role -> weights.get(role) == null || weights.get(role) <= 0);
            if (eligible.isEmpty()) {
                return null;
            }
            eligible.sort(java.util.Comparator.naturalOrder());
            double total = eligible.stream().mapToDouble(weights::get).sum();
            double point = random.nextDouble(total);
            for (String role : eligible) {
                point -= weights.get(role);
                if (point < 0) {
                    return role;
                }
            }
            return eligible.get(eligible.size() - 1);
        }

        @Override
        public String strategyName() {
            return "weighted";
        }
    }

    /** 自动选人策略：端口（LLM 选人）+ 轮转兜底（端口异常或返回空） */
    class Auto implements SpeakerSelector {

        /** 自动选人端口 */
        public interface SelectionPort {
            String select(GroupChatSession session);
        }

        private final SelectionPort port;
        private final SpeakerSelector fallback = new RoundRobin();

        public Auto(SelectionPort port) {
            this.port = port;
        }

        @Override
        public String nextSpeaker(GroupChatSession session) {
            try {
                String picked = port.select(session);
                if (picked != null && session.getParticipants().contains(picked)) {
                    return picked;
                }
            } catch (RuntimeException ignored) {
                // 端口异常走轮转兜底
            }
            return fallback.nextSpeaker(session);
        }

        @Override
        public String strategyName() {
            return "auto";
        }
    }
}
