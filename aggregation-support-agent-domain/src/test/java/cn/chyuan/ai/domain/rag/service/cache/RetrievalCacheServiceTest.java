package cn.chyuan.ai.domain.rag.service.cache;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索结果缓存纯内核单测（工单 0167）
 * <p>
 * 覆盖验收：归一化与键生成（等价 query 同键）/ TTL 淘汰 / LRU 淘汰 / hit/miss 计数
 */
class RetrievalCacheServiceTest {

    /** 可推进的固定时钟（Clock.offset 相对基准偏移） */
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private MutableClock clock;

    private RetrievalCacheService newService(int capacity, long ttlSeconds, Set<String> stopwords) {
        clock = new MutableClock(BASE);
        return new RetrievalCacheService(capacity, ttlSeconds, clock, stopwords);
    }

    @Test
    void equivalentQueriesShareTheSameKey() {
        // 停用词表注入（W3 表），使"的"参与归一化过滤
        RetrievalCacheService service = newService(10, 300, Set.of("的"));

        // 小写/空白等价：等价 query 同键
        assertThat(service.buildKey("Hello World")).isEqualTo(service.buildKey("hello world"));
        assertThat(service.buildKey("helloworld")).isEqualTo(service.buildKey("Hello World"));
        // 停用词等价："什么的"与"什么"同键（"的"被停用词表过滤）
        assertThat(service.buildKey("什么的 RAG 用途")).isEqualTo(service.buildKey("什么 RAG 用途"));
        // 不同 query 不同键
        assertThat(service.buildKey("RAG 用途")).isNotEqualTo(service.buildKey("RAG 原理"));
    }

    @Test
    void normalizationRemovesStopwordsAndWhitespace() {
        Set<String> stopwords = Set.of("the", "的");
        RetrievalCacheService service = newService(10, 300, stopwords);

        assertThat(service.normalizeQuery("Hello World")).isEqualTo("helloworld");
        assertThat(service.normalizeQuery("what is the RAG 的用途")).isEqualTo("whatisrag用途");
        assertThat(service.normalizeQuery(null)).isEmpty();
    }

    @Test
    void ttlExpiryEvictsEntry() {
        RetrievalCacheService service = newService(10, 60, null);
        service.put("query", List.of(result("缓存块")));

        // TTL 内命中
        assertThat(service.get("query")).isPresent();
        assertThat(service.hitCount()).isEqualTo(1);

        // 推进超过 TTL（60s）→ 过期淘汰计 miss
        clock.advance(Duration.ofSeconds(61));
        assertThat(service.get("query")).isEmpty();
        assertThat(service.size()).isZero();
        assertThat(service.missCount()).isEqualTo(1);
    }

    @Test
    void lruEvictsLeastRecentlyAccessed() {
        RetrievalCacheService service = newService(2, 300, null);
        service.put("a", List.of(result("A")));
        service.put("b", List.of(result("B")));

        // 访问 a：a 变为最近使用
        service.get("a");

        // 放入 c：容量 2 → 淘汰最久未访问的 b
        service.put("c", List.of(result("C")));

        assertThat(service.get("a")).isPresent();
        assertThat(service.get("c")).isPresent();
        assertThat(service.get("b")).isEmpty();
    }

    @Test
    void insertionWithoutAccessEvictsOldest() {
        RetrievalCacheService service = newService(2, 300, null);
        service.put("a", List.of(result("A")));
        service.put("b", List.of(result("B")));
        service.put("c", List.of(result("C")));

        // a 最先插入且未被访问 → 淘汰
        assertThat(service.get("a")).isEmpty();
        assertThat(service.get("b")).isPresent();
        assertThat(service.get("c")).isPresent();
    }

    @Test
    void hitAndMissCountersTrackAccess() {
        RetrievalCacheService service = newService(10, 300, null);

        service.get("missing");
        service.put("hit", List.of(result("R")));
        service.get("hit");
        service.get("hit");

        assertThat(service.hitCount()).isEqualTo(2);
        assertThat(service.missCount()).isEqualTo(1);
    }

    @Test
    void putNullsAreIgnored() {
        RetrievalCacheService service = newService(10, 300, null);
        service.put(null, List.of(result("R")));
        service.put("query", null);
        assertThat(service.size()).isZero();
    }

    @Test
    void invalidConfigFallsBackToDefaults() {
        // 非法容量/TTL 回退默认（容量 1000 / TTL 300s）
        clock = new MutableClock(BASE);
        RetrievalCacheService service = new RetrievalCacheService(0, 0, clock, null);
        service.put("q", List.of(result("R")));
        clock.advance(Duration.ofSeconds(299));
        assertThat(service.get("q")).isPresent();
        clock.advance(Duration.ofSeconds(2));
        assertThat(service.get("q")).isEmpty();
    }

    private VectorSearchResultVO result(String content) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", "d1");
        return VectorSearchResultVO.builder()
                .content(content)
                .score(0.5f)
                .metadata(metadata)
                .build();
    }

    /** 可手动推进的测试时钟 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
