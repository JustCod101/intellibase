package com.intellibase.server.service.doc;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文档解析服务
 * 使用 Apache Tika 从 PDF/Word/PPT/TXT/Markdown 等格式中提取纯文本
 */
@Slf4j
@Service
public class DocParseService {

    private final Parser parser = new AutoDetectParser();

    /**
     * 解析文档，提取纯文本内容
     *
     * @param inputStream 文件输入流
     * @param fileType    文件类型（pdf/docx/md/txt）
     * @return 提取出的纯文本
     */
    public String parse(InputStream inputStream, String fileType) throws IOException, TikaException {
        log.debug("开始 Tika 解析, fileType={}", fileType);

        // writeLimit = -1 表示不限制提取文本长度（默认 100,000 字符会截断大文档）
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        try {
            parser.parse(inputStream, handler, metadata, context);
        } catch (org.xml.sax.SAXException e) {
            throw new TikaException("SAX parsing error", e);
        }

        String text = handler.toString();
        log.debug("Tika 解析完成, 提取文本长度={}", text.length());
        return text;
    }

}
