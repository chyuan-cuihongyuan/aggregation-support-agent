# Aggregation Support Agent

## 项目概述

Aggregation Support Agent 是一个基于 Spring Boot 的智能运维平台，集成了大模型对话、RAG（Retrieval-Augmented Generation）知识库检索和 AIOps 告警分析能力。该项目采用领域驱动设计（DDD）架构，实现了多智能体协作的运维分析系统。

### 核心特性

- **RAG 智能问答**：基于文档知识库的智能问答助手，支持多轮对话和语义检索
- **AIOps 智能运维**：多智能体协作的告警分析系统，采用 Planner-Executor 串行工作流
- **四层 RAG 优化**：索引优化、查询优化、召回优化、重排序优化
- **多模型支持**：支持 z.ai、阿里云千问、DeepSeek 等多种大模型
- **ReAct Agent**：推理与行动相结合的智能体模式

## 使用功能

### 1. 智能体管理

| 智能体 ID | 名称 | 说明 |
|-----------|------|------|
| 200001 | RAG 智能问答 | 基于文档知识库的智能问答助手 |
| 200002 | AIOps 智能运维 | 多智能体协作的告警分析系统 |
| 200003 | 千问 RAG 问答 | 基于阿里云千问模型的文档知识库问答 |

### 2. API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/query_ai_agent_config_list` | 查询智能体列表 |
| POST | `/api/v1/create_session` | 创建会话 |
| POST | `/api/v1/chat` | 同步对话 |
| POST | `/api/v1/chat_stream` | 流式对话（SSE） |
| POST | `/api/v1/upload` | 上传文档到知识库 |
| POST | `/api/v1/ai_ops` | AIOps 一键分析（SSE） |
| GET | `/api/v1/milvus/health` | Milvus 健康检查 |

### 3. RAG 知识库功能

- **文档上传**：支持 `.txt` 和 `.md` 格式，自动完成分块、向量化和存储
- **语义检索**：基于向量相似度的文档片段检索
- **混合检索**：向量检索 + BM25 关键词检索，通过 RRF 融合算法实现更精准检索
- **多粒度索引**：章节级、段落级、句子级三层索引

### 4. AIOps 告警分析

- **告警收集**：集成 Prometheus 获取活动告警
- **日志查询**：集成 Loki 查询相关日志
- **知识检索**：从运维手册检索处理方案
- **报告生成**：自动生成 Markdown 格式运维报告

## 设计思路

### 1. DDD 架构设计

```
aggregation-support-agent/
├── api/          # API 层：定义服务接口和数据传输对象
├── types/        # 类型层：定义通用类型、枚举和异常
├── domain/       # 领域层：核心业务逻辑
│   ├── agent/    # 智能体领域
│   ├── rag/      # RAG 领域
│   └── business/ # 业务领域
├── infrastructure/ # 基础设施层：技术实现
│   ├── gateway/  # 网关层：外部服务调用
│   ├── persistent/ # 持久化层：数据库操作
│   └── config/   # 配置层
├── trigger/      # 触发器层：HTTP 接口、定时任务、事件监听
└── app/          # 应用层：启动配置
```

### 2. 四层 RAG 优化框架

```
┌─────────────────────────────────────────────────────────────┐
│                    RAG 检索优化四层框架                        │
├─────────────────────────────────────────────────────────────┤
│  第一层：索引优化    │  知识怎么「存」- 文档切割粒度和方式      │
│  第二层：查询优化    │  问题怎么「转」- 检索前对query做加工     │
│  第三层：召回优化    │  从哪里「找」- 多条检索路径并行捞取      │
│  第四层：重排序优化  │  谁最「相关」- 精排保证进入prompt的质量  │
└─────────────────────────────────────────────────────────────┘
```

#### 索引优化
- **Parent-Child Chunking**：小块检索、大块使用
- **摘要索引**：LLM 生成摘要建索引
- **多粒度分层索引**：章节级、段落级、句子级三层

#### 查询优化
- **Query 改写**：口语化 → 书面语
- **Multi-Query 扩展**：一个问题扩展成 3-5 个不同角度
- **HyDE**：生成假设答案进行检索
- **Step-back Prompting**：具体问题 → 抽象问题

#### 召回优化
- **向量检索**：语义匹配
- **BM25 关键词检索**：精确匹配
- **RRF 融合算法**：倒数排名融合

#### 重排序优化
- **Cross-encoder Rerank**：精排
- **Lost in the Middle 处理**：优化 chunk 排列顺序

