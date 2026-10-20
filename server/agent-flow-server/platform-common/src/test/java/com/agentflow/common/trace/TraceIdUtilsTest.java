package com.agentflow.common.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceIdUtilsTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void generateProduces32HexChars() {
        String id = TraceIdUtils.generate();
        assertThat(id).hasSize(32).matches("[0-9a-f]+");
    }

    @Test
    void putAndCurrentRoundTrip() {
        TraceIdUtils.put("abc123");
        assertThat(TraceIdUtils.current()).isEqualTo("abc123");
        TraceIdUtils.clear();
        assertThat(TraceIdUtils.current()).isNull();
    }

    @Test
    void isValidRejectsInjectionAndOversized() {
        assertThat(TraceIdUtils.isValid("deadbeef01")).isTrue();
        assertThat(TraceIdUtils.isValid(null)).isFalse();
        assertThat(TraceIdUtils.isValid("")).isFalse();
        assertThat(TraceIdUtils.isValid("evil\nlog-line")).isFalse();
        assertThat(TraceIdUtils.isValid("z".repeat(10))).isFalse();
        assertThat(TraceIdUtils.isValid("a".repeat(65))).isFalse();
    }
}
