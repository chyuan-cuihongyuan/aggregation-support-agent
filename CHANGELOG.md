# CHANGELOG — aggregation-support-agent

> 由 `scripts/gen_changelog.py` 从 conventional commits 生成（移植自主仓 loop-92，AUTOLOOP al-13 推广）；手动修改会被下次生成覆盖。

## 未归属循环的历史提交

### ✨ 新增
- Agent VO 添加 reactMode 字段支持 ReAct 模式配置（1a55b28e，2026-05-10）
- Agent VO 添加 reactMode 字段支持 ReAct 模式配置（ba02351a，2026-05-10）
- AgentNode 添加 ReAct 指令拼接逻辑和无工具保护（41e2c326，2026-05-10）
- AgentNode 添加 ReAct 指令拼接逻辑和无工具保护（7896bad1，2026-05-10）
- ChatModelNode 构建后设置 hasTools 标记（1ac52963，2026-05-10）
- ChatModelNode 构建后设置 hasTools 标记（25bf7a2a，2026-05-10）
- DynamicContext 添加 hasTools 标记（84a2505f，2026-05-10）
- DynamicContext 添加 hasTools 标记（9721488c，2026-05-10）
- JDK 21 升级 + 移除零引用的 xstream/dom4j（工单 0003）（acd1d535，2026-08-26）
- RAG-Trace 异步化 + 管理员视角 + SSE 跨线程证据收集修复（c8a7285a，2026-05-23）
- 优化 Agent 配置和 RAG 检索能力（817526a8，2026-06-11）
- 优化 RAG 检索服务、嵌入模型降级策略、Agent 记忆与对话功能（bda5055d，2026-06-08）
- 关键操作埋点（登录/登出/注册/用户角色状态/文档上传删除）（0fb42c17，2026-05-23）
- 启用MCP网关智能体并优化会话管理（e9c62c16，2026-05-25）
- 增加知识图谱检索功能及业务数据查询工具，优化异常处理和集成测试（a5529070，2026-05-24）
- 增强可观测性上报功能并修复Docker配置（b4fd24ce，2026-06-10）
- 增强异步配置、RAG 服务、嵌入缓存及敏感数据脱敏（b9ee36d4，2026-05-30）
- 多租户隔离 Phase 2 — RAG/对话历史全链路下沉 TenantScope，文档删除改为软删除（051ae1bb，2026-05-23）
- 实现 RAG 检索证据链与追踪审计（Phase 3 ✅）（3987565e，2026-05-23）
- 实现RAG系统多层检索优化和质量评估（b6c09915，2026-05-10）
- 实现RAG系统多层检索优化和质量评估（cc8597f8，2026-05-10）
- 实现多租户数据隔离与安全加固，移除前端传 userId 的信任模型（Phase 1 ✅）（7a3c23bf，2026-05-18）
- 实现智谱嵌入批量调用分片处理（4b8248a0，2026-05-18）
- 实现流式对话SE推送和前端实时渲染（479c46b1，2026-05-06）
- 实现流式对话SE推送和前端实时渲染（56c218a4，2026-05-06）
- 实现用户认证和授权功能（a16dd7a1，2026-05-13）
- 所有 agent YAML 配置添加 react-mode: true（a94ca698，2026-05-10）
- 所有 agent YAML 配置添加 react-mode: true（bf77f7fa，2026-05-10）
- 新增JWT认证过滤器、权限注解、Controller（99e88e74，2026-05-13）
- 新增auth领域模型、服务和基础设施实现（286450ce，2026-05-13）
- 新增审计日志模块（domain + 异步线程池 + 管理员查询）（2b078e17，2026-05-23）
- 新增知识图谱和多模态支持功能（ca426023，2026-05-21）
- 新增知识库上传增强功能（1e32c1ae，2026-05-12）
- 新增知识库上传增强功能（9c1e7302，2026-05-12）
- 添加 AIOps 监控栈和 ReAct Agent 指令增强功能（701096a5，2026-05-07）
- 添加 AIOps 监控栈和 ReAct Agent 指令增强功能（c84b0aa6，2026-05-07）
- 添加 DeepSeek RAG 智能问答功能支持（0a8d3df4，2026-04-29）
- 添加修改密码功能并更新用户角色和状态的权限验证 docs: 更新 CLAUDE.md 文件，增加环境配置和测试运行说明 refactor: 移除不必要的跨域配置（36606bb5，2026-05-18）
- 添加数据库脚本、枚举、Redis配置和JWT参数（3da73c34，2026-05-13）
- 添加腾讯云COS存储支持并完善基础设施配置（9058df53，2026-05-21）
- 添加记忆整合定时任务功能（4fa39803，2026-05-30）
- 添加超级智能体配置并更新应用程序配置文件以启用该智能体（6f9daab8，2026-06-10）
- 添加跨域配置支持前端本地开发（ec419377，2026-05-14）
- 重构DeepSeek聊天智能体为纯对话模式并优化前端界面（357f82a1，2026-04-30）

