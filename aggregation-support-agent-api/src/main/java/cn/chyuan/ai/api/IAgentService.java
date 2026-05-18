package cn.chyuan.ai.api;

import cn.chyuan.ai.api.dto.*;
import cn.chyuan.ai.api.response.Response;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 智能体服务接口
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/20 08:16
 */
public interface IAgentService {

    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();

    public Response<CreateSessionResponseDTO> createSession(HttpServletRequest request, CreateSessionRequestDTO requestDTO);

    Response<ChatResponseDTO> chat(HttpServletRequest request, ChatRequestDTO requestDTO);

    SseEmitter chatStream(HttpServletRequest request, ChatRequestDTO requestDTO);

}
