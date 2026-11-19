package com.company.incert.kms.messaging;

import java.time.Instant;
import java.util.List;

/**
 * Purely event-driven Outbox Manifest published by KMS over RabbitMQ so OCSP Responder
 * can reconcile its Inbox and revocation_status table against KMS Outbox with zero REST calls.
 */
public record OutboxManifestEvent(
        String manifestId,
        Instant generatedAt,
        int totalCertificates,
        long totalOutboxEvents,
        long sentOutboxEvents,
        long pendingOutboxEvents,
        List<String> outboxEventIds,
        List<CertificateDigest> certificates
) {
    public record CertificateDigest(
            String serialNumberHex,
            String status,
            String revocationReason,
            long sequence,
            String latestEventId
    ) {}
}

