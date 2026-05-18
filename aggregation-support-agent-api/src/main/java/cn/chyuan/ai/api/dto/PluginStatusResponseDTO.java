package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 插件状态响应DTO
 */
@Data
public class PluginStatusResponseDTO {
    /**
     * 内置插件状态
     */
    private Map<String, PluginAvailabilityDTO> builtIn;

    /**
     * MCP服务器列表
     */
    private List<MCPServerConfigDTO> mcpServers;

    /**
     * 自定义工具列表
     */
    private List<CustomToolConfigDTO> customTools;

    @Data
    public static class PluginAvailabilityDTO {
        private boolean available;
        private boolean enabled;
    }

    @Data
    public static class MCPServerConfigDTO {
        private String id;
        private String name;
        private String description;
        private boolean enabled;
        private boolean available;
        private boolean connected;
        private boolean connecting;
        private List<MCPToolDTO> tools;
        private MCPTransportConfigDTO config;
    }

    @Data
    public static class MCPToolDTO {
        private String name;
        private String description;
    }

    @Data
    public static class MCPTransportConfigDTO {
        private String transport;
        private String command;
        private List<String> args;
        private String url;
        private Map<String, String> env;
    }

    @Data
    public static class CustomToolConfigDTO {
        private String id;
        private String name;
        private String description;
        private boolean enabled;
        private boolean available;
        private String toolType;
        private ToolConfigDTO config;
        private ToolTestResultDTO lastTest;
    }

    @Data
    public static class ToolConfigDTO {
        private String apiUrl;
        private String apiMethod;
        private Map<String, String> apiHeaders;
        private String webhookUrl;
        private String scriptPath;
    }

    @Data
    public static class ToolTestResultDTO {
        private boolean success;
        private String timestamp;
        private Long responseTime;
        private String error;
    }
}
