package com.agentflow.server.web;

import com.agentflow.common.trace.TraceIdUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 入口 traceId：透传合法上游 id，否则生成；写响应头供前端/排障关联；请求结束必清 MDC。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(TraceIdUtils.HEADER);
        String traceId = TraceIdUtils.isValid(incoming) ? incoming : TraceIdUtils.generate();
        TraceIdUtils.put(traceId);
        response.setHeader(TraceIdUtils.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIdUtils.MDC_KEY);
        }
    }
}
