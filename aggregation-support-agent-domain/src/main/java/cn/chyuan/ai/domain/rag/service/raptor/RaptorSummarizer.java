package cn.chyuan.ai.domain.rag.service.raptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * RAPTOR 层级摘要纯函数（工单 0228 AE1，借鉴 RAGFlow/LlamaIndex RAPTOR）—
 * 文本分层聚类：按块序每 clusterSize 个聚一组，每组拼接送摘要器（函数式注入：
 * 规则截取兜底 / LLM 挂点）生成上层节点，递归至单节点；输出层级树
 * （层号 + 父引用 + 原块引用），摘要节点随 chunk 入向量索引由调用方挂接。
 *
 * @author chyuan
 */
public final class RaptorSummarizer {

    /** 树节点：layer 0=原始块；父引用 parentKey；摘要文本 */
    public record RaptorNode(String nodeKey, int layer, String text, String parentKey,
            List<String> childKeys) {
    }

    /** 层级树 */
    public record RaptorTree(String docId, Map<String, RaptorNode> nodes, int maxLayer) {
    }

    private final int clusterSize;
    private final UnaryOperator<String> summarizer;

    public RaptorSummarizer(int clusterSize, UnaryOperator<String> summarizer) {
        this.clusterSize = Math.max(2, clusterSize);
        this.summarizer = summarizer == null ? RaptorSummarizer::truncateSummary : summarizer;
    }

    /** 默认：4 块一组 + 规则截取兜底摘要器 */
    public RaptorSummarizer() {
        this(4, null);
    }

    /** 构树：texts 为原始块（保序，layer 0） */
    public RaptorTree build(String docId, List<String> texts) {
        Map<String, RaptorNode> nodes = new LinkedHashMap<>();
        if (texts == null || texts.isEmpty()) {
            return new RaptorTree(docId, nodes, 0);
        }
        List<String> currentKeys = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String key = docId + ".L0." + i;
            nodes.put(key, new RaptorNode(key, 0, texts.get(i), null, List.of()));
            currentKeys.add(key);
        }
        int layer = 0;
        while (currentKeys.size() > 1) {
            List<String> nextKeys = new ArrayList<>();
            for (int g = 0; g * clusterSize < currentKeys.size(); g++) {
                List<String> group = currentKeys.subList(g * clusterSize,
                        Math.min((g + 1) * clusterSize, currentKeys.size()));
                String joined = group.stream().map(k -> nodes.get(k).text())
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                String key = docId + ".L" + (layer + 1) + "." + g;
                String summary = summarizer.apply(joined);
                nodes.put(key, new RaptorNode(key, layer + 1, summary, null,
                        List.copyOf(group)));
                // 父引用回填
                for (String child : group) {
                    RaptorNode childNode = nodes.get(child);
                    nodes.put(child, new RaptorNode(childNode.nodeKey(), childNode.layer(),
                            childNode.text(), key, childNode.childKeys()));
                }
                nextKeys.add(key);
            }
            currentKeys = nextKeys;
            layer++;
        }
        return new RaptorTree(docId, nodes, layer);
    }

    /** 检索树式上卷：命中块 → 沿父链逐层摘要文本（去重保序） */
    public static List<String> rollUp(RaptorTree tree, List<String> hitNodeKeys) {
        List<String> out = new ArrayList<>();
        if (tree == null || hitNodeKeys == null) {
            return out;
        }
        for (String key : hitNodeKeys) {
            RaptorNode node = tree.nodes().get(key);
            while (node != null && node.parentKey() != null) {
                RaptorNode parent = tree.nodes().get(node.parentKey());
                if (parent == null) {
                    break;
                }
                if (!out.contains(parent.text())) {
                    out.add(parent.text());
                }
                node = parent;
            }
        }
        return out;
    }

    /** 规则兜底摘要器：截取前 200 字符 */
    static String truncateSummary(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200);
    }
}
