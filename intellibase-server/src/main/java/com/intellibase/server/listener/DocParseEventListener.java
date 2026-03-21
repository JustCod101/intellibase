package com.intellibase.server.listener;

import com.intellibase.server.common.Constants;
import com.intellibase.server.domain.dto.DocParseMessage;
import com.intellibase.server.domain.event.DocParseEvent;
import com.intellibase.server.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 文档解析事件监听器
 * <p>
 * 在数据库事务成功提交后，才向 RabbitMQ 发送解析消息，
 * 从根本上避免以下两类不一致：
 * <ul>
 *   <li><b>幻读消息</b>：MQ 已发送但事务回滚 → Consumer 查不到 document 记录</li>
 *   <li><b>僵尸文档</b>：事务已提交但 MQ 发送失败 → 文档永远停在 PENDING</li>
 * </ul>
 *
 * <h3>对「僵尸文档」的兜底处理</h3>
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 在事务提交后执行，
 * 如果此时 MQ 发送失败（如 Broker 宕机），会记录 ERROR 日志并将文档标记为 FAILED。
 * 运维人员可通过查询 PENDING 超时的文档进行人工重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocParseEventListener {

    private final RabbitTemplate rabbitTemplate;
    private final DocumentMapper documentMapper;

    /**
     * 事务提交后发送 MQ 消息。
     * <p>
     * 使用 {@code @Async} 避免 MQ 发送耗时阻塞调用方线程（HTTP 响应已返回）。
     *
     * @param event 包含完整 DocParseMessage 的事件对象
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocParseEvent(DocParseEvent event) {
        DocParseMessage message = event.getMessage();
        try {
            rabbitTemplate.convertAndSend(Constants.QUEUE_DOC_PARSE, message);
            log.info("事务提交后 MQ 消息发送成功: docId={}, messageId={}",
                    message.getDocId(), message.getMessageId());
        } catch (Exception e) {
            // MQ 发送失败：标记文档为 FAILED，防止永远停在 PENDING
            log.error("事务提交后 MQ 消息发送失败: docId={}, messageId={}",
                    message.getDocId(), message.getMessageId(), e);
            try {
                documentMapper.updateStatus(message.getDocId(), Constants.DOC_STATUS_FAILED);
            } catch (Exception dbEx) {
                log.error("标记文档 FAILED 也失败了，需人工介入: docId={}", message.getDocId(), dbEx);
            }
        }
    }
}
