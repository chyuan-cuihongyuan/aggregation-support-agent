package cn.chyuan.ai.domain.vcskernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分支引用与 HEAD（工单 0613 BU4，git ref/HEAD 思想）。
 * ref 创建/前移到已有 commit/分支列表；HEAD 指向分支（符号引用）
 * 或分离指向 commit/解析链（HEAD→分支→提交）/未知 ref 拒绝。
 */
public final class RefStore {

    private final Map<String, String> refs = new LinkedHashMap<>();
    private String headBranch;
    private String headDetached;

    /** 创建分支（已存在拒绝） */
    public synchronized void createBranch(String name, String commitOid) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("分支名不得为空");
        }
        if (refs.containsKey(name)) {
            throw new IllegalArgumentException("分支已存在：" + name);
        }
        refs.put(name, commitOid);
    }

    /** 前移分支到已有提交（未知分支拒绝；提交校验由调用方负责） */
    public synchronized void updateBranch(String name, String commitOid) {
        if (!refs.containsKey(name)) {
            throw new IllegalArgumentException("未知分支：" + name);
        }
        refs.put(name, commitOid);
    }

    /** 分支指向 */
    public synchronized String branchTip(String name) {
        String tip = refs.get(name);
        if (tip == null) {
            throw new IllegalArgumentException("未知分支：" + name);
        }
        return tip;
    }

    /** 分支列表（创建序） */
    public synchronized Map<String, String> branches() {
        return new LinkedHashMap<>(refs);
    }

    /** HEAD 指向分支（符号引用） */
    public synchronized void attachHead(String branch) {
        if (!refs.containsKey(branch)) {
            throw new IllegalArgumentException("未知分支：" + branch);
        }
        headBranch = branch;
        headDetached = null;
    }

    /** HEAD 分离指向提交 */
    public synchronized void detachHead(String commitOid) {
        if (commitOid == null || commitOid.isBlank()) {
            throw new IllegalArgumentException("分离目标不得为空");
        }
        headDetached = commitOid;
        headBranch = null;
    }

    /** 解析 HEAD → 提交 oid（符号链 HEAD→分支→提交） */
    public synchronized String resolveHead() {
        if (headDetached != null) {
            return headDetached;
        }
        if (headBranch != null) {
            return branchTip(headBranch);
        }
        throw new IllegalStateException("HEAD 未指向任何分支或提交");
    }

    public synchronized boolean detached() {
        return headDetached != null;
    }

    public synchronized String headBranch() {
        return headBranch;
    }
}
