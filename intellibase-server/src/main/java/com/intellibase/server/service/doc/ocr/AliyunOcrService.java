package com.intellibase.server.service.doc.ocr;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralRequest;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralResponse;
import com.aliyun.teaopenapi.models.Config;
import com.intellibase.server.config.OcrProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;

@Slf4j
@Service
public class AliyunOcrService implements OcrService {

    private final OcrProperties props;
    private volatile Client client;

    public AliyunOcrService(OcrProperties props) {
        this.props = props;
    }

    @Override
    public boolean isEnabled() {
        OcrProperties.Aliyun aliyun = props.getAliyun();
        return props.isEnabled()
                && StringUtils.hasText(aliyun.getAccessKeyId())
                && StringUtils.hasText(aliyun.getAccessKeySecret());
    }

    @Override
    public String recognize(byte[] imageBytes, String hint) throws OcrException {
        if (!isEnabled()) {
            throw new OcrException("OCR 未启用或凭证未配置", false);
        }

        log.debug("开始 OCR 识别: hint={}, size={}bytes", hint, imageBytes.length);

        try {
            byte[] prepared = ImagePreprocessor.prepare(imageBytes, props.getImage());

            RecognizeGeneralRequest request = new RecognizeGeneralRequest();
            request.setBody(new ByteArrayInputStream(prepared));

            RecognizeGeneralResponse response = getClient().recognizeGeneral(request);
            String data = response.getBody().getData();

            log.debug("OCR 识别完成: hint={}, 结果长度={}", hint, data == null ? 0 : data.length());
            return data != null ? data : "";

        } catch (OcrException e) {
            throw e;
        } catch (com.aliyun.tea.TeaException e) {
            throw mapTeaException(e, hint);
        } catch (Exception e) {
            throw new OcrException("OCR 识别失败: " + hint + ", " + e.getMessage(), true, e);
        }
    }

    private OcrException mapTeaException(com.aliyun.tea.TeaException e, String hint) {
        String code = e.getCode();
        int status = e.getStatusCode() != null ? e.getStatusCode() : 0;

        // 瞬时错误：可重试
        if (status >= 500 || isTransientCode(code)) {
            log.warn("OCR 瞬时错误，将重试: hint={}, code={}, status={}", hint, code, status);
            return new OcrException("OCR 瞬时错误: " + code, true, e);
        }

        // 永久错误：不重试
        log.error("OCR 永久性错误: hint={}, code={}, status={}, message={}", hint, code, status, e.getMessage());
        return new OcrException("OCR 永久性错误: " + code, false, e);
    }

    private boolean isTransientCode(String code) {
        if (code == null) return true;
        return code.contains("Throttling")
                || code.contains("ServerUnreachable")
                || code.contains("ServiceUnavailable")
                || code.contains("InternalError")
                || code.contains("Timeout");
    }

    private Client getClient() throws Exception {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = createClient();
                }
            }
        }
        return client;
    }

    private Client createClient() throws Exception {
        OcrProperties.Aliyun aliyun = props.getAliyun();
        Config config = new Config()
                .setAccessKeyId(aliyun.getAccessKeyId())
                .setAccessKeySecret(aliyun.getAccessKeySecret())
                .setEndpoint(aliyun.getEndpoint());
        return new Client(config);
    }

    // 用于测试注入 mock client
    void setClient(Client client) {
        this.client = client;
    }
}
