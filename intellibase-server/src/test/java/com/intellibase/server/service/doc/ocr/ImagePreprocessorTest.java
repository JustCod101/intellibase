package com.intellibase.server.service.doc.ocr;

import com.intellibase.server.config.OcrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ImagePreprocessorTest {

    private OcrProperties.Image cfg;

    @BeforeEach
    void setUp() {
        cfg = new OcrProperties.Image();
        cfg.setMaxDimension(4096);
        cfg.setMaxSizeMb(4);
    }

    @Test
    @DisplayName("超大图片 - 应缩放到 maxDimension 以内")
    void prepare_OversizedImage_ScalesDown() throws IOException {
        BufferedImage large = new BufferedImage(5000, 3000, BufferedImage.TYPE_INT_RGB);
        byte[] input = toJpeg(large);

        byte[] result = ImagePreprocessor.prepare(input, cfg);

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result));
        assertTrue(output.getWidth() <= 4096);
        assertTrue(output.getHeight() <= 4096);
        // 验证宽高比基本保持
        double inputRatio = 5000.0 / 3000.0;
        double outputRatio = (double) output.getWidth() / output.getHeight();
        assertEquals(inputRatio, outputRatio, 0.1);
    }

    @Test
    @DisplayName("小图片 - 不缩放，正常通过")
    void prepare_SmallImage_PassesThrough() throws IOException {
        BufferedImage small = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        byte[] input = toJpeg(small);

        byte[] result = ImagePreprocessor.prepare(input, cfg);

        assertNotNull(result);
        assertTrue(result.length > 0);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result));
        // 小图片仍会经过 JPEG 编码，但尺寸不变
        assertEquals(800, output.getWidth());
        assertEquals(600, output.getHeight());
    }

    @Test
    @DisplayName("无法解码的图片 - 抛出 OcrException")
    void prepare_InvalidImage_ThrowsOcrException() {
        byte[] garbage = "not an image".getBytes();

        OcrException ex = assertThrows(OcrException.class,
                () -> ImagePreprocessor.prepare(garbage, cfg));
        assertFalse(ex.isRetryable());
    }

    @Test
    @DisplayName("正方形超大图 - 宽高都限制在 maxDimension")
    void prepare_SquareOversized_ScalesDown() throws IOException {
        BufferedImage square = new BufferedImage(6000, 6000, BufferedImage.TYPE_INT_RGB);
        byte[] input = toJpeg(square);

        byte[] result = ImagePreprocessor.prepare(input, cfg);

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(4096, output.getWidth());
        assertEquals(4096, output.getHeight());
    }

    private byte[] toJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }
}
