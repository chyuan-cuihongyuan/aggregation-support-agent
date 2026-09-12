package cn.chyuan.ai.domain.crew.service;

import cn.chyuan.ai.domain.crew.model.AgentRole;
import cn.chyuan.ai.domain.crew.model.HandoffMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色化 Agent 单测（工单 0213 AC1）：值对象校验/白名单越权/注册表唯一/人设提示。
 */
class AgentRoleTest {

    @Test
    void 值对象构造校验() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentRole("", "g", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentRole("r", " ", null, null));
        // backstory/白名单可空
        AgentRole role = new AgentRole("研究员", "找资料", null, null);
        assertEquals(Set.of(), role.toolWhitelist());
        assertFalse(role.toolAllowed("search"));
    }

    @Test
    void 工具白名单越权拒绝() {
        AgentRole role = new AgentRole("检索员", "查资料", "负责检索", Set.of("search", "browse"));
        assertTrue(role.toolAllowed("search"));
        role.assertToolAllowed("browse");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> role.assertToolAllowed("delete_all"));
        assertTrue(ex.getMessage().contains("无权使用工具"));
        // 空白名单全禁
        assertFalse(new AgentRole("r", "g", null, Set.of()).toolAllowed("search"));
    }

    @Test
    void 注册表唯一性() {
        AgentRoleRegistry registry = new AgentRoleRegistry();
        registry.register(new AgentRole("a", "g1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new AgentRole("a", "g2", null, null)));
        assertEquals("g1", registry.get("a").goal());
        assertEquals(1, registry.size());
        assertEquals(1, registry.names().size());
    }

    @Test
    void 人设提示组装() {
        AgentRole full = new AgentRole("写手", "写稿", "十年编辑", Set.of("pen"));
        String prompt = full.personaPrompt();
        assertTrue(prompt.contains("写手"));
        assertTrue(prompt.contains("写稿"));
        assertTrue(prompt.contains("十年编辑"));
        assertTrue(prompt.contains("pen"));
        // 无背景无工具：不出现空段
        String minimal = new AgentRole("r", "g", null, null).personaPrompt();
        assertFalse(minimal.contains("背景：。"));
        assertFalse(minimal.contains("可用工具"));
    }

    @Test
    void 交接消息构造与注册校验() {
        assertThrows(IllegalArgumentException.class,
                () -> new HandoffMessage("", "b", "t", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HandoffMessage("a", "b", "", null, null));
        AgentRoleRegistry registry = new AgentRoleRegistry();
        registry.register(new AgentRole("a", "g", null, null));
        // to 未注册
        assertThrows(IllegalArgumentException.class,
                () -> new HandoffMessage("a", "ghost", "t", "s", null)
                        .assertRegistered(registry));
        new HandoffMessage("a", "a", "t", "s", List.of("k1")).assertRegistered(registry);
    }
}
