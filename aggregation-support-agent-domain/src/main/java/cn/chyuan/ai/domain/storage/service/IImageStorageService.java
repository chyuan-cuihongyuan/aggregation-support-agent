package cn.chyuan.ai.domain.storage.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * 图片存储服务接口
 * 支持多种存储策略：本地文件系统、阿里云OSS、腾讯云COS
 */
public interface IImageStorageService {

    /**
     * 上传图片
     *
     * @param file     文件
     * @param userId   用户ID
     * @param fileName 目标文件名
     * @return 图片访问URL
     */
    String upload(MultipartFile file, String userId, String fileName) throws Exception;

    /**
     * 获取图片输入流
     *
     * @param userId   用户ID
     * @param fileName 文件名
     * @return 输入流
     */
    InputStream getInputStream(String userId, String fileName) throws Exception;

    /**
     * 删除图片
     *
     * @param userId   用户ID
     * @param fileName 文件名
     */
    void delete(String userId, String fileName) throws Exception;

    /**
     * 查询用户图片列表
     *
     * @param userId 用户ID
     * @return 图片文件名列表
     */
    List<String> listImages(String userId) throws Exception;

    /**
     * 获取图片访问URL
     *
     * @param userId   用户ID
     * @param fileName 文件名
     * @return 访问URL
     */
    String getImageUrl(String userId, String fileName);

    /**
     * 检查文件是否存在
     *
     * @param userId   用户ID
     * @param fileName 文件名
     * @return 是否存在
     */
    boolean exists(String userId, String fileName) throws Exception;
}