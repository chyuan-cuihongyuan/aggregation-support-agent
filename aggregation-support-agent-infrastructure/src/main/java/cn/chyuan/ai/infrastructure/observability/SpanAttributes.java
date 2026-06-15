package cn.chyuan.ai.infrastructure.observability;

public final class SpanAttributes {
    public static final String AGENT_ID = "agent.id";
    public static final String AGENT_NAME = "agent.name";
    public static final String SESSION_ID = "session.id";
    public static final String USER_ID = "user.id";
    public static final String TOOL_NAME = "tool.name";
    public static final String TOOL_TYPE = "tool.type";
    public static final String RAG_QUERY = "rag.query";
    public static final String RAG_RESULT_COUNT = "rag.result.count";
    public static final String LLM_MODEL = "llm.model";
    public static final String LLM_TOKENS_INPUT = "llm.tokens.input";
    public static final String LLM_TOKENS_OUTPUT = "llm.tokens.output";
    public static final String ERROR_TYPE = "error.type";
    public static final String ERROR_MESSAGE = "error.message";

    private SpanAttributes() {
        // Prevent instantiation
    }
}
