package com.intellibase.server.service.doc;

import com.intellibase.server.config.OcrProperties;
import com.intellibase.server.service.doc.ocr.OcrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocParseServiceOcrTest {

    @Mock
    private OcrService ocrService;

    @Mock
    private PdfPageRenderer pdfRenderer;

    private OcrProperties ocrProps;
    private DocParseService docParseService;

    @BeforeEach
    void setUp() {
        ocrProps = new OcrProperties();
        docParseService = new DocParseService(ocrService, ocrProps, pdfRenderer);
    }

    @Test
    @DisplayName("图片文件 - 直接走 OCR")
    void parse_ImageFile_UsesOcr() throws Exception {
        when(ocrService.isEnabled()).thenReturn(true);
        when(ocrService.recognize(any(byte[].class), eq("image.jpg"))).thenReturn("OCR 识别文本");

        String result = docParseService.parse("fake image bytes".getBytes(), "jpg");

        assertEquals("OCR 识别文本", result);
        verify(ocrService).recognize(any(byte[].class), eq("image.jpg"));
    }

    @Test
    @DisplayName("图片文件 - OCR 未启用时抛异常")
    void parse_ImageFile_OcrDisabled_Throws() {
        when(ocrService.isEnabled()).thenReturn(false);

        assertThrows(Exception.class,
                () -> docParseService.parse("fake".getBytes(), "png"));
    }

    @Test
    @DisplayName("文字型文本 - 走 Tika，不调 OCR")
    void parse_TextFile_SkipsOcr() throws Exception {
        String content = "这是一段足够长的文本用于测试".repeat(10);

        String result = docParseService.parse(content.getBytes(), "txt");

        assertFalse(result.isEmpty());
        verify(ocrService, never()).recognize(any(), any());
    }

    @Test
    @DisplayName("非 PDF 文件 - 不检查页数也不触发 OCR 回退")
    void parse_NonPdfFile_NoPdfFallback() throws Exception {
        String result = docParseService.parse("plain text".getBytes(), "txt");

        verify(pdfRenderer, never()).pageCount(any());
        verify(ocrService, never()).recognize(any(), any());
    }

    @Test
    @DisplayName("jpeg 和 png 格式也走 OCR")
    void parse_JpegAndPng_UsesOcr() throws Exception {
        when(ocrService.isEnabled()).thenReturn(true);
        when(ocrService.recognize(any(byte[].class), anyString())).thenReturn("text");

        docParseService.parse("jpeg".getBytes(), "jpeg");
        verify(ocrService).recognize(any(), eq("image.jpeg"));

        docParseService.parse("png".getBytes(), "png");
        verify(ocrService).recognize(any(), eq("image.png"));
    }
}
