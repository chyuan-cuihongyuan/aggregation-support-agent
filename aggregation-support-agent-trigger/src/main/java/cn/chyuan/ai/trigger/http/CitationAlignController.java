package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.CitationAlignRequestDTO;
import cn.chyuan.ai.api.dto.CitationAlignResponseDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.rag.service.alignment.CitationAlignService;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 引用溯源对齐端点（工单 0169，W7）
 * <p>
 * POST /api/v1/insight/citation-align：无状态即算 —
 * 答案按句切分后与命中片段做词面 Jaccard 对齐，输出每句最佳来源片段索引与对齐度、
 * 未对齐句列表；对齐均值 = citationScore 供评测复用（不落库、不改状态）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/insight")
public class CitationAlignController {

    @Autowired(required = false)
    private CitationAlignService citationAlignService;

    @PostMapping("/citation-align")
    public Response<CitationAlignResponseDTO> align(@RequestBody CitationAlignRequestDTO request) {
        // 参数合同：请求体与答案必填（空答案允许：返回空结果与 citationScore=0）
        if (request == null || request.getAnswer() == null) {
            return Response.<CitationAlignResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("answer 不能为空")
                    .build();
        }
        if (citationAlignService == null) {
            return Response.<CitationAlignResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("引用对齐服务未启用")
                    .build();
        }

        try {
            List<String> sources = extractSources(request);
            CitationAlignService.CitationAlignResult result = citationAlignService.align(request.getAnswer(), sources);
            return Response.<CitationAlignResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toDTO(result))
                    .build();
        } catch (Exception e) {
            log.error("引用溯源对齐失败", e);
            return Response.<CitationAlignResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("对齐失败: " + e.getMessage())
                    .build();
        }
    }

    /** 提取片段内容列表（保持请求顺序作为来源索引） */
    private List<String> extractSources(CitationAlignRequestDTO request) {
        List<String> sources = new ArrayList<>();
        if (request.getSources() == null) {
            return sources;
        }
        for (CitationAlignRequestDTO.SourceItem item : request.getSources()) {
            sources.add(item != null && item.getContent() != null ? item.getContent() : "");
        }
        return sources;
    }

    private CitationAlignResponseDTO toDTO(CitationAlignService.CitationAlignResult result) {
        CitationAlignResponseDTO dto = new CitationAlignResponseDTO();
        dto.setCitationScore(result.citationScore());
        dto.setUnalignedSentences(result.unalignedSentences());
        List<CitationAlignResponseDTO.SentenceItem> items = new ArrayList<>();
        for (CitationAlignService.SentenceAlignment alignment : result.sentences()) {
            CitationAlignResponseDTO.SentenceItem item = new CitationAlignResponseDTO.SentenceItem();
            item.setSentence(alignment.sentence());
            item.setBestSourceIndex(alignment.bestSourceIndex());
            item.setAlignment(alignment.alignment());
            items.add(item);
        }
        dto.setSentences(items);
        return dto;
    }
}
