package cn.chyuan.ai.domain.searchkernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 索引文档生命周期（工单 0396 AW1，meilisearch 引擎核心简化）。
 * 添加/更新（版本号必须严格递增，旧版本拒绝）/删除（墓碑）；倒排表（词→文档集）随写随建；
 * 快照导出（按 id 排序）重建等价。纯内存实现。
 */
public class InMemoryInvertedIndex {

    /** 索引文档 */
    public static final class Doc {
        private final String id;
        private String title;
        private String body;
        private final Map<String, String> fields;
        private long version;
        private boolean deleted;

        Doc(String id, String title, String body, Map<String, String> fields, long version) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.fields = fields == null ? Map.of() : new TreeMap<>(fields);
            this.version = version;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getBody() {
            return body;
        }

        public Map<String, String> getFields() {
            return fields;
        }

        public long getVersion() {
            return version;
        }

        public boolean isDeleted() {
            return deleted;
        }
    }

    /** 版本冲突 */
    public static final class VersionConflictException extends RuntimeException {
        public VersionConflictException(String message) {
            super(message);
        }
    }

    private final SearchTokenizer tokenizer = new SearchTokenizer();
    private final Map<String, Doc> docs = new HashMap<>();
    private final Map<String, java.util.Set<String>> postings = new HashMap<>();

    /**
     * 添加或更新：已存在且 version 不大于现版本 → 拒绝（旧版本防御）。
     */
    public void upsert(String id, String title, String body, Map<String, String> fields, long version) {
        Doc existing = docs.get(id);
        if (existing != null && version <= existing.version) {
            throw new VersionConflictException("版本必须严格递增: " + id + " " + version);
        }
        if (existing != null) {
            removeFromPostings(existing);
        }
        Doc doc = new Doc(id, title, body, fields, version);
        docs.put(id, doc);
        addToPostings(doc);
    }

    /** 删除（墓碑：保留 doc 记录 deleted=true，检索不可见） */
    public void delete(String id) {
        Doc doc = docs.get(id);
        if (doc == null) {
            return;
        }
        removeFromPostings(doc);
        doc.deleted = true;
    }

    /** 词→活跃文档集 */
    public java.util.Set<String> posting(String token) {
        return postings.getOrDefault(token, java.util.Set.of());
    }

    /** 活跃文档（未删除）按 id 排序 */
    public List<Doc> activeDocs() {
        List<Doc> out = new ArrayList<>();
        for (Doc doc : docs.values()) {
            if (!doc.deleted) {
                out.add(doc);
            }
        }
        out.sort(Comparator.comparing(Doc::getId));
        return out;
    }

    /** 快照导出：按 id 排序的活跃文档（确定性） */
    public List<Doc> snapshot() {
        return activeDocs();
    }

    /** 快照重建：清空后逐条 upsert → 与导出前等价 */
    public void restore(List<Doc> snapshot) {
        docs.clear();
        postings.clear();
        for (Doc doc : snapshot) {
            upsert(doc.id, doc.title, doc.body, doc.fields, doc.version);
        }
    }

    public int size() {
        return activeDocs().size();
    }

    private void addToPostings(Doc doc) {
        for (String token : tokenizeDoc(doc)) {
            postings.computeIfAbsent(token, k -> new java.util.LinkedHashSet<>()).add(doc.id);
        }
    }

    private void removeFromPostings(Doc doc) {
        for (String token : tokenizeDoc(doc)) {
            java.util.Set<String> ids = postings.get(token);
            if (ids != null) {
                ids.remove(doc.id);
                if (ids.isEmpty()) {
                    postings.remove(token);
                }
            }
        }
    }

    private List<String> tokenizeDoc(Doc doc) {
        List<String> tokens = new ArrayList<>();
        tokens.addAll(tokenizer.tokenize(doc.title));
        tokens.addAll(tokenizer.tokenize(doc.body));
        for (String value : doc.fields.values()) {
            tokens.addAll(tokenizer.tokenize(value));
        }
        return tokens;
    }
}
