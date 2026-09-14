package cn.chyuan.ai.domain.rag.service.chunker;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context 批大小外置化钳位语义（SELFLOOP3 loop-325，工单 0448/0449）
 */
class ContextualRetrievalChunkerBatchSizeTest {

    private ContextualRetrievalChunker chunkerWith(int configured) {
        ContextualRetrievalChunker chunker = new ContextualRetrievalChunker();
        ReflectionTestUtils.setField(chunker, "contextBatchSize", configured);
        return chunker;
    }

    @Test
    void defaultEquivalentValuePassesThrough() {
        assertThat(chunkerWith(5).resolveContextBatchSize()).isEqualTo(5);
        assertThat(chunkerWith(10).resolveContextBatchSize()).isEqualTo(10);
    }

    @Test
    void nonPositiveClampsToOne() {
        assertThat(chunkerWith(0).resolveContextBatchSize()).isEqualTo(1);
        assertThat(chunkerWith(-3).resolveContextBatchSize()).isEqualTo(1);
    }
}
