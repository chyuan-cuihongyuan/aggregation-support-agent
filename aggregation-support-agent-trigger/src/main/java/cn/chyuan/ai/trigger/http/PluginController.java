package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.PluginConfigRequestDTO;
import cn.chyuan.ai.api.dto.PluginStatusResponseDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 插件管理控制器 — 插件状态查询和配置管理
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugins")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true", allowedHeaders = "*")
public class PluginController {

    @Autowired(required = false)
    private cn.chyuan.ai.domain.rag.service.IRagService ragService;

    /**
     * 获取插件状态
     */
    @RequestMapping(value = "status", method = RequestMethod.GET)
    public Response<PluginStatusResponseDTO> getPluginStatus() {
        try {
            log.info("查询插件状态");
            PluginStatusResponseDTO response = new PluginStatusResponseDTO();

            // 内置插件状态
            Map<String, PluginStatusResponseDTO.PluginAvailabilityDTO> builtIn = new HashMap<>();

            // 知识库插件
            PluginStatusResponseDTO.PluginAvailabilityDTO knowledge = new PluginStatusResponseDTO.PluginAvailabilityDTO();
            knowledge.setAvailable(ragService != null);
            knowledge.setEnabled(ragService != null);
            builtIn.put("knowledge", knowledge);

            // AIOps插件
            PluginStatusResponseDTO.PluginAvailabilityDTO aiops = new PluginStatusResponseDTO.PluginAvailabilityDTO();
            aiops.setAvailable(true);
            aiops.setEnabled(true);
            builtIn.put("aiops", aiops);

            // 导出插件
            PluginStatusResponseDTO.PluginAvailabilityDTO exportPlugin = new PluginStatusResponseDTO.PluginAvailabilityDTO();
            exportPlugin.setAvailable(true);
            exportPlugin.setEnabled(true);
            builtIn.put("export", exportPlugin);

            // 历史插件
            PluginStatusResponseDTO.PluginAvailabilityDTO history = new PluginStatusResponseDTO.PluginAvailabilityDTO();
            history.setAvailable(true);
            history.setEnabled(true);
            builtIn.put("history", history);

            response.setBuiltIn(builtIn);
            response.setMcpServers(new ArrayList<>());
            response.setCustomTools(new ArrayList<>());

            return Response.<PluginStatusResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();
        } catch (Exception e) {
            log.error("查询插件状态失败", e);
            return Response.<PluginStatusResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询插件状态失败")
                    .build();
        }
    }

    /**
     * 保存插件配置
     */
    @RequestMapping(value = "{pluginId}/config", method = RequestMethod.POST)
    public Response<Boolean> savePluginConfig(
            @PathVariable("pluginId") String pluginId,
            @RequestBody PluginConfigRequestDTO config) {
        try {
            log.info("保存插件配置: pluginId={}", pluginId);
            // 这里可以实现配置持久化逻辑
            // 目前返回成功，配置由前端localStorage管理
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("保存插件配置失败: pluginId={}", pluginId, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("保存插件配置失败")
                    .build();
        }
    }
}
