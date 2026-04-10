package com.intellibase.server.service.doc.ocr;

public interface OcrService {

    /**
     * 识别图片中的文字
     *
     * @param imageBytes 图片字节数组（jpg/png）
     * @param hint       日志/错误用途的文件名或页标签
     * @return 识别出的文本
     * @throws OcrException OCR 识别失败
     */
    String recognize(byte[] imageBytes, String hint) throws OcrException;

    /**
     * 当前 OCR 服务是否可用（enabled 且凭证已配置）
     */
    boolean isEnabled();
}
