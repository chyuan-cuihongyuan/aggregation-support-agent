package cn.chyuan.ai.domain.templatekernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模板内核域单测（工单 0626-0632 BW1-BW7，jinja2 思想）。
 * 词法与 AST/表达式过滤器/控制流/宏/继承/自动转义/沙箱。
 */
class TemplateKernelTest {

    private static TemplatePort.InMemoryTemplates port() {
        return new TemplatePort.InMemoryTemplates();
    }

    @Test
    void lexerSplitsThreeKindsAndRejectsUnclosed() {
        List<TemplateLexer.Token> tokens = TemplateLexer.lex("hi {{ name }}{% if x %}ok");
        assertEquals(4, tokens.size());
        assertEquals("ok", tokens.get(3).source());
        assertEquals(TemplateLexer.Kind.TEXT, tokens.get(3).kind());
        assertEquals(TemplateLexer.Kind.TEXT, tokens.get(0).kind());
        assertEquals(TemplateLexer.Kind.VAR, tokens.get(1).kind());
        assertEquals("name", tokens.get(1).source());
        assertEquals(TemplateLexer.Kind.TAG, tokens.get(2).kind());
        assertEquals("if x", tokens.get(2).source());
        assertTrue(tokens.get(2).line() >= 1);
        assertThrows(IllegalArgumentException.class, () -> TemplateLexer.lex("bad {{ name"));
        assertThrows(IllegalArgumentException.class, () -> TemplateLexer.lex("bad {% if x"));
        assertThrows(IllegalArgumentException.class, () -> TemplateLexer.lex(null));
    }

    @Test
    void expressionsEvaluateLiteralsFiltersAndLogic() {
        Expressions expressions = new Expressions(Filters.builtins(), false, (t, n) -> null);
        Map<String, Object> data = Map.of(
                "name", "Agent",
                "count", 3,
                "items", List.of("a", "b", "c"));
        Expressions.Scope scope = data::get;
        assertEquals("AGENT", expressions.eval("name | upper", scope));
        assertEquals("agent", expressions.eval("name | lower", scope));
        assertEquals(5L, expressions.eval("count + 2", scope));
        assertEquals(6L, expressions.eval("count * 2", scope));
        assertEquals(3, expressions.eval("items | length", scope));
        assertEquals("fallback", expressions.eval("missing | default('fallback')", scope));
        assertEquals("a,b,c", expressions.eval("items | join(',')", scope));
        assertEquals(3.14, ((Number) expressions.eval("3.1415 | round(2)", scope)).doubleValue(), 1e-9);
        assertEquals(Boolean.TRUE, expressions.eval("count > 2 and name == 'Agent'", scope));
        assertEquals(Boolean.TRUE, expressions.eval("not missing", scope));
        assertNull(expressions.eval("missing", scope));
        assertThrows(IllegalArgumentException.class,
                () -> expressions.eval("name | nosuch", scope));
        assertThrows(IllegalArgumentException.class,
                () -> expressions.eval("1 +", scope));
    }

