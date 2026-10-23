package com.agentflow.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行闸门配置（agentflow.exec.*）。
 * 容量必须在运行期可调：编译期常量意味着每次扩缩容都要重新构建镜像，应急时等于容量不可调（评审 P7-3 #10）。
 */
@ConfigurationProperties(prefix = "agentflow.exec")
public record ExecProperties(Integer maxConcurrent, Duration acquireTimeout, Integer maxQueued,
                             GlobalQuota globalQuota) {

    public ExecProperties {
        maxConcurrent = maxConcurrent == null ? 16 : maxConcurrent;
        acquireTimeout = acquireTimeout == null ? Duration.ofMinutes(5) : acquireTimeout;
        maxQueued = maxQueued == null ? 64 : maxQueued;
        globalQuota = globalQuota == null ? GlobalQuota.disabled() : globalQuota;
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("agentflow.exec.max-concurrent 必须 >= 1，当前: " + maxConcurrent);
        }
        if (acquireTimeout.isZero() || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("agentflow.exec.acquire-timeout 必须 > 0，当前: " + acquireTimeout);
        }
        if (maxQueued < 0) {
            throw new IllegalArgumentException("agentflow.exec.max-queued 必须 >= 0，当前: " + maxQueued);
        }
    }

    /**
     * 跨实例全局执行配额（评审 P7-3 #11）。默认关闭：单实例形态下多一张表、多一处启动依赖纯属风险。
     * 开启即意味着"上限由数据库说了算"，所以此时上限本身不许含糊——留空或自相矛盾的配置直接启动失败，
     * 而不是悄悄退化成"每实例各一套"（那正是 #11 要解决的问题）。
     */
    public record GlobalQuota(Boolean enabled, Integer maxConcurrent, Integer perUserMaxConcurrent,
                              Duration leaseTtl, Duration renewInterval, Duration reserveRetryInterval) {

        /** 单实例形态（本地闸门就是全局上限）；record 只留一个构造器，否则 Boot 无法判定用哪个做绑定。 */
        public static GlobalQuota disabled() {
            return new GlobalQuota(false, null, null, null, null, null);
        }

        public GlobalQuota {
            enabled = enabled == null ? false : enabled;
            leaseTtl = leaseTtl == null ? Duration.ofMinutes(10) : leaseTtl;
            renewInterval = renewInterval == null ? Duration.ofSeconds(30) : renewInterval;
            reserveRetryInterval = reserveRetryInterval == null ? Duration.ofSeconds(1) : reserveRetryInterval;
            if (enabled) {
                if (maxConcurrent == null || maxConcurrent < 1) {
                    throw new IllegalArgumentException(
                            "启用 agentflow.exec.global-quota 必须给出 max-concurrent >= 1（这是全集群上限，不是每实例上限）");
                }
                if (perUserMaxConcurrent == null || perUserMaxConcurrent < 0) {
                    throw new IllegalArgumentException(
                            "agentflow.exec.global-quota.per-user-max-concurrent 必须 >= 0（0=只受全局上限约束），当前: "
                                    + perUserMaxConcurrent);
                }
                if (perUserMaxConcurrent > maxConcurrent) {
                    throw new IllegalArgumentException("per-user-max-concurrent(" + perUserMaxConcurrent
                            + ") 大于全局上限(" + maxConcurrent + ")，该配置永不起效——要么写小，要么调大全局上限");
                }
                if (leaseTtl.isZero() || leaseTtl.isNegative()) {
                    throw new IllegalArgumentException("lease-ttl 必须 > 0，当前: " + leaseTtl);
                }
                // 续租赶不上 TTL 时，租约会在执行还在跑的时候过期被别的实例回收 ⇒ 全局上限被静默突破，
                // 这种"配了等于没配"的口子必须在启动时堵掉。
                if (renewInterval.isZero() || renewInterval.isNegative()
                        || renewInterval.compareTo(leaseTtl) >= 0) {
                    throw new IllegalArgumentException(
                            "renew-interval 必须落在 (0, lease-ttl) 区间，当前 renew-interval="
                                    + renewInterval + ", lease-ttl=" + leaseTtl);
                }
                if (reserveRetryInterval.isZero() || reserveRetryInterval.isNegative()) {
                    throw new IllegalArgumentException("reserve-retry-interval 必须 > 0，当前: " + reserveRetryInterval);
                }
            }
        }
    }
}
