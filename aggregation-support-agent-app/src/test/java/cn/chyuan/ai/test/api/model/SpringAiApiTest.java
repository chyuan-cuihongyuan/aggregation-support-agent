package cn.chyuan.ai.test.api.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

/**
 * Spring AI Test
 * 文档：<a href="https://docs.spring.io/spring-ai/reference/1.0/api/advisors.html">spring ai</a>
 * @author chyuan @chyuan
 * 2025/12/14 09:15
 */
@Slf4j
public class SpringAiApiTest {

    public static void main(String[] args) {
        OpenAIClient openAIClient = OpenAIOkHttpClient.builder()
                .baseUrl("https://apis.itedus.cn/v1")
                .apiKey("sk-REDACTED")
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAIClient)
                .options(OpenAiChatOptions.builder()
                        .model("gpt-4.1")
                        .build())
                .build();

        String call = chatModel.call("hi 你好哇!");

        log.info("测试结果:{}", call);
    }

}
