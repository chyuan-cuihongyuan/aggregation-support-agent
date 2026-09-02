package cn.chyuan.ai.test.config;

import cn.chyuan.ai.config.BuzhouChatClientConfig;
import io.github.chyuan_cuihongyuan.buzhou.core.Buzhou;
import io.github.chyuan_cuihongyuan.buzhou.core.spi.BuzhouStores;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单 0026：Buzhou 基座装配与 memory 行为（缝：ChatClient 对话边界）
 *
 * - 开关开 + ChatModel/BuzhouStores 就位 → 增强 Builder 装配，跨轮记忆回灌、会话隔离
 * - 开关关 → 完全不装配（既有上下文零影响）
 */
public class BuzhouChatClientConfigTest {

    /** 记录每次调用收到的 instructions 的桩模型（模式同 buzhou examples StubChatModel） */
    static class RecordingChatModel implements ChatModel {
        final List<String> instructions = new CopyOnWriteArrayList<>();

        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            instructions.add(prompt.getInstructions().toString());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("好的"))));
        }

        @Override
        public Flux<ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    /** 桩装配：ChatModel + 内存态 BuzhouStores（隔离于业务上下文） */
    @Configuration
    static class StubConfig {
        @Bean
        ChatModel chatModel() {
            return new RecordingChatModel();
        }

        @Bean
        BuzhouStores buzhouStores() {
            return Buzhou.inMemoryStores();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubConfig.class)
            .withUserConfiguration(BuzhouChatClientConfig.class);

    @Test
    public void test_enhance链装配_跨轮记忆回灌与会话隔离() {
        runner.withPropertyValues("buzhou.chain.enhance-enabled=true").run(context -> {
            assertThat(context).hasBean("buzhouChatClientBuilder");
            RecordingChatModel model = (RecordingChatModel) context.getBean(ChatModel.class);
            ChatClient chatClient = context.getBean("buzhouChatClientBuilder", ChatClient.Builder.class).build();

            // 第一轮：用户消息经 advisor 写入 memory
            chatClient.prompt().user("我的名字是阿远")
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "conv-1"))
                    .call().content();

            // 第二轮（同会话）：重建后的 instructions 应包含第一轮用户消息
            chatClient.prompt().user("我叫什么名字？")
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "conv-1"))
                    .call().content();
            assertThat(model.instructions.get(1)).contains("阿远");

            // 第三轮（新会话）：隔离，不含其他会话内容
            chatClient.prompt().user("你说什么？")
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "conv-2"))
                    .call().content();
            assertThat(model.instructions.get(2)).doesNotContain("阿远");
        });
    }

    @Test
    public void test_开关未开时不装配() {
        runner.run(context -> assertThat(context).doesNotHaveBean("buzhouChatClientBuilder"));
    }

    @Test
    public void test_缺BuzhouStores时不装配() {
        new ApplicationContextRunner()
                .withUserConfiguration(BuzhouChatClientConfig.class)
                .withBean(ChatModel.class, RecordingChatModel::new)
                .withPropertyValues("buzhou.chain.enhance-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean("buzhouChatClientBuilder"));
    }
}
