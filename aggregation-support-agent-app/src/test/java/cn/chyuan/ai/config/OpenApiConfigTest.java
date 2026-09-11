package cn.chyuan.ai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void openApiMetadataDescribesServiceAndCookieAuthContract() {
        OpenAPI openApi = new OpenApiConfig().aggregationOpenApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("Aggregation Support Agent API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("1.0");
        assertThat(openApi.getInfo().getDescription()).contains("智能运维");

        // 鉴权契约：auth_token Cookie，与 JwtAuthFilter.COOKIE_NAME 一致
        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get("auth-token-cookie");
        assertThat(scheme).isNotNull();
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(scheme.getName()).isEqualTo("auth_token");
        assertThat(scheme.getIn()).isEqualTo(SecurityScheme.In.COOKIE);
    }
}
