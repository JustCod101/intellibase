package com.intellibase.server.service.doc.ocr;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralRequest;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralResponse;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralResponseBody;
import com.aliyun.tea.TeaException;
import com.intellibase.server.config.OcrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AliyunOcrServiceTest {

    @Mock
    private Client client;

    private OcrProperties props;
    private AliyunOcrService ocrService;

    @BeforeEach
    void setUp() {
        props = new OcrProperties();
        props.setEnabled(true);
        OcrProperties.Aliyun aliyun = new OcrProperties.Aliyun();
        aliyun.setAccessKeyId("test-ak");
        aliyun.setAccessKeySecret("test-sk");
        props.setAliyun(aliyun);

        ocrService = new AliyunOcrService(props);
        ocrService.setClient(client);
    }

    @Test
    @DisplayName("正常识别 - 返回文本")
    void recognize_Success() throws Exception {
        RecognizeGeneralResponseBody body = new RecognizeGeneralResponseBody();
        body.setData("识别出的文字内容");
        RecognizeGeneralResponse response = new RecognizeGeneralResponse();
        response.setBody(body);

        when(client.recognizeGeneral(any(RecognizeGeneralRequest.class))).thenReturn(response);

        byte[] testImage = createSmallJpeg();
        String result = ocrService.recognize(testImage, "test.jpg");

        assertEquals("识别出的文字内容", result);
    }

    @Test
    @DisplayName("5xx 错误 - 抛出可重试异常")
    void recognize_ServerError_ThrowsRetryable() throws Exception {
        TeaException teaEx = new TeaException(null, null);
        teaEx.setCode("InternalError");
        teaEx.setStatusCode(500);

        when(client.recognizeGeneral(any(RecognizeGeneralRequest.class))).thenThrow(teaEx);

        byte[] testImage = createSmallJpeg();
        OcrException ex = assertThrows(OcrException.class,
                () -> ocrService.recognize(testImage, "test.jpg"));
        assertTrue(ex.isRetryable());
    }

    @Test
    @DisplayName("403 鉴权失败 - 抛出不可重试异常")
    void recognize_Forbidden_ThrowsNonRetryable() throws Exception {
        TeaException teaEx = new TeaException(null, null);
        teaEx.setCode("InvalidAccessKey");
        teaEx.setStatusCode(403);

        when(client.recognizeGeneral(any(RecognizeGeneralRequest.class))).thenThrow(teaEx);

        byte[] testImage = createSmallJpeg();
        OcrException ex = assertThrows(OcrException.class,
                () -> ocrService.recognize(testImage, "test.jpg"));
        assertFalse(ex.isRetryable());
    }

    @Test
    @DisplayName("限流错误 - 抛出可重试异常")
    void recognize_Throttling_ThrowsRetryable() throws Exception {
        TeaException teaEx = new TeaException(null, null);
        teaEx.setCode("Throttling");
        teaEx.setStatusCode(429);

        when(client.recognizeGeneral(any(RecognizeGeneralRequest.class))).thenThrow(teaEx);

        byte[] testImage = createSmallJpeg();
        OcrException ex = assertThrows(OcrException.class,
                () -> ocrService.recognize(testImage, "test.jpg"));
        assertTrue(ex.isRetryable());
    }

    @Test
    @DisplayName("OCR 未启用 - isEnabled 返回 false")
    void isEnabled_Disabled() {
        props.setEnabled(false);
        assertFalse(ocrService.isEnabled());
    }

    @Test
    @DisplayName("AK/SK 为空 - isEnabled 返回 false")
    void isEnabled_NoCredentials() {
        props.getAliyun().setAccessKeyId("");
        assertFalse(ocrService.isEnabled());
    }

    @Test
    @DisplayName("OCR 未启用时调用 recognize - 抛出不可重试异常")
    void recognize_Disabled_ThrowsNonRetryable() {
        props.setEnabled(false);

        OcrException ex = assertThrows(OcrException.class,
                () -> ocrService.recognize("test".getBytes(), "test.jpg"));
        assertFalse(ex.isRetryable());
    }

    private byte[] createSmallJpeg() throws IOException {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }
}
