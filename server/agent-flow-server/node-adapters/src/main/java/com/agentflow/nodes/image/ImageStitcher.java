package com.agentflow.nodes.image;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 图片拼接器：把多张图拼成小红书/公众号常见的拼图布局（四宫格、横排等），
 * 带手机截屏风格的白边框，不像是裸代码生成的像素格。
 */
public final class ImageStitcher {

    private ImageStitcher() {
    }

    /**
     * 2×2 四宫格拼图，每格之间 3px 白边，外围无边框。
     * 每张源图会被等比缩放填满所在格子（居中裁剪，不拉伸变形）。
     * 输出 PNG 字节数组。
     */
    public static byte[] stitch2x2(List<byte[]> images) throws IOException {
        if (images.size() != 4) {
            throw new IllegalArgumentException("四宫格需要正好 4 张图，收到 " + images.size() + " 张");
        }
        BufferedImage[] tiles = new BufferedImage[4];
        for (int i = 0; i < 4; i++) {
            tiles[i] = ImageIO.read(new java.io.ByteArrayInputStream(images.get(i)));
        }
        int cellW = 0;
        int cellH = 0;
        for (BufferedImage t : tiles) {
            cellW = Math.max(cellW, t.getWidth());
            cellH = Math.max(cellH, t.getHeight());
        }
        int gap = 3;
        int totalW = cellW * 2 + gap;
        int totalH = cellH * 2 + gap;
        BufferedImage collage = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = collage.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, totalW, totalH);
        for (int i = 0; i < 4; i++) {
            int col = i % 2;
            int row = i / 2;
            int dx = col * (cellW + gap);
            int dy = row * (cellH + gap);
            BufferedImage scaled = scaleToFill(tiles[i], cellW, cellH);
            g.drawImage(scaled, dx, dy, null);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(collage, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage scaleToFill(BufferedImage src, int targetW, int targetH) {
        double srcRatio = (double) src.getWidth() / src.getHeight();
        double targetRatio = (double) targetW / targetH;
        int cropW, cropH;
        if (srcRatio > targetRatio) {
            cropH = src.getHeight();
            cropW = (int) (cropH * targetRatio);
        } else {
            cropW = src.getWidth();
            cropH = (int) (cropW / targetRatio);
        }
        int x = (src.getWidth() - cropW) / 2;
        int y = (src.getHeight() - cropH) / 2;
        BufferedImage cropped = src.getSubimage(x, y, cropW, cropH);
        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(cropped, 0, 0, targetW, targetH, null);
        g.dispose();
        return scaled;
    }
}