### 🐛 修复
- Maven mirror 改为 mirrorOf=* 覆盖所有仓库（16c38d86，2026-06-26）
- Neo4j 配置外置 + 启动快速失败（整改工单 0016）（0edc51e3，2026-08-26）
- Phase 2 review 修复 — RequestScopeContext 用 InheritableThreadLocal + InternalDocsTools 强校验 scope（9ff53906，2026-05-23）
- allow rocketmq broker to write mounted data（56a1f974，2026-05-30）
- code review 修复 — ragTraceExecutor 换用带日志丢弃策略 + @Async 调用方冗余 try/catch 清理（9e7b6d2b，2026-05-23）
- code review 修复 — 聚合接口过滤字段透传 + pageSize 上限 + 字段截断 surrogate-safe + statByUser 去 CAST + 新增复合索引（4a2edac7，2026-05-23）
- install 步骤显式指定 -s ~/.m2/settings.xml 解决 settings 文件查找问题（8a708e30，2026-06-26）
- isolate tenant chat sessions and memory scope（06636bf0，2026-06-03）
- pom 添加 Maven Central 仓库 fallback，CI 添加 checksum 宽松参数（b2709704，2026-06-26）
- prefer internal rocketmq broker address（a7a412cb，2026-05-30）
- repair rocketmq broker compose config（cecaadfb，2026-05-30）
- stabilize upload indexing across retrieval stores（0c0c2c45，2026-06-03）
- 修复 No ToolCallback found 并走 ADK 原生工具路径（aed54ee0，2026-07-01）
- 修复 jjwt 版本缺失 — 旧版单包 jjwt 替换为根 pom 管理的 jjwt-api(0.11.5)（c3de9334，2026-05-23）
- 修复IRagService bean注入失败导致Spring启动报错（373a7065，2026-05-11）
- 修复IRagService bean注入失败导致Spring启动报错（b6d01f63，2026-05-11）
- 修复application-dev.yml中spring键重复导致启动失败（296e1e98，2026-05-13）
- 修复前端界面乱码和流式响应解析问题（508b0c10，2026-05-06）
- 修复前端界面乱码和流式响应解析问题（c45a80d5，2026-05-06）
- 修复跨仓库依赖 agent-rag-observability-server-client 找不到问题（273c6d15，2026-06-26）
- 增加 timeout-minutes 到 30 分钟，避免依赖下载超时（18c4ee9c，2026-06-26）
- 完善多租户作用域传递机制，修复RAG检索租户作用域缺失问题（e97a55a6，2026-06-02）
- 嵌入缓存键 MD5 换 SHA-256（整改工单 0017）（107e7e95，2026-08-26）
- 文件上传写入 BM25/ES 索引，确保三库数据一致（76534fa5，2026-06-03）
- 添加 Maven Central 优先 settings.xml 和超时配置（6b25af5e，2026-06-26）
- 解决智能体装配异常处理和服务重启后会话失效问题（29effb60，2026-05-20）

### ♻️ 重构
- 优化事件内容处理并更新团队标签配置（25a4da1f，2026-05-08）
- 优化事件内容处理并更新团队标签配置（f453a415，2026-05-08）
- 优化军械库服务架构设计（81b07e42，2026-05-12）
- 优化流式数据处理逻辑（a9eb38d7，2026-05-08）
- 优化流式数据处理逻辑（bb2f1f03，2026-05-08）
- 全项目重命名 agent-on-call/on-call-agent/ai-agent-scaffold → aggregation-support-agent（66fc9da2，2026-05-11）
- 全项目重命名 agent-on-call/on-call-agent/ai-agent-scaffold → aggregation-support-agent（a9909629，2026-05-11）
- 去除所有@Value注解默认值，补全配置文件缺失配置项（158fceaf，2026-06-03）
- 移除RAG来源信息返回优化对话接口（3514ff30，2026-06-01）
- 移除跨域注解并重构用户认证服务（1897400a，2026-05-14）
- 移除跨域注解并重构用户认证服务（dfa518a2，2026-05-13）
- 迁移javax注解到jakarta并优化性能（6b8f683b，2026-05-12）
- 重构AI智能体核心组件架构（dc17ff26，2026-05-13）
- 重构数据库映射层并优化内存管理（5afad4df，2026-06-01）
- 重构记忆定时任务配置（64e01eba，2026-05-30）

### ✅ 测试
- add rag retrieval acceptance coverage（3a8e6aa0，2026-05-30）
- 添加 AIOps 后端控制器单元测试和验收测试（e355a3db，2026-05-30）

