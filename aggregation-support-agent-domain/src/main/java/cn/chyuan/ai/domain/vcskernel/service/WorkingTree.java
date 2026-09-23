package cn.chyuan.ai.domain.vcskernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区状态（工单 0616 BU7，git status/checkout 思想）。
 * 暂存区与快照比对/脏文件检测（修改/新增/删除三分类）/
 * checkout 恢复指定快照全量文件/暂存快照构建。
 */
public final class WorkingTree {

    /** 文件状态三分类 */
    public enum Status {
        MODIFIED, ADDED, DELETED
    }

    /** 单文件状态 */
    public record FileStatus(String path, Status status) {
    }

    private Map<String, String> files = new LinkedHashMap<>();

    /** 写文件（路径→内容） */
    public synchronized void write(String path, String content) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("路径不得为空");
        }
        files.put(path, content == null ? "" : content);
    }

    /** 删除文件（不存在拒绝） */
    public synchronized void remove(String path) {
        if (files.remove(path) == null) {
            throw new IllegalArgumentException("文件不存在：" + path);
        }
    }

    /** 当前文件集（路径→内容） */
    public synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(files);
    }

    /** 脏检测：与快照（路径→内容）比对，三分类（路径序确定） */
    public synchronized List<FileStatus> status(Map<String, String> committed) {
        if (committed == null) {
            throw new IllegalArgumentException("快照不得为 null");
        }
        List<FileStatus> out = new ArrayList<>();
        java.util.TreeSet<String> paths = new java.util.TreeSet<>();
        paths.addAll(files.keySet());
        paths.addAll(committed.keySet());
        for (String path : paths) {
            String work = files.get(path);
            String head = committed.get(path);
            if (work != null && head != null) {
                if (!work.equals(head)) {
                    out.add(new FileStatus(path, Status.MODIFIED));
                }
            } else if (work != null) {
                out.add(new FileStatus(path, Status.ADDED));
            } else {
                out.add(new FileStatus(path, Status.DELETED));
            }
        }
        return out;
    }

    /** checkout：恢复快照（全量替换工作区） */
    public synchronized void checkout(Map<String, String> committed) {
        if (committed == null) {
            throw new IllegalArgumentException("快照不得为 null");
        }
        this.files = new LinkedHashMap<>(committed);
    }

    /** 文件读取 */
    public synchronized String read(String path) {
        String content = files.get(path);
        if (content == null) {
            throw new IllegalArgumentException("文件不存在：" + path);
        }
        return content;
    }

    /** 文件数 */
    public synchronized int size() {
        return files.size();
    }
}
