package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.ChatHistoryResponseDTO;
import cn.chyuan.ai.api.dto.ChatHistorySaveDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import cn.chyuan.ai.domain.agent.model.entity.ChatHistoryEntity;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.trigger.support.CurrentUserSupport;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/")
public class ChatHistoryController {

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @RequestMapping(value = "chat_history/save", method = RequestMethod.POST)
    public Response<Boolean> saveChatHistory(HttpServletRequest request, @RequestBody ChatHistorySaveDTO dto) {
        try {
            TenantScopeVO scope = currentScope(request);
            String userId = scope.getOwnerUserId();
            log.info("保存对话历史 userId:{} agentId:{}", userId, dto.getAgentId());
            ChatHistoryEntity entity = ChatHistoryEntity.builder()
                    .tenantId(scope.getTenantId())
                    .ownerUserId(scope.getOwnerUserId())
                    .userId(userId)
                    .agentId(dto.getAgentId())
                    .agentName(dto.getAgentName())
                    .sessionId(dto.getSessionId())
                    .question(dto.getQuestion())
                    .answer(dto.getAnswer())
                    .traceId("")
                    .build();
            chatHistoryRepository.save(entity);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("保存对话历史失败 agentId:{}", dto.getAgentId(), e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat_history/query", method = RequestMethod.GET)
    public Response<List<ChatHistoryResponseDTO>> queryChatHistory(HttpServletRequest request) {
        TenantScopeVO scope = currentScope(request);
        String userId = scope.getOwnerUserId();
        try {
            log.info("查询对话历史 userId:{}", userId);
            List<ChatHistoryEntity> entities = chatHistoryRepository.queryByScope(scope);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            List<ChatHistoryResponseDTO> dtos = entities.stream().map(e -> {
                ChatHistoryResponseDTO dto = new ChatHistoryResponseDTO();
                dto.setId(e.getId());
                dto.setTenantId(e.getTenantId());
                dto.setOwnerUserId(e.getOwnerUserId());
                dto.setUserId(e.getUserId());
                dto.setAgentId(e.getAgentId());
                dto.setAgentName(e.getAgentName());
                dto.setSessionId(e.getSessionId());
                dto.setQuestion(e.getQuestion());
                dto.setAnswer(e.getAnswer());
                dto.setTraceId(e.getTraceId());
                dto.setCreateTime(e.getCreateTime() != null ? sdf.format(e.getCreateTime()) : "");
                return dto;
            }).collect(Collectors.toList());
            return Response.<List<ChatHistoryResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dtos)
                    .build();
        } catch (Exception e) {
            log.error("查询对话历史失败 userId:{}", userId, e);
            return Response.<List<ChatHistoryResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat_history/delete", method = RequestMethod.POST)
    public Response<Boolean> deleteChatHistory(
            HttpServletRequest request,
            @RequestParam(value = "id", required = false) Long id) {
        TenantScopeVO scope = currentScope(request);
        String userId = scope.getOwnerUserId();
        try {
            if (id != null) {
                ChatHistoryEntity entity = chatHistoryRepository.queryById(id, scope);
                if (entity == null) {
                    throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "对话历史不存在");
                }
                log.info("删除单条对话历史 userId:{} id:{}", userId, id);
                chatHistoryRepository.deleteById(id, scope);
            } else {
                log.info("清空对话历史 userId:{}", userId);
                chatHistoryRepository.deleteByScope(scope);
            }
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("删除对话历史失败 userId:{}", userId, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private TenantScopeVO currentScope(HttpServletRequest request) {
        return TenantScopeVO.singleUser(CurrentUserSupport.requireUserIdString(request));
    }
}
