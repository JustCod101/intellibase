package com.intellibase.server.service.doc.ocr;

import com.intellibase.server.config.OcrProperties;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 图片预处理工具：缩放和压缩以满足阿里云 OCR 限制（单图 4MB / 4096px）
 */
class ImagePreprocessor {

    static byte[] prepare(byte[] bytes, OcrProperties.Image cfg) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new OcrException("无法解码图片", false);
        }

        try {
            // 缩放：如果宽或高超过 maxDimension
            int w = image.getWidth();
            int h = image.getHeight();
            int maxDim = cfg.getMaxDimension();
            if (w > maxDim || h > maxDim) {
                double scale = (double) maxDim / Math.max(w, h);
                int newW = (int) (w * scale);
                int newH = (int) (h * scale);
                BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image, 0, 0, newW, newH, null);
                g.dispose();
                image.flush();
                image = scaled;
            }

            // JPEG 编码 + 质量递减压缩
            long maxBytes = (long) cfg.getMaxSizeMb() * 1024 * 1024;
            float quality = 0.9f;

            for (int attempt = 0; attempt < 5; attempt++) {
                byte[] encoded = encodeJpeg(image, quality);
                if (encoded.length <= maxBytes) {
                    return encoded;
                }
                quality -= 0.1f;
            }

            throw new OcrException("图片压缩后仍超过 " + cfg.getMaxSizeMb() + "MB 限制", false);
        } finally {
            image.flush();
        }
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG ImageWriter found");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
