package cn.chyuan.ai.test.domain.auth;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("请求租户作用域上下文测试")
class RequestScopeContextTest {

    @AfterEach
    void tearDown() {
        RequestScopeContext.clear();
    }

    @Test
    @DisplayName("在 subscribeOn 工作线程执行前显式注入租户作用域")
    void shouldAttachScopeBeforeSubscribeOnWorkerRuns() {
        TenantScopeVO scope = TenantScopeVO.singleUser("10001");
        AtomicReference<TenantScopeVO> actualScope = new AtomicReference<>();

        Flowable.just("query")
                .doOnSubscribe(subscription -> RequestScopeContext.attach(scope))
                .map(query -> {
                    actualScope.set(RequestScopeContext.get());
                    return query;
                })
                .doFinally(RequestScopeContext::clear)
                .subscribeOn(Schedulers.io())
                .blockingSubscribe();

        assertEquals("10001", actualScope.get().getTenantId());
        assertEquals("10001", actualScope.get().getOwnerUserId());
        assertNull(RequestScopeContext.get());
    }

    @Test
    @DisplayName("attach null 时清理当前线程租户作用域")
    void shouldClearScopeWhenAttachNull() {
        RequestScopeContext.set(TenantScopeVO.singleUser("10001"));

        RequestScopeContext.attach(null);

        assertNull(RequestScopeContext.get());
    }
}
