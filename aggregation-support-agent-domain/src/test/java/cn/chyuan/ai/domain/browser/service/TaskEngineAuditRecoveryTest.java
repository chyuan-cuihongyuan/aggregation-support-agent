package cn.chyuan.ai.domain.browser.service;

import cn.chyuan.ai.domain.browser.adapter.port.BrowserAuditPort;
import cn.chyuan.ai.domain.browser.adapter.port.BrowserDriverPort;
import cn.chyuan.ai.domain.browser.model.valobj.BrowserActionVO;
import cn.chyuan.ai.domain.browser.model.valobj.PageSnapshotVO;
import cn.chyuan.ai.domain.browser.model.valobj.RecoveryDecision;
import cn.chyuan.ai.domain.browser.model.valobj.TaskRunResultVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务引擎/审计/恢复单测（工单 0342 AQ4 + 0345 AQ7 + 0346 AQ8，假驱动确定性）。
 */
class TaskEngineAuditRecoveryTest {

    /** 假驱动：navigate/scroll/wait/extract 成功并回新快照；click 命中 #ok 成功、其余选择器抛错 */
    private final BrowserDriverPort driver = new BrowserDriverPort() {
        @Override
        public PageSnapshotVO initial(String startUrl) {
            return PageSnapshotVO.builder().url(startUrl).title("首页").capturedAtMs(1L)
                    .elements(List.of()).build();
        }

        @Override
        public PageSnapshotVO execute(BrowserActionVO action, PageSnapshotVO current) {
            if (BrowserActionVO.CLICK.equals(action.getType())
                    && !"#ok".equals(action.getSelector())) {
                throw new IllegalStateException("元素不可点击: " + action.getSelector());
            }
            return PageSnapshotVO.builder()
                    .url(current.getUrl()).title(current.getTitle())
                    .capturedAtMs(current.getCapturedAtMs() + 1)
                    .elements(current.getElements()).build();
        }
    };

    @Test
    void 正常序列执行与步级记录() {
        BrowserTaskEngine engine = new BrowserTaskEngine(driver);
        TaskRunResultVO result = engine.run("task-1", "https://a.com", List.of(
                BrowserActionVO.builder().type(BrowserActionVO.NAVIGATE).url("https://a.com/list").build(),
                BrowserActionVO.builder().type(BrowserActionVO.CLICK).selector("#ok").build()));
        assertTrue(result.isSuccess());
        assertEquals(2, result.getSteps().size());
        assertEquals(-1, result.getFailedStep());
        assertEquals("SUCCESS", result.getSteps().get(0).getStatus());
        assertTrue(result.getSteps().get(0).getActionSummary().contains("https://a.com/list"));
        assertTrue(result.getSteps().get(0).getCostMs() >= 0);
    }

    @Test
    void 失败中止与失败上下文留痕() {
        BrowserTaskEngine engine = new BrowserTaskEngine(driver);
        TaskRunResultVO result = engine.run("task-2", "https://a.com", List.of(
                BrowserActionVO.builder().type(BrowserActionVO.CLICK).selector("#ok").build(),
                BrowserActionVO.builder().type(BrowserActionVO.CLICK).selector("#bad").build(),
                BrowserActionVO.builder().type(BrowserActionVO.WAIT).waitMs(1).build()));
        assertFalse(result.isSuccess());
        assertEquals(1, result.getFailedStep());
        assertEquals(2, result.getSteps().size(), "失败后不再执行后续步");
        assertTrue(result.getFailureContext().contains("元素不可点击"));
        assertTrue(result.getFailureContext().contains("页面快照"));
        // 校验失败也在引擎层拦截
        TaskRunResultVO invalid = engine.run("task-3", "https://a.com", List.of(
                BrowserActionVO.builder().type(BrowserActionVO.CLICK).build()));
        assertFalse(invalid.isSuccess());
        assertTrue(invalid.getFailureContext().contains("动作校验失败"));
        assertThrows(IllegalArgumentException.class, () -> engine.run(" ", "u", List.of()));
    }

