package com.agentflow.server.persistence;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * user_credentials 仓库：只存 AES-GCM 封装密文（secret_json_enc），明文流经即弃。
 * upsert 走 UPDATE→未命中再 INSERT（两语句），不用 ON CONFLICT——H2/PG 方言差异最小化。
 */
@Repository
public class CredentialRepository {

    public record CredentialMeta(long id, long ownerId, String provider, String name, String updatedAt) {
    }

    private final JdbcTemplate jdbc;

    public CredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertIfAbsent(long ownerId, String provider, String name, String secretJsonEnc) {
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO user_credentials (owner_id, provider, name, secret_json_enc) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, ownerId);
            ps.setString(2, provider);
            ps.setString(3, name);
            ps.setString(4, secretJsonEnc);
            return ps;
        });
    }

    public List<CredentialMeta> listByOwner(long ownerId, int limit, Long beforeId) {
        return jdbc.query("SELECT id, owner_id, provider, name, CAST(updated_at AS VARCHAR) AS updated_at "
                        + "FROM user_credentials WHERE owner_id = ?"
                        + (beforeId == null ? "" : " AND id < ?")
                        + " ORDER BY id DESC LIMIT ?",
                (rs, i) -> new CredentialMeta(rs.getLong("id"), rs.getLong("owner_id"),
                        rs.getString("provider"), rs.getString("name"), rs.getString("updated_at")),
                beforeId == null ? new Object[]{ownerId, limit} : new Object[]{ownerId, beforeId, limit});
    }

    public Optional<CredentialMeta> findMeta(long id) {
        return jdbc.query("SELECT id, owner_id, provider, name, CAST(updated_at AS VARCHAR) AS updated_at "
                        + "FROM user_credentials WHERE id = ?",
                (rs, i) -> new CredentialMeta(rs.getLong("id"), rs.getLong("owner_id"),
                        rs.getString("provider"), rs.getString("name"), rs.getString("updated_at")),
                id).stream().findFirst();
    }

    /** 归属者限定的密文读取：owner 不匹配即查不到，跨用户借用密钥在执行链路上拿不到密文（评审 P6 #1）。 */
    public Optional<String> findSecretEncOwned(long id, long ownerId) {
        return jdbc.query("SELECT secret_json_enc FROM user_credentials WHERE id = ? AND owner_id = ?",
                (rs, i) -> rs.getString(1), id, ownerId).stream().findFirst();
    }

    public int deleteOwned(long id, long ownerId) {
        return jdbc.update("DELETE FROM user_credentials WHERE id = ? AND owner_id = ?", id, ownerId);
    }

    /** 便捷方法：upsert 语义 = UPDATE 未命中则 INSERT（控制器串行调用，无并发同键场景）。 */
    public long saveOrUpdate(long ownerId, String provider, String name, String secretJsonEnc) {
        int updated = jdbc.update("UPDATE user_credentials SET secret_json_enc = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE owner_id = ? AND provider = ? AND name = ?",
                secretJsonEnc, ownerId, provider, name);
        if (updated > 0) {
            return findMetaByKey(ownerId, provider, name).id();
        }
        insertIfAbsent(ownerId, provider, name, secretJsonEnc);
        return findMetaByKey(ownerId, provider, name).id();
    }

    private CredentialMeta findMetaByKey(long ownerId, String provider, String name) {
        return jdbc.query("SELECT id, owner_id, provider, name, CAST(updated_at AS VARCHAR) AS updated_at "
                        + "FROM user_credentials WHERE owner_id = ? AND provider = ? AND name = ?",
                (rs, i) -> new CredentialMeta(rs.getLong("id"), rs.getLong("owner_id"),
                        rs.getString("provider"), rs.getString("name"), rs.getString("updated_at")),
                ownerId, provider, name).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL, "凭证写入后不可读（不应发生）"));
    }
}