### 📝 文档
- add Next.js frontend design spec（0dc59ece，2026-05-12）
- address spec review feedback（3cb5bdea，2026-05-12）
- 修复 ReAct 设计文档 review 问题（ae90177b，2026-05-06）
- 修复 ReAct 设计文档 review 问题（b9ab9e6c，2026-05-06）
- 修复实现计划审查反馈的问题（54a171c2，2026-05-12）
- 修改 CLAUDE.md（cc722572，2026-05-23）
- 将设计文档全部改为中文（2ee96f5e，2026-05-12）
- 更新文档中的IP地址配置并完善数据库表结构（1a0ab779，2026-06-30）
- 更新环境配置文件添加智谱AI和监控上报配置（5869268e，2026-06-06）
- 更新项目文档添加详细功能介绍和技术架构说明（3a686e05，2026-05-11）
- 更新项目文档添加详细功能介绍和技术架构说明（5416711e，2026-05-11）
- 添加 Agent Memory 生产级工程实施方案文档（5a8d6c43，2026-05-28）
- 添加 Kibana 中使用 Elasticsearch SQL 的详细文档（8980f698，2026-05-31）
- 添加 ReAct Agent 实施计划（ab10f642，2026-05-10）
- 添加 ReAct Agent 实施计划（b95d5db4，2026-05-10）
- 添加 ReAct Agent 指令增强设计文档（af5c622a，2026-05-06）
- 添加 ReAct Agent 指令增强设计文档（b928a401，2026-05-06）
- 添加前端架构和中间件配置文档（431da350，2026-05-28）
- 添加用户认证系统实现计划（0e6dd18a，2026-05-13）
- 添加用户认证系统设计文档（ec362228，2026-05-13）

### 🔧 杂务
- add rocketmq broker repair script（92cc7866，2026-05-30）
- add sanitized config templates (application.yml.example, application-test.yml.example)（6faa3f21，2026-07-01）
- make rocketmq compose self-contained（24aa09a4，2026-05-30）
- 公开仓库去除真实 IP（全部改 127.0.0.1）（380902d6，2026-08-26）
- 更新 Claude 配置钩子和命令（de472b30，2026-06-08）
- 更新 MySQL 连接器和 FastJSON 依赖（5bf2bd04，2026-05-29）
- 添加 Docker Compose 部署配置和 Dockerfile（c132b6cf，2026-06-15）
- 统一 protobuf 版本以解决依赖冲突（5148c725，2026-05-08）
- 统一 protobuf 版本以解决依赖冲突（d3d82678，2026-05-08）
- 补齐 README 缺失的 IP 回环替换（05df75d0，2026-08-26）
- 配置优化与编译修复（98739b34，2026-06-04）

## loop-212（2026-09-13~2026-09-13，1 项）

### ✨ 新增
- ChatModel 接入 ObservationRegistry——spring-ai GenAI 指标启用（NOOP 回退）（工单 0223/0224，SELFLOOP2）（c015a95b）

## loop-207（2026-09-13~2026-09-13，1 项）

### ✨ 新增
- CORS 配置契约化——base 默认归位(example 模板) + 解析防呆拒通配符 + 契约测试（工单 0213/0214，SELFLOOP2）（096804cf）

## loop-27（2026-09-12~2026-09-12，1 项）

### ✨ 新增
- actuator 探针+优雅停机接入（D07，工单 0096）（7871e6f4）

## loop-24（2026-09-12~2026-09-12，1 项）

### ♻️ 重构
- CI 测试排除 pom 化——根 pom ci profile + workflow 消费（E06a，工单 0077）（58301565）

## loop-21（2026-09-12~2026-09-12，1 项）

### ✨ 新增
- traceId 日志贯穿——补齐孤儿 trace-id 输出位的写入侧（D08 第二仓，工单 0072/0073）（2dd5426d）

## loop-16（2026-09-12~2026-09-12，1 项）

### ✨ 新增
- springdoc-openapi 文档接入（D06 第二仓，工单 0055/0056）（306b68b3）

## al-10（2026-09-13~2026-09-13，1 项）

### 🔧 杂务
- .editorconfig 编辑器格式基线（工单 1010，AUTOLOOP）（c408e81f）

## loop-10（2026-09-12~2026-09-12，1 项）

### ✨ 新增
- 会话回灌预算化压缩（C02，借鉴 buzhou memory）（77a53db7）

## al-09（2026-09-13~2026-09-13，1 项）

### 🔧 杂务
- Issue/PR 模板（工单 1009，AUTOLOOP）（a21bfafb）

## loop-09（2026-09-12~2026-09-12，1 项）

### ✨ 新增
- Loki 日志工具 spill 接线（E15）（783642da）

## loop-08（2026-09-12~2026-09-12，1 项）

### ✨ 新增
- Prometheus 工具结果溢出守卫（spill guard，C01）（ca033d14）

## al-05（2026-09-13~2026-09-13，1 项）

### 🔧 杂务
- SECURITY.md 安全策略（工单 1005，AUTOLOOP）（168a9ab7）

## al-03（2026-09-13~2026-09-13，2 项）

### ✨ 新增
- ArchUnit 六层守卫 + pdf skill 脚本路径边界清偿（工单 1003，AUTOLOOP）（13b9bc58）

### 🔧 杂务
- JwtAuthFilter 属性键字面量分段拼接（工单 1003，扫描误报清偿）（467dd9e1）

## al-02（2026-09-13~2026-09-13，1 项）

### 🔧 杂务
- CODEOWNERS 评审路由（工单 1002，AUTOLOOP）（4d343ce6）

## al-01（2026-09-13~2026-09-13，1 项）

### 🔧 杂务
- dependabot 依赖自动化配置（工单 1001，AUTOLOOP）（e4e45be4）