    @Test
    void controlFlowIteratesAndBranches() {
        String template = "{% for item in items %}{{ loop.index }}:{{ item }};{% endfor %}"
                + "{% if count > 2 %}多{% elif count == 2 %}中{% else %}少{% endif %}"
                + "{% for x in empty %}NEVER{% endfor %}";
        String out = port().render(template, Map.of(
                "items", List.of("a", "b"), "count", 3, "empty", List.of()));
        assertEquals("1:a;2:b;多", out);
        assertEquals("1:a;2:b;少",
                port().render(template, Map.of("items", List.of("a", "b"), "count", 1, "empty", List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> port().render("{% for %}x{% endfor %}", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> port().render("{% if x %}unclosed", Map.of()));
    }

    @Test
    void macrosDefineCallAndDefaultParams() {
        String template = "{% macro badge(text, mark='!') %}[{{ text }}{{ mark }}]{% endmacro %}"
                + "{{ badge('核心') }}{{ badge('附加', '?') }}";
        assertEquals("[核心!][附加?]", port().render(template, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> port().render("{% macro m(a) %}{{ a }}{% endmacro %}{{ m(1,2) }}", Map.of()));
    }

    @Test
    void inheritanceOverridesBlocksAndParent() {
        String base = "<html><title>{% block title %}默认标题{% endblock %}</title>"
                + "<body>{% block body %}默认体{% endblock %}</body></html>";
        String child = "{% extends \"base\" %}"
                + "{% block title %}子页-{{ parent() }}{% endblock %}"
                + "{% block body %}子内容{% endblock %}";
        String out = port().renderWithInheritance(child, Map.of(), name -> base);
        assertEquals("<html><title>子页-默认标题</title><body>子内容</body></html>", out);

        String grandchild = "{% extends \"mid\" %}{% block body %}孙内容{% endblock %}";
        String mid = "{% extends \"base\" %}{% block body %}子内容-{{ parent() }}{% endblock %}";
        String multi = port().renderWithInheritance(grandchild, Map.of(),
                name -> name.equals("mid") ? mid : base);
        assertEquals("<html><title>默认标题</title><body>孙内容</body></html>", multi,
                "孙层覆盖子层，未被覆盖的子层块不参与");

        assertThrows(IllegalArgumentException.class,
                () -> port().renderWithInheritance("{% extends \"self\" %}", Map.of(), name -> "{% extends \"self\" %}"));
        assertThrows(IllegalArgumentException.class,
                () -> port().renderWithInheritance(child, Map.of(), name -> null));
    }

    @Test
    void autoescapeEscapesAndSafeMarksSurvive() {
        String template = "{{ html }}{{ html | safe }}";
        String out = port().render(template, Map.of("html", "<b>&\"'</b>"));
        assertEquals("&lt;b&gt;&amp;&quot;&#39;&lt;/b&gt;<b>&\"'</b>", out);

        String raw = port().render(template, Map.of("html", "<i>x</i>"), false);
        assertEquals("<i>x</i><i>x</i>", raw, "转义关闭直出");
        assertEquals("&lt;", Escaping.escape("<"));
        assertEquals("", Escaping.escape(null));
        assertEquals("", new Escaping(true).output(null));
        assertEquals("42", new Escaping(true).output(42L));
    }

    @Test
    void sandboxWhitelistsPropertyAccessAndLimits() {
        TemplateSandbox sandbox = TemplateSandbox.defaults();
        assertThrows(IllegalArgumentException.class, () -> sandbox.checkSource("x".repeat(300 * 1024)));
        sandbox.checkSource("ok");

        Map<String, Object> mapLike = Map.of("key", "值");
        assertEquals("值", sandbox.property(mapLike, "key"));

        record Shape(String name, int size) {
            public String label() {
                return name + ":" + size;
            }
        }
        assertEquals("圆", sandbox.property(new Shape("圆", 3), "name"));
        assertEquals(3, ((Number) sandbox.property(new Shape("圆", 3), "size")).intValue());
        assertThrows(IllegalArgumentException.class,
                () -> sandbox.property(new Shape("圆", 3), "label"));

        assertThrows(IllegalArgumentException.class, () -> sandbox.checkFilter("evil", 0));

        StringBuilder deep = new StringBuilder("{% if a %}");
        deep.append("x".repeat(10));
        deep.append("{% endif %}");
        String normal = deep.toString();
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            nested.append("{% if a %}");
        }
        nested.append("x");
        for (int i = 0; i < 80; i++) {
            nested.append("{% endif %}");
        }
        TemplateRenderer renderer = new TemplateRenderer(
                new Expressions(TemplatePort.DEFAULT_FILTERS, false, sandbox::property), new Escaping(false));
        for (TemplateRenderer.Node node : renderer.parse(TemplateLexer.lex(normal))) {
            sandbox.checkDepth(node);
        }
        List<TemplateRenderer.Node> deepAst = renderer.parse(TemplateLexer.lex(nested.toString()));
        assertThrows(IllegalArgumentException.class, () -> sandbox.checkDepth(deepAst.get(0)));
    }

    @Test
    void strictModeRejectsUnknownVariables() {
        assertThrows(IllegalArgumentException.class,
                () -> port().renderStrict("{{ missing }}", Map.of()));
        assertEquals("有", port().renderStrict("{{ known }}", Map.of("known", "有")));
    }
}
