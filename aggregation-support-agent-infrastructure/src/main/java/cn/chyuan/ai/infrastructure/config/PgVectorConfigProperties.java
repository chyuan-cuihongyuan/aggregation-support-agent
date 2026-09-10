package cn.chyuan.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pgvector 向量引擎配置（工单 0129，三期 Milvus→pgvector 替换）
 *
 * <p>默认启用（pgvector.enabled 缺省视为 true）；与 Milvus 过渡开关互斥使用
 * （两者同开时注入歧义，矩阵治理见工单 0131）。
 */
@Data
@ConfigurationProperties(prefix = "pgvector")
public class PgVectorConfigProperties {

    /** 引擎开关：默认启用（matchIfMissing=true） */
    private boolean enabled = true;

    /** 向量表名（RAG 文档块） */
    private String table = "biz_chunks";

    /** 向量维度（与 embedding 提供方一致；超 2000 维不可建 vector 索引，列类型固定 halfvec） */
    private int dimension = 2048;

    /** HNSW 参数：每层最大连接数 */
    private int m = 16;

    /** HNSW 参数：构建期候选队列长度 */
    private int efConstruction = 200;

    /** HNSW 参数：查询期搜索宽度（对齐 Milvus 时代 nprobe=128 的召回口径） */
    private int efSearch = 128;

    /** 独立向量数据源（可选：主库为 MySQL 而向量在 PG 时配置；缺省回落主数据源） */
    private Datasource datasource = new Datasource();

    @Data
    public static class Datasource {
        /** JDBC URL（jdbc:postgresql://host:5432/db?currentSchema=public），空=复用主数据源 */
        private String url;
        private String username;
        private String password;
        private int maxPoolSize = 8;
    }
}
