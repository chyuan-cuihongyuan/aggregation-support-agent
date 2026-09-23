package cn.chyuan.ai.domain.templatekernel.service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;

/**
 * 模板沙箱（工单 0632 BW7，jinja2 sandbox 思想）。
 * 属性访问白名单（Map key/public 字段/is·getter 方法，其余拒绝）/
 * 过滤器白名单（未注册过滤器拒绝）/模板源尺寸上限+AST 递归深度上限/
 * 越限报告位置拒绝。
 */
public final class TemplateSandbox {

    /** 默认模板源尺寸上限 */
    public static final int DEFAULT_MAX_SOURCE_BYTES = 256 * 1024;
    /** 默认 AST 递归深度上限 */
    public static final int DEFAULT_MAX_DEPTH = 64;

    private final Set<String> filterWhitelist;
    private final int maxSourceBytes;
    private final int maxDepth;

    public TemplateSandbox(Set<String> filterWhitelist, int maxSourceBytes, int maxDepth) {
        this.filterWhitelist = Set.copyOf(filterWhitelist);
        this.maxSourceBytes = maxSourceBytes;
        this.maxDepth = maxDepth;
    }

    public static TemplateSandbox defaults() {
        return new TemplateSandbox(TemplatePort.DEFAULT_FILTERS.keySet(), DEFAULT_MAX_SOURCE_BYTES, DEFAULT_MAX_DEPTH);
    }

    /** 模板源校验：尺寸上限（越限报告位置拒绝） */
    public void checkSource(String template) {
        if (template == null) {
            throw new IllegalArgumentException("模板不得为 null");
        }
        if (template.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxSourceBytes) {
            throw new IllegalArgumentException("模板源超限：> " + maxSourceBytes + " 字节");
        }
    }

    /** 过滤器白名单校验 */
    public void checkFilter(String name, int position) {
        if (!filterWhitelist.contains(name)) {
            throw new IllegalArgumentException("沙箱拒绝未白名单过滤器：" + name + " 于 " + position);
        }
    }

    /** 属性访问白名单：Map key / public 字段 / is·getter；其余拒绝 */
    public Object property(Object target, String name) {
        if (target == null) {
            return null;
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(name);
        }
        try {
            Field field = target.getClass().getField(name);
            if (Modifier.isPublic(field.getModifiers())) {
                return field.get(target);
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // 落到 getter 面板
        }
        if (target.getClass().isRecord()) {
            for (java.lang.reflect.RecordComponent component : target.getClass().getRecordComponents()) {
                if (component.getName().equals(name)) {
                    try {
                        return component.getAccessor().invoke(target);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalArgumentException("record 访问失败：" + name, e);
                    }
                }
            }
        }
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String prefix : new String[]{"get", "is"}) {
            try {
                Method method = target.getClass().getMethod(prefix + suffix);
                if (Modifier.isPublic(method.getModifiers())) {
                    return method.invoke(target);
                }
            } catch (NoSuchMethodException ignored) {
                // 尝试下一前缀
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("属性访问失败：" + name, e);
            }
        }
        throw new IllegalArgumentException("沙箱拒绝属性访问：" + target.getClass().getSimpleName() + "." + name);
    }

    /** AST 深度校验（渲染前递归下降检查） */
    public void checkDepth(TemplateRenderer.Node node) {
        checkDepth(node, 1);
    }

    private void checkDepth(TemplateRenderer.Node node, int depth) {
        if (depth > maxDepth) {
            throw new IllegalArgumentException("AST 递归深度超限：" + depth + " > " + maxDepth);
        }
        if (node instanceof TemplateRenderer.IfNode ifNode) {
            for (TemplateRenderer.IfNode.Branch branch : ifNode.branches()) {
                for (TemplateRenderer.Node child : branch.body()) {
                    checkDepth(child, depth + 1);
                }
            }
        } else if (node instanceof TemplateRenderer.ForNode forNode) {
            for (TemplateRenderer.Node child : forNode.body()) {
                checkDepth(child, depth + 1);
            }
        } else if (node instanceof TemplateRenderer.MacroNode macro) {
            for (TemplateRenderer.Node child : macro.body()) {
                checkDepth(child, depth + 1);
            }
        } else if (node instanceof TemplateRenderer.BlockNode block) {
            for (TemplateRenderer.Node child : block.body()) {
                checkDepth(child, depth + 1);
            }
        }
    }

    public Set<String> filterWhitelist() {
        return filterWhitelist;
    }
}
