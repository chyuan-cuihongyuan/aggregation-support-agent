# RAG 评测数据集

## 概述

本目录包含 RAG 检索系统的评测数据集，用于质量回归测试和性能基准对比。

## 目录结构

```
eval-datasets/
├── rag-retrieval-baseline.json    # RAG 检索基线数据集（15 条，覆盖 8 类场景）
├── regression/
│   └── edge-cases.json            # 边界场景数据集（8 条，覆盖兜底/拒答/批量）
└── README.md                      # 本文件
```

## 数据集格式

每条记录包含以下字段：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string | ✅ | 用户查询文本 |
| `standardAnswer` | string | ✅ | 标准答案（人工编写） |
| `standardChunks` | string[] | ✅ | 标准检索文档片段（黄金集），空数组表示期望无结果 |
| `category` | string | ✅ | 场景分类 |
| `difficulty` | string | ✅ | 难度：easy / medium / hard |
| `note` | string | ❌ | 补充说明（如期望的 mode、工具调用等） |

## 场景分类

| 分类 | 条数 | 说明 |
|------|------|------|
| 产品规格精确查询 | 2 | 型号/接口/功耗等精确参数 |
| 产品规格对比查询 | 1 | 两代产品差异对比 |
| 价格查询 | 1 | 促销/到手价查询 |
| 法律条文查询 | 3 | 条款引用+解释 |
| 运维故障排查 | 3 | 排查步骤+解决方案 |
| 多主题批量查询 | 2 | batch 模式测试 |
| 跨领域关联查询 | 1 | 多配件兼容性+预算 |
| 空结果与兜底处理 | 1 | 知识库外问题 |
| 闲聊/能力边界 | 2 | mode=none 测试 |

## 使用方式

### 1. 单元测试（Mock 模式）

```java
// 从 classpath 加载数据集
List<EvalDatasetItem> items = loadFromJson("eval-datasets/rag-retrieval-baseline.json");

// Mock 全链路，验证评测逻辑
for (EvalDatasetItem item : items) {
    EvaluationResult result = evaluator.evaluate(item.getQuery(), item.getStandardAnswer(), item.getStandardChunks());
    assertTrue(result.isAcceptable(), "Query: " + item.getQuery());
}
```

### 2. 集成测试（真实服务）

```java
// 调用真实 RAG 检索 + 评测
for (EvalDatasetItem item : items) {
    SearchOutcomeVO outcome = ragService.searchWithTrace(item.getQuery(), 10, scope);
    EvaluationResult result = evaluator.evaluate(item.getQuery(), outcome.getAnswer(), outcome.getChunks());
    assertTrue(result.getOverallScore() >= 0.6, "Query: " + item.getQuery());
}
```

### 3. 可观测性平台评测

通过 `agent-rag-observability-server` 的评测 API：
1. 创建数据集：POST /api/eval/dataset
2. 创建评测任务：POST /api/eval/task
3. 查看评测结果：GET /api/eval/task/{taskId}/result
4. 版本对比：GET /api/eval/compare?task1=xxx&task2=xxx

## 质量阈值

| 指标 | 阈值 | 说明 |
|------|------|------|
| overallScore | ≥ 0.6 | 综合分数（忠实度 40% + 相关度 40% + 非幻觉 20%） |
| hallucinationRate | < 0.3 | 幻觉比例 |
| Recall@5 | ≥ 0.7 | 检索召回率 |
| MRR | ≥ 0.5 | 平均倒数排名 |

## 维护指南

- **新增场景**：在对应 JSON 文件中添加条目，确保 `standardChunks` 来自真实知识库内容
- **更新基线**：知识库内容变更后，重新运行评测并更新 `standardAnswer`
- **版本管理**：数据集文件纳入 Git，每次变更需 commit 说明原因
