package cn.chyuan.ai.domain.browser.service;

import cn.chyuan.ai.domain.browser.model.valobj.ElementVO;
import cn.chyuan.ai.domain.browser.model.valobj.PageSnapshotVO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快照摘要器（工单 0340 AQ2）。
 * 快照 → 紧凑摘要（按 role 分组计数 + 可编辑元素清单 + 截断上限），
 * 序列化/反序列化确定性（字段稳定序）。供 LLM 感知上下文。domain 纯函数。
 */
public class SnapshotSummarizer {

    private final int maxElements;

    public SnapshotSummarizer(int maxElements) {
        if (maxElements <= 0) {
            throw new IllegalArgumentException("摘要元素上限必须为正数");
        }
        this.maxElements = maxElements;
    }

    /** 摘要：分组计数行 + 可编辑清单（截断后标注） */
    public String summarize(PageSnapshotVO snapshot) {
        if (snapshot == null || snapshot.getElements() == null || snapshot.getElements().isEmpty()) {
            return "页面 " + (snapshot == null ? "-" : snapshot.getUrl()) + "：无可交互元素。";
        }
        Map<String, Integer> byRole = new LinkedHashMap<>();
        List<String> editable = new java.util.ArrayList<>();
        for (ElementVO element : snapshot.getElements()) {
            byRole.merge(element.getRole(), 1, Integer::sum);
            if (element.isEditable()) {
                if (editable.size() < maxElements) {
                    editable.add(element.getRole() + "#" + element.getName());
                }
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("页面 ").append(snapshot.getUrl())
                .append("（").append(snapshot.getTitle()).append("）共 ")
                .append(snapshot.getElements().size()).append(" 个可交互元素：");
        byRole.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> out.append('\n').append("- ").append(e.getKey()).append(" × ").append(e.getValue()));
        if (!editable.isEmpty()) {
            out.append('\n').append("可编辑：").append(String.join("、", editable));
            long editableTotal = snapshot.getElements().stream().filter(ElementVO::isEditable).count();
            if (editable.size() < editableTotal) {
                out.append("…（共 ").append(editableTotal).append(" 个，已截断）");
            }
        }
        return out.toString();
    }

    /** 序列化（字段稳定序，重放一致） */
    public String serialize(PageSnapshotVO snapshot) {
        StringBuilder out = new StringBuilder();
        out.append("url=").append(snapshot.getUrl())
                .append("|title=").append(snapshot.getTitle())
                .append("|at=").append(snapshot.getCapturedAtMs())
                .append("|elements=").append(snapshot.getElements().size());
        for (ElementVO element : snapshot.getElements()) {
            out.append('\n').append(element.getElementId()).append(',')
                    .append(element.getRole()).append(',')
                    .append(element.getName()).append(',')
                    .append(element.getSelector()).append(',')
                    .append(element.isEnabled() ? 1 : 0)
                    .append(element.isEditable() ? 1 : 0);
        }
        return out.toString();
    }

    /** 反序列化（与 serialize 往返一致） */
    public PageSnapshotVO deserialize(String text) {
        if (text == null || !text.startsWith("url=")) {
            throw new IllegalArgumentException("快照文本非法");
        }
        String[] lines = text.split("\n");
        String[] head = lines[0].split("\\|");
        PageSnapshotVO snapshot = PageSnapshotVO.builder()
                .url(head[0].substring(4))
                .title(head[1].substring(6))
                .capturedAtMs(Long.parseLong(head[2].substring(3)))
                .elements(new java.util.ArrayList<>())
                .build();
        for (int i = 1; i < lines.length; i++) {
            String[] parts = lines[i].split(",", -1);
            String flags = parts[4];
            snapshot.getElements().add(ElementVO.builder()
                    .elementId(parts[0])
                    .role(parts[1])
                    .name(parts[2])
                    .selector(parts[3])
                    .enabled(flags.charAt(0) == '1')
                    .editable(flags.length() > 1 && flags.charAt(1) == '1')
                    .build());
        }
        return snapshot;
    }
}
