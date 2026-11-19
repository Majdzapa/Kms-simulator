package com.company.incert.kms.messaging;

import java.time.Instant;
import java.util.List;

/**
 * Command message consumed by KMS over RabbitMQ from OCSP Responder
 * to request an immediate Outbox manifest or trigger event replay.
 */
public record ReconciliationCommandMessage(
        String command, // "REQUEST_MANIFEST" or "REPLAY_EVENTS"
        Instant requestedAt,
        List<String> missingEventIds,
        List<String> missingSerials
) {}

