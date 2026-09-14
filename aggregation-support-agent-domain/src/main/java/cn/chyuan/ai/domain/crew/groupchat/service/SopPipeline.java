package cn.chyuan.ai.domain.crew.groupchat.service;

import cn.chyuan.ai.domain.crew.groupchat.model.SopRunResultVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SOP 流水线（工单 0320 AN6，MetaGPT SOP 思想）。
 * SOP 定义（环节序列：角色+产物必填字段）→ 逐环执行（产物端口，上游产物全集
 * 作为上下文）→ 产物校验（必填字段非空），不合规产物拒绝流入下一环并留拒绝
 * 记录、链路即停。全链通过率统计。domain 纯函数编排。
 */
public class SopPipeline {

    /** 单环节定义：角色 + 产物必填字段 */
    public record SopStep(String role, List<String> requiredFields) {
    }

    /** 产物生成端口：环节 + 上游产物全集 → 本环产物（字段表）；异常=产空产物走校验拒绝 */
    public interface ArtifactPort {
        Map<String, Object> produce(SopStep step, List<Map<String, Object>> upstream);
    }

    private final List<SopStep> steps;
    private final ArtifactPort port;

    public SopPipeline(List<SopStep> steps, ArtifactPort port) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("SOP 环节不能为空");
        }
        steps.forEach(step -> {
            if (step.role() == null || step.role().isBlank()) {
                throw new IllegalArgumentException("环节角色不能为空");
            }
        });
        this.steps = List.copyOf(steps);
        this.port = port;
    }

    public SopRunResultVO run(Map<String, Object> initialInput) {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        List<Map<String, Object>> upstream = new ArrayList<>();
        if (initialInput != null && !initialInput.isEmpty()) {
            upstream.add(initialInput);
        }
        int executed = 0;
        for (SopStep step : steps) {
            executed++;
            Map<String, Object> artifact = safeProduce(step, List.copyOf(upstream));
            List<String> missing = validate(step, artifact);
            roles.add(step.role());
            if (!missing.isEmpty()) {
                rejections.add("第" + executed + "环[" + step.role() + "]缺字段:" + String.join(",", missing));
                // 拒绝：不流入下一环，链路即停
                return build(false, executed, artifacts, roles, rejections);
            }
            artifacts.add(artifact);
            upstream.add(artifact);
        }
        return build(true, executed, artifacts, roles, rejections);
    }

    /** 产物校验：必填字段存在且非空 */
    static List<String> validate(SopStep step, Map<String, Object> artifact) {
        List<String> missing = new ArrayList<>();
        if (artifact == null) {
            return List.copyOf(step.requiredFields());
        }
        for (String field : step.requiredFields()) {
            Object value = artifact.get(field);
            if (value == null || (value instanceof String text && text.isBlank())) {
                missing.add(field);
            }
        }
        return missing;
    }

    private Map<String, Object> safeProduce(SopStep step, List<Map<String, Object>> upstream) {
        try {
            Map<String, Object> artifact = port.produce(step, upstream);
            return artifact == null ? new LinkedHashMap<>() : artifact;
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    private SopRunResultVO build(boolean completed, int executed,
                                 List<Map<String, Object>> artifacts, List<String> roles,
                                 List<String> rejections) {
        double passRate = (double) (executed - rejections.size()) / steps.size();
        return SopRunResultVO.builder()
                .completed(completed)
                .executedSteps(executed)
                .artifacts(artifacts)
                .roles(roles)
                .rejections(rejections)
                .passRate(passRate)
                .build();
    }
}
