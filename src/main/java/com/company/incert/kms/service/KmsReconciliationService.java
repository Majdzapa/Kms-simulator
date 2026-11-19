package com.company.incert.kms.service;

import com.company.incert.kms.entity.Certificate;
import com.company.incert.kms.entity.OutboxEvent;
import com.company.incert.kms.messaging.CertificateEventPublisher;
import com.company.incert.kms.messaging.OutboxManifestEvent;
import com.company.incert.kms.messaging.ReconciliationCommandMessage;
import com.company.incert.kms.repository.CertificateRepository;
import com.company.incert.kms.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Publishes KMS Outbox manifest events over RabbitMQ and listens for reconciliation/replay
 * requests from the OCSP Responder (purely event-driven, zero REST calls).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KmsReconciliationService {

    private final CertificateRepository certRepository;
    private final OutboxEventRepository outboxRepository;
    private final CertificateEventPublisher eventPublisher;
    private final RevocationService revocationService;
    private final OutboxRelayService outboxRelay;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kms.rabbitmq.exchange:incert.certificate.status}")
    private String exchange;

    @Value("${kms.rabbitmq.reconciliation-manifest-routing-key:incert.reconciliation.manifest.v1}")
    private String manifestRoutingKey;

    @Scheduled(fixedDelayString = "${kms.reconciliation.manifest-delay-ms:3000}")
    @Transactional(readOnly = true)
    public void broadcastManifest() {
        try {
            OutboxManifestEvent manifest = buildManifest();
            String json = objectMapper.writeValueAsString(manifest);

            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setContentEncoding(StandardCharsets.UTF_8.name());
            props.setMessageId(manifest.manifestId());

            Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);
            rabbitTemplate.send(exchange, manifestRoutingKey, message);
        } catch (Exception e) {
            log.debug("Could not broadcast outbox manifest: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public OutboxManifestEvent buildManifest() {
        List<Certificate> certs = certRepository.findAllByOrderBySerialNumberHexAsc();
        List<OutboxEvent> allOutbox = outboxRepository.findAll();

        long totalOutbox = allOutbox.size();
        long pendingOutbox = allOutbox.stream().filter(e -> !e.isSent()).count();
        long sentOutbox = totalOutbox - pendingOutbox;

        List<String> outboxEventIds = allOutbox.stream()
                .map(OutboxEvent::getEventId)
                .toList();

        // Map eventKey ("issuerKeyHash:serialHex") -> latest OutboxEvent by createdAt
        Map<String, OutboxEvent> latestOutboxByKey = new HashMap<>();
        for (OutboxEvent ev : allOutbox) {
            OutboxEvent prev = latestOutboxByKey.get(ev.getEventKey());
            if (prev == null || (ev.getCreatedAt() != null && prev.getCreatedAt() != null
                    && ev.getCreatedAt().isAfter(prev.getCreatedAt()))) {
                latestOutboxByKey.put(ev.getEventKey(), ev);
            }
        }

        List<OutboxManifestEvent.CertificateDigest> digests = new ArrayList<>(certs.size());
        for (Certificate c : certs) {
            String key = c.getIssuerKeyHashHex() + ":" + c.getSerialNumberHex();
            OutboxEvent latestEv = latestOutboxByKey.get(key);
            digests.add(new OutboxManifestEvent.CertificateDigest(
                    c.getSerialNumberHex(),
                    c.getStatus().name(),
                    c.getRevocationReason() != null ? c.getRevocationReason().name() : null,
                    c.getSequence(),
                    latestEv != null ? latestEv.getEventId() : null
            ));
        }

        return new OutboxManifestEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                certs.size(),
                totalOutbox,
                sentOutbox,
                pendingOutbox,
                outboxEventIds,
                digests
        );
    }

    @RabbitListener(queues = "${kms.rabbitmq.reconciliation-request-queue:kms.reconciliation.request}")
    public void onReconciliationCommand(String payload) {
        try {
            ReconciliationCommandMessage cmd = objectMapper.readValue(payload, ReconciliationCommandMessage.class);
            if ("REPLAY_EVENTS".equalsIgnoreCase(cmd.command())) {
                handleReplay(cmd);
            }
            broadcastManifest();
        } catch (Exception e) {
            log.warn("Failed to process reconciliation command from RabbitMQ: {}", e.getMessage());
        }
    }

    private void handleReplay(ReconciliationCommandMessage cmd) {
        List<String> eventIds = cmd.missingEventIds() != null ? cmd.missingEventIds() : List.of();
        List<String> serials = cmd.missingSerials() != null ? cmd.missingSerials() : List.of();

        int replayedEvents = 0;
        for (String eventId : eventIds) {
            Optional<OutboxEvent> opt = outboxRepository.findById(eventId);
            if (opt.isPresent()) {
                OutboxEvent ev = opt.get();
                eventPublisher.publish(ev.getEventId(), ev.getTopic(), ev.getEventKey(), ev.getPayload());
                ev.setSent(true);
                ev.setSentAt(Instant.now());
                outboxRepository.save(ev);
                replayedEvents++;
            }
        }

        if (!serials.isEmpty()) {
            List<OutboxEvent> allOutbox = outboxRepository.findAll();
            for (String serial : serials) {
                String clean = serial.trim().toLowerCase();
                allOutbox.stream()
                        .filter(ev -> ev.getEventKey() != null && ev.getEventKey().toLowerCase().endsWith(":" + clean))
                        .max(Comparator.comparing(OutboxEvent::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                        .ifPresent(ev -> {
                            eventPublisher.publish(ev.getEventId(), ev.getTopic(), ev.getEventKey(), ev.getPayload());
                            ev.setSent(true);
                            ev.setSentAt(Instant.now());
                            outboxRepository.save(ev);
                        });
            }
        } else if (eventIds.isEmpty()) {
            // Full replay requested
            revocationService.syncAll();
        }

        outboxRelay.relay();
        log.info("Processed RabbitMQ reconciliation replay: replayedEvents={} requestedSerials={}",
                replayedEvents, serials.size());
    }
}

