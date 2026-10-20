package com.agentflow.server.security;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.security.PasswordHasher;
import com.agentflow.server.persistence.UserRepository;
import com.agentflow.server.persistence.UserRepository.UserRow;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 注册/登录。规则刻意保守：
 * - 用户名 ^[a-zA-Z0-9_]{3,32}$，口令 8..64 位（复杂度策略留给后续接入企业 SSO 时统一）；
 * - 首个注册用户自动 admin（系统引导），之后一律 user；
 * - 登录失败原因不外泄（用户名不存在与口令错误同文案，防用户名枚举）。
 */
@Service
public class AuthService {

    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_]{3,32}$");

    private final UserRepository users;
    private final JwtCodec jwtCodec;

    public AuthService(UserRepository users, JwtCodec jwtCodec) {
        this.users = users;
        this.jwtCodec = jwtCodec;
    }

    public record Registration(long uid, String username, String role) {
    }

    public Registration register(String username, String password) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "用户名需为 3-32 位字母/数字/下划线");
        }
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "口令长度需为 8-64 位");
        }
        String role = users.count() == 0 ? Principal.ROLE_ADMIN : "user";
        long uid = users.insert(username, PasswordHasher.hash(password), role);
        return new Registration(uid, username, role);
    }

    /** @return token + 用户信息（响应里不回显 hash/内部字段） */
    public Map<String, Object> login(String username, String password) {
        UserRow row = users.findByUsername(username == null ? "" : username)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "用户名或口令错误"));
        if (!PasswordHasher.verify(password == null ? "" : password, row.passwordHash())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "用户名或口令错误");
        }
        String token = jwtCodec.issue(row.id(), row.username(), row.role());
        return Map.of("token", token, "tokenType", "Bearer",
                "uid", row.id(), "username", row.username(), "role", row.role());
    }
}
