package com.agentflow.server.persistence;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import java.sql.PreparedStatement;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** users 表仓库：唯一键冲突映射为 A0400（用户名占用不该以 500 面客）。 */
@Repository
public class UserRepository {

    public record UserRow(long id, String username, String passwordHash, String role) {
    }

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String username, String passwordHash, String role) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        String actor = AuditActor.current();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO users (username, password_hash, role, creator, updater) VALUES (?, ?, ?, ?, ?)",
                        new String[]{"id"});
                ps.setString(1, username);
                ps.setString(2, passwordHash);
                ps.setString(3, role);
                ps.setString(4, actor);
                ps.setString(5, actor);
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "用户名已存在: " + username);
        }
        return keyHolder.getKey().longValue();
    }

    public Optional<UserRow> findByUsername(String username) {
        return jdbc.query("SELECT id, username, password_hash, role FROM users WHERE username = ?",
                (rs, n) -> new UserRow(rs.getLong("id"), rs.getString("username"),
                        rs.getString("password_hash"), rs.getString("role")),
                username).stream().findFirst();
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return c == null ? 0 : c;
    }
}