### 3. ReAct Agent 模式

采用 Reasoning + Acting 模式，使推理过程可追溯、可调试：

```
Thought: 分析当前情况，推理下一步行动
Action: 调用可用工具
Observation: 系统返回工具执行结果
... (可多轮循环)
Final Answer: 提供最终结论
```

### 4. 多智能体协作

AIOps 采用 Planner-Executor 串行工作流：
1. **Planner**：分解问题、调用工具收集信息、制定分析计划
2. **Executor**：根据计划生成结构化的 Markdown 运维报告

## 使用技术

### 核心框架

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.4.3 | 应用框架 |
| Java | 17 | 编程语言 |
| Google ADK | 0.5.0 | Agent Development Kit |
| Spring AI | 1.1.0-M3 | AI 集成框架 |
| LangChain4j | 1.4.0 | LLM 应用框架 |

### 数据存储

| 技术 | 版本 | 说明 |
|------|------|------|
| Milvus | 2.6.10 | 向量数据库 |
| MySQL | 8.0.32 | 关系数据库 |
| Redis | 6.2 | 缓存 |
| Elasticsearch | 8.x | 全文检索（可选） |

### AI 模型

| 提供商 | 模型 | 用途 |
|--------|------|------|
| 智谱 BigModel | GLM-5.1 | 对话模型 |
| 智谱 BigModel | embedding-3 | 文本嵌入 |
| 阿里云 DashScope | qwen-plus | 对话模型 |
| 阿里云 DashScope | text-embedding-v4 | 文本嵌入 |
| DeepSeek | deepseek-v4-pro | 对话模型 |

### 工具与中间件

| 技术 | 说明 |
|------|------|
| OkHttp | HTTP 客户端 |
| Fastjson | JSON 处理 |
| MyBatis | ORM 框架 |
| Lombok | 代码简化 |
| XStream | XML 处理 |
| Guava | 工具库 |
| Protobuf | 序列化 |

### 运维与监控

| 技术 | 说明 |
|------|------|
| Docker | 容器化部署 |
| Docker Compose | 服务编排 |
| Prometheus | 监控指标 |
| Grafana | 监控面板 |
| Loki | 日志聚合 |
| Attu | Milvus 可视化管理 |

### 设计模式

| 模式 | 应用 |
|------|------|
| 策略模式 | 多种嵌入模型切换 |
| 工厂模式 | 智能体装配工厂 |
| 责任链模式 | 装配节点链 |
| 模板方法模式 | 抽象装配支持 |
| 观察者模式 | 事件监听 |

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- Docker 20.10+
- Docker Compose 2.0+

### 启动步骤

1. **启动基础设施**
```bash
docker-compose -f docs/dev-ops/docker-compose-environment.yml up -d
```

2. **配置环境变量**
```bash
export LLM_API_KEY=your-api-key
export DASHSCOPE_API_KEY=your-dashscope-key
```

3. **构建项目**
```bash
mvn clean package -DskipTests
```

4. **启动应用**
```bash
java -jar aggregation-support-agent-app/target/aggregation-support-agent-app.jar
```

5. **上传运维文档**
```bash
for f in docs/aiops-docs/*.md; do
  curl -X POST http://49.232.169.33:8091/api/v1/upload -F "file=@$f"
done
```

### 验证服务

```bash
# 查询智能体列表
curl http://49.232.169.33:8091/api/v1/query_ai_agent_config_list

# 检查 Milvus 健康状态
curl http://49.232.169.33:8091/api/v1/milvus/health
```

## 项目结构

```
aggregation-support-agent/
├── README.md                           # 项目说明
├── pom.xml                             # Maven 配置
├── docs/                               # 文档
│   ├── deployment-guide.md             # 部署指南
│   ├── rag-optimization-plan.md        # RAG 优化方案
│   ├── rag-optimization-progress.md    # RAG 优化进度
│   ├── aiops-docs/                     # AIOps 运维文档
│   ├── dev-ops/                        # 运维配置
│   └── prompt/                         # 提示词模板
├── aggregation-support-agent-api/      # API 层
├── aggregation-support-agent-types/    # 类型层
├── aggregation-support-agent-domain/   # 领域层
├── aggregation-support-agent-infrastructure/ # 基础设施层
├── aggregation-support-agent-trigger/  # 触发器层
└── aggregation-support-agent-app/      # 应用层
```

## 许可证

Apache License, Version 2.0

## 联系方式

- 开发者：chyuan
- 邮箱：chyuan18894909019@163.com