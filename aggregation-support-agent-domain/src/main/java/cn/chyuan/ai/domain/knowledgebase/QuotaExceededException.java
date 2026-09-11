package cn.chyuan.ai.domain.knowledgebase;

/**
 * 配额超限异常（工单 0168）— 文档/分块入库前校验被拒时抛出，
 * 携带明确的超限维度与边界信息，供 trigger 层转为用户可读错误
 */
public class QuotaExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QuotaExceededException(String message) {
        super(message);
    }
}
