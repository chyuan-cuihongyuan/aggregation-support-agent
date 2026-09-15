package cn.chyuan.ai.infrastructure.config;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OkHttpClientConfig 超时外置单测（SELFLOOP4 loop-415，工单 0628/0629）。
 * 默认值零漂移（shared 30/600/30）+ embedding 专用 client 判据 T=10s + 下限钳位。
 */
class OkHttpClientConfigTest {

    private OkHttpClientConfig configWith(String sharedRead, String embeddingRead) {
        OkHttpClientConfig config = new OkHttpClientConfig();
        ReflectionTestUtils.setField(config, "connectTimeoutSeconds", 30);
        ReflectionTestUtils.setField(config, "readTimeoutSeconds", sharedRead == null ? 600 : Integer.parseInt(sharedRead));
        ReflectionTestUtils.setField(config, "writeTimeoutSeconds", 30);
        ReflectionTestUtils.setField(config, "embeddingConnectTimeoutSeconds", 30);
        ReflectionTestUtils.setField(config, "embeddingReadTimeoutSeconds",
                embeddingRead == null ? 10 : Integer.parseInt(embeddingRead));
        ReflectionTestUtils.setField(config, "embeddingWriteTimeoutSeconds", 30);
        return config;
    }

    @Test
    @DisplayName("默认值零漂移：shared 30/600/30，embedding read 10s")
    void defaultsUnchanged() {
        OkHttpClient shared = configWith(null, null).httpClient();
        assertEquals(30000, shared.connectTimeoutMillis());
        assertEquals(600000, shared.readTimeoutMillis());
        assertEquals(30000, shared.writeTimeoutMillis());

        OkHttpClient embedding = configWith(null, null).embeddingHttpClient();
        assertEquals(30000, embedding.connectTimeoutMillis());
        assertEquals(10000, embedding.readTimeoutMillis());
    }

    @Test
    @DisplayName("钳位：embedding read 0 → 1s（防误配无超时）")
    void embeddingReadClampedToOneSecond() {
        OkHttpClient embedding = configWith(null, "0").embeddingHttpClient();
        assertEquals(1000, embedding.readTimeoutMillis());
    }
}
