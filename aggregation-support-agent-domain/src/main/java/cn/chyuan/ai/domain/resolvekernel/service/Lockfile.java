package cn.chyuan.ai.domain.resolvekernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 锁文件（工单 0668 CA6，uv 思想）。
 * 解析解冻结（包/版本/依赖闭包/SHA-256 内容指纹）/确定性输出（字典序行协议）/
 * 重解析一致性（parse→equals）/增量最小变更清单（增删改）。
 */
public final class Lockfile {

    /** 冻结条目：包名/版本/直接依赖名/内容指纹 */
    public record Locked(String name, String version, List<String> deps, String digest) {
    }

    /** 变更清单：added/removed/changed（同名不同版本或依赖） */
    public record Diff(List<Locked> added, List<Locked> removed, List<Locked[]> changed) {
    }

    private final TreeMap<String, Locked> entries = new TreeMap<>();

    public static Lockfile from(Map<String, String> assigned, DependencySource source) {
        Lockfile lock = new Lockfile();
        for (Map.Entry<String, String> e : new TreeMap<>(assigned).entrySet()) {
            String name = e.getKey();
            String version = e.getValue();
            List<String> deps = source.dependencies(name, version).stream()
                    .map(spec -> spec.split(" ", 2)[0])
                    .sorted()
                    .toList();
            lock.entries.put(name, new Locked(name, version, deps, sha256(name + "@" + version)));
        }
        return lock;
    }

    public static Lockfile parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("锁文件文本不得为 null");
        }
        Lockfile lock = new Lockfile();
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length != 3 || !parts[0].startsWith("==")) {
                throw new IllegalArgumentException("锁文件行协议非法：" + line);
            }
            String name = parts[0].substring(2);
            List<String> deps = parts[1].isEmpty() ? List.of() : List.of(parts[1].split(",", -1));
            lock.entries.put(name, new Locked(name, Semver.of(parts[2]).toString(), deps, sha256(name + "@" + parts[2])));
        }
        return lock;
    }

    public String toCanonicalText() {
        StringBuilder sb = new StringBuilder();
        for (Locked locked : entries.values()) {
            sb.append("==").append(locked.name())
                    .append('|').append(String.join(",", locked.deps()))
                    .append('|').append(locked.version())
                    .append('\n');
        }
        return sb.toString();
    }

    public List<Locked> sorted() {
        return List.copyOf(entries.values());
    }

    public Locked get(String name) {
        return entries.get(name);
    }

    public int size() {
        return entries.size();
    }

    public static Diff diff(Lockfile oldLock, Lockfile newLock) {
        if (oldLock == null || newLock == null) {
            throw new IllegalArgumentException("比对双方不得为 null");
        }
        List<Locked> added = new ArrayList<>();
        List<Locked> removed = new ArrayList<>();
        List<Locked[]> changed = new ArrayList<>();
        for (Locked cur : newLock.entries.values()) {
            Locked prev = oldLock.entries.get(cur.name());
            if (prev == null) {
                added.add(cur);
            } else if (!prev.version().equals(cur.version()) || !prev.deps().equals(cur.deps())) {
                changed.add(new Locked[]{prev, cur});
            }
        }
        for (Locked prev : oldLock.entries.values()) {
            if (newLock.entries.get(prev.name()) == null) {
                removed.add(prev);
            }
        }
        return new Diff(added, removed, changed);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Lockfile o && entries.equals(o.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 依赖来源只读接口（解析器与锁文件共用的候选面） */
    public interface DependencySource {
        List<String> versions(String name);

        List<String> dependencies(String name, String version);
    }

    @Override
    public String toString() {
        return "Lockfile" + entries.values() + Objects.hashCode(entries);
    }
}
