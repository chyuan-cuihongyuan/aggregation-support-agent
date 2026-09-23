package cn.chyuan.ai.domain.vcskernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内容寻址版本内核域单测（工单 0610-0616 BU1-BU7，git 思想）。
 * blob 摘要去重/tree Merkle/commit 父链 DAG/ref 与 HEAD/
 * LCS 行级 diff/三方合并/工作区状态。
 */
class VcsKernelTest {

    @Test
    void blobContentAddressingDedupesAndRejectsMissing() {
        BlobStore store = new BlobStore();
        String a1 = store.put("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String a2 = store.put("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(a1, a2, "同内容同 oid");
        assertEquals(1, store.size());
        assertTrue(a1.startsWith("blob:"));
        assertTrue(store.contains(a1));
        assertArrayEquals("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), store.get(a1));
        assertThrows(IllegalArgumentException.class, () -> store.get("blob:missing"));
        assertThrows(IllegalArgumentException.class, () -> BlobStore.oid(null));
    }

    @Test
    void treeBuildsMerkleAndSharesSubtrees() {
        TreeObject trees = new TreeObject();
        String root1 = trees.put(Map.of(
                "docs/readme.md", "blob:a",
                "docs/guide.md", "blob:b",
                "root.txt", "blob:c"));
        Map<String, String> files = trees.files(root1);
        assertEquals(Map.of(
                "docs/readme.md", "blob:a",
                "docs/guide.md", "blob:b",
                "root.txt", "blob:c"), files, "扁平化应还原全部路径");
        assertTrue(trees.treeCount() >= 2, "应有子树");

        String root2 = trees.put(Map.of(
                "docs/readme.md", "blob:a",
                "docs/guide.md", "blob:b",
                "root.txt", "blob:changed"));
        assertNotEquals(root1, root2);
        String root3 = trees.put(Map.of(
                "docs/readme.md", "blob:a",
                "docs/guide.md", "blob:b",
                "root.txt", "blob:c"));
        assertEquals(root1, root3, "同内容树根摘要稳定");
        assertTrue(trees.treeCount() < 6, "未变子树共享（docs 子树不重复存）");

        assertThrows(IllegalArgumentException.class, () -> trees.put(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> trees.put(Map.of("/bad", "blob:x")));
        assertThrows(IllegalArgumentException.class, () -> trees.put(Map.of("a/b", "blob:x", "a/b", "blob:y")));
        assertThrows(IllegalArgumentException.class, () -> TreeObject.oid(List.of()));
    }

    @Test
    void commitGraphTraversesAncestorsAndFindsMergeBase() {
        CommitGraph graph = new CommitGraph();
        String c1 = graph.commit("tree:1", List.of(), "chyuan", "init", 1L);
        String c2 = graph.commit("tree:2", List.of(c1), "chyuan", "second", 2L);
        String c3 = graph.commit("tree:3", List.of(c2), "chyuan", "third", 3L);
        String side = graph.commit("tree:4", List.of(c2), "dev", "branch", 4L);

        List<CommitGraph.Commit> ancestors = graph.ancestors(c3);
        assertEquals(3, ancestors.size());
        assertEquals(c3, ancestors.get(0).oid());
        assertEquals(c1, ancestors.get(2).oid(), "广度序到达根");
        assertEquals(c2, graph.mergeBase(c3, side), "分叉点即共同祖先");
        assertThrows(IllegalArgumentException.class, () -> graph.commit("tree:x", List.of(), " ", "m", 1L));
        assertThrows(IllegalArgumentException.class, () -> graph.commit("tree:x", List.of("commit:none"), "a", "m", 1L));
        assertThrows(IllegalArgumentException.class, () -> graph.get("commit:none"));
    }

    @Test
    void refStoreResolvesHeadThroughBranches() {
        RefStore refs = new RefStore();
        refs.createBranch("main", "commit:c1");
        refs.createBranch("dev", "commit:c2");
        refs.attachHead("main");
        assertEquals("commit:c1", refs.resolveHead());
        assertFalse(refs.detached());
        assertEquals("main", refs.headBranch());
        refs.updateBranch("main", "commit:c3");
        assertEquals("commit:c3", refs.resolveHead());
        refs.detachHead("commit:c2");
        assertTrue(refs.detached());
        assertEquals("commit:c2", refs.resolveHead());
        assertThrows(IllegalArgumentException.class, () -> refs.createBranch("main", "commit:x"));
        assertThrows(IllegalArgumentException.class, () -> refs.attachHead("nope"));
        assertThrows(IllegalArgumentException.class, () -> refs.updateBranch("nope", "c"));
        refs.createBranch("empty", "commit:c9");
        RefStore fresh = new RefStore();
        assertThrows(IllegalStateException.class, fresh::resolveHead);
    }

    @Test
    void lineDiffComputesLcsWithHunksAndStats() {
        List<String> a = List.of("一", "二", "三", "四", "五");
        List<String> b = List.of("一", "二改", "三", "四", "五", "六");
        List<LineDiff.Row> rows = LineDiff.diff(a, b);
        LineDiff.Stat stat = LineDiff.stat(rows);
        assertEquals(2, stat.added());
        assertEquals(1, stat.deleted());

        String unified = LineDiff.unified(a, b, 1);
        assertTrue(unified.contains("@@"));
        assertTrue(unified.contains("-二"));
        assertTrue(unified.contains("+二改"));
        assertTrue(unified.contains("+六"));

        assertEquals("", LineDiff.unified(a, a, 3), "无变更无输出");
        List<LineDiff.Hunk> hunks = LineDiff.hunks(rows, 0);
        assertEquals(2, hunks.size(), "两处变更两个 hunk");
        assertThrows(IllegalArgumentException.class, () -> LineDiff.diff(null, b));
        List<String> bigA = new java.util.ArrayList<>();
        List<String> bigB = new java.util.ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            bigA.add("line-a-" + i);
            bigB.add("line-b-" + i);
        }
        List<String> finalBigB = bigB;
        assertThrows(IllegalArgumentException.class, () -> LineDiff.diff(bigA, finalBigB));
    }

    @Test
    void threeWayMergeAutoResolvesAndMarksConflicts() {
        List<String> base = List.of("头部", "中段", "尾部");
        List<String> ours = List.of("头部", "中段-我改", "尾部");
        List<String> theirs = List.of("头部", "中段", "尾部");
        ThreeWayMerge.Result clean = ThreeWayMerge.merge(base, ours, theirs);
        assertTrue(clean.clean());
        assertEquals(ours, clean.lines(), "一侧未改自动采用另一侧");

        List<String> theirs2 = List.of("头部", "中段-他改", "尾部");
        ThreeWayMerge.Result conflict = ThreeWayMerge.merge(base, ours, theirs2);
        assertEquals(1, conflict.conflicts());
        assertTrue(conflict.lines().contains("<<<<<<< OURS"));
        assertTrue(conflict.lines().contains("中段-我改"));
        assertTrue(conflict.lines().contains("======="));
        assertTrue(conflict.lines().contains("中段-他改"));
        assertTrue(conflict.lines().contains(">>>>>>> THEIRS"));

        ThreeWayMerge.Result same = ThreeWayMerge.merge(base, ours, ours);
        assertTrue(same.clean(), "相同改动自动采用");

        ThreeWayMerge.Markers custom = new ThreeWayMerge.Markers("{{{", "|||", "}}}");
        ThreeWayMerge.Result styled = ThreeWayMerge.merge(base, ours, theirs2, custom, "L", "R");
        assertTrue(styled.lines().contains("{{{ L"));

        assertThrows(IllegalArgumentException.class,
                () -> ThreeWayMerge.merge(List.of("a\0b"), List.of("a"), List.of("a")));
    }

    @Test
    void workingTreeClassifiesDirtyFilesAndChecksOut() {
        WorkingTree tree = new WorkingTree();
        tree.write("a.md", "v1");
        tree.write("b.md", "v1");
        Map<String, String> committed = Map.of("a.md", "v2", "c.md", "v1");
        List<WorkingTree.FileStatus> status = tree.status(committed);
        assertEquals(3, status.size());
        assertEquals(WorkingTree.Status.MODIFIED, status.stream()
                .filter(s -> s.path().equals("a.md")).findFirst().orElseThrow().status());
        assertEquals(WorkingTree.Status.ADDED, status.stream()
                .filter(s -> s.path().equals("b.md")).findFirst().orElseThrow().status());
        assertEquals(WorkingTree.Status.DELETED, status.stream()
                .filter(s -> s.path().equals("c.md")).findFirst().orElseThrow().status());

        tree.checkout(committed);
        assertTrue(tree.status(committed).isEmpty(), "恢复后干净");
        assertEquals("v2", tree.read("a.md"));
        assertEquals(2, tree.size());
        assertThrows(IllegalArgumentException.class, () -> tree.remove("missing.md"));
        assertThrows(IllegalArgumentException.class, () -> tree.read("missing.md"));
    }
}
