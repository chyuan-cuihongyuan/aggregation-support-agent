package cn.chyuan.ai.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Ai Agent 智能体配置表值对象
 *
 * @author chyuan @chyuan
 * 2025/11/29 10:54
 */
@Data
public class AiAgentConfigTableVO {

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 智能体配置
     */
    private Agent agent;

    /**
     * 智能体模块
     */
    private Module module;

    @Data
    public static class Agent {

        /**
         * 智能体ID
         */
        private String agentId;

        /**
         * 智能体名称
         */
        private String agentName;

        /**
         * 智能体描述
         */
        private String agentDesc;

    }

    @Data
    public static class Module {

        private AiApi aiApi;

        private ChatModel chatModel;

        private List<Agent> agents;

        private List<AgentWorkflow> agentWorkflows;

        private Runner runner;

        @Data
        public static class AiApi {
            private String baseUrl;
            private String apiKey;
            private String completionsPath = "/chat/completions";
            private String embeddingsPath = "embeddings";

        }

        @Data
        public static class ChatModel {

            private String model;

            /**
             * 最大输出 token 数，防止模型无限生成
             */
            private Integer maxTokens;

            private List<ToolMcp> toolMcpList;

            private List<ToolSkills> toolSkillsList;

            @Data
            public static class ToolMcp {

                private SSEServerParameters sse;

                private StdioServerParameters stdio;

                private LocalParameters local;

                /**
                 * M3: StreamableHttp 传输参数 —— 支持 MCP 协议 2025-03-26 版本的 StreamableHttp 传输。
                 * <p>
                 * 与 SSE 的区别：SSE 使用 GET 建立 long-lived 事件流；StreamableHttp 使用 POST 到单一端点（如 /mcp），
                 * 服务端可选择以 JSON 或 SSE-stream 响应，是 MCP 推荐的新传输方式。
                 */
                private StreamableHttpServerParameters streamableHttp;

                @Data
                public static class SSEServerParameters {
                    private String name;
                    private String baseUri;
                    private String sseEndpoint;
                    private Integer requestTimeout = 3000;

                }

                @Data
                public static class StdioServerParameters {
                    private String name;
                    private Integer requestTimeout = 3000;
                    private ServerParameters serverParameters;

                    @Data
                    public static class ServerParameters {
                        private String command;
                        private List<String> args;
                        private Map<String, String> env;

                    }
                }

                @Data
                public static class LocalParameters {
                    private String name;
                }

                /**
                 * StreamableHttp 传输参数（MCP 2025-03-26 规范）。
                 */
                @Data
                public static class StreamableHttpServerParameters {
                    private String name;
                    /** 服务端基础地址，如 https://api.example.com */
                    private String baseUri;
                    /**
                     * MCP 端点子路径（StreamableHttp 服务端通常为 /mcp）。
                     * 为空时默认 "/mcp"。支持子路径前缀，如 /api/v1/mcp。
                     */
                    private String mcpEndpoint = "/mcp";
                    /** 可选 Bearer Token，通过 Authorization 请求头传递 */
                    private String apiKey;
                    private Integer requestTimeout = 3000;
                }

            }

            @Data
            public static class ToolSkills {

                /**
                 * 类型；directory（用户配置的，映射进来的）、resource（放到工程下的）
                 */
                private String type = "directory";

                /**
                 * 路径；
                 */
                private String path;

            }

        }

        @Data
        public static class Agent {
            private String name;
            private String instruction;
            private String description;
            private String outputKey;
            private Boolean reactMode;
            /**
             * ReAct 循环最大步数，防止 LLM 空响应导致无限循环
             * 默认为 10 步
             */
            private Integer maxSteps = 10;

            /**
             * 是否注入 exitLoop 工具，用于 LoopAgent 中 Reflexion 循环的语义级提前退出。
             * 设为 true 时，AgentNode 会自动通过 FunctionTool.create() 注入 ExitLoopTool。
             * <p>
             * 适用于 LoopAgent 中的 ExitOrReplan / ExitOrRefine 等决策智能体，
             * 当 Critic 评估结果为 SUFFICIENT 时可提前退出循环，无需等待 maxIterations 硬限制。
             * <p>
             * 默认 false，不影响现有智能体行为。
             */
            private Boolean exitLoopEnabled = false;

