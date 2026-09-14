package cn.chyuan.ai.domain.knowledgegraph.graphrag.service;

import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphEdgeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphIndexVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.GraphNodeVO;
import cn.chyuan.ai.domain.knowledgegraph.graphrag.model.valobj.TextUnitVO;
import cn.chyuan.ai.domain.knowledgegraph.model.valobj.EntityExtractionResultVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 图谱索引构建器（工单 0306 AM1）。
 * 文档按段落切块 text_units（块大小/重叠可配置）→实体与关系映射到来源块
 * （名称包含命中，大小写折叠）→规范化序列化 SHA-256 索引哈希，同输入重放一致。
 * graphrag 索引 + llama_index text_units 思想的确定性简化，domain 纯函数。
 */
public class GraphIndexBuilder {

    private final int chunkSize;
    private final int overlap;

    public GraphIndexBuilder(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("块大小必须为正数");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("重叠必须满足 0 <= overlap < chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /** 构建（提取结果可空：空抽取产出仅含 text_units 的索引） */
    public GraphIndexVO build(String documentId, String content, EntityExtractionResultVO extraction) {
        if (documentId == null || documentId.trim().isEmpty()) {
            throw new IllegalArgumentException("文档ID不能为空");
        }
        List<TextUnitVO> units = splitToUnits(documentId, content == null ? "" : content);

        Map<String, List<String>> entitySources = new LinkedHashMap<>();
        Map<String, List<String>> relationSources = new LinkedHashMap<>();
        Map<String, GraphNodeVO> nodeMap = new TreeMap<>();
        Map<String, GraphEdgeVO> edgeMap = new TreeMap<>();

        if (extraction != null && extraction.getEntities() != null) {
            extraction.getEntities().forEach(e -> {
                String key = normalize(e.getEntityName());
                if (key.isEmpty()) {
                    return;
                }
                nodeMap.putIfAbsent(key, GraphNodeVO.builder()
                        .nodeKey(key)
                        .entityName(e.getEntityName())
                        .entityType(e.getEntityType())
                        .entityId(e.getEntityId())
                        .build());
                entitySources.put(key, findUnits(units, key));
            });
        }
        if (extraction != null && extraction.getRelations() != null) {
            extraction.getRelations().forEach(r -> {
                String sourceKey = normalize(r.getSourceEntityName());
                String targetKey = normalize(r.getTargetEntityName());
                if (sourceKey.isEmpty() || targetKey.isEmpty()) {
                    return;
                }
                String edgeKey = sourceKey + "|" + r.getRelationType() + "|" + targetKey;
                edgeMap.putIfAbsent(edgeKey, GraphEdgeVO.builder()
                        .edgeKey(edgeKey)
                        .sourceKey(sourceKey)
                        .targetKey(targetKey)
                        .relationType(r.getRelationType())
                        .build());
                // 关系来源块：首取两端实体共现块，无共现退化为任一端所在块
                List<String> both = intersect(entitySources.get(sourceKey), entitySources.get(targetKey));
                List<String> either = union(entitySources.get(sourceKey), entitySources.get(targetKey));
                relationSources.put(edgeKey, both.isEmpty() ? either : both);
            });
        }

        GraphIndexVO index = GraphIndexVO.builder()
                .documentId(documentId)
                .textUnits(units)
                .nodes(new ArrayList<>(nodeMap.values()))
                .edges(new ArrayList<>(edgeMap.values()))
                .entitySources(entitySources)
                .relationSources(relationSources)
                .build();
        index.setIndexHash(hash(index));
        return index;
    }

    /** 段落优先切块：空行分段，超长段按 chunkSize 滑窗（overlap 重叠）续切 */
    List<TextUnitVO> splitToUnits(String documentId, String content) {
        List<TextUnitVO> units = new ArrayList<>();
        int ordinal = 0;
        for (String paragraph : content.split("\\n\\s*\\n")) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= chunkSize) {
                units.add(unit(documentId, ordinal++, p));
                continue;
            }
            int start = 0;
            while (start < p.length()) {
                int end = Math.min(start + chunkSize, p.length());
                units.add(unit(documentId, ordinal++, p.substring(start, end)));
                if (end == p.length()) {
                    break;
                }
                start = end - overlap;
            }
        }
        return units;
    }

    private TextUnitVO unit(String documentId, int ordinal, String content) {
        return TextUnitVO.builder()
                .unitId(documentId + "#u" + ordinal)
                .documentId(documentId)
                .ordinal(ordinal)
                .content(content)
                .build();
    }

    /** 实体名（折叠后）在哪些块中出现，按块序返回 */
    private List<String> findUnits(List<TextUnitVO> units, String normalizedKey) {
        List<String> hit = new ArrayList<>();
        for (TextUnitVO unit : units) {
            if (normalize(unit.getContent()).contains(normalizedKey)) {
                hit.add(unit.getUnitId());
            }
        }
        return hit;
    }

    /** 归一：去空白 + 小写 + 全角转半角（确定性映射） */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            sb.append(Character.toLowerCase(fullToHalf(c)));
        }
        return sb.toString();
    }

    private static char fullToHalf(char c) {
        // 全角区 ASCII 段（！-～ → !-~）
        if (c >= 0xFF01 && c <= 0xFF5E) {
            return (char) (c - 0xFEE0);
        }
        return c;
    }

    static List<String> intersect(List<String> a, List<String> b) {
        List<String> both = new ArrayList<>();
        if (a == null || b == null) {
            return both;
        }
        for (String x : a) {
            if (b.contains(x)) {
                both.add(x);
            }
        }
        return both;
    }

    static List<String> union(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>();
        if (a != null) {
            all.addAll(a);
        }
        if (b != null) {
            for (String x : b) {
                if (!all.contains(x)) {
                    all.add(x);
                }
            }
        }
        return all;
    }

    /** 规范化序列化（TreeMap 有序拼接）后 SHA-256，十六进制小写 */
    static String hash(GraphIndexVO index) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("doc=").append(index.getDocumentId()).append('\n');
        for (TextUnitVO unit : index.getTextUnits()) {
            canonical.append("u#").append(unit.getOrdinal()).append('=')
                    .append(unit.getContent()).append('\n');
        }
        for (GraphNodeVO node : index.getNodes()) {
            canonical.append("n#").append(node.getNodeKey()).append('|')
                    .append(node.getEntityType()).append('\n');
        }
        for (GraphEdgeVO edge : index.getEdges()) {
            canonical.append("e#").append(edge.getEdgeKey()).append('\n');
        }
        new TreeMap<>(index.getEntitySources()).forEach((k, v) ->
                canonical.append("es#").append(k).append('=').append(String.join(",", v)).append('\n'));
        new TreeMap<>(index.getRelationSources()).forEach((k, v) ->
                canonical.append("rs#").append(k).append('=').append(String.join(",", v)).append('\n'));
        return sha256Hex(canonical.toString());
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
