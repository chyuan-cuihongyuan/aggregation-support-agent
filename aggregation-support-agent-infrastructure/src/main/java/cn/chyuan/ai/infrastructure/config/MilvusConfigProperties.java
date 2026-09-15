package cn.chyuan.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Milvus 向量数据库连接配置属性 — 从 application.yml 中读取 milvus.* 配置
 * <p>
 * 配置示例：
 * <pre>
 * milvus:
 *   host: 127.0.0.1
 *   port: 19530
 *   collection-name: biz
 *   dimension: 1024
 *   index-type: IVF_FLAT
 *   metric-type: L2
 *   nlist: 1024
 *   top-k: 3
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "milvus")
public class MilvusConfigProperties {

    /** Milvus 服务地址 */
    private String host = "127.0.0.1";

    /** Milvus gRPC 端口 */
    private int port = 19530;

    /** 集合名称 */
    private String collectionName = "biz";

    /** 向量维度（智谱 embedding-3 默认输出 2048 维，可配置 256/512/1024/2048） */
    private int dimension = 2048;

    /** 索引类型 */
    private String indexType = "IVF_FLAT";

    /** 距离度量类型 */
    private String metricType = "L2";

    /** IVF 聚类数量 */
    private int nlist = 1024;

    /** 默认检索返回数量（当前无消费者：检索链路显式传参直达 withTopK；yml 的
     * milvus.top-k 仅落在本字段上，预留可配默认——G50 判据，loop-434） */
    private int topK = 3;

}
