package cn.chyuan.ai.test.trigger.http;

import cn.chyuan.ai.api.dto.CitationAlignRequestDTO;
import cn.chyuan.ai.api.dto.CitationAlignResponseDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.rag.service.alignment.CitationAlignService;
import cn.chyuan.ai.domain.rag.service.alignment.CitationAlignService.CitationAlignResult;
import cn.chyuan.ai.domain.rag.service.alignment.CitationAlignService.SentenceAlignment;
import cn.chyuan.ai.trigger.http.CitationAlignController;
import cn.chyuan.ai.types.enums.ResponseCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 引用溯源对齐端点合同测试（工单 0169）
 * <p>
 * 覆盖验收：端点合同 — 成功对齐映射 / 空答案非法参数 / 服务缺席 / 来源透传。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("引用溯源对齐端点测试")
public class CitationAlignControllerTest {

    @Mock
    private CitationAlignService citationAlignService;

    @InjectMocks
    private CitationAlignController controller;

    @Test
    @DisplayName("成功对齐 — 返回逐句明细与 citationScore")
    public void testAlign_ReturnsSentencesAndScore() {
        CitationAlignResult result = new CitationAlignResult(
                List.of(new SentenceAlignment("向量检索很快。", 0, 0.8),
                        new SentenceAlignment("天气真好。", -1, 0.0)),
                List.of("天气真好。"),
                0.4);
        when(citationAlignService.align(eq("向量检索很快。天气真好。"), anyList())).thenReturn(result);

        Response<CitationAlignResponseDTO> response = controller.align(request("向量检索很快。天气真好。"));

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertNotNull(response.getData());
        assertEquals(0.4, response.getData().getCitationScore());
        assertEquals(2, response.getData().getSentences().size());
        assertEquals(0, response.getData().getSentences().get(0).getBestSourceIndex());
        assertEquals(0.8, response.getData().getSentences().get(0).getAlignment());
        assertEquals(-1, response.getData().getSentences().get(1).getBestSourceIndex());
        assertEquals(List.of("天气真好。"), response.getData().getUnalignedSentences());
    }

    @Test
    @DisplayName("来源内容按请求顺序透传")
    public void testAlign_PassesSourcesInOrder() {
        when(citationAlignService.align(anyString(), anyList()))
                .thenReturn(new CitationAlignResult(List.of(), List.of(), 0.0));

        CitationAlignRequestDTO request = request("任意答案");
        CitationAlignRequestDTO.SourceItem s1 = new CitationAlignRequestDTO.SourceItem();
        s1.setIndex(0);
        s1.setContent("片段一");
        CitationAlignRequestDTO.SourceItem s2 = new CitationAlignRequestDTO.SourceItem();
        s2.setContent("片段二");
        request.setSources(List.of(s1, s2));

        controller.align(request);

        verify(citationAlignService).align(eq("任意答案"), eq(List.of("片段一", "片段二")));
    }

    @Test
    @DisplayName("answer 缺失 — 非法参数")
    public void testAlign_MissingAnswerRejected() {
        Response<CitationAlignResponseDTO> response = controller.align(request(null));

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
    }

    @Test
    @DisplayName("请求体缺失 — 非法参数")
    public void testAlign_NullBodyRejected() {
        Response<CitationAlignResponseDTO> response = controller.align(null);

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), response.getCode());
    }

    @Test
    @DisplayName("对齐服务缺席 — 返回未错误而非 500")
    public void testAlign_ServiceAbsent() {
        CitationAlignController bareController = new CitationAlignController();

        Response<CitationAlignResponseDTO> response = bareController.align(request("答案"));

        assertEquals(ResponseCode.UN_ERROR.getCode(), response.getCode());
    }

    private CitationAlignRequestDTO request(String answer) {
        CitationAlignRequestDTO request = new CitationAlignRequestDTO();
        request.setAnswer(answer);
        return request;
    }
}
