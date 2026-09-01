package cn.chyuan.ai.test.api.tool.skills;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;

/**
 * Spring Ai Tool
 *
 * @author chyuan @chyuan
 * 2025/12/14 09:51
 */
@Slf4j
public class SpringAiToolTest {

    public static void main(String[] args) {
        OpenAIClient openAIClient = OpenAIOkHttpClient.builder()
                .baseUrl("https://apis.itedus.cn/v1")
                .apiKey("sk-REDACTED")
                .build();

        // https://github.com/spring-ai-community/spring-ai-agent-utils
//        ToolCallback toolCallback01 = SkillsTool.builder()
//                .addSkillsDirectory("/Users/fuzhengwei/coding/gitcode/KnowledgePlanet/road-map/xfg-dev-tech-agent-skills/docs/skills")
//                .build();

        ToolCallback toolCallback02 = SkillsTool.builder()
                .addSkillsResource(new ClassPathResource("agent/skills"))
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAIClient)
                .options(OpenAiChatOptions.builder()
                        .model("gpt-4.1")
                        .toolCallbacks(new ArrayList<>(){{
                            add(toolCallback02);
                        }})
                        .build())
                .build();

        String call = chatModel.call("基于 skill 解答，电脑性能优化");

        log.info("测试结果:{}", call);
    }

}
