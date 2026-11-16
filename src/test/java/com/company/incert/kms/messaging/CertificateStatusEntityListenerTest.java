package com.company.incert.kms.messaging;

import com.company.incert.kms.entity.Certificate;
import com.company.incert.kms.entity.CertificateStatus;
import com.company.incert.kms.entity.CertificateStatusEvent;
import com.company.incert.kms.entity.OutboxEvent;
import com.company.incert.kms.entity.RevocationReason;
import com.company.incert.kms.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CertificateStatusEntityListenerTest {

    @Test
    void onPostUpdate_whenStatusChanged_publishesEventAndListenerWritesOutbox() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        CertificateStatusEntityListener entityListener = new CertificateStatusEntityListener();
        entityListener.setApplicationContext(ctx);

        Certificate cert = Certificate.builder()
                .serialNumberHex("00a1b2c3")
                .issuerKeyHashHex("0123456789abcdef0123456789abcdef01234567")
                .issuerNameHashHex("fedcba9876543210fedcba9876543210fedcba98")
                .status(CertificateStatus.GOOD)
                .sequence(1L)
                .notBefore(Instant.now().minusSeconds(60))
                .notAfter(Instant.now().plusSeconds(3600))
                .build();
        cert.snapshotState();

        cert.setStatus(CertificateStatus.REVOKED);
        cert.setRevocationReason(RevocationReason.KEY_COMPROMISE);
        cert.setRevocationTime(Instant.now());
        cert.setSequence(2L);

        entityListener.onPostUpdate(cert);

        var captor = org.mockito.ArgumentCaptor.forClass(CertificateStatusEvent.class);
        verify(ctx, times(1)).publishEvent(captor.capture());

        OutboxEventRepository outboxRepo = mock(OutboxEventRepository.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        CertificateOutboxEventListener outboxListener = new CertificateOutboxEventListener(outboxRepo, mapper);

        outboxListener.onCertificateStatusChanged(captor.getValue());
        verify(outboxRepo, times(1)).save(any(OutboxEvent.class));
    }

    @Test
    void onPostUpdate_whenStatusAndSequenceUnchanged_doesNotPublish() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        CertificateStatusEntityListener entityListener = new CertificateStatusEntityListener();
        entityListener.setApplicationContext(ctx);

        Certificate cert = Certificate.builder()
                .serialNumberHex("00a1b2c3")
                .status(CertificateStatus.GOOD)
                .sequence(1L)
                .build();
        cert.snapshotState();

        entityListener.onPostUpdate(cert);
        verify(ctx, never()).publishEvent(any());
    }
}

