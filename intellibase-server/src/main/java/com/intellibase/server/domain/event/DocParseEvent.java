package com.intellibase.server.domain.event;

import com.intellibase.server.domain.dto.DocParseMessage;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 文档解析事件
 * <p>
 * 在 {@code DocumentServiceImpl.upload()} 事务提交后发布，
 * 由 {@link com.intellibase.server.listener.DocParseEventListener} 监听并发送 MQ 消息。
 *
 * <h3>为什么不在 Service 中直接发 MQ？</h3>
 * <pre>
 *   旧流程（有缺陷）：
 *     @Transactional
 *     upload() {
 *         minioService.upload(...)   // ① 外部 IO
 *         documentMapper.insert(...) // ② DB 写入（事务中）
 *         rabbitTemplate.send(...)   // ③ MQ 发送（事务中）
 *     }
 *
 *   问题 1: ③ 成功 → 事务回滚 → Consumer 收到消息但 DB 中无记录
 *   问题 2: ② 成功 → ③ 失败 → 文档永远停在 PENDING
 * </pre>
 *
 * <pre>
 *   新流程（本次改进）：
 *     @Transactional
 *     upload() {
 *         documentMapper.insert(...)                  // ① DB 写入
 *         applicationEventPublisher.publishEvent(...) // ② 发布事件（暂存）
 *     }  // ← 事务提交
 *
 *     @TransactionalEventListener(phase = AFTER_COMMIT)
 *     onDocParse(DocParseEvent event) {
 *         rabbitTemplate.send(...)  // ③ 事务已提交，安全发送
 *     }
 * </pre>
 */
@Getter
public class DocParseEvent extends ApplicationEvent {

    private final DocParseMessage message;

    public DocParseEvent(Object source, DocParseMessage message) {
        super(source);
        this.message = message;
    }
}