    @Test
    void 审计端口集成登记与存证() {
        InMemoryBrowserAudit audit = new InMemoryBrowserAudit();
        BrowserTaskEngine engine = new BrowserTaskEngine(driver);
        TaskRunResultVO result = engine.run("task-4", "https://a.com", List.of(
                BrowserActionVO.builder().type(BrowserActionVO.CLICK).selector("#ok").build()));
        audit.recordRun(result);
        audit.evidence("task-4", 1, new byte[]{1, 2, 3}, "首页截图");
        assertEquals(1, audit.byTask("task-4").size());
        assertEquals("SUCCESS", audit.byTask("task-4").get(0).status());
        assertEquals(1, audit.evidenceList("task-4").size());
        assertTrue(audit.evidenceList("task-4").get(0).contains("3B"));
        assertThrows(IllegalArgumentException.class, () -> audit.log(
                new BrowserAuditPort.AuditEntry(" ", 0, "s", "-", "SUCCESS", 0)));
    }

    @Test
    void 三级恢复链与恢复留痕() {
        // 一级重试：幂等动作（navigate）首失败后重试成功 —— 用可失败驱动包装
        BrowserDriverPort flaky = new BrowserDriverPort() {
            int navigateCount = 0;

            @Override
            public PageSnapshotVO initial(String startUrl) {
                return driver.initial(startUrl);
            }

            @Override
            public PageSnapshotVO execute(BrowserActionVO action, PageSnapshotVO current) {
                if (BrowserActionVO.NAVIGATE.equals(action.getType()) && navigateCount++ == 0) {
                    throw new IllegalStateException("瞬时网络错误");
                }
                return driver.execute(action, current);
            }
        };
        RecoveryPolicy policy = new RecoveryPolicy(3);
        TaskRunResultVO recovered = policy.runWithRecovery("task-5", "https://a.com",
                List.of(BrowserActionVO.builder().type(BrowserActionVO.NAVIGATE)
                        .url("https://a.com/page").build()), flaky, new ElementLocator());
        assertTrue(recovered.isSuccess(), "幂等动作应一级重试成功");
        assertTrue(policy.trace().stream().anyMatch(t -> t.contains("RETRY")));
        // 二级跳过：非幂等（click）且 skippable
        RecoveryPolicy skipPolicy = new RecoveryPolicy(1);
        TaskRunResultVO skipped = skipPolicy.runWithRecovery("task-6", "https://a.com",
                List.of(BrowserActionVO.builder().type(BrowserActionVO.CLICK).selector("#bad")
                        .skippable(true).build()), driver, new ElementLocator());
        assertTrue(skipped.isSuccess(), "可跳过动作失败后跳过视为继续");
        assertTrue(skipPolicy.trace().stream().anyMatch(t -> t.contains("SKIP")));
        // 三级替代定位：click 非 skippable → RELOCATE 定位仍失败 → 终止留轨迹
        RecoveryPolicy relocatePolicy = new RecoveryPolicy(1);
        TaskRunResultVO aborted = relocatePolicy.runWithRecovery("task-7", "https://a.com",
                List.of(BrowserActionVO.builder().type(BrowserActionVO.CLICK).selector("#ghost")
                        .skippable(false).build()), driver, new ElementLocator());
        assertFalse(aborted.isSuccess());
        assertTrue(aborted.getFailureContext().contains("恢复轨迹"));
        assertTrue(relocatePolicy.trace().stream().anyMatch(t -> t.contains("RELOCATE")));
        // 校验失败直接 ABORT
        RecoveryDecision decision = new RecoveryPolicy(1).decide(
                BrowserActionVO.builder().type(BrowserActionVO.CLICK).build(), "校验失败: selector");
        assertEquals(RecoveryDecision.ABORT, decision.getLevel());
    }
}
