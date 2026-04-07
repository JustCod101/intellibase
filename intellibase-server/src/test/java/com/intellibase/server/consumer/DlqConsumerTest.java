package com.intellibase.server.consumer;

import com.rabbitmq.client.impl.LongStringHelper;
import com.intellibase.server.common.Constants;
import com.intellibase.server.mapper.DocumentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DlqConsumerTest {

    @Mock
    private DocumentMapper documentMapper;

    @InjectMocks
    private DlqConsumer dlqConsumer;

    @Test
    void handleDeadLetter_SupportsLongStringHeadersAndMarksDocumentFailed() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-original-routingKey", LongStringHelper.asLongString("error.doc.embed.queue"));
        properties.setHeader("x-exception-message", LongStringHelper.asLongString("vector insert failed"));
        Message message = new Message("{\"docId\":2}".getBytes(StandardCharsets.UTF_8), properties);

        assertDoesNotThrow(() -> dlqConsumer.handleDeadLetter(message));

        verify(documentMapper).updateStatus(2L, Constants.DOC_STATUS_FAILED);
    }

    @Test
    void handleDeadLetter_MissingDocIdDoesNotThrow() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-original-routingKey", LongStringHelper.asLongString("error.doc.parse.queue"));
        properties.setHeader("x-exception-message", LongStringHelper.asLongString("parse failed"));
        Message message = new Message("{\"message\":\"no doc id\"}".getBytes(StandardCharsets.UTF_8), properties);

        assertDoesNotThrow(() -> dlqConsumer.handleDeadLetter(message));

        verify(documentMapper, never()).updateStatus(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }
}
