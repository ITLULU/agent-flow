package com.agentflow.server.security;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 启动期路由敏感级别清单：所有 controller 处理函数必须带 @EndpointSensitivity。
 * 单例初始化完成后、端口绑定之前扫描，漏标即 fail-fast，避免"新接口默认没人做过安全分类"。
 */
@Component
public class EndpointSensitivityCatalog implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(EndpointSensitivityCatalog.class);
    private static final String CONTROLLER_PACKAGE = "com.agentflow.server.web";

    private final ObjectProvider<RequestMappingHandlerMapping> mappingProvider;
    private volatile List<Entry> entries = List.of();

    public EndpointSensitivityCatalog(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappingProvider) {
        this.mappingProvider = mappingProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        RequestMappingHandlerMapping mapping = mappingProvider.getIfAvailable();
        if (mapping == null) {
            // 非 Web 上下文（如纯调度/单测）没有路由可分类，生产 Web 上下文一定存在该 bean
            log.info("未发现 MVC 上下文，跳过 API 敏感级别清单校验");
            return;
        }
        List<Entry> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith(CONTROLLER_PACKAGE)) {
                return;
            }
            Sensitivity level = resolve(handler);
            if (level == null) {
                missing.add(describe(info, handler));
                return;
            }
            found.add(new Entry(methods(info), String.join(",", info.getPatternValues()), level,
                    handler.getShortLogMessage()));
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException("以下 API 缺少 @EndpointSensitivity 标记: " + missing);
        }
        found.sort(Comparator.comparing(Entry::path));
        entries = List.copyOf(found);
        found.forEach(e -> log.info("API 敏感级别: {} {} -> {}", e.methods(), e.path(), e.level()));
        log.info("API 敏感级别清单已加载: {} 条（SENSITIVE {} 条）", found.size(),
                found.stream().filter(e -> e.level() == Sensitivity.SENSITIVE).count());
    }

    public List<Entry> entries() {
        return entries;
    }

    static Sensitivity resolve(HandlerMethod handler) {
        EndpointSensitivity annotation =
                AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), EndpointSensitivity.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), EndpointSensitivity.class);
        }
        return annotation == null ? null : annotation.value();
    }

    private static String methods(RequestMappingInfo info) {
        Set<org.springframework.web.bind.annotation.RequestMethod> methods = info.getMethodsCondition().getMethods();
        if (methods.isEmpty()) {
            return "ALL";
        }
        return methods.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static String describe(RequestMappingInfo info, HandlerMethod handler) {
        return methods(info) + " " + String.join(",", info.getPatternValues()) + " -> " + handler.getShortLogMessage();
    }

    public record Entry(String methods, String path, Sensitivity level, String handler) {
    }
}
