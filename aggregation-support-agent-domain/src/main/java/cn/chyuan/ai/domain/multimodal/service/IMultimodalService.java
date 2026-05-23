package cn.chyuan.ai.domain.multimodal.service;

import cn.chyuan.ai.domain.multimodal.model.entity.ImageEntity;
import cn.chyuan.ai.domain.multimodal.model.valobj.CrossModalSearchResultVO;

import java.util.List;

/**
 * 多模态领域服务接口
 */
public interface IMultimodalService {

    ImageEntity uploadImage(String fileName, byte[] imageData, String mimeType, String userId);

    List<CrossModalSearchResultVO> textToImageSearch(String query, int topK);

    List<CrossModalSearchResultVO> imageToTextSearch(byte[] imageData, int topK);

    List<ImageEntity> listImages(String userId);

    void deleteImage(String imageId);
}
