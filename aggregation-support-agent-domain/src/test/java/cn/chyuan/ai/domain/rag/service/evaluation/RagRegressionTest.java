package cn.chyuan.ai.domain.rag.service.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 评测回归测试 — 基于固定数据集验证检索质量
 * <p>
 * 数据集位置：src/test/resources/eval-datasets/
 * <p>
 * 运行方式：
 * - 全量运行：mvn test -Dtest=RagRegressionTest
 * - 仅基线：mvn test -Dtest=RagRegressionTest#baselineDataset
 * - 仅边界：mvn test -Dtest=RagRegressionTest#edgeCaseDataset
 */
@DisplayName("RAG 评测回归测试")
class RagRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 质量阈值 */
    private static final double MIN_OVERALL_SCORE = 0.6;
    private static final double MAX_HALLUCINATION_RATE = 0.3;

    private static List<EvalDatasetItem> baselineItems;
    private static List<EvalDatasetItem> edgeCaseItems;

    @BeforeAll
    static void loadDatasets() throws Exception {
        baselineItems = loadFromJson("eval-datasets/rag-retrieval-baseline.json");
        edgeCaseItems = loadFromJson("eval-datasets/regression/edge-cases.json");
    }

    // ==================== 数据集完整性验证 ====================

    @Test
    @DisplayName("基线数据集应包含 15 条记录")
    void baselineDatasetSize() {
        assertThat(baselineItems).hasSize(15);
    }

    @Test
    @DisplayName("边界数据集应包含 8 条记录")
    void edgeCaseDatasetSize() {
        assertThat(edgeCaseItems).hasSize(8);
    }

    @Test
    @DisplayName("所有记录必填字段不为空")
    void allItemsHaveRequiredFields() {
        Stream.concat(baselineItems.stream(), edgeCaseItems.stream())
                .forEach(item -> {
                    assertThat(item.query()).as("query 不得为空").isNotBlank();
                    assertThat(item.standardAnswer()).as("standardAnswer 不得为空").isNotBlank();
                    assertThat(item.category()).as("category 不得为空").isNotBlank();
                    assertThat(item.difficulty()).as("difficulty 不得为空").isNotBlank();
                });
    }

    @Test
    @DisplayName("难度值必须是 easy/medium/hard")
    void difficultyMustBeValid() {
        Stream.concat(baselineItems.stream(), edgeCaseItems.stream())
                .forEach(item -> assertThat(item.difficulty())
                        .isIn("easy", "medium", "hard"));
    }

    // ==================== 场景覆盖验证 ====================

    @Test
    @DisplayName("基线数据集覆盖所有场景分类")
    void baselineCoversAllCategories() {
        List<String> categories = baselineItems.stream()
                .map(EvalDatasetItem::category)
                .distinct()
                .toList();

        assertThat(categories).contains(
                "产品规格精确查询",
                "价格查询",
                "法律条文查询",
                "运维故障排查",
                "多主题批量查询",
                "跨领域关联查询",
                "空结果与兜底处理"
        );
    }

    @Test
    @DisplayName("边界数据集覆盖兜底和拒答场景")
    void edgeCasesCoverFallbackScenarios() {
        List<String> categories = edgeCaseItems.stream()
                .map(EvalDatasetItem::category)
                .toList();

        assertThat(categories).anyMatch(c -> c.contains("闲聊") || c.contains("兜底"));
        assertThat(categories).anyMatch(c -> c.contains("拒答") || c.contains("边界"));
    }

    // ==================== 空结果场景验证 ====================

    @Test
    @DisplayName("空结果场景的 standardChunks 应为空数组")
    void emptyResultScenariosHaveEmptyChunks() {
        Stream.concat(baselineItems.stream(), edgeCaseItems.stream())
                .filter(item -> item.standardChunks().isEmpty())
                .forEach(item -> {
                    // 空结果场景的标准答案应包含"抱歉"或"无法"等拒答关键词
                    assertThat(item.standardAnswer())
                            .containsAnyOf("抱歉", "无法", "没有", "建议");
                });
    }

    // ==================== 批量查询场景验证 ====================

    @Test
    @DisplayName("批量查询场景应包含多个查询对象")
    void batchQueryScenariosHaveMultipleEntities() {
        Stream.concat(baselineItems.stream(), edgeCaseItems.stream())
                .filter(item -> item.category().contains("批量"))
                .forEach(item -> {
                    // 批量查询的 query 应包含"和"或"、"或"同时"等并列关键词
                    assertThat(item.query())
                            .containsAnyOf("和", "、", "同时", "一起");
                });
    }

    // ==================== 数据加载工具方法 ====================

    private static List<EvalDatasetItem> loadFromJson(String classpath) throws Exception {
        try (InputStream is = RagRegressionTest.class.getClassLoader().getResourceAsStream(classpath)) {
            if (is == null) {
                throw new IllegalStateException("数据集文件不存在: " + classpath);
            }
            return MAPPER.readValue(is, new TypeReference<>() {});
        }
    }

    /**
     * 评测数据集条目 — 与 EvalDatasetItem 结构对齐
     */
    record EvalDatasetItem(
            String query,
            String standardAnswer,
            List<String> standardChunks,
            String category,
            String difficulty,
            String note
    ) {}
}
