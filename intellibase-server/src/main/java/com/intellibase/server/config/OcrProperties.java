package com.intellibase.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ocr")
public class OcrProperties {

    private boolean enabled = true;
    private String provider = "aliyun";
    private Aliyun aliyun = new Aliyun();
    private PdfFallback pdfFallback = new PdfFallback();
    private Image image = new Image();

    @Data
    public static class Aliyun {
        private String accessKeyId;
        private String accessKeySecret;
        private String endpoint = "ocr-api.cn-shanghai.aliyuncs.com";
    }

    @Data
    public static class PdfFallback {
        private int minCharsPerPage = 50;
    }

    @Data
    public static class Image {
        private int maxDimension = 4096;
        private int maxSizeMb = 4;
        private int renderDpi = 200;
    }
}
