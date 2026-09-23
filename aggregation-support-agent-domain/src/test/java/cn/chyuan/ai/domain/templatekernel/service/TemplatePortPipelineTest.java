package cn.chyuan.ai.domain.templatekernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模板端口组合管线测试（工单 0633 BW8）。
 * template-kernel.enabled 默认关（开启才改变行为）；
 * 与 textkernel 只读联动：变量上下文可选来自文本分析结果。
 */
class TemplatePortPipelineTest {

    @Test
    void fullChainRendersPromptTemplate() {
        TemplatePort port = new TemplatePort.InMemoryTemplates();
        String template = "角色：{{ role }}\n"
                + "{% for term in top_terms %}关键词{{ loop.index }}：{{ term }}\n{% endfor %}"
                + "{% if total_terms > 3 %}词量：{{ total_terms }}{% endif %}";
        Map<String, Object> context = Map.of(
                "role", "检索问答助手",
                "top_terms", List.of("向量", "召回", "重排"),
                "total_terms", 7);
        String out = port.render(template, context);
        assertEquals("角色：检索问答助手\n关键词1：向量\n关键词2：召回\n关键词3：重排\n词量：7", out);
    }

    @Test
    void escapingAndSandboxGuardUntrustedInput() {
        TemplatePort port = new TemplatePort.InMemoryTemplates();
        String out = port.render("{{ user_input }}", Map.of("user_input", "<script>alert(1)</script>"));
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", out);

        record User(String name) {
        }
        String bean = port.render("{{ user.name }}", Map.of("user", new User("楚源")));
        assertEquals("楚源", bean);
        assertThrows(IllegalArgumentException.class,
                () -> port.render("{{ user.toString }}", Map.of("user", new User("x"))));
    }

    @Test
    void inheritancePipelineRendersLayout() {
        TemplatePort port = new TemplatePort.InMemoryTemplates();
        String base = "<布局>{% block content %}默认{% endblock %}</布局>";
        String child = "{% extends \"base\" %}{% block content %}定制-{{ parent() }}{% endblock %}";
        assertEquals("<布局>定制-默认</布局>",
                port.renderWithInheritance(child, Map.of(), name -> base));
    }

    @Test
    void textkernelShapedContextFlowsIn() {
        TemplatePort port = new TemplatePort.InMemoryTemplates();
        Map<String, Object> context = port.toContext(
                Map.of("向量", 12L, "召回", 30L, "重排", 5L),
                Map.of("向量", 0.9d));
        String out = port.render("TOP1={{ top_terms | first }};"
                + "总数={{ total_terms }}", context);
        // top_terms 按词频降序：召回 30 > 向量 12
        assertTrue(out.startsWith("TOP1=召回;"));
        assertTrue(out.endsWith("总数=47"));
        assertEquals(List.of("召回", "向量", "重排"), context.get("top_terms"));
    }

    @Test
    void rejectsBrokenTemplatesAtRightStage() {
        TemplatePort port = new TemplatePort.InMemoryTemplates();
        assertThrows(IllegalArgumentException.class,
                () -> port.render("{{ name", Map.of()), "未闭合定界符");
        assertThrows(IllegalArgumentException.class,
                () -> port.render("{% unknown %}", Map.of()), "未知标签");
        assertThrows(IllegalArgumentException.class,
                () -> port.render("{{ a | evil }}", Map.of("a", 1)), "未知过滤器");
        assertThrows(IllegalArgumentException.class,
                () -> port.renderStrict("{{ nope }}", Map.of()), "严格模式未知变量");
        assertThrows(IllegalArgumentException.class,
                () -> port.renderWithInheritance("{% extends \"x\" %}", Map.of(), name -> null));
        assertThrows(IllegalArgumentException.class,
                () -> port.render(null, Map.of()));
    }
}
