package com.agentflow.nodes.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 四宫格拼图：尺寸、白缝、等比裁切（不拉伸变形）、张数校验。 */
class ImageStitcherTest {

    private static byte[] tile(int w, int h, Color color) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static BufferedImage read(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static int rgb(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xFFFFFF;
    }

    @Test
    void stitchesFourTilesWithThreePixelWhiteGap() throws Exception {
        byte[] t = tile(64, 64, new Color(214, 122, 88));
        BufferedImage out = read(ImageStitcher.stitch2x2(List.of(t, t, t, t)));

        assertThat(out.getWidth()).isEqualTo(131); // 64*2 + 3
        assertThat(out.getHeight()).isEqualTo(131);
        assertThat(rgb(out, 64, 64)).isEqualTo(0xFFFFFF);  // 缝正中是白
        assertThat(rgb(out, 10, 10)).isNotEqualTo(0xFFFFFF); // 格内是原图
        assertThat(rgb(out, 120, 120)).isNotEqualTo(0xFFFFFF);
    }

    @Test
    void centerCropsWiderTilesInsteadOfStretching() throws Exception {
        byte[] wide = tile(120, 60, new Color(20, 60, 120));
        byte[] square = tile(60, 60, new Color(200, 20, 20));
        BufferedImage out = read(ImageStitcher.stitch2x2(List.of(wide, square, square, square)));

        // 格子取最大宽高 ⇒ 120x60；成品 243x123
        assertThat(out.getWidth()).isEqualTo(243);
        assertThat(out.getHeight()).isEqualTo(123);
        assertThat(rgb(out, 10, 10)).isEqualTo(0x143C78); // 左上仍是宽图裁切后的原色，没被拉伸成渐变
    }

    @Test
    void rejectsAnythingButFourImages() throws Exception {
        byte[] t = tile(8, 8, Color.RED);
        assertThatThrownBy(() -> ImageStitcher.stitch2x2(List.of(t, t, t)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正好 4 张");
    }
}
