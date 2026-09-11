package cn.chyuan.ai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi 文档装配（D06）
 *
 * <p>提供 /v3/api-docs 与 /swagger-ui.html；按业务域分四组（chat/knowledge/ops/system）。
 * 鉴权契约与 JwtAuthFilter 实现一致：auth_token Cookie（登录接口签发）。</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aggregationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Aggregation Support Agent API")
                        .description("AIOps 智能运维平台：大模型对话、RAG 知识库、AIOps 告警分析、多智能体协作")
                        .version("1.0")
                        .contact(new Contact().name("chyuan").url("https://github.com/chyuan-cuihongyuan"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components()
                        .addSecuritySchemes("auth-token-cookie",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.COOKIE)
                                        .name("auth_token")
                                        .description("登录接口（/api/v1/auth/login）签发的 JWT Cookie，JwtAuthFilter 校验")));
    }
}
