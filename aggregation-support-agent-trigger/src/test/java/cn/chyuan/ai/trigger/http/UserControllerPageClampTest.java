package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.auth.adapter.repository.IUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户列表分页护栏收敛（SELFLOOP3 loop-312，工单 0422/0423）
 */
class UserControllerPageClampTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(Response<Map<String, Object>> response) {
        return response.getData();
    }

    private UserController controllerWith(IUserRepository repository) {
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userRepository", repository);
        return controller;
    }

    @Test
    void oversizedPageSizeClampsTo100() {
        IUserRepository repository = mock(IUserRepository.class);
        when(repository.queryList(anyInt(), anyInt())).thenReturn(List.of());
        when(repository.countAll()).thenReturn(0);

        Map<String, Object> data = dataOf(controllerWith(repository).listUsers(1, 100000));

        assertThat(data.get("pageSize")).isEqualTo(100);
        verify(repository).queryList(eq(1), eq(100));
    }

    @Test
    void nonPositivePageSizeFallsBackTo20() {
        IUserRepository repository = mock(IUserRepository.class);
        when(repository.queryList(anyInt(), anyInt())).thenReturn(List.of());
        when(repository.countAll()).thenReturn(0);

        Map<String, Object> data = dataOf(controllerWith(repository).listUsers(1, 0));

        assertThat(data.get("pageSize")).isEqualTo(20);
    }

    @Test
    void negativePageNormalizesToOne() {
        IUserRepository repository = mock(IUserRepository.class);
        when(repository.queryList(anyInt(), anyInt())).thenReturn(List.of());
        when(repository.countAll()).thenReturn(0);

        Map<String, Object> data = dataOf(controllerWith(repository).listUsers(-5, 20));

        assertThat(data.get("page")).isEqualTo(1);
        verify(repository).queryList(eq(1), eq(20));
    }
}
