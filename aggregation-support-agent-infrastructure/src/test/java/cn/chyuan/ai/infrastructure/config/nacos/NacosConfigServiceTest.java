package cn.chyuan.ai.infrastructure.config.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NacosConfigService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class NacosConfigServiceTest {

    @Mock
    private ConfigService configService;

    @Mock
    private Listener listener;

    private NacosConfigService nacosConfigService;

    @BeforeEach
    void setUp() {
        nacosConfigService = new NacosConfigService(configService);
    }

    @Test
    void testGetConfig_Success() throws NacosException {
        // Given
        String dataId = "test-config.yml";
        String group = "DEFAULT_GROUP";
        long timeout = 5000L;
        String expectedConfig = "server:\n  port: 8080";

        when(configService.getConfig(dataId, group, timeout)).thenReturn(expectedConfig);

        // When
        String actualConfig = nacosConfigService.getConfig(dataId, group, timeout);

        // Then
        assertEquals(expectedConfig, actualConfig);
        verify(configService, times(1)).getConfig(dataId, group, timeout);
    }

    @Test
    void testGetConfig_Failure() throws NacosException {
        // Given
        String dataId = "test-config.yml";
        String group = "DEFAULT_GROUP";
        long timeout = 5000L;

        when(configService.getConfig(dataId, group, timeout))
            .thenThrow(new NacosException(500, "Config not found"));

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            nacosConfigService.getConfig(dataId, group, timeout);
        });
    }

    @Test
    void testPublishConfig_Success() throws NacosException {
        // Given
        String dataId = "test-config.yml";
        String group = "DEFAULT_GROUP";
        String content = "server:\n  port: 8080";

        when(configService.publishConfig(dataId, group, content)).thenReturn(true);

        // When
        boolean result = nacosConfigService.publishConfig(dataId, group, content);

        // Then
        assertTrue(result);
        verify(configService, times(1)).publishConfig(dataId, group, content);
    }

    @Test
    void testPublishConfig_Failure() throws NacosException {
        // Given
        String dataId = "test-config.yml";
        String group = "DEFAULT_GROUP";
        String content = "server:\n  port: 8080";

        when(configService.publishConfig(dataId, group, content))
            .thenThrow(new NacosException(500, "Publish failed"));

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            nacosConfigService.publishConfig(dataId, group, content);
        });
    }

    @Test
    void testRemoveConfig_Success() throws NacosException {
        // Given
        String dataId = "test-config.yml";
        String group = "DEFAULT_GROUP";

        when(configService.removeConfig(dataId, group)).thenReturn(true);

        // When
        boolean result = nacosConfigService.removeConfig(dataId, group);

        // Then
        assertTrue(result);
        verify(configService, times(1)).removeConfig(dataId, group);
    }

    @Test
    void testRemoveConfig_Failure() throws NacosException {
        // Given
        String dataId = "test-config.yml";
        String group = "DEFAULT_GROUP";

        when(configService.removeConfig(dataId, group))
            .thenThrow(new NacosException(500, "Remove failed"));

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            nacosConfigService.removeConfig(dataId, group);
        });
    }

    @Test
    void testAddListener_Success() throws NacosException {
        // Given
        String dataId = "test-config.yml";
        String group = "DEFAULT_GROUP";

        doNothing().when(configService).addListener(dataId, group, listener);

        // When
        nacosConfigService.addListener(dataId, group, listener);

        // Then
        verify(configService, times(1)).addListener(dataId, group, listener);
    }

    @Test
    void testAddListener_Failure() throws NacosException {
        // Given
        String dataId = "test-config.yml";
        String group = "DEFAULT_GROUP";

        doThrow(new NacosException(500, "Add listener failed"))
            .when(configService).addListener(dataId, group, listener);

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            nacosConfigService.addListener(dataId, group, listener);
        });
    }

    @Test
    void testRemoveListener_Success() throws NacosException {
        // Given
        String dataId = "test-config.yml";
        String group = "DEFAULT_GROUP";

        doNothing().when(configService).removeListener(dataId, group, listener);

        // When
        nacosConfigService.removeListener(dataId, group, listener);

        // Then
        verify(configService, times(1)).removeListener(dataId, group, listener);
    }

    @Test
    void testRemoveListener_Failure() throws NacosException {
        // Given
        String dataId = "test-config.yml";
        String group = "DEFAULT_GROUP";

        doThrow(new NacosException(500, "Remove listener failed"))
            .when(configService).removeListener(dataId, group, listener);

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            nacosConfigService.removeListener(dataId, group, listener);
        });
    }

    @Test
    void testGetConfigService() {
        // When
        ConfigService result = nacosConfigService.getConfigService();

        // Then
        assertNotNull(result);
        assertEquals(configService, result);
    }
}
