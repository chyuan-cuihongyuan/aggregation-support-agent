package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.CrossModalSearchResultDTO;
import cn.chyuan.ai.api.dto.SearchTestRequestDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.multimodal.model.valobj.CrossModalSearchResultVO;
import cn.chyuan.ai.domain.multimodal.service.IMultimodalService;
import cn.chyuan.ai.trigger.support.CurrentUserSupport;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 跨模态检索控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search")
public class CrossModalSearchController {

    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 50;

    @Autowired(required = false)
    private IMultimodalService multimodalService;

    @PostMapping("/text-to-image")
    public Response<List<CrossModalSearchResultDTO>> textToImageSearch(
            HttpServletRequest servletRequest,
            @RequestBody SearchTestRequestDTO request) {
        try {
            if (multimodalService == null) {
                return serviceUnavailable();
            }

            String query = request.getQuery();
            if (query == null || query.trim().isEmpty()) {
                return illegalParameter("查询内容不能为空");
            }

            TenantScopeVO scope = currentScope(servletRequest);
            List<CrossModalSearchResultVO> results = multimodalService.textToImageSearch(
                    query.trim(),
                    normalizeTopK(request.getTopK()),
                    scope
            );

            return success(toDTOList(results));
        } catch (Exception e) {
            log.error("以文搜图失败", e);
            return error("以文搜图失败: " + e.getMessage());
        }
    }

    @PostMapping("/image-to-text")
    public Response<List<CrossModalSearchResultDTO>> imageToTextSearch(
            HttpServletRequest servletRequest,
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "topK", required = false) Integer topK) {
        try {
            if (multimodalService == null) {
                return serviceUnavailable();
            }
            if (image == null || image.isEmpty()) {
                return illegalParameter("图片不能为空");
            }

            TenantScopeVO scope = currentScope(servletRequest);
            List<CrossModalSearchResultVO> results = multimodalService.imageToTextSearch(
                    image.getBytes(),
                    normalizeTopK(topK),
                    scope
            );

            return success(toDTOList(results));
        } catch (Exception e) {
            log.error("以图搜文失败", e);
            return error("以图搜文失败: " + e.getMessage());
        }
    }

    private TenantScopeVO currentScope(HttpServletRequest request) {
        return TenantScopeVO.singleUser(CurrentUserSupport.requireUserIdString(request));
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private List<CrossModalSearchResultDTO> toDTOList(List<CrossModalSearchResultVO> results) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        return results.stream().map(this::toDTO).collect(Collectors.toList());
    }

    private CrossModalSearchResultDTO toDTO(CrossModalSearchResultVO vo) {
        CrossModalSearchResultDTO dto = new CrossModalSearchResultDTO();
        dto.setItemId(vo.getItemId());
        dto.setModalityType(vo.getModalityType());
        dto.setContent(vo.getContent());
        dto.setImageUrl(vo.getImageUrl());
        dto.setScore(vo.getScore());
        dto.setMetadata(vo.getMetadata());
        return dto;
    }

    private Response<List<CrossModalSearchResultDTO>> success(List<CrossModalSearchResultDTO> data) {
        return Response.<List<CrossModalSearchResultDTO>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private Response<List<CrossModalSearchResultDTO>> serviceUnavailable() {
        return Response.<List<CrossModalSearchResultDTO>>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info("多模态服务未启用")
                .data(Collections.emptyList())
                .build();
    }

    private Response<List<CrossModalSearchResultDTO>> illegalParameter(String info) {
        return Response.<List<CrossModalSearchResultDTO>>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(info)
                .data(Collections.emptyList())
                .build();
    }

    private Response<List<CrossModalSearchResultDTO>> error(String info) {
        return Response.<List<CrossModalSearchResultDTO>>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(info)
                .data(Collections.emptyList())
                .build();
    }
}
