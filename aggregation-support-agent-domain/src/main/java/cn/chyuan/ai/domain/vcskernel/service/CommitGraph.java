package cn.chyuan.ai.domain.vcskernel.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * commit 父链（工单 0612 BU3，git commit 思想）。
 * tree 根+父提交列表+元数据（作者/消息/时间戳注入端口）/
 * 祖先 DAG 遍历（广度序确定性）/最近共同祖先/空消息拒绝。
 */
public final class CommitGraph {

    /** 提交对象 */
    public record Commit(String oid, String treeRoot, List<String> parents,
                         String author, String message, long timestamp) {
    }

    public static final String TYPE = "commit";

    private final Map<String, Commit> commits = new LinkedHashMap<>();

    /** 新提交（oid=内容寻址：树根+父链+元数据） */
    public synchronized String commit(String treeRoot, List<String> parents,
                                      String author, String message, long timestamp) {
        if (treeRoot == null || treeRoot.isBlank()) {
            throw new IllegalArgumentException("树根不得为空");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("提交消息不得为空");
        }
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("作者不得为空");
        }
        List<String> parentList = parents == null ? List.of() : List.copyOf(parents);
        for (String parent : parentList) {
            if (!commits.containsKey(parent)) {
                throw new IllegalArgumentException("父提交缺失：" + parent);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(treeRoot).append('\n');
        for (String parent : parentList) {
            sb.append("parent ").append(parent).append('\n');
        }
        sb.append("author ").append(author).append('\n');
        sb.append("time ").append(timestamp).append('\n');
        sb.append('\n').append(message);
        String oid = TYPE + ":" + BlobStore.hex(BlobStore.digest(
                (TYPE + "\n").getBytes(StandardCharsets.UTF_8),
                sb.toString().getBytes(StandardCharsets.UTF_8)));
        Commit commit = new Commit(oid, treeRoot, parentList, author, message, timestamp);
        commits.put(oid, commit);
        return oid;
    }

    /** 读取（缺失拒绝） */
    public synchronized Commit get(String oid) {
        Commit commit = commits.get(oid);
        if (commit == null) {
            throw new IllegalArgumentException("提交缺失：" + oid);
        }
        return commit;
    }

    /** 祖先遍历（含自身，广度序确定性：按层内提交序） */
    public synchronized List<Commit> ancestors(String oid) {
        Commit start = get(oid);
        List<Commit> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start.oid());
        seen.add(start.oid());
        while (!queue.isEmpty()) {
            Commit cur = commits.get(queue.poll());
            out.add(cur);
            for (String parent : cur.parents()) {
                if (seen.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return out;
    }

    /** 最近共同祖先（无公共祖先返回 null） */
    public synchronized String mergeBase(String a, String b) {
        Set<String> ancestorsB = new LinkedHashSet<>();
        for (Commit c : ancestors(b)) {
            ancestorsB.add(c.oid());
        }
        for (Commit candidate : ancestors(a)) {
            if (!candidate.oid().equals(a) && ancestorsB.contains(candidate.oid())) {
                return candidate.oid();
            }
        }
        return ancestorsB.contains(a) ? a : null;
    }

    /** 提交数 */
    public synchronized int size() {
        return commits.size();
    }
}
