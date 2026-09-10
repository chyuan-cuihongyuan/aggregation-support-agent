package cn.chyuan.ai.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * pgvector 向量数据源装配（工单 0129）。
 *
 * <p>优先级：配置了 pgvector.datasource.url → 独立 PG 连接池（主库仍可为 MySQL）；
 * 未配置 → 回落主数据源（PG 已是主库的常规形态）。@Bean 参数注入天然排除
 * 正在创建的自身（self-reference），ObjectProvider 解析到的即主数据源。
 */
@Configuration
@EnableConfigurationProperties(PgVectorConfigProperties.class)
public class PgVectorDataSourceConfig {

    @Bean("vectorDataSource")
    public DataSource vectorDataSource(PgVectorConfigProperties properties,
            ObjectProvider<DataSource> dataSources) {
        PgVectorConfigProperties.Datasource ds = properties.getDatasource();
        if (ds == null || ds.getUrl() == null || ds.getUrl().isBlank()) {
            DataSource primary = dataSources.getIfAvailable();
            if (primary == null) {
                throw new IllegalStateException(
                        "pgvector.datasource.url 未配置且无主数据源可回落——请配置主数据源或 pgvector.datasource.*");
            }
            return primary;
        }
        HikariDataSource hikari = new HikariDataSource();
        hikari.setJdbcUrl(ds.getUrl());
        hikari.setUsername(ds.getUsername());
        hikari.setPassword(ds.getPassword());
        hikari.setMaximumPoolSize(ds.getMaxPoolSize());
        hikari.setPoolName("Vector_HikariCP");
        return hikari;
    }

    @Bean("vectorJdbc")
    public JdbcTemplate vectorJdbc(@org.springframework.beans.factory.annotation.Qualifier("vectorDataSource") DataSource vectorDataSource) {
        return new JdbcTemplate(vectorDataSource);
    }
}
