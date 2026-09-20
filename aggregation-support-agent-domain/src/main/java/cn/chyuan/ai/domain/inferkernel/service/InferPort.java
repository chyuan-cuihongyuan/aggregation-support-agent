package cn.chyuan.ai.domain.inferkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 生成端口+组合管线（工单 0511 BI8，llama.cpp sampling pipeline 思想）。
 * InferPort（小词表 + 固定种子采样循环：BPE 分词 → logits → 惩罚 → 温度 →
 * top-k/top-p → 采样 → 停止判定）+ 端到端小语料生成 + 确定性断言/
 * infer-kernel.enabled 默认关（不发起外部模型调用）。
 */
public interface InferPort {

    /** 生成请求 */
    record Request(String prompt, int maxTokens, double temperature, int topK, double topP) {
    }

    /** 生成结果：token 序列 + finish_reason（stop/length） */
    record Output(List<InferPortToken> tokens, StopSequenceHandler.FinishReason reason) {
    }

    /** 输出 token */
    record InferPortToken(String text, int id) {
    }

    /** 生成（确定性：同请求同输出） */
    Output generate(Request request);

    /** 内存假实现：字符级小词表 + 简易“模型”（bigram 频次查表 + 固定随机端口） */
    class InMemoryInfer implements InferPort {

        private final BpeTokenizer tokenizer;
        private final Map<String, double[]> fakeModel;

        public InMemoryInfer(BpeTokenizer tokenizer, Map<String, double[]> fakeModel) {
            this.tokenizer = tokenizer;
            this.fakeModel = fakeModel;
        }

        @Override
        public Output generate(Request request) {
            List<BpeTokenizer.Token> promptTokens = tokenizer.encode(request.prompt());
            List<Integer> generated = new ArrayList<>();
            StopSequenceHandler stop = new StopSequenceHandler(List.of(), request.maxTokens());
            RepetitionPenalty penalty = new RepetitionPenalty(0.1, 0.1, 1.1, 8);
            Sampler sampler = new Sampler(new java.util.Random(42)::nextDouble);
            int lastId = promptTokens.isEmpty() ? 0
                    : promptTokens.get(promptTokens.size() - 1).id();
            while (generated.size() < request.maxTokens) {
                double[] logits = fakeModel.getOrDefault(String.valueOf(lastId),
                        new double[]{0.1, 0.1, 0.1, 0.1});
                logits = penalty.apply(logits, generated);
                logits = LogitsOps.applyTemperature(logits, request.temperature);
                List<Sampler.Candidate> candidates = sampler.topK(logits, request.topK);
                candidates = sampler.topP(candidates, request.topP);
                int sampled = sampler.sample(candidates);
                generated.add(sampled);
                lastId = sampled;
            }
            StopSequenceHandler.Generation generation = stop.feed(new java.util.function.IntSupplier() {
                private int index;

                @Override
                public int getAsInt() {
                    return generated.get(index++);
                }
            });
            List<InferPortToken> tokens = generated.stream()
                    .map(id -> new InferPortToken(String.valueOf(id), id)).toList();
            return new Output(tokens, generation.reason());
        }
    }
}
