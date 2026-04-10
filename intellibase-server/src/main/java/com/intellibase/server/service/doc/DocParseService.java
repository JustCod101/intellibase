package com.intellibase.server.service.doc;

import com.intellibase.server.config.OcrProperties;
import com.intellibase.server.service.doc.ocr.OcrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 文档解析服务
 * 使用 Apache Tika 从 PDF/Word/PPT/TXT/Markdown 等格式中提取纯文本。
 * 对于扫描件 PDF 和图片文件，自动走 OCR 识别。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocParseService {

    private final Parser parser = new AutoDetectParser();
    private final OcrService ocrService;
    private final OcrProperties ocrProps;
    private final PdfPageRenderer pdfRenderer;

    /**
     * 解析文档，提取纯文本内容
     *
     * @param bytes    文件字节数组
     * @param fileType 文件类型（pdf/docx/md/txt/jpg/jpeg/png）
     * @return 提取出的纯文本
     */
    public String parse(byte[] bytes, String fileType) throws IOException, TikaException {
        String type = fileType == null ? "" : fileType.toLowerCase();
        log.debug("开始解析文档, fileType={}, size={}bytes", type, bytes.length);

        // 图片：直接走 OCR
        if (type.equals("jpg") || type.equals("jpeg") || type.equals("png")) {
            if (!ocrService.isEnabled()) {
                throw new TikaException("OCR 未启用，无法解析图片文件");
            }
            String result = ocrService.recognize(bytes, "image." + type);
            log.debug("图片 OCR 完成, 文本长度={}", result.length());
            return result;
        }

        // 其它格式：Tika 提取
        String text = tikaExtract(bytes);

        // PDF 扫描件智能回退
        if (type.equals("pdf") && ocrService.isEnabled()) {
            int pages = pdfRenderer.pageCount(bytes);
            int avg = pages == 0 ? 0 : text.length() / pages;
            if (avg < ocrProps.getPdfFallback().getMinCharsPerPage()) {
                log.info("检测到扫描件 PDF，启动 OCR 回退: pages={}, charsPerPage={}", pages, avg);
                StringBuilder sb = new StringBuilder();
                pdfRenderer.forEachPage(bytes, ocrProps.getImage().getRenderDpi(), (idx, jpeg) -> {
                    sb.append(ocrService.recognize(jpeg, "pdf-page-" + idx)).append("\n\n");
                });
                return sb.toString();
            }
        }

        return text;
    }

    private String tikaExtract(byte[] bytes) throws IOException, TikaException {
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        try {
            parser.parse(new ByteArrayInputStream(bytes), handler, metadata, context);
        } catch (org.xml.sax.SAXException e) {
            throw new TikaException("SAX parsing error", e);
        }

        String text = handler.toString();
        log.debug("Tika 解析完成, 提取文本长度={}", text.length());
        return text;
    }
}
