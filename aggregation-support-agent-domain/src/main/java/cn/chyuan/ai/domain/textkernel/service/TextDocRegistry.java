package cn.chyuan.ai.domain.textkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文本文档表登记器（工单 0494 BG7）。
 * 文档登记（doc id/字段文本 JSON 序列化/词数/状态 INDEXED|DELETED）与查询。
 * text-kernel.enabled 默认关。持久化面 = 第 31 表 text_doc。
 */
public class TextDocRegistry {

    /** 文档状态 */
    public enum Status {
        INDEXED, DELETED
    }

    /** 登记行（对应 text_doc 表行） */
    public record DocRow(int docId, String fieldTextJson, int termCount, Status status) {
    }

    /** 简单 JSON 对象序列化（键序确定性：字典序，值转义反斜杠与引号） */
    static String fieldsToJson(Map<String, String> fields) {
        StringBuilder json = new StringBuilder("{");
        new java.util.TreeMap<>(fields).forEach((key, value) -> json.append(quote(key)).append(':')
                .append(quote(value)).append(','));
        if (!fields.isEmpty()) {
            json.setLength(json.length() - 1);
        }
        return json.append('}').toString();
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private final Map<Integer, DocRow> rows = new ConcurrentHashMap<>();

    /** 登记文档（词数由分析器产物计），返回登记行 */
    public synchronized DocRow index(int docId, Map<String, String> fields, List<AnalyzerChain.Token> analyzed) {
        DocRow row = new DocRow(docId, fieldsToJson(fields), analyzed.size(), Status.INDEXED);
        rows.put(docId, row);
        return row;
    }

    /** 删除文档（置 DELETED，索引侧过滤），重复删除幂等返回 false */
    public synchronized boolean markDeleted(int docId) {
        DocRow row = rows.get(docId);
        if (row == null || row.status() == Status.DELETED) {
            return false;
        }
        rows.put(docId, new DocRow(row.docId(), row.fieldTextJson(), row.termCount(), Status.DELETED));
        return true;
    }

    /** INDEXED 文档（doc id 升序） */
    public synchronized List<DocRow> indexedDocs() {
        List<DocRow> result = new ArrayList<>();
        rows.values().stream()
                .filter(row -> row.status() == Status.INDEXED)
                .sorted(java.util.Comparator.comparingInt(DocRow::docId))
                .forEach(result::add);
        return result;
    }

    public synchronized int size() {
        return rows.size();
    }
}
