package cn.chyuan.ai.domain.bufkernel.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 泄漏检测（工单 1202 FE7，netty ResourceLeakDetector 思想）。
 * 分配登记采样、释放清案；已登记未释放即在泄漏报告内；报告为派生视图，重复查询幂等。
 */
public final class LeakDetector {

    private final Set<Integer> tracked = new HashSet<>();
    private final Set<Integer> closed = new HashSet<>();

    /** 登记追踪 */
    public void track(int bufId) {
        tracked.add(bufId);
        closed.remove(bufId);
    }

    /** 释放清案 */
    public void close(int bufId) {
        closed.add(bufId);
    }

    /** 泄漏报告：已登记未清案的 bufId（升序） */
    public List<Integer> leakReport() {
        List<Integer> leaks = new ArrayList<>();
        for (Integer id : tracked) {
            if (!closed.contains(id)) {
                leaks.add(id);
            }
        }
        return leaks.stream().sorted().toList();
    }
}
