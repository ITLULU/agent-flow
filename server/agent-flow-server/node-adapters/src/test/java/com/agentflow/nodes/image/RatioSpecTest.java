package com.agentflow.nodes.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

/** RatioSpec：比例口径的取值必须钉死——出图尺寸直接决定成品观感，改动即回归。 */
class RatioSpecTest {

    @Test
    void nullFallsBackToXhsPortrait() {
        RatioSpec spec = RatioSpec.resolve(null);
        assertThat(spec.ratio()).isEqualTo("3:4");
        assertThat(spec.size()).isEqualTo("768*1024");
    }

    @Test
    void presetsCoverBothCreationChannels() {
        assertThat(RatioSpec.resolve("xhs").size()).isEqualTo("768*1024");
        assertThat(RatioSpec.resolve("banner").size()).isEqualTo("1280*720");
        assertThat(RatioSpec.resolve("wechat-share").size()).isEqualTo("1200*512");
        assertThat(RatioSpec.resolve("square").size()).isEqualTo("1024*1024");
        assertThat(RatioSpec.presetNames()).contains("xhs", "story", "wechat-share");
    }

    @Test
    void aColonBExpandsOnLongSide1024() {
        assertThat(RatioSpec.resolve("16:9").size()).isEqualTo("1024*576");
        assertThat(RatioSpec.resolve("9:16").size()).isEqualTo("576*1024");
        assertThat(RatioSpec.resolve("1:1").size()).isEqualTo("1024*1024");
    }

    @Test
    void aColonBKeepsTheUsersOwnNotation() {
        // 用户说 3:4，产物文件名与元数据就必须写 3:4，不能回算成 0.75:1
        assertThat(RatioSpec.resolve("3:4").ratio()).isEqualTo("3:4");
        assertThat(RatioSpec.resolve("16:9").ratio()).isEqualTo("16:9");
        assertThat(RatioSpec.resolve("9:16").ratio()).isEqualTo("9:16");
        assertThat(RatioSpec.resolve("2.35:1").ratio()).isEqualTo("2.35:1");
        assertThat(RatioSpec.resolve("8:12").ratio()).isEqualTo("2:3"); // 等价写法约分后统一
    }

    @Test
    void extremeRatioIsScaledUpUntilShortSideReachesFloor() {
        RatioSpec spec = RatioSpec.resolve("21:9");
        assertThat(spec.width()).isGreaterThanOrEqualTo(RatioSpec.MIN_SIDE);
        assertThat(spec.height()).isGreaterThanOrEqualTo(RatioSpec.MIN_SIDE);
        assertThat(spec.width()).isLessThanOrEqualTo(RatioSpec.MAX_SIDE);
    }

    @Test
    void explicitPixelSizeIsAcceptedAfterRangeCheck() {
        RatioSpec spec = RatioSpec.resolve("1024x768");
        assertThat(spec.width()).isEqualTo(1024);
        assertThat(spec.height()).isEqualTo(768);
        assertThat(RatioSpec.resolve("800*600").size()).isEqualTo("800*600");
    }

    @Test
    void rejectsOutOfRangeAndUnparsableValues() {
        assertThatThrownBy(() -> RatioSpec.resolve("2000*2000"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> RatioSpec.resolve("黄金比例"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessage()).contains("无法识别的比例"));
    }

    @Test
    void everyPresetStaysInsideWanxiangBounds() {
        for (String name : RatioSpec.presetNames()) {
            RatioSpec spec = RatioSpec.resolve(name);
            assertThat(spec.width()).as(name + " 宽").isBetween(RatioSpec.MIN_SIDE, RatioSpec.MAX_SIDE);
            assertThat(spec.height()).as(name + " 高").isBetween(RatioSpec.MIN_SIDE, RatioSpec.MAX_SIDE);
        }
    }
}
