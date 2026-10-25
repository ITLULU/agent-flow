package com.agentflow.server.persistence;

import com.agentflow.server.config.ExecProperties;
import com.agentflow.server.exec.ExecutionQuota;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 跨实例执行配额的数据库仲裁实现（评审 P7-3 #11）。
 *
 * <p>为什么用数据库而不是进程内计数：租约记在共享库里，N 个实例看的是同一本账。
 * 为什么不用 Redis：Redis 不在本项目的依赖面里，为"限制自己别把 PG 打爆"而引入一个需要单独高可用的
 * 组件，等于把可用性上限降到那个新组件上；PG 已经是执行的硬依赖，用它仲裁不新增故障域。
 *
 * <p>仲裁段（哨兵行 FOR UPDATE → 回收过期租约 → 数名额 → 插租约）必须在一个事务里，
 * 少了那把行锁，两个实例会在同一瞬间各自数出"还剩一个名额"，上限形同虚设。
 *
 * <p><strong>失效即拒绝（fail-closed）</strong>：哨兵行或配额表不在（V7 没跑全）时，预留会直接抛错，
 * 执行落 FAILED —— 宁可拒绝执行，也不能让"配了配额"退化成"没配配额"。
 */
public class JdbcExecutionQuota implements ExecutionQuota, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdbcExecutionQuota.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ExecProperties.GlobalQuota props;
    private final String instanceId;
    private final MeterRegistry registry;

    /** 本实例认为自己在跑的执行 id：续租与优雅停机释放都以它为准。 */
    private final Set<Long> held = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService renewer;

    public JdbcExecutionQuota(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                             ExecProperties.GlobalQuota props, String instanceId, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.props = props;
        this.instanceId = instanceId;
        this.registry = registry;
        // 心跳续租：租约 TTL 防实例崩溃，续租防"活着的执行被当成崩溃残留"。
        // 只有 TTL ⇒ 超过 TTL 的长执行会被别人抢走名额；只有续租 ⇒ 崩溃后名额永不归还。两者缺一不可。
        // 用自带单线程调度而不是 @Scheduled：节拍来自已解析好的 Duration，不再让配置以字符串形式重解析一遍。
        long millis = props.renewInterval().toMillis();
        this.renewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "exec-quota-renew");
            t.setDaemon(true);
            return t;
        });
        this.renewer.scheduleWithFixedDelay(this::renewSafely, millis, millis, TimeUnit.MILLISECONDS);
        // 抓取时查库：报的是全集群真实占用，不是本机记忆。DB 正好不可用时返回 NaN——
        // 指标面不能因为一次数据库抖动而整个 500（那正是运维最需要看指标的时候）。
        registry.gauge("agentflow.exec.global.reserved", this, q -> {
            try {
                return (double) q.reserved();
            } catch (RuntimeException e) {
                return Double.NaN;
            }
        });
        registry.gauge("agentflow.exec.global.capacity", props.maxConcurrent(), c -> (double) c);
        log.info("跨实例执行配额已启用：全局上限={} 单用户上限={} 实例={} 租约 TTL={}",
                props.maxConcurrent(), props.perUserMaxConcurrent() == 0 ? "不限" : props.perUserMaxConcurrent(),
                instanceId, props.leaseTtl());
    }

    @Override
    public boolean hasCapacity(long ownerId) {
        Timestamp now = Timestamp.from(Instant.now());
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_lease WHERE expires_at >= ?", Long.class, now);
        if (total >= props.maxConcurrent()) {
            return false;
        }
        if (props.perUserMaxConcurrent() > 0) {
            long mine = jdbc.queryForObject("SELECT COUNT(*) FROM execution_lease"
                    + " WHERE expires_at >= ? AND owner_id = ?", Long.class, now, ownerId);
            return mine < props.perUserMaxConcurrent();
        }
        return true;
    }

    @Override
    public boolean tryReserve(long executionId, long ownerId) {
        Timestamp now = Timestamp.from(Instant.now());
        Boolean reserved = tx.execute(status -> {
            // 哨兵行：拿不到这行锁就进不了仲裁段。查不到行 ⇒ 配额表没初始化，抛错走 fail-closed。
            try {
                jdbc.queryForObject("SELECT id FROM quota_gate WHERE id = 1 FOR UPDATE", Integer.class);
            } catch (EmptyResultDataAccessException e) {
                throw new IllegalStateException(
                        "quota_gate 哨兵行缺失，全局配额无法仲裁（V7 迁移未跑全？），拒绝执行而不是放开上限", e);
            }
            int reaped = jdbc.update("DELETE FROM execution_lease WHERE expires_at < ?", now);
            if (reaped > 0) {
                // 走到这里说明有实例没来得及释放：崩溃、被 kill -9，或长 GC 停顿里租约过期
                log.warn("回收 {} 条过期执行租约（实例崩溃或长时间停顿留下的名额）", reaped);
                counter("reaped").increment();
            }
            // 锁内已把过期租约清掉，此处直接数全表就是"当前在跑"
            long total = jdbc.queryForObject("SELECT COUNT(*) FROM execution_lease", Long.class);
            if (total >= props.maxConcurrent()) {
                return false;
            }
            if (props.perUserMaxConcurrent() > 0) {
                long mine = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM execution_lease WHERE owner_id = ?", Long.class, ownerId);
                if (mine >= props.perUserMaxConcurrent()) {
                    return false;
                }
            }
            jdbc.update("INSERT INTO execution_lease (execution_id, owner_id, instance_id, acquired_at, expires_at,"
                            + " creator, updater) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    executionId, ownerId, instanceId, now, Timestamp.from(now.toInstant().plus(props.leaseTtl())),
                    AuditActor.current(), AuditActor.current());
            return true;
        });
        boolean ok = Boolean.TRUE.equals(reserved);
        if (ok) {
            held.add(executionId);
        }
        return ok;
    }

    @Override
    public void release(long executionId) {
        held.remove(executionId);
        // 只删自己的：别人的执行 id 不可能相同，但"顺手删全表同名行"这种写法一旦实例语义变化就会误伤
        jdbc.update("DELETE FROM execution_lease WHERE execution_id = ? AND instance_id = ?",
                executionId, instanceId);
    }

    @Override
    public void renewHeld() {
        if (held.isEmpty()) {
            return;
        }
        Timestamp until = Timestamp.from(Instant.now().plus(props.leaseTtl()));
        for (Long executionId : held) {
            int rows = jdbc.update("UPDATE execution_lease SET expires_at = ?, update_time = CURRENT_TIMESTAMP, updater = ?"
                    + " WHERE execution_id = ? AND instance_id = ?", until, AuditActor.current(), executionId, instanceId);
            if (rows == 0 && held.remove(executionId)) {
                // 执行还在跑但租约没了：多半是本机停顿超过 TTL，被别的实例回收走了。
                // 不重新抢占——那会让同一执行在两个实例的名额下重复计数；如实记一笔，让人看见上限被突破过。
                log.error("执行 {} 的租约已不在配额表里（可能被其它实例按过期回收），本轮不再续租", executionId);
                counter("lost").increment();
            }
        }
    }

    /** 节拍线程不能因为一次数据库抖动就退出：租约最坏在 TTL 后被回收，但线程死了就再也不会续租。 */
    private void renewSafely() {
        try {
            renewHeld();
        } catch (RuntimeException e) {
            log.error("执行配额续租失败（下个节拍重试）: {}", e.toString());
        }
    }

    @Override
    public long reserved() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM execution_lease WHERE expires_at >= ?",
                Long.class, Timestamp.from(Instant.now()));
    }

    /** 优雅停机：先停心跳，再把本机还在占的名额立刻交回去，而不是让滚动重启的执行堵住上限一个 TTL。 */
    @Override
    public void close() {
        renewer.shutdownNow();
        if (held.isEmpty()) {
            return;
        }
        int n = held.size();
        for (Long executionId : held) {
            jdbc.update("DELETE FROM execution_lease WHERE execution_id = ? AND instance_id = ?",
                    executionId, instanceId);
        }
        held.clear();
        log.info("停机释放 {} 条执行租约", n);
    }

    private Counter counter(String kind) {
        return Counter.builder("agentflow.exec.quota").tag("kind", kind).register(registry);
    }

    /** 供装配层与测试读取的实例标识（不含任何密钥信息）。 */
    public String instanceId() {
        return instanceId;
    }
}
