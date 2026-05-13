package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.ChatHistoryResponseDTO;
import cn.chyuan.ai.api.dto.ChatHistorySaveDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import cn.chyuan.ai.domain.agent.model.entity.ChatHistoryEntity;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
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
    public Response<Boolean> saveChatHistory(@RequestBody ChatHistorySaveDTO dto) {
        try {
            log.info("保存对话历史 userId:{} agentId:{}", dto.getUserId(), dto.getAgentId());
            ChatHistoryEntity entity = ChatHistoryEntity.builder()
                    .userId(dto.getUserId())
                    .agentId(dto.getAgentId())
                    .agentName(dto.getAgentName())
                    .sessionId(dto.getSessionId())
                    .question(dto.getQuestion())
                    .answer(dto.getAnswer())
                    .build();
            chatHistoryRepository.save(entity);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("保存对话历史失败 userId:{}", dto.getUserId(), e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat_history/query", method = RequestMethod.GET)
    public Response<List<ChatHistoryResponseDTO>> queryChatHistory(@RequestParam("userId") String userId) {
        try {
            log.info("查询对话历史 userId:{}", userId);
            List<ChatHistoryEntity> entities = chatHistoryRepository.queryByUserId(userId);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            List<ChatHistoryResponseDTO> dtos = entities.stream().map(e -> {
                ChatHistoryResponseDTO dto = new ChatHistoryResponseDTO();
                dto.setId(e.getId());
                dto.setUserId(e.getUserId());
                dto.setAgentId(e.getAgentId());
                dto.setAgentName(e.getAgentName());
                dto.setSessionId(e.getSessionId());
                dto.setQuestion(e.getQuestion());
                dto.setAnswer(e.getAnswer());
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
    public Response<Boolean> deleteChatHistory(@RequestParam("userId") String userId) {
        try {
            log.info("清空对话历史 userId:{}", userId);
            chatHistoryRepository.deleteByUserId(userId);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("清空对话历史失败 userId:{}", userId, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
