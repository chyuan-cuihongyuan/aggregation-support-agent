package cn.chyuan.ai.infrastructure.gateway.rerank;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上游 rerank API 适配单测（工单 0164）
 * <p>
 * 覆盖验收：upstream 适配 mock 合同（正常重排 / HTTP 非 2xx 降级 / 超时与 IO 异常降级不抛出 / 无 Key 不可用）
 */
class UpstreamRerankPortTest {

    private UpstreamRerankPort port;

    private OkHttpClient httpClient;

    @BeforeEach
    void setUp() {
        port = new UpstreamRerankPort();
        httpClient = mock(OkHttpClient.class);
        ReflectionTestUtils.setField(port, "httpClient", httpClient);
        ReflectionTestUtils.setField(port, "rerankApiUrl", "http://upstream/rerank");
        ReflectionTestUtils.setField(port, "rerankApiKey", "test-key");
        ReflectionTestUtils.setField(port, "rerankModel", "bge-reranker-v2-m3");
        ReflectionTestUtils.setField(port, "timeout", 3000);
    }

    @Test
    void appliesUpstreamRelevanceScores() throws IOException {
        // 响应把 index=1 排第一（相关性 0.95），index=0 第二（0.60）
        stubCall(response(200, "{\"results\":[{\"index\":1,\"relevance_score\":0.95},"
                + "{\"index\":0,\"relevance_score\":0.60}]}"));

        List<VectorSearchResultVO> reranked = port.rerank("query", candidates(), 5);

        assertThat(reranked).extracting(VectorSearchResultVO::getContent).containsExactly("块二", "块一");
        assertThat(reranked.get(0).getScore()).isEqualTo(0.95f);
        assertThat(reranked.get(0).getMetadata()).isEqualTo(candidates().get(1).getMetadata());
    }

    @Test
    void httpErrorDegradesToOriginalOrder() throws IOException {
        stubCall(response(500, "internal error"));

        List<VectorSearchResultVO> reranked = port.rerank("query", candidates(), 5);

        // HTTP 非 2xx：降级原分数原序
        assertThat(reranked).extracting(VectorSearchResultVO::getContent).containsExactly("块一", "块二");
        assertThat(reranked).extracting(VectorSearchResultVO::getScore).containsExactly(0.9f, 0.8f);
    }

    @Test
    void ioExceptionDegradesWithoutThrowing() throws IOException {
        // 超时/网络异常合同：execute 抛 IOException，端口必须降级且不抛出
        Call call = mock(Call.class);
        when(call.execute()).thenThrow(new IOException("timeout"));
        when(httpClient.newCall(any(Request.class))).thenReturn(call);

        List<VectorSearchResultVO> candidates = candidates();

        assertThatCode(() -> {
            List<VectorSearchResultVO> reranked = port.rerank("query", candidates, 5);
            assertThat(reranked).extracting(VectorSearchResultVO::getContent).containsExactly("块一", "块二");
        }).doesNotThrowAnyException();
    }

    @Test
    void malformedBodyDegradesToOriginalOrder() throws IOException {
        stubCall(response(200, "not-json"));

        List<VectorSearchResultVO> reranked = port.rerank("query", candidates(), 5);

        assertThat(reranked).extracting(VectorSearchResultVO::getContent).containsExactly("块一", "块二");
    }

    @Test
    void emptyResultsArrayDegradesToOriginalOrder() throws IOException {
        stubCall(response(200, "{\"results\":[]}"));

        List<VectorSearchResultVO> reranked = port.rerank("query", candidates(), 5);

        assertThat(reranked).extracting(VectorSearchResultVO::getContent).containsExactly("块一", "块二");
    }

    @Test
    void emptyCandidatesShortCircuit() {
        assertThat(port.rerank("query", List.of(), 5)).isEmpty();
    }

    @Test
    void availableDependsOnApiKey() {
        assertThat(port.isAvailable()).isTrue();
        ReflectionTestUtils.setField(port, "rerankApiKey", "");
        assertThat(port.isAvailable()).isFalse();
    }

    private void stubCall(Response response) throws IOException {
        Call call = mock(Call.class);
        when(call.execute()).thenReturn(response);
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
    }

    private Response response(int code, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("http://upstream/rerank").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 200 ? "OK" : "Error")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }

    private List<VectorSearchResultVO> candidates() {
        return List.of(result("块一", 0.9f), result("块二", 0.8f));
    }

    private VectorSearchResultVO result(String content, float score) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", "d1");
        metadata.put("chunkIndex", content.hashCode());
        return VectorSearchResultVO.builder()
                .content(content)
                .score(score)
                .metadata(metadata)
                .build();
    }
}
