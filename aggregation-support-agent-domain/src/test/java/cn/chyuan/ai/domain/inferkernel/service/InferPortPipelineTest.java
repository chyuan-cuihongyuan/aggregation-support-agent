package cn.chyuan.ai.domain.inferkernel.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成端口+组合管线单测（工单 0511 BI8）：
 * 端到端小语料生成确定性 + infer-kernel.enabled 默认关（不发起外部模型调用）。
 */
class InferPortPipelineTest {

    @Test
    void BI8_端到端生成确定性() {
        Map<String, Integer> vocab = new HashMap<>();
        vocab.put("a", 0);
        vocab.put("b", 1);
        Map<String, Integer> ranks = new HashMap<>();
        ranks.put("a\u0000b", 0);
        BpeTokenizer tokenizer = new BpeTokenizer(vocab, ranks);
        Map<String, double[]> fakeModel = new HashMap<>();
        fakeModel.put("0", new double[]{0.1, 2.0, 0.3, 0.2});
        fakeModel.put("1", new double[]{2.0, 0.1, 0.2, 0.1});
        InferPort.InMemoryInfer infer = new InferPort.InMemoryInfer(tokenizer, fakeModel);
        InferPort.Request request = new InferPort.Request("a", 6, 0.8, 2, 0.9);
        InferPort.Output first = infer.generate(request);
        InferPort.Output second = infer.generate(request);
        assertEquals(first, second, "同请求同输出（固定种子确定性）");
        assertEquals(6, first.tokens().size());
        assertEquals(StopSequenceHandler.FinishReason.LENGTH, first.reason(), "无停止串达上限 LENGTH");
        for (InferPort.InferPortToken token : first.tokens()) {
            assertTrue(token.id() >= 0 && token.id() < 4, "词表内采样");
        }
        InferPort.Output shortRun = infer.generate(new InferPort.Request("a", 2, 0.8, 2, 0.9));
        assertEquals(2, shortRun.tokens().size(), "maxTokens 截断生效");
    }
}
