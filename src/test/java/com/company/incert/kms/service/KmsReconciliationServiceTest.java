package com.company.incert.kms.service;

import com.company.incert.kms.entity.Certificate;
import com.company.incert.kms.entity.CertificateStatus;
import com.company.incert.kms.entity.OutboxEvent;
import com.company.incert.kms.messaging.CertificateEventPublisher;
import com.company.incert.kms.messaging.ReconciliationCommandMessage;
import com.company.incert.kms.repository.CertificateRepository;
import com.company.incert.kms.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KmsReconciliationServiceTest {

    @Mock
    private CertificateRepository certRepository;

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock
    private CertificateEventPublisher eventPublisher;

    @Mock
    private RevocationService revocationService;

    @Mock
    private OutboxRelayService outboxRelay;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private ObjectMapper objectMapper;
    private KmsReconciliationService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new KmsReconciliationService(
                certRepository,
                outboxRepository,
                eventPublisher,
                revocationService,
                outboxRelay,
                rabbitTemplate,
                objectMapper
        );
    }

    @Test
    void shouldSynthesizeAndPublishOutboxEventWhenSerialIsMissingFromOutboxTable() throws Exception {
        Certificate cert = Certificate.builder()
                .serialNumberHex("01a2b3")
                .issuerDn("CN=Test CA")
                .issuerKeyHashHex("aabbcc")
                .issuerNameHashHex("ddeeff")
                .subjectDn("CN=leaf")
                .status(CertificateStatus.GOOD)
                .sequence(2L)
                .build();

        when(outboxRepository.findAll()).thenReturn(List.of());
        when(revocationService.findBySerial("01a2b3")).thenReturn(Optional.of(cert));
        when(certRepository.findAllByOrderBySerialNumberHexAsc()).thenReturn(List.of(cert));

        ReconciliationCommandMessage cmd = new ReconciliationCommandMessage(
                "REPLAY_EVENTS",
                Instant.now(),
                List.of(),
                List.of("01a2b3")
        );

        service.onReconciliationCommand(objectMapper.writeValueAsString(cmd));

        ArgumentCaptor<OutboxEvent> savedOutbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(savedOutbox.capture());
        assertThat(savedOutbox.getValue().getEventKey()).isEqualTo("aabbcc:01a2b3");
        assertThat(savedOutbox.getValue().isSent()).isTrue();
        assertThat(savedOutbox.getValue().getPayload()).contains("01a2b3");

        verify(eventPublisher).publish(
                eq(savedOutbox.getValue().getEventId()),
                anyString(),
                eq("aabbcc:01a2b3"),
                eq(savedOutbox.getValue().getPayload())
        );
    }
}
