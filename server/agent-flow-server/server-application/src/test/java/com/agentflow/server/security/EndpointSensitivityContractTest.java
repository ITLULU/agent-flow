package com.agentflow.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.server.security.EndpointSensitivityCatalog.Entry;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.method.HandlerMethod;

/**
 * 路由敏感级别是鉴权收口清单（评审 P7-3#14）：
 * 全部 controller 处理函数必须显式分类，缺标记时启动期 catalog 直接失败。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:af-sensitivity;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class EndpointSensitivityContractTest {

    @Autowired
    EndpointSensitivityCatalog catalog;

    @Test
    void everyControllerRouteIsClassified() {
        // 1 ping + 2 auth + 9 workflow（含逻辑删除）+ 3 credential + 8 artifact + 3 image-studio
        assertThat(catalog.entries()).hasSize(26);
        assertThat(catalog.entries()).allSatisfy(e -> assertThat(e.level()).isNotNull());
    }

    /** 生图工作台三个端点都要身份：它会花真钱调万相/qwen-vl，绝不能落进匿名可达清单。 */
    @Test
    void imageStudioRoutesRequireUserLevel() {
        assertThat(entry("POST", "/api/image/studio/generate").level()).isEqualTo(Sensitivity.USER);
        assertThat(entry("POST", "/api/image/studio/analyze-reference").level()).isEqualTo(Sensitivity.USER);
        assertThat(entry("GET", "/api/image/studio/file").level()).isEqualTo(Sensitivity.USER);
    }

    @Test
    void credentialRoutesAreSensitiveAndPublicRoutesAreMarked() {
        assertThat(entry("GET", "/api/credentials").level()).isEqualTo(Sensitivity.SENSITIVE);
        assertThat(entry("PUT", "/api/credentials").level()).isEqualTo(Sensitivity.SENSITIVE);
        assertThat(entry("DELETE", "/api/credentials/{id}").level()).isEqualTo(Sensitivity.SENSITIVE);
        assertThat(entry("POST", "/api/workflows/{id}/executions").level()).isEqualTo(Sensitivity.USER);
        assertThat(entry("GET", "/api/artifacts/{id}/file").level()).isEqualTo(Sensitivity.USER);
        // 补偿是写操作，但只影响属主自己的成品目录，不碰密钥也不外发 ⇒ 与下载同档
        assertThat(entry("GET", "/api/artifacts/incomplete").level()).isEqualTo(Sensitivity.USER);
        assertThat(entry("POST", "/api/artifacts/{id}/repair").level()).isEqualTo(Sensitivity.USER);
        // 同步只推属主自己的成品到已配置的远端桶，不外发密钥、不读别人的目录 ⇒ 与 repair 同档
        assertThat(entry("GET", "/api/artifacts/sync/pending").level()).isEqualTo(Sensitivity.USER);
        assertThat(entry("POST", "/api/artifacts/{id}/sync").level()).isEqualTo(Sensitivity.USER);
        assertThat(entry("GET", "/api/ping").level()).isEqualTo(Sensitivity.USER);
        assertThat(entry("POST", "/api/auth/login").level()).isEqualTo(Sensitivity.PUBLIC);
    }

    @Test
    void missingMarkerAndClassLevelFallbackAreResolvedCorrectly() throws Exception {
        HandlerMethod unmarked = handlerOf(new Unmarked(), "handle");
        assertThat(EndpointSensitivityCatalog.resolve(unmarked)).isNull();

        HandlerMethod classLevel = handlerOf(new ClassLevel(), "handle");
        assertThat(EndpointSensitivityCatalog.resolve(classLevel)).isEqualTo(Sensitivity.SENSITIVE);
    }

    private Entry entry(String methods, String path) {
        return catalog.entries().stream()
                .filter(e -> e.methods().equals(methods) && e.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到路由 " + methods + " " + path));
    }

    private static HandlerMethod handlerOf(Object bean, String method) throws Exception {
        Method m = bean.getClass().getMethod(method);
        return new HandlerMethod(bean, m);
    }

    static class Unmarked {
        public void handle() {
        }
    }

    @EndpointSensitivity(Sensitivity.SENSITIVE)
    static class ClassLevel {
        public void handle() {
        }
    }
}
