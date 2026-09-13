package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.service.BlueprintService.BlueprintStore;
import cn.chyuan.ai.domain.workflow.service.BlueprintService.BlueprintTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 蓝图模板服务单测（工单 0268 AI1）：保存校验/CRUD/分类与标签检索。
 */
class BlueprintServiceTest {

    private static final String GRAPH_JSON = """
            {"schemaVersion":1,"name":"tpl","nodes":[
              {"id":"a","type":"TASK","config":{"echo":"${msg}"}},
              {"id":"b","type":"TASK","config":{}}],"edges":[{"from":"a","to":"b"}]}
            """;

    private ConcurrentHashMap<Long, BlueprintTemplate> rows;
    private BlueprintService service;

    @BeforeEach
    void setUp() {
        rows = new ConcurrentHashMap<>();
        service = new BlueprintService(new MemoryStore(rows));
    }

    @Test
    void 保存校验与自增ID() {
        BlueprintTemplate saved = service.save(new BlueprintTemplate(null, "bp1", "描述", "etl",
                Set.of("rag", "daily"), GRAPH_JSON, null, "op"));
        assertEquals(1L, saved.id());
        assertEquals("etl", saved.category());
        assertEquals(2, saved.tags().size());
        // 坏图（环）拒绝
        String cyclic = """
                {"schemaVersion":1,"name":"bad","nodes":[
                  {"id":"a","type":"TASK","config":{}},
                  {"id":"b","type":"TASK","config":{}}],
                 "edges":[{"from":"a","to":"b"},{"from":"b","to":"a"}]}
                """;
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new BlueprintTemplate(null, "bad", null, null, null, cyclic, null, "op")));
        // 非法 DSL 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new BlueprintTemplate(null, "bad2", null, null, null, "not-json", null, "op")));
    }

    @Test
    void 更新删除与检索() {
        BlueprintTemplate saved = service.save(new BlueprintTemplate(null, "bp1", null, "etl",
                Set.of("daily"), GRAPH_JSON, null, "op"));
        service.update(new BlueprintTemplate(saved.id(), "bp1-renamed", "新描述", "etl",
                Set.of("daily"), GRAPH_JSON, null, "op"));
        assertEquals("bp1-renamed", service.get(saved.id()).name());
        assertEquals(1, service.listByCategory("etl").size());
        assertEquals(0, service.listByCategory("other").size());
        assertEquals(1, service.listByTag("daily").size());
        // 不存在拒绝
        assertThrows(IllegalArgumentException.class, () -> service.get(99));
        assertThrows(IllegalArgumentException.class, () -> service.update(
                new BlueprintTemplate(99L, "x", null, null, null, GRAPH_JSON, null, "op")));
        service.delete(saved.id());
        assertNull(rows.get(saved.id()));
        assertThrows(IllegalArgumentException.class, () -> service.delete(99));
    }

    @Test
    void 参数schema非法拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new BlueprintTemplate(null, "bp2", null, null, null,
                        GRAPH_JSON, "{\"p\":\"bogus\"}", "op")));
    }

    /** 内存蓝图存储 */
    private static class MemoryStore implements BlueprintStore {
        private final ConcurrentHashMap<Long, BlueprintTemplate> rows;

        MemoryStore(ConcurrentHashMap<Long, BlueprintTemplate> rows) {
            this.rows = rows;
        }

        @Override
        public void insert(BlueprintTemplate template) {
            rows.put(template.id(), template);
        }

        @Override
        public void update(BlueprintTemplate template) {
            rows.put(template.id(), template);
        }

        @Override
        public void deleteById(long id) {
            rows.remove(id);
        }

        @Override
        public BlueprintTemplate findById(long id) {
            return rows.get(id);
        }

        @Override
        public List<BlueprintTemplate> listAll() {
            return new ArrayList<>(rows.values());
        }
    }
}
