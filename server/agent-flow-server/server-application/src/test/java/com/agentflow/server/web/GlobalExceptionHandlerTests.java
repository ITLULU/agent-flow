package com.agentflow.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 错误报文口径：默认文案只能出现一次。
 * 回归的是"ApiResponse.fail 再拼一次 ex.getMessage()"导致的双前缀（容器实测样本：
 * "资源不存在: 资源不存在: artifact 99999"），所以断言写死完整字符串而不是 contains。
 */
class GlobalExceptionHandlerTests {

    private final GlobalExceptionHandler advice = new GlobalExceptionHandler();

    private ApiResponse<Void> bodyOf(ApiException ex) {
        ResponseEntity<ApiResponse<Void>> response = advice.handleApi(ex);
        return response.getBody();
    }

    @Test
    @DisplayName("带 detail 的业务异常：默认文案 + 原因，且默认文案只出现一次")
    void detailIsPrefixedExactlyOnce() {
        ApiResponse<Void> body = bodyOf(new ApiException(ErrorCode.NOT_FOUND, "artifact 99999"));
        assertThat(body.code()).isEqualTo("A0404");
        assertThat(body.message()).isEqualTo("资源不存在: artifact 99999");
        assertThat(body.message()).doesNotContain("资源不存在: 资源不存在");
    }

    @Test
    @DisplayName("限流与熔断报文同口径（这两条是给运维看的，重复前缀会盖掉真正的原因）")
    void rateLimitedAndCircuitOpenKeepSinglePrefix() {
        assertThat(bodyOf(new ApiException(ErrorCode.RATE_LIMITED, "集群执行配额已满（在跑 2 条）")).message())
                .isEqualTo("请求过于频繁: 集群执行配额已满（在跑 2 条）");
        assertThat(bodyOf(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "llm 舱壁已满")).message())
                .isEqualTo("外部依赖熔断或并发舱壁已满: llm 舱壁已满");
    }

    @Test
    @DisplayName("无 detail 时只回默认文案，不留一个光秃秃的冒号")
    void withoutDetailNoTrailingSeparator() {
        ApiResponse<Void> body = bodyOf(new ApiException(ErrorCode.BAD_REQUEST));
        assertThat(body.message()).isEqualTo("请求参数不合法");
    }

    @Test
    @DisplayName("HTTP 状态映射不因报文组装改动而漂移")
    void statusMappingUnchanged() {
        assertThat(advice.handleApi(new ApiException(ErrorCode.NOT_FOUND, "x")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(advice.handleApi(new ApiException(ErrorCode.RATE_LIMITED, "x")).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(advice.handleApi(new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "x")).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(advice.handleApi(new ApiException(ErrorCode.UPSTREAM_TIMEOUT, "x")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("getMessage() 仍带前缀：日志与执行记录里要靠它自解释，修复只动响应组装")
    void exceptionMessageStillSelfDescribing() {
        ApiException ex = new ApiException(ErrorCode.NOT_FOUND, "artifact 99999");
        assertThat(ex.getMessage()).isEqualTo("资源不存在: artifact 99999");
        assertThat(ex.detail()).isEqualTo("artifact 99999");
    }
}
