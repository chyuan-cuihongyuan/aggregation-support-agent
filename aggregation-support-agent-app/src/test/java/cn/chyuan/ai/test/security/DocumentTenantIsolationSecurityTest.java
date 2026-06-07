package cn.chyuan.ai.test.security;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.audit.model.entity.AuditLogEntity;
import cn.chyuan.ai.domain.audit.service.IAuditLogService;
import cn.chyuan.ai.domain.audit.model.valobj.AuditQueryVO;
import cn.chyuan.ai.domain.audit.model.valobj.AuditStatVO;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.rag.adapter.repository.IDocumentMetadataRepository;
import cn.chyuan.ai.domain.rag.model.entity.DocumentMetadataEntity;
import cn.chyuan.ai.trigger.filter.JwtAuthFilter;
import cn.chyuan.ai.trigger.http.DocumentController;
import cn.chyuan.ai.types.enums.AuditAction;
import cn.chyuan.ai.types.enums.AuditResult;
import cn.chyuan.ai.types.enums.ResponseCode;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DocumentTenantIsolationSecurityTest {

    private DocumentController controller;
    private InMemoryDocumentMetadataRepository repository;

    @Before
    public void setUp() {
        controller = new DocumentController();
        repository = new InMemoryDocumentMetadataRepository();
        repository.save(document("doc-a", "101"));
        repository.save(document("doc-b", "202"));
        ReflectionTestUtils.setField(controller, "documentMetadataRepository", repository);
        ReflectionTestUtils.setField(controller, "auditLogService", new NoopAuditLogService());
    }

    @Test
    public void getDocument_rejectsDocumentOwnedByAnotherUser() {
        MockHttpServletRequest aliceRequest = authenticatedRequest(101L, "alice");

        Response<?> response = controller.getDocument(aliceRequest, "doc-b");

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        assertEquals("文档不存在", response.getInfo());
    }

    @Test
    public void deleteDocument_doesNotDeleteDocumentOwnedByAnotherUser() {
        MockHttpServletRequest aliceRequest = authenticatedRequest(101L, "alice");

        Response<Void> response = controller.deleteDocument(aliceRequest, "doc-b");

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
        assertFalse(repository.isDeleted("doc-b"));
    }

    @Test
    public void deleteDocument_deletesOnlyCurrentUsersDocument() {
        MockHttpServletRequest aliceRequest = authenticatedRequest(101L, "alice");

        Response<Void> response = controller.deleteDocument(aliceRequest, "doc-a");

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(TenantScopeVO.singleUser("101"), repository.lastDeletedScope);
        assertFalse(repository.isDeleted("doc-b"));
    }

    private static MockHttpServletRequest authenticatedRequest(Long userId, String username) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthFilter.ATTR_USER_ID, userId);
        request.setAttribute(JwtAuthFilter.ATTR_USERNAME, username);
        request.setAttribute(JwtAuthFilter.ATTR_ROLE, "user");
        return request;
    }

    private static DocumentMetadataEntity document(String documentId, String ownerUserId) {
        return DocumentMetadataEntity.builder()
                .documentId(documentId)
                .tenantId(ownerUserId)
                .ownerUserId(ownerUserId)
                .userId(ownerUserId)
                .fileName(documentId + ".txt")
                .deletedFlag(0)
                .build();
    }

    private static class InMemoryDocumentMetadataRepository implements IDocumentMetadataRepository {
        private final List<DocumentMetadataEntity> documents = new ArrayList<>();
        private TenantScopeVO lastDeletedScope;

        @Override
        public void save(DocumentMetadataEntity entity) {
            documents.add(entity);
        }

        @Override
        public List<DocumentMetadataEntity> queryByScope(TenantScopeVO scope) {
            return documents.stream()
                    .filter(entity -> visibleToScope(entity, scope))
                    .toList();
        }

        @Override
        public DocumentMetadataEntity adminQueryByDocumentId(String documentId) {
            return documents.stream()
                    .filter(entity -> Objects.equals(entity.getDocumentId(), documentId))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public DocumentMetadataEntity queryByDocumentId(String documentId, TenantScopeVO scope) {
            return documents.stream()
                    .filter(entity -> Objects.equals(entity.getDocumentId(), documentId))
                    .filter(entity -> visibleToScope(entity, scope))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public void updateStatus(String documentId, String status, Integer totalChunks,
                                 Integer totalChars, Integer sectionCount, String errorMessage,
                                 TenantScopeVO scope) {
            DocumentMetadataEntity entity = queryByDocumentId(documentId, scope);
            if (entity != null) {
                entity.setProcessingStatus(status);
                entity.setTotalChunks(totalChunks);
                entity.setTotalChars(totalChars);
                entity.setSectionCount(sectionCount);
                entity.setErrorMessage(errorMessage);
            }
        }

        @Override
        public void markDeletedByDocumentId(String documentId, TenantScopeVO scope) {
            this.lastDeletedScope = scope;
            DocumentMetadataEntity entity = queryByDocumentId(documentId, scope);
            if (entity != null) {
                entity.setDeletedFlag(1);
            }
        }

        private boolean isDeleted(String documentId) {
            DocumentMetadataEntity entity = adminQueryByDocumentId(documentId);
            return entity != null && Objects.equals(1, entity.getDeletedFlag());
        }

        private boolean visibleToScope(DocumentMetadataEntity entity, TenantScopeVO scope) {
            return !Objects.equals(1, entity.getDeletedFlag())
                    && Objects.equals(entity.getTenantId(), scope.getTenantId())
                    && Objects.equals(entity.getOwnerUserId(), scope.getOwnerUserId());
        }
    }

    private static class NoopAuditLogService implements IAuditLogService {
        @Override
        public void recordAsync(Long userId, String username, AuditAction action, String resourceType,
                                String resourceId, AuditResult result, String traceId, String detail,
                                String ipAddress, String userAgent) {
        }

        @Override
        public List<AuditLogEntity> queryByCondition(AuditQueryVO query) {
            return List.of();
        }

        @Override
        public long countByCondition(AuditQueryVO query) {
            return 0;
        }

        @Override
        public List<AuditStatVO> statByAction(AuditQueryVO query) {
            return List.of();
        }

        @Override
        public List<AuditStatVO> statByUser(AuditQueryVO query) {
            return List.of();
        }
    }
}
