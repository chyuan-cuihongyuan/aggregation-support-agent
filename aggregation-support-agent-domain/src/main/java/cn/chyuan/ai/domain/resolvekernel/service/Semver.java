package cn.chyuan.ai.domain.resolvekernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义版本（工单 0663 CA1，uv 思想）。
 * major.minor.patch+预发布标识+构建元数据解析/全序比较（无预发布 > 有预发布，
 * 预发布标识数字段数值比较且低于字母段，段数多者大）/构建元数据不参与比较/
 * 非法版本拒绝（核心段缺位、前导零、空标识）。
 */
public final class Semver implements Comparable<Semver> {

    /** 区间分析哨兵：最小/最大版本 */
    public static final Semver MIN = of("0.0.0-alpha");
    public static final Semver MAX = new Semver(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, List.of(), "");

    private final int major;
    private final int minor;
    private final int patch;
    private final List<String> prerelease;
    private final String build;

    Semver(int major, int minor, int patch, List<String> prerelease, String build) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
        this.build = build;
    }

    public static Semver of(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("版本不得为空");
        }
        String rest = text.trim();
        String build = "";
        int plus = rest.indexOf('+');
        if (plus >= 0) {
            build = rest.substring(plus + 1);
            if (build.isEmpty()) {
                throw new IllegalArgumentException("构建元数据不得为空：" + text);
            }
            rest = rest.substring(0, plus);
        }
        List<String> prerelease = List.of();
        int dash = rest.indexOf('-');
        if (dash >= 0) {
            String pre = rest.substring(dash + 1);
            if (pre.isEmpty()) {
                throw new IllegalArgumentException("预发布标识不得为空：" + text);
            }
            List<String> ids = new ArrayList<>();
            for (String id : pre.split("\\.", -1)) {
                if (id.isEmpty() || !id.matches("[0-9A-Za-z-]+")) {
                    throw new IllegalArgumentException("预发布标识非法：" + text);
                }
                ids.add(id);
            }
            prerelease = List.copyOf(ids);
            rest = rest.substring(0, dash);
        }
        String[] core = rest.split("\\.", -1);
        if (core.length != 3) {
            throw new IllegalArgumentException("核心版本须三段：" + text);
        }
        int[] nums = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!core[i].matches("0|[1-9][0-9]*")) {
                throw new IllegalArgumentException("核心段非法（前导零或非数字）：" + text);
            }
            nums[i] = Integer.parseInt(core[i]);
        }
        return new Semver(nums[0], nums[1], nums[2], prerelease, build);
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    public List<String> prerelease() {
        return prerelease;
    }

    @Override
    public int compareTo(Semver o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, o.minor);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(patch, o.patch);
        if (c != 0) {
            return c;
        }
        if (prerelease.isEmpty() && o.prerelease.isEmpty()) {
            return 0;
        }
        if (prerelease.isEmpty()) {
            return 1;
        }
        if (o.prerelease.isEmpty()) {
            return -1;
        }
        int len = Math.max(prerelease.size(), o.prerelease.size());
        for (int i = 0; i < len; i++) {
            if (i >= prerelease.size()) {
                return -1;
            }
            if (i >= o.prerelease.size()) {
                return 1;
            }
            String x = prerelease.get(i);
            String y = o.prerelease.get(i);
            boolean xn = x.matches("[0-9]+");
            boolean yn = y.matches("[0-9]+");
            if (xn && yn) {
                c = Long.compare(Long.parseLong(x), Long.parseLong(y));
            } else if (xn) {
                return -1;
            } else if (yn) {
                return 1;
            } else {
                c = x.compareTo(y);
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Semver o && compareTo(o) == 0 && build.equals(o.build);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * major + minor) + patch;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append(major).append('.').append(minor).append('.').append(patch);
        if (!prerelease.isEmpty()) {
            sb.append('-').append(String.join(".", prerelease));
        }
        if (!build.isEmpty()) {
            sb.append('+').append(build);
        }
        return sb.toString();
    }
}
