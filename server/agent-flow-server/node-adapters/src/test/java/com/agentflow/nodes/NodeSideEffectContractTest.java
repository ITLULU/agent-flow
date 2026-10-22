package com.agentflow.nodes;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.engine.spi.NodeExecutor;
import com.agentflow.nodes.core.EchoNodeExecutor;
import com.agentflow.nodes.http.HttpCollectNodeExecutor;
import com.agentflow.nodes.image.CoverImageNodeExecutor;
import com.agentflow.nodes.image.WanxiangImageNodeExecutor;
import com.agentflow.nodes.publish.LocalArchiveNodeExecutor;
import com.agentflow.nodes.publish.XhsDraftNodeExecutor;
import com.agentflow.nodes.wechat.WechatDraftNodeExecutor;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 引擎用 hasSideEffects 决定"超时后能不能重试"（评审 P6 #7），所以这个标志本身就是契约：
 * 漏标 ⇒ 超时后引擎照样重试 ⇒ 同一篇内容发两次、同一张图扣两次费。本类把四类外部效应钉住，
 * 并把"只读/幂等写入"节点钉成 false，防止有人把重试预算一起关掉导致抓取链路一抖就失败。
 */
class NodeSideEffectContractTest {

    @Test
    void externalEffectNodesDeclareSideEffects() {
        List<NodeExecutor> effectNodes = List.of(
                new WechatDraftNodeExecutor("app-id", "app-secret"), // 真建草稿
                new XhsDraftNodeExecutor(null),                     // 真建笔记
                new WanxiangImageNodeExecutor("key"),               // 付费出图
                new CoverImageNodeExecutor("key", "https://dashscope.example")); // 付费出图

        assertThat(effectNodes)
                .allSatisfy(n -> assertThat(n.hasSideEffects())
                        .as("%s 会留下外部不可逆副作用", n.type())
                        .isTrue());
    }

    @Test
    void readOnlyOrIdempotentNodesStayRetryable() {
        List<NodeExecutor> safeNodes = List.of(
                new EchoNodeExecutor(),
                new HttpCollectNodeExecutor(),
                // publish.local 最坏只在内容库里多一个孤儿 artifact 目录（对外没有可见效应），且自身已 noRetry
                new LocalArchiveNodeExecutor(null));

        assertThat(safeNodes)
                .allSatisfy(n -> assertThat(n.hasSideEffects())
                        .as("%s 无重复扣费/重复发布风险，应保留重试", n.type())
                        .isFalse());
    }
}
