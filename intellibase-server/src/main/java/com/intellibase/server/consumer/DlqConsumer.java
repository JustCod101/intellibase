package com.intellibase.server.consumer;

import com.intellibase.server.common.Constants;
import com.intellibase.server.config.RabbitConfig;
import com.intellibase.server.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 死信队列消费者
 * <p>
 * 监听所有死信队列，负责在消息重试耗尽后执行最终处理：
 * <ul>
 *   <li>从消息 header 中提取 docId</li>
 *   <li>将文档状态标记为 FAILED</li>
 *   <li>记录完整的异常信息用于运维排查</li>
 * </ul>
 *
 * <p>RepublishMessageRecoverer 会在消息 header 中写入以下字段：
 * <ul>
 *   <li>x-exception-message — 异常消息</li>
 *   <li>x-exception-stacktrace — 异常堆栈</li>
 *   <li>x-original-exchange — 原始交换机</li>
 *   <li>x-original-routingKey — 原始路由键</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqConsumer {

    private final DocumentMapper documentMapper;

    @RabbitListener(queues = {RabbitConfig.DLQ_DOC_PARSE, RabbitConfig.DLQ_DOC_EMBED})
    public void handleDeadLetter(Message message) {
        Map<String, Object> headers = message.getMessageProperties().getHeaders();

        String originalQueue = (String) headers.get("x-original-routingKey");
        String exceptionMessage = (String) headers.get("x-exception-message");

        // 尝试从消息体中提取 docId
        Long docId = extractDocId(headers, message);

        log.error("消息重试耗尽进入死信队列: originalQueue={}, docId={}, exception={}",
                originalQueue, docId, exceptionMessage);

        if (docId != null) {
            documentMapper.updateStatus(docId, Constants.DOC_STATUS_FAILED);
            log.info("已将文档标记为 FAILED: docId={}", docId);
        } else {
            log.warn("无法从死信消息中提取 docId，消息已消费但未更新状态。body={}",
                    new String(message.getBody()));
        }
    }

    /**
     * 从消息体 JSON 中提取 docId。
     * 消息格式为 Jackson 序列化的 DocParseMessage 或 EmbedBatchMessage，
     * 两者都包含 docId 字段。
     */
    private Long extractDocId(Map<String, Object> headers, Message message) {
        try {
            // 消息体是 JSON，简单匹配 "docId":xxx
            String body = new String(message.getBody());
            int idx = body.indexOf("\"docId\"");
            if (idx < 0) return null;

            // 跳过 "docId": 找到值的起始位置
            int colonIdx = body.indexOf(':', idx);
            if (colonIdx < 0) return null;

            StringBuilder sb = new StringBuilder();
            for (int i = colonIdx + 1; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == ' ' || c == '\t') continue;
                if (Character.isDigit(c)) {
                    sb.append(c);
                } else if (sb.length() > 0) {
                    break;
                }
            }
            return sb.length() > 0 ? Long.parseLong(sb.toString()) : null;
        } catch (Exception e) {
            log.warn("从死信消息中提取 docId 失败", e);
            return null;
        }
    }
}
