package cn.chyuan.ai.domain.agent.support.prompt;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板注册表（借鉴 langfuse prompt management：name + version + content）。
 *
 * <p>所有常驻 prompt 模板在定义处以 name+version 登记于此，消费方按 name 取当前版本；
 * 迭代 prompt 时注册新版本（旧版本保留，评测可回放对齐历史行为）。
 */
public final class PromptRegistry {

    /** name -> version -> content */
    private static final Map<String, Map<String, String>> PROMPTS = new ConcurrentHashMap<>();

    /** name -> 当前（最新）版本号 */
    private static final Map<String, String> CURRENT_VERSION = new ConcurrentHashMap<>();

    private PromptRegistry() {
    }

    /**
     * 注册一个 prompt 版本；重复注册同名同版本（幂等）要求内容一致，否则抛出以暴露漂移。
     */
    public static synchronized void register(String name, String version, String content) {
        if (name == null || name.isBlank() || version == null || version.isBlank() || content == null) {
            throw new IllegalArgumentException("prompt name/version/content must not be blank");
        }
        Map<String, String> versions =
                PROMPTS.computeIfAbsent(name, k -> new ConcurrentHashMap<>());
        String existing = versions.putIfAbsent(version, content);
        if (existing != null && !existing.equals(content)) {
            throw new IllegalStateException(
                    "prompt version conflict: " + name + "@" + version + " already registered with different content");
        }
        CURRENT_VERSION.merge(name, version, (oldV, newV) -> compareVersions(newV, oldV) > 0 ? newV : oldV);
    }

    /** 取当前版本的 prompt 内容；未注册返回 empty。 */
    public static Optional<String> current(String name) {
        String version = CURRENT_VERSION.get(name);
        if (version == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PROMPTS.get(name).get(version));
    }

    /** 按精确版本取 prompt（评测回放用）。 */
    public static Optional<String> get(String name, String version) {
        Map<String, String> versions = PROMPTS.get(name);
        if (versions == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(versions.get(version));
    }

    public static String currentVersion(String name) {
        return CURRENT_VERSION.get(name);
    }

    /** v2 > v10 语义正确：剥离 v 前缀后按点分段数值比较，非数值段按字典序兜底。 */
    static int compareVersions(String a, String b) {
        String[] pa = stripPrefix(a).split("\\.");
        String[] pb = stripPrefix(b).split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int cmp = segment(pa, i).compareTo(segment(pb, i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static String stripPrefix(String version) {
        return (version.length() > 1 && (version.charAt(0) == 'v' || version.charAt(0) == 'V'))
                ? version.substring(1) : version;
    }

    private static String segment(String[] parts, int i) {
        if (i >= parts.length) {
            return "0";
        }
        return parts[i].matches("\\d+") ? String.format("%10s", parts[i]).replace(' ', '0') : parts[i];
    }
}
