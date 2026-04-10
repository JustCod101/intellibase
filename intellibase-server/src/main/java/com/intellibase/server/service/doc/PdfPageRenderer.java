package com.intellibase.server.service.doc;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * PDF 页面渲染器
 * 使用 PDFBox 将 PDF 的每一页渲染为 JPEG 图片，供 OCR 识别。
 * 采用逐页回调模式，避免把所有页面位图同时驻留内存。
 */
@Component
public class PdfPageRenderer {

    @FunctionalInterface
    public interface PageConsumer {
        void accept(int pageIndex, byte[] jpegBytes) throws IOException;
    }

    /**
     * 获取 PDF 总页数
     */
    public int pageCount(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            return doc.getNumberOfPages();
        }
    }

    /**
     * 逐页渲染 PDF 为 JPEG，通过回调交给调用方处理。
     * 每页渲染完毕后立即释放 BufferedImage，峰值内存仅为单页位图大小。
     *
     * @param pdfBytes PDF 文件字节数组
     * @param dpi      渲染 DPI（推荐 200）
     * @param consumer 每页的回调处理器
     */
    public void forEachPage(byte[] pdfBytes, int dpi, PageConsumer consumer) throws IOException {
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = doc.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(image, "jpg", baos);
                    consumer.accept(i, baos.toByteArray());
                } finally {
                    image.flush();
                }
            }
        }
    }
}
