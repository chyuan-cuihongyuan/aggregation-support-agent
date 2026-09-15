package cn.chyuan.ai.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagElasticsearchHealthIndicator 三分支单测（SELFLOOP4 loop-404，工单 0606/0607）。
 */
class RagElasticsearchHealthIndicatorTest {

    @Test
    void pingTrueShouldBeUpWithElapsedDetail() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.ping()).thenReturn(new BooleanResponse(true));
        Health health = new RagElasticsearchHealthIndicator()
                .ragElasticsearchHealth(client).health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("ok", health.getDetails().get("ping"));
        assertTrue(((Number) health.getDetails().get("elapsedMs")).longValue() >= 0);
    }

    @Test
    void pingFalseShouldBeDown() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.ping()).thenReturn(new BooleanResponse(false));
        Health health = new RagElasticsearchHealthIndicator()
                .ragElasticsearchHealth(client).health();
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("cluster unreachable", health.getDetails().get("ping"));
    }

    @Test
    void pingThrowShouldBeDownWithReason() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.ping()).thenThrow(new RuntimeException("Connection refused"));
        Health health = new RagElasticsearchHealthIndicator()
                .ragElasticsearchHealth(client).health();
        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(String.valueOf(health.getDetails().get("error"))
                .contains("Connection refused"));
    }
}
