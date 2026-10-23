package com.agentflow.server.exec;

/**
 * 跨实例执行配额（评审 P7-3 #11）。
 *
 * <p>要解决的问题很具体：执行闸门 {@code Semaphore(max-concurrent)} 是进程内的，N 个实例就是 N×16 条
 * 链路同时打向同一个 PG 和同一批下游；限流、幂等都能在库里共享，只有"谁现在在跑"这件事各实例看不见彼此。
 * 这个接口把"正在跑"变成库里的一份公共账本。
 *
 * <p>口径：一次租约覆盖<strong>引擎真正执行的这段时间</strong>（拿到本地槽之后、收尾之前），不含本地排队。
 * 因此 {@code max-concurrent} 读作"全集群同时在跑的执行数上限"。
 */
public interface ExecutionQuota {

    /**
     * 只读判据，用于提交时同步拒绝（不建执行记录、不进队列）。
     * 不预留名额，所以并发下可能"看着还有位"随后被抢走——真正的上限由 {@link #tryReserve} 守住，
     * 这里只负责让明显已满的集群不必再收单。
     */
    boolean hasCapacity(long ownerId);

    /** 原子地预留一个名额；拿不到返回 false，由调用方决定等多久。 */
    boolean tryReserve(long executionId, long ownerId);

    /** 释放本实例为该执行持有的名额（幂等；不是自己的名额不动，避免实例间互相踩账）。 */
    void release(long executionId);

    /** 续租本实例持有的全部名额：进程活着就不该被别人当成崩溃残留。 */
    void renewHeld();

    /** 当前占用的名额总数（含其它实例，不含已过期未回收的）。 */
    long reserved();
}
