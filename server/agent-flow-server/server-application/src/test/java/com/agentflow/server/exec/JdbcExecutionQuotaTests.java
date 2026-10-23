package com.agentflow.server.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.server.config.ExecProperties;
import com.agentflow.server.persistence.JdbcExecutionQuota;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.micrometer.core.instrument.MeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 跨实例执行配额（评审 P7-3 #11）的数据库仲裁语义。
 *
 * <p>这里的两个配额实例故意指向同一个 URL、各自持有连接池：这就是"两个应用实例共用一个库"的最小复现。
 * 只用一个实例测，测的是本机 Semaphore，不是全局上限——正是 #11 抱怨的东西。
 *
 * <p>H2 加 LOCK_TIMEOUT 只是让并发用例不被锁等待噪声干扰；PG 侧的行锁默认就一直等。
 */
class JdbcExecutionQuotaTests {

    private final List<JdbcExecutionQuota> created = new ArrayList<>();
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:af-quota-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;LOCK_TIMEOUT=5000";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource());
    }

    @AfterEach
    void tearDown() {
        created.forEach(JdbcExecutionQuota::close);
        jdbc.update("DELETE FROM execution_lease");
    }

    @Test
    void twoInstancesDrawFromOneSharedBudget() {
        JdbcExecutionQuota a = quota("instance-a", 3, 0, Duration.ofMinutes(5), Duration.ofSeconds(30));
        JdbcExecutionQuota b = quota("instance-b", 3, 0, Duration.ofMinutes(5), Duration.ofSeconds(30));

        assertThat(a.tryReserve(1, 7L)).isTrue();
        assertThat(a.tryReserve(2, 7L)).isTrue();
        assertThat(b.tryReserve(3, 9L)).isTrue();
        // 第 4 条无论从哪个实例提交都必须被拒：上限是共享账本算出来的，不是每实例一份
        assertThat(b.tryReserve(4, 9L)).isFalse();
        assertThat(a.tryReserve(5, 7L)).isFalse();
        assertThat(a.reserved()).isEqualTo(3);
        assertThat(b.reserved()).isEqualTo(3);

        // 另一个实例腾出名额，本实例马上能用——跨实例可见性就是这条要证的
        b.release(3);
        assertThat(a.hasCapacity(7L)).isTrue();
        assertThat(a.tryReserve(6, 7L)).isTrue();
    }

    @Test
    void perUserCapBindsWhileGlobalBudgetStillHasRoom() {
        JdbcExecutionQuota a = quota("instance-a", 5, 2, Duration.ofMinutes(5), Duration.ofSeconds(30));
        JdbcExecutionQuota b = quota("instance-b", 5, 2, Duration.ofMinutes(5), Duration.ofSeconds(30));

        assertThat(a.tryReserve(1, 42L)).isTrue();
        assertThat(b.tryReserve(2, 42L)).isTrue();
        // 只拦这个人，不拦全局：全局 5、他已占 2
        assertThat(a.hasCapacity(42L)).isFalse();
        assertThat(a.hasCapacity(43L)).isTrue();
        assertThat(b.tryReserve(3, 42L)).isFalse();

        a.release(1);
        assertThat(b.tryReserve(4, 42L)).isTrue();
    }

    @Test
    void expiredLeaseFromCrashedInstanceIsReclaimedNotLeakedForever() {
        // 崩溃的实例不会释放也不会续租：库里留下一条永远占额的租约。
        // 没有回收机制时，一次 OOMKill 就永久吃掉一份全局容量——这是多实例形态最隐蔽的慢性死法。
        jdbc.update("INSERT INTO execution_lease (execution_id, owner_id, instance_id, acquired_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                900L, 7L, "crashed-instance", Timestamp.from(Instant.now().minusSeconds(60)),
                Timestamp.from(Instant.now().minusSeconds(30)));
        JdbcExecutionQuota live = quota("instance-live", 1, 0, Duration.ofMinutes(5), Duration.ofSeconds(30));

        // 只读预检按"未过期"计数，所以陈旧租约连提交都不该拦
        assertThat(live.hasCapacity(7L)).isTrue();
        assertThat(live.tryReserve(1001L, 8L)).isTrue();

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_lease WHERE instance_id = 'crashed-instance'", Integer.class);
        assertThat(remaining).isZero();
        assertThat(registry.get("agentflow.exec.quota").tag("kind", "reaped").counter().count()).isEqualTo(1.0);
    }

    @Test
    void heartbeatKeepsLongRunningExecutionOffTheReapersList() {
        // 真跑心跳线程（不手动调 renewHeld）：执行时长超过 3 个 TTL 也不能掉名额，
        // 否则"长执行"在多实例下等于自我放弃配额，全局上限会被反复突破
        JdbcExecutionQuota a = quota("instance-a", 1, 0, Duration.ofMillis(250), Duration.ofMillis(80));
        JdbcExecutionQuota b = quota("instance-b", 1, 0, Duration.ofMillis(250), Duration.ofMillis(80));
        assertThat(a.tryReserve(1, 7L)).isTrue();

        sleep(900);

        assertThat(a.reserved()).isEqualTo(1);
        assertThat(b.tryReserve(2, 9L)).isFalse();
    }

    @Test
    void renewDoesNotSilentlyReclaimAStolenLease() throws Exception {
        // 本机停顿超过 TTL ⇒ 租约被别的实例回收。续租时不重新插一条：
        // 那会让同一执行同时占着两个实例的名额，把上限悄悄放大。只如实记账。
        JdbcExecutionQuota a = quota("instance-a", 2, 0, Duration.ofMinutes(5), Duration.ofSeconds(30));
        assertThat(a.tryReserve(1, 7L)).isTrue();
        jdbc.update("DELETE FROM execution_lease WHERE execution_id = 1");

        a.renewHeld();

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM execution_lease", Integer.class);
        assertThat(rows).isZero();
        assertThat(registry.get("agentflow.exec.quota").tag("kind", "lost").counter().count()).isEqualTo(1.0);
    }

    @Test
    void missingSentinelGateRowRefusesReserveInsteadOfSilentlyUnlimiting() {
        // 反向验证：把仲裁用的哨兵行删掉（迁移没跑全/被人误删）。
        // 如果实现忘了加锁，这段代码会照常插入租约 ⇒ "配了配额"静默退化成"没配配额"。
        jdbc.update("DELETE FROM quota_gate WHERE id = 1");
        JdbcExecutionQuota a = quota("instance-a", 1, 0, Duration.ofMinutes(5), Duration.ofSeconds(30));

        assertThatThrownBy(() -> a.tryReserve(1, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quota_gate");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execution_lease", Integer.class)).isZero();
    }

    @Test
    void concurrentReservesAcrossInstancesNeverExceedTheCap() throws Exception {
        int cap = 4;
        JdbcExecutionQuota a = quota("instance-a", cap, 0, Duration.ofMinutes(5), Duration.ofSeconds(30));
        JdbcExecutionQuota b = quota("instance-b", cap, 0, Duration.ofMinutes(5), Duration.ofSeconds(30));
        int attempts = 12;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger errored = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            final long executionId = 100 + i;
            final JdbcExecutionQuota owner = i % 2 == 0 ? a : b;
            pool.submit(() -> {
                try {
                    start.await();
                    if (owner.tryReserve(executionId, executionId % 3)) {
                        int now = inFlight.incrementAndGet();
                        peak.accumulateAndGet(now, Math::max);
                        TimeUnit.MILLISECONDS.sleep(60);
                        owner.release(executionId);
                        inFlight.decrementAndGet();
                    } else {
                        refused.incrementAndGet();
                    }
                } catch (Exception e) {
                    errored.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // 仲裁失效的话，这个断言会当场破：多个实例会在同一瞬间各自数出"还有名额"
        assertThat(peak.get()).isLessThanOrEqualTo(cap);
        assertThat(peak.get()).isEqualTo(cap);
        assertThat(refused.get()).isGreaterThan(0); // 没有拒绝就没有验证到拒绝路径，绿灯会骗人
        assertThat(errored.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execution_lease", Integer.class)).isZero(); // 全部归还
    }

    private JdbcExecutionQuota quota(String instanceId, int maxConcurrent, int perUser,
                                     Duration leaseTtl, Duration renewInterval) {
        ExecProperties.GlobalQuota props = new ExecProperties.GlobalQuota(true, maxConcurrent, perUser,
                leaseTtl, renewInterval, Duration.ofMillis(50));
        DriverManagerDataSource ds = dataSource();
        JdbcExecutionQuota q = new JdbcExecutionQuota(new JdbcTemplate(ds),
                new DataSourceTransactionManager(ds), props, instanceId, registry);
        created.add(q);
        return q;
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        return ds;
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
