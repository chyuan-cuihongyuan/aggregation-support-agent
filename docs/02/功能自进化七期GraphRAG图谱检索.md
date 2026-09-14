# 功能自进化七期：GraphRAG 图谱检索与社区摘要（工单 0306-0314）

> 七期 [0302 地图](../../../issues/0302-wayfinder-map-capability-self-evolution-7.md) AM 簇收官。借鉴 microsoft/graphrag 36.0K（社区发现/社区摘要/local+global search）、run-llama/llama_index 52.2K（text_units 索引抽象），stars 经 GitHub API 2026-09-14 认证核实。全部 domain 纯函数内核，落聚合 knowledgegraph 域新子包 `knowledgegraph.graphrag`，零外部依赖。

## 一、九票能力面

| 工单 | 能力 | 内核 |
|------|------|------|
| 0306 AM1 | 图谱索引模型 | GraphIndexBuilder：段落切块 text_units（chunkSize/overlap 可配，滑窗续切）→实体/关系按归一名映射来源块（共现块优先，无共现退化并集）→规范化序列化 SHA-256 索引哈希可重放 |
| 0307 AM2 | 社区发现 | LabelPropagationCommunityDetector：标签传播确定性简化（初始标签=自身键，稳定序加权多数票，并列取最小标签），收敛/迭代上限双出口，社区ID=c_+胜出标签 |
| 0308 AM3 | 社区摘要层级 | CommunitySummarizer：C0 逐社区（ICommunitySummaryPort 端口+成员清单模板兜底）→C1/C2 按扇出分桶聚合（最多三层），叶子计数守恒；graph_index 第 17 表+graph_community 第 18 表双方言 DDL+守卫 |
| 0309 AM4 | Local Search | LocalSearchPlanner：锚定三态（精确键/子串单候选采纳/多候选 AMBIGUOUS）→K 跳 BFS 度数截断→预算贪心装填（实体>关系>原文块），截断标记 |
| 0310 AM5 | Global Search | GlobalSearchOrchestrator：C0 摘要按批 map（IGlobalInsightPort）→reduce 汇总；批次失败降级跳过=partial，reduce 失败退化要点拼接，全失败无答案 |
| 0311 AM6 | 增量索引与实体对齐 | GraphIndexIncrementalMerger：归一+别名表重定向+类型冲突报告；边键去重/来源块保序追加/重复合并幂等（哈希不变） |
| 0312 AM7 | 答案引用溯源 | AnswerProvenance：断言句切分→E:实体/R:关系两端同现/U:文本块 bigram 重叠阈值三类锚点，unanchored 标记+锚定率 |
| 0313 AM8 | 图谱质量评估 | GraphQualityEvaluator：来源覆盖率/连通分量/孤儿率/最大社区占比/规模熵；SummaryGroundednessAsserter：摘要句须命中成员实体或社区内关系 |
| 0314 AM9 | 可视化数据导出 | GraphVisualizationExporter：nodes(id/label/community/degree)+edges(type)，度数降序+键序采样；`/api/v1/graphrag/visualization` 端点默认关（graphrag.enabled）+ 注解守卫 |

## 二、端点与开关

- 端点：`GET /api/v1/graphrag/visualization?indexId=&limit=`（默认关，`graphrag.enabled=true` 才注册；IGraphIndexRepository 端口供数）。
- 开关：graphrag.enabled 缺省不注册端点（GraphRagEndpointGuardTest 反射守卫）。
- 数据面 JSON 结构：`{nodes:[{id,label,community,degree}], edges:[{source,target,type}], totalNodes, totalEdges, sampled}`，前端零依赖渲染（React Flow 数据模型思想，不引依赖）。

## 三、DDL

- 聚合库第 17 表 `graph_index`（index_id 唯一/document_id/unit|node|edge 计数/index_hash/graph_json）与第 18 表 `graph_community`（index_id+community_id+level 三级唯一/summary_text/member_keys），双方言同步追加，应用层维护 update_time。

## 四、测试与回归

- 域单测 35 例（AM1 5 / AM2 4 / AM3 4 / AM4 5 / AM5 4 / AM6 5 / AM7 5 / AM8 4 / AM9 3+守卫 1+DDL 2），全部绿。
- 关键确定性验证：索引重放哈希一致、社区划分重放一致、合并幂等（重复导入哈希不变）、map-reduce 批次调用序稳定。
- 挂点：全部纯函数+端口（摘要/要点/存储），无 LLM 与中间件依赖，测试用假端口与模板兜底路径。
