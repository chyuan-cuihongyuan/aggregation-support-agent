package cn.chyuan.ai.domain.knowledgegraph.adapter.port;

import cn.chyuan.ai.domain.knowledgegraph.model.valobj.EntityExtractionResultVO;

import java.util.List;

/**
 * 实体抽取服务端口
 */
public interface IEntityExtractionService {

    /**
     * 从文本中抽取实体和关系
     */
    EntityExtractionResultVO extractEntities(String text, String context);

    /**
     * 批量抽取
     */
    List<EntityExtractionResultVO> batchExtract(List<String> texts, List<String> contexts);
}
