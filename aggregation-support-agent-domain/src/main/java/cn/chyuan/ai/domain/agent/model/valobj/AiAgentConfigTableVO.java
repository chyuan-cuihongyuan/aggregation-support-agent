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
             * 类型；loop、parallel、sequential
             */
            private String type;
            private String name;
            private List<String> subAgents;
            private String description;
            private Integer maxIterations = 3;

        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList;
        }
    }

}
