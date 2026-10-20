package com.agentflow.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 触发器定义（cron 注册表在 P3 兑现；P1 仅承载手动触发）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TriggerDef(String type, String expression, String timezone) {

    public static TriggerDef manual() {
        return new TriggerDef("manual", null, null);
    }
}
