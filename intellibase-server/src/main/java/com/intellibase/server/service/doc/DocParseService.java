package com.intellibase.server.service.doc;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
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

    private final Tika tika = new Tika();

    /**
     * 解析文档，提取纯文本内容
     *
     * @param inputStream 文件输入流
     * @param fileType    文件类型（pdf/docx/md/txt）
     * @return 提取出的纯文本
     */
    public String parse(InputStream inputStream, String fileType) throws IOException, TikaException {
        log.debug("开始 Tika 解析, fileType={}", fileType);

        // Tika 会根据文件内容自动识别格式并提取文本
        // 支持 PDF、Word(docx/doc)、PPT、HTML、Markdown、TXT 等 1000+ 种格式
        String text = tika.parseToString(inputStream);

        log.debug("Tika 解析完成, 提取文本长度={}", text.length());
        return text;
    }

}
