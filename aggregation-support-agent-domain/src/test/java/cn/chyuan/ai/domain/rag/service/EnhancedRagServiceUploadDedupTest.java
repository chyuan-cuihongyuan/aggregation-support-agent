package cn.chyuan.ai.domain.rag.service;

import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParserFactory;
import cn.chyuan.ai.domain.rag.adapter.repository.IDocumentMetadataRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;
import cn.chyuan.ai.domain.rag.model.valobj.DocumentUploadCommand;
import cn.chyuan.ai.types.exception.AppException;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档上传内容去重 — 命中同哈希拒绝 / 未命中落库携哈希（工单 0402/0403，SELFLOOP3 loop-302）
 */
class EnhancedRagServiceUploadDedupTest {

    private EnhancedRagService serviceWith(IDocumentMetadataRepository repository,
                                           IDocumentParserFactory parserFactory) {
        EnhancedRagService service = new EnhancedRagService();
        ReflectionTestUtils.setField(service, "documentMetadataRepository", repository);
        ReflectionTestUtils.setField(service, "documentParserFactory", parserFactory);
        return service;
    }

    private DocumentUploadCommand command(byte[] raw) {
        return DocumentUploadCommand.builder()
                .fileName("a.txt")
                .rawContent(raw)
                .mimeType("text/plain")
                .userId("u1")
                .tenantId("t1")
                .build();
    }

    @Test
    void duplicateContentIsRejectedBeforeSave() {
        IDocumentMetadataRepository repository = mock(IDocumentMetadataRepository.class);
        when(repository.existsByContentHash(anyString(), any())).thenReturn(true);
        IDocumentParserFactory parserFactory = mock(IDocumentParserFactory.class);

        EnhancedRagService service = serviceWith(repository, parserFactory);

        // AppException 的用户可读信息在 info 字段（getMessage 为空），对齐仓内异常约定
        assertThatThrownBy(() -> service.uploadDocument(command("hello".getBytes())))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getInfo()).contains("相同内容的文档已存在"));
        verify(repository, never()).save(any());
    }

    @Test
    void firstUploadSavesMetadataWithSha256Hash() {
        IDocumentMetadataRepository repository = mock(IDocumentMetadataRepository.class);
        when(repository.existsByContentHash(anyString(), any())).thenReturn(false);
        // 解析器抛错以短路后续链路（分块/嵌入），聚焦本切片断言面：save 实体携带指纹
        IDocumentParserFactory parserFactory = mock(IDocumentParserFactory.class);
        when(parserFactory.parse(any(), anyString(), any())).thenThrow(new RuntimeException("stop pipeline"));

        EnhancedRagService service = serviceWith(repository, parserFactory);

        // 上传链路对解析失败 rethrow（记录日志后），本切片只关心 save 已先于解析发生且指纹正确
        assertThatThrownBy(() -> service.uploadDocument(command("hello".getBytes())))
                .hasMessage("stop pipeline");

        ArgumentCaptor<DocumentMetadataEntity> captor = ArgumentCaptor.forClass(DocumentMetadataEntity.class);
        verify(repository).save(captor.capture());
        String expected = Hashing.sha256().hashBytes("hello".getBytes()).toString();
        assertThat(captor.getValue().getContentHash()).isEqualTo(expected);
    }
}
