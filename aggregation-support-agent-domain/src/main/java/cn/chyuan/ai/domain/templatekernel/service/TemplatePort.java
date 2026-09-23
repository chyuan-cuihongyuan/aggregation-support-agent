package cn.chyuan.ai.domain.templatekernel.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板端口+组合管线（工单 0633 BW8）。
 * TemplatePort（模板源+数据上下文→渲染文本）组合管线：
 * 沙箱校验→词法→AST→求值渲染（for/if/宏/继承/转义/沙箱全链）；
 * 与 textkernel 只读联动（变量上下文可选来自文本分析结果，
 * 泛型入参不 import textkernel，不改任何类）/
 * template-kernel.enabled 默认关（开启才改变行为）。
 */
public interface TemplatePort {

    /** 默认过滤器集（沙箱白名单基线） */
    Map<String, Expressions.Filter> DEFAULT_FILTERS = Filters.builtins();

    /** 渲染（自动转义默认开） */
    String render(String template, Map<String, Object> data);

    /** 渲染（转义可配） */
    String render(String template, Map<String, Object> data, boolean autoescape);

    /** 继承渲染（loader 供父模板源） */
    String renderWithInheritance(String template, Map<String, Object> data,
                                 java.util.function.Function<String, String> loader);

    /** 严格模式（未知变量拒绝） */
    String renderStrict(String template, Map<String, Object> data);

    /** textkernel 联动形态：分析结果（词→计数）转模板变量上下文 */
    Map<String, Object> toContext(Map<String, ? extends Number> termFrequencies,
                                  Map<String, ? extends Number> scores);

    /** 内存假实现：词法→AST→渲染全链（沙箱+转义+过滤器白名单） */
    class InMemoryTemplates implements TemplatePort {

        @Override
        public synchronized String render(String template, Map<String, Object> data) {
            return render(template, data, true);
        }

        @Override
        public synchronized String render(String template, Map<String, Object> data, boolean autoescape) {
            TemplateSandbox sandbox = TemplateSandbox.defaults();
            sandbox.checkSource(template);
            TemplateRenderer renderer = newRenderer(autoescape, sandbox);
            List<TemplateRenderer.Node> ast = renderer.parse(TemplateLexer.lex(template));
            for (TemplateRenderer.Node node : ast) {
                sandbox.checkDepth(node);
            }
            return renderer.render(ast, data == null ? Map.of() : data);
        }

        @Override
        public synchronized String renderWithInheritance(String template, Map<String, Object> data,
                                                          java.util.function.Function<String, String> loader) {
            if (loader == null) {
                throw new IllegalArgumentException("父模板加载器不得为 null");
            }
            TemplateSandbox sandbox = TemplateSandbox.defaults();
            sandbox.checkSource(template);
            TemplateRenderer renderer = newRenderer(true, sandbox);
            java.util.function.Function<String, List<TemplateRenderer.Node>> astLoader =
                    name -> {
                        String source = loader.apply(name);
                        if (source == null) {
                            throw new IllegalArgumentException("父模板缺失：" + name);
                        }
                        return renderer.parse(TemplateLexer.lex(source));
                    };
            List<TemplateRenderer.Node> templateAst = renderer.parse(TemplateLexer.lex(template));
            for (TemplateRenderer.Node node : templateAst) {
                sandbox.checkDepth(node);
            }
            return renderer.renderWithInheritance(templateAst, astLoader,
                    data == null ? Map.of() : data);
        }

        @Override
        public synchronized String renderStrict(String template, Map<String, Object> data) {
            TemplateSandbox sandbox = TemplateSandbox.defaults();
            sandbox.checkSource(template);
            Expressions expressions = new Expressions(DEFAULT_FILTERS, true, sandbox::property);
            TemplateRenderer renderer = new TemplateRenderer(expressions, new Escaping(true));
            List<TemplateRenderer.Node> ast = renderer.parse(TemplateLexer.lex(template));
            return renderer.render(ast, data == null ? Map.of() : data);
        }

        @Override
        public synchronized Map<String, Object> toContext(Map<String, ? extends Number> termFrequencies,
                                                          Map<String, ? extends Number> scores) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (termFrequencies != null) {
                out.put("terms", new LinkedHashMap<String, Object>(termFrequencies));
                out.put("top_terms", termFrequencies.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue().longValue(), a.getValue().longValue()))
                        .limit(10)
                        .map(Map.Entry::getKey)
                        .toList());
                long total = termFrequencies.values().stream().mapToLong(Number::longValue).sum();
                out.put("total_terms", total);
            }
            if (scores != null) {
                out.put("scores", new LinkedHashMap<String, Object>(scores));
            }
            return out;
        }

        private TemplateRenderer newRenderer(boolean autoescape, TemplateSandbox sandbox) {
            Map<String, Expressions.Filter> whitelisted = new LinkedHashMap<>();
            DEFAULT_FILTERS.forEach((name, filter) -> {
                if (sandbox.filterWhitelist().contains(name)) {
                    whitelisted.put(name, filter);
                }
            });
            Expressions expressions = new Expressions(whitelisted, false, sandbox::property);
            return new TemplateRenderer(expressions, new Escaping(autoescape));
        }
    }
}
