package cn.chyuan.ai.domain.vcskernel.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * 版本端口+组合管线（工单 0617 BU8）。
 * VcsPort（文档树→blob/tree/commit 提交/diff 查询/三方合并）组合管线：
 * 多文档两代快照→diff→分支合并端到端；
 * 与 knowledgebase·textkernel 只读联动（文档树导出版本化、文本内容
 * 作 diff 输入，泛型入参不 import 两域，不改任何类）/
 * vcs-kernel.enabled 默认关（开启才改变行为）。
 */
public interface VcsPort {

    /** 文件行列表拆合 */
    static List<String> toLines(String content) {
        if (content == null) {
            throw new IllegalArgumentException("内容不得为 null");
        }
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= content.length(); i++) {
            if (i == content.length() || content.charAt(i) == '\n') {
                lines.add(content.substring(start, i));
                start = i + 1;
            }
        }
        return lines;
    }

    static String fromLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 提交文件集（blob+tree+commit 全链），返回提交 oid */
    String commit(Map<String, String> files, String author, String message);

    /** 提交历史（自某提交起祖先序） */
    List<CommitGraph.Commit> log(String commitOid);

    /** 提交的扁平文件内容（路径→内容） */
    Map<String, String> filesAt(String commitOid);

    /** 两提交统一 diff（逐文件，路径序） */
    String unifiedDiff(String fromOid, String toOid, int context);

    /** 两提交变更统计（总增删行） */
    LineDiff.Stat diffStat(String fromOid, String toOid);

    /** 三方合并两提交（base 为共同祖先） */
    ThreeWayMerge.Result mergeCommits(String oursOid, String theirsOid);

    /** 工作区（status/checkout 联动面） */
    WorkingTree workingTree();

    /** 对象仓统计（blob 数） */
    int blobCount();

    /** 内存假实现：BlobStore+TreeObject+CommitGraph+RefStore+WorkingTree 全链 */
    class InMemoryRepository implements VcsPort {

        private final BlobStore blobs = new BlobStore();
        private final TreeObject trees = new TreeObject();
        private final CommitGraph commits = new CommitGraph();
        private final RefStore refs = new RefStore();
        private final WorkingTree workTree = new WorkingTree();
        private final LongSupplier clock;

        public InMemoryRepository(LongSupplier clock) {
            if (clock == null) {
                throw new IllegalArgumentException("时钟不得为 null");
            }
            this.clock = clock;
        }

        @Override
        public synchronized String commit(Map<String, String> files, String author, String message) {
            if (files == null || files.isEmpty()) {
                throw new IllegalArgumentException("提交至少一个文件");
            }
            Map<String, String> blobOids = new TreeMap<>();
            for (Map.Entry<String, String> e : files.entrySet()) {
                blobOids.put(e.getKey(), blobs.put(e.getValue().getBytes(StandardCharsets.UTF_8)));
            }
            String root = trees.put(blobOids);
            List<String> parents = new ArrayList<>();
            String head = headTipOrNull();
            if (head != null) {
                parents.add(head);
            }
            String oid = commits.commit(root, parents, author, message, clock.getAsLong());
            String branch = refs.headBranch();
            if (branch != null) {
                refs.updateBranch(branch, oid);
            }
            return oid;
        }

        @Override
        public synchronized List<CommitGraph.Commit> log(String commitOid) {
            return commits.ancestors(commitOid);
        }

        @Override
        public synchronized Map<String, String> filesAt(String commitOid) {
            Map<String, String> blobOids = trees.files(commits.get(commitOid).treeRoot());
            Map<String, String> out = new TreeMap<>();
            for (Map.Entry<String, String> e : blobOids.entrySet()) {
                out.put(e.getKey(), new String(blobs.get(e.getValue()), StandardCharsets.UTF_8));
            }
            return out;
        }

        @Override
        public synchronized String unifiedDiff(String fromOid, String toOid, int context) {
            Map<String, String> fromFiles = filesAt(fromOid);
            Map<String, String> toFiles = filesAt(toOid);
            java.util.TreeSet<String> paths = new java.util.TreeSet<>();
            paths.addAll(fromFiles.keySet());
            paths.addAll(toFiles.keySet());
            StringBuilder sb = new StringBuilder();
            for (String path : paths) {
                String diff = LineDiff.unified(
                        toLines(fromFiles.getOrDefault(path, "")),
                        toLines(toFiles.getOrDefault(path, "")), context);
                if (!diff.isEmpty()) {
                    sb.append("--- ").append(path).append('\n');
                    sb.append("+++ ").append(path).append('\n');
                    sb.append(diff);
                }
            }
            return sb.toString();
        }

        @Override
        public synchronized LineDiff.Stat diffStat(String fromOid, String toOid) {
            Map<String, String> fromFiles = filesAt(fromOid);
            Map<String, String> toFiles = filesAt(toOid);
            int added = 0;
            int deleted = 0;
            java.util.TreeSet<String> paths = new java.util.TreeSet<>();
            paths.addAll(fromFiles.keySet());
            paths.addAll(toFiles.keySet());
            for (String path : paths) {
                LineDiff.Stat stat = LineDiff.stat(LineDiff.diff(
                        toLines(fromFiles.getOrDefault(path, "")),
                        toLines(toFiles.getOrDefault(path, ""))));
                added += stat.added();
                deleted += stat.deleted();
            }
            return new LineDiff.Stat(added, deleted);
        }

        @Override
        public synchronized ThreeWayMerge.Result mergeCommits(String oursOid, String theirsOid) {
            String baseOid = commits.mergeBase(oursOid, theirsOid);
            if (baseOid == null) {
                throw new IllegalArgumentException("无共同祖先，拒绝合并");
            }
            Map<String, String> baseFiles = filesAt(baseOid);
            Map<String, String> oursFiles = filesAt(oursOid);
            Map<String, String> theirsFiles = filesAt(theirsOid);
            java.util.TreeSet<String> paths = new java.util.TreeSet<>();
            paths.addAll(baseFiles.keySet());
            paths.addAll(oursFiles.keySet());
            paths.addAll(theirsFiles.keySet());
            List<String> mergedLines = new ArrayList<>();
            int conflicts = 0;
            for (String path : paths) {
                if (!mergedLines.isEmpty()) {
                    mergedLines.add("");
                }
                mergedLines.add("# " + path);
                ThreeWayMerge.Result result = ThreeWayMerge.merge(
                        toLines(baseFiles.getOrDefault(path, "")),
                        toLines(oursFiles.getOrDefault(path, "")),
                        toLines(theirsFiles.getOrDefault(path, "")));
                mergedLines.addAll(result.lines());
                conflicts += result.conflicts();
            }
            return new ThreeWayMerge.Result(mergedLines, conflicts);
        }

        @Override
        public synchronized WorkingTree workingTree() {
            return workTree;
        }

        @Override
        public synchronized int blobCount() {
            return blobs.size();
        }

        /** 建分支并挂 HEAD（组合管线辅助） */
        public synchronized void initBranch(String name) {
            String tip = headTipOrNull();
            refs.createBranch(name, tip == null ? "commit:none" : tip);
            refs.attachHead(name);
        }

        /** 切分支（checkout 联动） */
        public synchronized void switchBranch(String name) {
            refs.attachHead(name);
            String tip = refs.branchTip(name);
            if (!"commit:none".equals(tip)) {
                workTree.checkout(filesAt(tip));
            }
        }

        private String headTipOrNull() {
            try {
                String tip = refs.resolveHead();
                return "commit:none".equals(tip) ? null : tip;
            } catch (IllegalStateException e) {
                return null;
            }
        }
    }
}