            /**
             * Agent 级别工具声明（可选，预留扩展）。
             * <p>
             * - null：使用全局 ChatModel 注册的工具（默认行为）
             * - 空列表：不注入任何工具（适用于 Planner、Critic 等纯推理智能体）
             * - 非空列表：仅注入指定名称的工具
             */
            private List<String> tools;
        }

        @Data
        public static class AgentWorkflow {
            /**
             * 类型；loop、parallel、sequential、reflection、reflexion、replan、plan_execute
             */
            private String type;
            private String name;
            private List<String> subAgents;
            private String description;
            private Integer maxIterations = 3;

            /**
             * 强门控阈值 — Critic/Evaluator 评分达到此值才视为通过（触发 escalate 退出循环）。
             * 默认 7.0（满分 10）
             */
            private Double gateThreshold = 7.0;

            /**
             * 负责"评估通过则退出循环"的子 agent 名称（挂 ExitLoopTool + 强门控 Callback）。
             * <ul>
             *   <li>reflexion 工作流：默认 Critic（subAgents[1]）</li>
             *   <li>replan 工作流：默认 Evaluator（subAgents[2]）</li>
             * </ul>
             * 为空时按工作流类型取默认位置。
             */
            private String exitAgent;

            /**
             * 强门控正则 — Critic/Evaluator 输出命中此模式即触发 escalate 退出循环。
             * <p>
             * 为空时不启用强门控，循环仅靠 ExitLoopTool/maxIterations 退出。
             * 例：{@code "verdict"\s*:\s*"SUFFICIENT"} 精确匹配 JSON 字段值，避免 {@code INSUFFICIENT} 子串误命中。
             * <p>
             * 默认为空，保持向后兼容。需配合 {@code exitAgent} 使用。
             */
            private String passPattern;

            /**
             * 强门控失败关键字 — Critic/Evaluator 输出含这些关键字时不触发退出（逗号分隔）。
             * <p>
             * 默认 "INSUFFICIENT,NEEDS_REPLAN,NEEDS_IMPROVEMENT"。
             * 用途：当 Critic 输出同时包含通过/失败信号时（罕见），失败关键字优先。
             */
            private String failKeywords = "INSUFFICIENT,NEEDS_REPLAN,NEEDS_IMPROVEMENT";

            /**
             * Reflexion 跨迭代记忆的 session state key。
             * 为空时默认 "reflections:{workflowName}"。
             */
            private String reflectionStateKey;

            /**
             * 是否启用 Reflexion 自动降级 —— 连续多轮反思无提升（或分数持续低于 gateThreshold）时
             * 自动退出循环，避免无效反思消耗 token。
             * <p>
             * 默认 false（向后兼容）。
             */
            private Boolean degradationEnabled = false;

            /**
             * Reflexion 降级容忍轮数 —— 连续 N 轮无提升即触发降级退出。
             * <p>
             * 默认 2。"无提升"指本轮分数 ≤ 上一轮分数。
             */
            private Integer degradationPatience = 2;

            /**
             * Reflexion 降级最小迭代数 —— 至少迭代 N 次后才考虑降级，
             * 避免第一轮就误降级（首轮分数本身可能较低）。
             * <p>
             * 默认 2。
             */
            private Integer degradationMinIterations = 2;

            /**
             * Reflexion 降级评分提取正则 —— 从 Critic/Evaluator 输出文本中提取分数的正则，
             * 第一个捕获组解析为 double 分数。
             * <p>
             * 默认 {@code "score"\s*:\s*([\d.]+)}，匹配 JSON 风格的 score 字段。
             * 自定义示例：{@code 评分[::]\s*([\d.]+)} 匹配中文前缀。
             */
            private String scoreRegex = "\"score\"\\s*:\\s*([\\d.]+)";

            /**
             * A6 Conditional 谓词 —— 简单 query 短路正则。
             * <p>
             * 配置后，Reflexion 工作流的 Actor 在 beforeModelCallback 中检查 user query：
             * <ul>
             *   <li>匹配此正则 → 视为"简单 query"，直接返回简短响应跳过 LLM 调用，
             *       Critic 第一轮就会 PASSED，Reflexion loop 立即退出（等效于不走 Reflexion）</li>
             *   <li>不匹配 → 正常进入 Reflexion 多轮迭代</li>
             * </ul>
             * <p>
             * 示例：{@code ^(你好|hi|hello|在吗|谢谢)} 匹配打招呼类简单 query。
             * 为空（默认）表示不启用短路，所有 query 都走完整 Reflexion。
             */
            private String queryPredicate;

        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList;
        }
    }

}
