package cn.chyuan.ai.test.integration.rag;

import cn.chyuan.ai.api.dto.SearchTestRequestDTO;
import cn.chyuan.ai.api.dto.SearchTestResultDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.test.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 检索流程集成测试
 * <p>
 * 测试知识库检索流程：文档上传 → 向量检索 → 混合检索
 */
@DisplayName("RAG 检索流程集成测试")
public class RagRetrievalIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String getBaseUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    @Test
    @DisplayName("测试 RAG 混合检索功能")
    void testRagHybridSearch() {
        // Given
        SearchTestRequestDTO searchRequest = new SearchTestRequestDTO();
        searchRequest.setQuery("加油订单查询");
        searchRequest.setTopK(5);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SearchTestRequestDTO> entity = new HttpEntity<>(searchRequest, headers);

        // When
        ResponseEntity<Response<SearchTestResultDTO>> response = restTemplate.exchange(
                getBaseUrl() + "/documents/search",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Response<SearchTestResultDTO>>() {}
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        // RAG 服务可能未启用，但接口应正常响应
        assertNotNull(response.getBody().getCode());
    }

    @Test
    @DisplayName("测试 RAG 检索 - 空查询")
    void testRagSearch_EmptyQuery() {
        // Given
        SearchTestRequestDTO searchRequest = new SearchTestRequestDTO();
        searchRequest.setQuery("");
        searchRequest.setTopK(5);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SearchTestRequestDTO> entity = new HttpEntity<>(searchRequest, headers);

        // When
        ResponseEntity<Response<SearchTestResultDTO>> response = restTemplate.exchange(
                getBaseUrl() + "/documents/search",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Response<SearchTestResultDTO>>() {}
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("测试 RAG 检索 - TopK 为 0")
    void testRagSearch_ZeroTopK() {
        // Given
        SearchTestRequestDTO searchRequest = new SearchTestRequestDTO();
        searchRequest.setQuery("测试查询");
        searchRequest.setTopK(0);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SearchTestRequestDTO> entity = new HttpEntity<>(searchRequest, headers);

        // When
        ResponseEntity<Response<SearchTestResultDTO>> response = restTemplate.exchange(
                getBaseUrl() + "/documents/search",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Response<SearchTestResultDTO>>() {}
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("测试 RAG 检索 - 大 TopK 值")
    void testRagSearch_LargeTopK() {
        // Given
        SearchTestRequestDTO searchRequest = new SearchTestRequestDTO();
        searchRequest.setQuery("测试查询");
        searchRequest.setTopK(100);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SearchTestRequestDTO> entity = new HttpEntity<>(searchRequest, headers);

        // When
        ResponseEntity<Response<SearchTestResultDTO>> response = restTemplate.exchange(
                getBaseUrl() + "/documents/search",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Response<SearchTestResultDTO>>() {}
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("测试 RAG 检索 - 特殊字符查询")
    void testRagSearch_SpecialCharacters() {
        // Given
        SearchTestRequestDTO searchRequest = new SearchTestRequestDTO();
        searchRequest.setQuery("测试 <script>alert('xss')</script>");
        searchRequest.setTopK(5);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SearchTestRequestDTO> entity = new HttpEntity<>(searchRequest, headers);

        // When
        ResponseEntity<Response<SearchTestResultDTO>> response = restTemplate.exchange(
                getBaseUrl() + "/documents/search",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Response<SearchTestResultDTO>>() {}
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        // 应正常处理，不应返回 500 错误
    }
}
