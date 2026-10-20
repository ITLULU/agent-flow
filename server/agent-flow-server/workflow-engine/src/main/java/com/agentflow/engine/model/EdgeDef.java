package com.agentflow.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** DSL 边：from → to，"from" 为 Java/JSON 双合法标识。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EdgeDef(String from, String to) {
}
