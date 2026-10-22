package com.agentflow.nodes.image;

import com.agentflow.common.error.ApiException;
import com.agentflow.common.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RatioSpec —— 出图比例 → 像素尺寸的唯一口径（"按比例生成主图"的规格表）。
 *
 * 为什么要成表而不是让调用方随手填 WxH：
 * 1. 万相只接受宽高都在 [512,1440] 像素之间的尺寸，超范围是任务级失败（异步轮询才报出来，费时费钱）；
 * 2. 创作者要的是"小红书竖图 / 公众号分享图"这种语义比例，不是像素数字；
 * 3. 尺寸决定裁切后的构图观感，必须可回归——本类有单测钉住每个比例的取值。
 *
 * 命名比例（preset）与 "a:b" 写法都支持；显式 "1024*1024" / "1024x1024" 也接受（只做范围校验）。
 */
public record RatioSpec(String ratio, int width, int height) {

    public static final int MIN_SIDE = 512;
    public static final int MAX_SIDE = 1440;
    private static final int LONG_BASE = 1024;

    /** 语义预设：创作场景里最常用的几种成图比例。 */
    private static final Map<String, RatioSpec> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("square", new RatioSpec("1:1", 1024, 1024));
        PRESETS.put("xhs", new RatioSpec("3:4", 768, 1024));            // 小红书图文首选
        PRESETS.put("xiaohongshu", PRESETS.get("xhs"));
        PRESETS.put("portrait", new RatioSpec("3:4", 768, 1024));
        PRESETS.put("landscape", new RatioSpec("4:3", 1024, 768));
        PRESETS.put("story", new RatioSpec("9:16", 720, 1280));          // 竖屏全屏
        PRESETS.put("banner", new RatioSpec("16:9", 1280, 720));
        PRESETS.put("wechat-share", new RatioSpec("2.35:1", 1200, 512)); // 公众号分享大图
        PRESETS.put("wechat-small", new RatioSpec("1:1", 1024, 1024));   // 公众号小图（-square）
    }

    public static Set<String> presetNames() {
        return PRESETS.keySet();
    }

    public String size() {
        return width + "*" + height;
    }

    /**
     * @param value 预设名 / "a:b" 比例 / "W*H" 显式尺寸；null 或空 → 3:4（小红书竖图，本项目的默认创作方向）
     */
    public static RatioSpec resolve(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return PRESETS.get("xhs");
        }
        RatioSpec preset = PRESETS.get(v);
        if (preset != null) {
            return preset;
        }
        if (v.matches("\\d{3,4}\\s*[*x×]\\s*\\d{3,4}")) {
            String[] wh = v.split("[*x×]");
            RatioSpec spec = new RatioSpec(ratioOf(Integer.parseInt(wh[0]), Integer.parseInt(wh[1])),
                    Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
            validate(spec);
            return spec;
        }
        if (v.matches("\\d+(\\.\\d+)?\\s*[:/]\\s*\\d+(\\.\\d+)?")) {
            String[] ab = v.split("[:/]");
            double rw = Double.parseDouble(ab[0]);
            double rh = Double.parseDouble(ab[1]);
            if (rw <= 0 || rh <= 0) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "比例非法（需为正数）: " + value);
            }
            return fromRatio(ratioLabel(ab[0], ab[1], rw, rh), rw / rh);
        }
        throw new ApiException(ErrorCode.BAD_REQUEST,
                "无法识别的比例/尺寸: " + value + "（支持预设名 " + String.join("/", presetNames()) + "、\"3:4\"、\"1024*1024\"）");
    }

    /** 由宽高比推尺寸：先按长边 1024 展开，再保证短边不低于 512（不足则整体放大并封顶 1440）。 */
    private static RatioSpec fromRatio(String ratioKey, double r) {
        int w;
        int h;
        if (r >= 1.0) {
            w = LONG_BASE;
            h = round8(LONG_BASE / r);
        } else {
            h = LONG_BASE;
            w = round8(LONG_BASE * r);
        }
        int shortSide = Math.min(w, h);
        if (shortSide < MIN_SIDE) {
            double factor = (double) MIN_SIDE / shortSide;
            w = round8(w * factor);
            h = round8(h * factor);
        }
        w = Math.min(w, MAX_SIDE);
        h = Math.min(h, MAX_SIDE);
        RatioSpec spec = new RatioSpec(ratioKey, w, h);
        validate(spec);
        return spec;
    }

    private static void validate(RatioSpec spec) {
        if (spec.width() < MIN_SIDE || spec.width() > MAX_SIDE
                || spec.height() < MIN_SIDE || spec.height() > MAX_SIDE) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "尺寸 " + spec.size() + " 超出万相允许范围（宽高需在 " + MIN_SIDE + "~" + MAX_SIDE + " 像素之间）");
        }
    }

    private static int round8(double v) {
        return Math.max(MIN_SIDE, (int) Math.round(v / 8.0) * 8);
    }

    private static String ratioOf(int w, int h) {
        return ratioKey(w, h);
    }

    /**
     * 比例标签按**用户写法**保留："3:4" 就叫 3:4，不回算成 0.75:1。
     * 文件名、元数据与封面文案都用这个标签，用户口中的比例和产物上看到的比例必须是同一个字。
     */
    private static String ratioLabel(String wRaw, String hRaw, double rw, double rh) {
        if (wRaw.indexOf('.') < 0 && hRaw.indexOf('.') < 0) {
            long w = Long.parseLong(wRaw);
            long h = Long.parseLong(hRaw);
            long g = gcd(w, h);
            return (w / g) + ":" + (h / g);
        }
        return trim(rw / rh) + ":" + trim(rh / rh);
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    private static String ratioKey(double w, double h) {
        double r = w / h;
        return trim(r) + ":1";
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
