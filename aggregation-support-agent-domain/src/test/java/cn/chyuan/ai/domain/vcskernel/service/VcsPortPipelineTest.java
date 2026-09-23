package cn.chyuan.ai.domain.vcskernel.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 版本端口组合管线测试（工单 0617 BU8）。
 * vcs-kernel.enabled 默认关（开启才改变行为）；
 * 与 knowledgebase·textkernel 只读联动：文档树导出版本化、文本内容作 diff 输入。
 */
class VcsPortPipelineTest {

    private static VcsPort.InMemoryRepository repo() {
        return new VcsPort.InMemoryRepository(new AtomicLong(1000L)::incrementAndGet);
    }

    @Test
    void commitPipelineBuildsObjectsAndLog() {
        VcsPort.InMemoryRepository repo = repo();
        repo.initBranch("main");
        Map<String, String> v1 = new LinkedHashMap<>();
        v1.put("kb/java/basics.md", "标题\nJava 基础\n");
        v1.put("kb/java/advanced.md", "进阶\nJVM\n");
        String c1 = repo.commit(v1, "chyuan", "init: 知识库初版");
        Map<String, String> v2 = new LinkedHashMap<>(v1);
        v2.put("kb/java/basics.md", "标题\nJava 基础\n新增一行\n");
        String c2 = repo.commit(v2, "chyuan", "update: basics 增补");

        assertEquals(2, repo.log(c2).size());
        assertEquals(c2, repo.log(c2).get(0).oid());
        assertEquals(3, repo.blobCount(), "未变文件 blob 去重");
        assertEquals(2, repo.filesAt(c2).size());
        assertTrue(repo.filesAt(c2).get("kb/java/basics.md").contains("新增一行"));

        LineDiff.Stat stat = repo.diffStat(c1, c2);
        assertEquals(1, stat.added());
        assertEquals(0, stat.deleted());
        String unified = repo.unifiedDiff(c1, c2, 1);
        assertTrue(unified.contains("--- kb/java/basics.md"));
        assertTrue(unified.contains("+新增一行"));
        assertFalse(unified.contains("advanced"), "未变文件不进 diff");
    }

    @Test
    void branchMergePipelineResolvesOrConflicts() {
        VcsPort.InMemoryRepository repo = repo();
        repo.initBranch("main");
        Map<String, String> base = new LinkedHashMap<>();
        base.put("doc/prompt.md", "系统提示\n输出规范\n");
        String c1 = repo.commit(base, "chyuan", "base");

        repo.initBranch("feature");
        repo.switchBranch("feature");
        Map<String, String> feature = new LinkedHashMap<>(base);
        feature.put("doc/prompt.md", "系统提示\n输出规范-增强\n");
        feature.put("doc/new.md", "新文档\n");
        String cFeature = repo.commit(feature, "dev", "feature 改动");

        repo.switchBranch("main");
        Map<String, String> mainline = new LinkedHashMap<>(base);
        mainline.put("doc/prompt.md", "系统提示\n输出规范-主干\n");
        String cMain = repo.commit(mainline, "chyuan", "主干改动");

        ThreeWayMerge.Result conflict = repo.mergeCommits(cMain, cFeature);
        assertEquals(1, conflict.conflicts(), "同区域不同改动应冲突");
        assertTrue(conflict.lines().contains("<<<<<<< OURS"));

        repo.switchBranch("main");
        Map<String, String> orthogonal = new LinkedHashMap<>(base);
        orthogonal.put("doc/other.md", "正交新增\n");
        String cOrth = repo.commit(orthogonal, "chyuan", "正交改动");
        ThreeWayMerge.Result clean = repo.mergeCommits(cOrth, cFeature);
        assertTrue(clean.clean(), "正交改动自动合并");
        assertTrue(clean.lines().stream().anyMatch(l -> l.contains("输出规范-增强")));
        assertTrue(clean.lines().stream().anyMatch(l -> l.contains("正交新增")));
    }

    @Test
    void workingTreeRoundTripThroughCommits() {
        VcsPort.InMemoryRepository repo = repo();
        repo.initBranch("main");
        Map<String, String> v1 = Map.of("note.md", "版本一\n");
        String c1 = repo.commit(v1, "chyuan", "v1");
        repo.workingTree().checkout(repo.filesAt(c1));
        repo.workingTree().write("note.md", "版本二\n");
        repo.workingTree().write("extra.md", "新增\n");
        List<WorkingTree.FileStatus> dirty = repo.workingTree().status(repo.filesAt(c1));
        assertEquals(2, dirty.size());

        String c2 = repo.commit(repo.workingTree().snapshot(), "chyuan", "v2 提交工作区");
        assertTrue(repo.workingTree().status(repo.filesAt(c2)).isEmpty(), "提交后干净");
        assertEquals("版本二\n", repo.filesAt(c2).get("note.md"));
    }

    @Test
    void textkernelShapedContentFlowsThroughDiff() {
        VcsPort.InMemoryRepository repo = repo();
        repo.initBranch("main");
        String article = "第一段\n第二段\n第三段\n";
        Map<String, String> v1 = Map.of("corpus/article.txt", article);
        String c1 = repo.commit(v1, "textkernel", "语料入库");
        Map<String, String> v2 = Map.of("corpus/article.txt", article.replace("第二段", "第二段（修订）"));
        String c2 = repo.commit(v2, "textkernel", "语料修订");
        assertEquals(1, repo.diffStat(c1, c2).added());
        assertEquals(1, repo.diffStat(c1, c2).deleted());
        List<String> merged = VcsPort.toLines("a\nb");
        assertEquals(List.of("a", "b"), merged);
        assertEquals("a\nb\n", VcsPort.fromLines(merged));
        assertThrows(IllegalArgumentException.class, () -> VcsPort.toLines(null));
    }
}
