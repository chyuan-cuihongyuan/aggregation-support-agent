package cn.chyuan.ai.domain.crew.groupchat.service;

import cn.chyuan.ai.domain.crew.groupchat.model.SopRunResultVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SOP 流水线单测（工单 0320 AN6）：产物校验/拒绝中止/上游传递/边界。
 */
class SopPipelineTest {

    private final SopPipeline.SopStep pm = new SopPipeline.SopStep("产品经理", List.of("需求文档"));
    private final SopPipeline.SopStep dev = new SopPipeline.SopStep("工程师", List.of("设计文档", "代码"));

    @Test
    void 全链通过与上游传递() {
        List<List<Map<String, Object>>> seenUpstream = new java.util.ArrayList<>();
        SopPipeline pipeline = new SopPipeline(List.of(pm, dev),
                (step, upstream) -> {
                    seenUpstream.add(List.copyOf(upstream));
                    if ("产品经理".equals(step.role())) {
                        return Map.of("需求文档", "PRD v1");
                    }
                    return Map.of("设计文档", "设计稿", "代码", "main.java");
                });
        SopRunResultVO result = pipeline.run(Map.of("目标", "登录功能"));
        assertTrue(result.isCompleted());
        assertEquals(2, result.getExecutedSteps());
        assertEquals(1.0, result.getPassRate());
        // 第一环携带初始输入，第二环携带初始输入+第一环产物
        assertEquals(1, seenUpstream.get(0).size());
        assertEquals(2, seenUpstream.get(1).size());
        assertEquals("PRD v1", seenUpstream.get(1).get(1).get("需求文档"));
    }

    @Test
    void 缺字段拒绝并链路中止() {
        SopPipeline pipeline = new SopPipeline(List.of(pm, dev),
                (step, upstream) -> "产品经理".equals(step.role())
                        ? Map.of("需求文档", "PRD v1")
                        : Map.of("设计文档", "设计稿"));
        SopRunResultVO result = pipeline.run(null);
        assertFalse(result.isCompleted());
        // 工程师产物缺「代码」→ 拒绝，链路停在第二环
        assertEquals(2, result.getExecutedSteps());
        assertEquals(1, result.getArtifacts().size(), "被拒产物不流入产物集");
        assertEquals(1, result.getRejections().size());
        assertTrue(result.getRejections().get(0).contains("代码"));
        assertEquals(0.5, result.getPassRate());
    }

    @Test
    void 端口异常按空产物拒绝() {
        SopPipeline pipeline = new SopPipeline(List.of(pm),
                (step, upstream) -> {
                    throw new IllegalStateException("生成模型挂");
                });
        SopRunResultVO result = pipeline.run(Map.of());
        assertFalse(result.isCompleted());
        assertEquals(1, result.getRejections().size());
        assertTrue(result.getRejections().get(0).contains("需求文档"));
        // 空白字符串字段视同缺失
        assertTrue(SopPipeline.validate(pm, Map.of("需求文档", "  ")).contains("需求文档"));
    }

    @Test
    void 非法配置拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new SopPipeline(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new SopPipeline(
                List.of(new SopPipeline.SopStep(" ", List.of())), null));
    }
}
