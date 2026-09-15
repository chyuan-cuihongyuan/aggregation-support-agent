package cn.chyuan.ai.infrastructure.config;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OkHttpClientConfig 超时外置单测（SELFLOOP4 loop-415/420，工单 0628/0629、0638/0639）。
 * 默认值（shared 30/600/30；embedding 5/10/30）+ embedding 双向钳位（G49 判据防线）。
 */
class OkHttpClientConfigTest {

    private OkHttpClientConfig configWith(String sharedRead, String embeddingRead) {
        OkHttpClientConfig config = new OkHttpClientConfig();
        ReflectionTestUtils.setField(config, "connectTimeoutSeconds", 30);
        ReflectionTestUtils.setField(config, "readTimeoutSeconds", sharedRead == null ? 600 : Integer.parseInt(sharedRead));
        ReflectionTestUtils.setField(config, "writeTimeoutSeconds", 30);
        ReflectionTestUtils.setField(config, "embeddingConnectTimeoutSeconds", 5);
        ReflectionTestUtils.setField(config, "embeddingReadTimeoutSeconds",
                embeddingRead == null ? 10 : Integer.parseInt(embeddingRead));
        ReflectionTestUtils.setField(config, "embeddingWriteTimeoutSeconds", 30);
        return config;
    }

    @Test
    @DisplayName("默认值：shared 30/600/30 零漂移；embedding 5/10/30（G49 connect 30→5）")
    void defaultsUnchanged() {
        OkHttpClient shared = configWith(null, null).httpClient();
        assertEquals(30000, shared.connectTimeoutMillis());
        assertEquals(600000, shared.readTimeoutMillis());
        assertEquals(30000, shared.writeTimeoutMillis());

        OkHttpClient embedding = configWith(null, null).embeddingHttpClient();
        assertEquals(5000, embedding.connectTimeoutMillis());
        assertEquals(10000, embedding.readTimeoutMillis());
        assertEquals(30000, embedding.writeTimeoutMillis());
    }

    @Test
    @DisplayName("钳位：embedding read 0 → 1s（防误配无超时）")
    void embeddingReadClampedToOneSecond() {
        OkHttpClient embedding = configWith(null, "0").embeddingHttpClient();
        assertEquals(1000, embedding.readTimeoutMillis());
    }

    @Test
    @DisplayName("钳位上限（G49 判据关键）：read 600 → 30s，预算不再无上界")
    void embeddingReadClampedToJudgementCeiling() {
        OkHttpClient embedding = configWith(null, "600").embeddingHttpClient();
        assertEquals(30000, embedding.readTimeoutMillis());
    }

    @Test
    @DisplayName("钳位上限：connect 600 → 15s、write 0 → 1s")
    void embeddingConnectAndWriteClamped() {
        OkHttpClientConfig config = configWith(null, "10");
        ReflectionTestUtils.setField(config, "embeddingConnectTimeoutSeconds", 600);
        ReflectionTestUtils.setField(config, "embeddingWriteTimeoutSeconds", 0);
        OkHttpClient embedding = config.embeddingHttpClient();
        assertEquals(15000, embedding.connectTimeoutMillis());
        assertEquals(1000, embedding.writeTimeoutMillis());
    }
}
