package com.agentflow.server.web;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.AesGcmCipher;
import com.agentflow.server.persistence.CredentialRepository;
import com.agentflow.server.persistence.CredentialRepository.CredentialMeta;
import com.agentflow.server.security.EndpointSensitivity;
import com.agentflow.server.security.PrincipalContext;
import com.agentflow.server.security.Sensitivity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 凭证库 API：PUT 存（AES-GCM 加密后落库）、GET 列表/详情只回元数据（永不回显秘密值）、DELETE 属主可删。
 * 主密钥缺失（credentialCipher NullBean）→ 每次调用报 A0400 配置错，与 P2 节点"注册在位、执行快败"同口径。
 */
@RestController
@RequestMapping("/api/credentials")
public class CredentialsController {

    private static final Pattern KEY = Pattern.compile("^[a-z0-9_]{2,32}$");
    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9_\\-]{2,64}$");

    private final CredentialRepository credentials;
    private final ObjectProvider<AesGcmCipher> cipherProvider;
    private final ObjectMapper mapper;

    public CredentialsController(CredentialRepository credentials,
                                 ObjectProvider<AesGcmCipher> cipherProvider, ObjectMapper mapper) {
        this.credentials = credentials;
        this.cipherProvider = cipherProvider;
        this.mapper = mapper;
    }

    /** body: {"provider":"wechat","name":"main","secrets":{"app_id":"...","app_secret":"..."}} —— 幂等 upsert。 */
    @EndpointSensitivity(Sensitivity.SENSITIVE)
    @PutMapping
    public ApiResponse<Map<String, Object>> put(@RequestBody String rawJson) {
        AesGcmCipher cipher = requireCipher();
        JsonNode node = parse(rawJson);
        String provider = node.path("provider").asText("");
        String name = node.path("name").asText("");
        JsonNode secrets = node.path("secrets");
        if (!KEY.matcher(provider).matches()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "provider 需为 2-32 位小写字母/数字/下划线");
        }
        if (!NAME.matcher(name).matches()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "name 需为 2-64 位字母/数字/下划线/中划线");
        }
        if (!secrets.isObject() || secrets.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "secrets 需为非空对象（键值对）");
        }
        String secretJson;
        try {
            secretJson = mapper.writeValueAsString(secrets);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "secrets 序列化失败");
        }
        long id = credentials.saveOrUpdate(PrincipalContext.get().uid(), provider, name, cipher.encrypt(secretJson));
        // 响应只回元数据：明文与密文都不出 API
        return ApiResponse.ok(Map.of("id", id, "provider", provider, "name", name));
    }

    @EndpointSensitivity(Sensitivity.SENSITIVE)
    @GetMapping
    public ApiResponse<List<CredentialMeta>> list() {
        return ApiResponse.ok(credentials.listByOwner(PrincipalContext.get().uid())); // 天然按属主过滤
    }

    @EndpointSensitivity(Sensitivity.SENSITIVE)
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable long id) {
        CredentialMeta meta = credentials.findMeta(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "credential " + id));
        if (!PrincipalContext.get().canAccess(meta.ownerId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "credential " + id); // 同工作流口径：非属主 404
        }
        credentials.deleteOwned(id, meta.ownerId());
        return ApiResponse.ok(Map.of("id", id, "deleted", true));
    }

    private AesGcmCipher requireCipher() {
        AesGcmCipher cipher = cipherProvider.getIfAvailable();
        if (cipher == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "凭证库不可用：未配置 AGENTFLOW_MASTER_KEY（base64 的 32 字节主密钥）");
        }
        return cipher;
    }

    private JsonNode parse(String rawJson) {
        try {
            return mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "JSON 解析失败: " + e.getClass().getSimpleName());
        }
    }
}
