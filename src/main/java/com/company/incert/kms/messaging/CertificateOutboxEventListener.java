package com.company.incert.kms.messaging;

import com.company.incert.kms.entity.CertificateStatusEvent;
import com.company.incert.kms.entity.OutboxEvent;
import com.company.incert.kms.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Listens for {@link CertificateStatusEvent} emitted by {@link CertificateStatusEntityListener}
 * after the Certificate transaction commits and persists the corresponding {@link OutboxEvent}
 * for RabbitMQ delivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertificateOutboxEventListener {

    private static final String DEFAULT_TOPIC = "incert.certificate.status.v1";

    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCertificateStatusChanged(CertificateStatusEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String eventKey = event.issuerKeyHashHex() + ":" + event.serialNumberHex();
            OutboxEvent outbox = OutboxEvent.builder()
                    .eventId(event.eventId())
                    .topic(DEFAULT_TOPIC)
                    .eventKey(eventKey)
                    .payload(payload)
                    .createdAt(Instant.now())
                    .sent(false)
                    .build();
            outboxRepo.save(outbox);
            log.debug("Outbox event persisted by listener: id={} key={} status={} seq={}",
                    event.eventId(), eventKey, event.status(), event.sequence());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox event", e);
        }
    }
}

