package com.company.incert.kms.messaging;

import com.company.incert.kms.entity.Certificate;
import com.company.incert.kms.entity.CertificateStatusEvent;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * JPA EntityListener that automatically detects Certificate creation and status/sequence
 * changes and publishes a {@link CertificateStatusEvent} via Spring's {@link ApplicationEventPublisher}.
 * <p>
 * Services (issue, revoke, unrevoke, updateStatus, seed) do not need to manually write outbox events.
 */
@Slf4j
@Component
public class CertificateStatusEntityListener implements ApplicationContextAware {

    private static volatile ApplicationEventPublisher eventPublisher;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        CertificateStatusEntityListener.eventPublisher = applicationContext;
    }

    @PostPersist
    public void onPostPersist(Certificate cert) {
        cert.snapshotState();
        publish(cert, "PERSIST");
    }

    @PostUpdate
    public void onPostUpdate(Certificate cert) {
        if (cert.hasStatusOrSequenceChanged()) {
            cert.snapshotState();
            publish(cert, "UPDATE");
        }
    }

    private void publish(Certificate cert, String action) {
        ApplicationEventPublisher publisher = CertificateStatusEntityListener.eventPublisher;
        if (publisher == null) {
            log.warn("ApplicationEventPublisher not initialized; skipping {} event for serial={}",
                    action, cert.getSerialNumberHex());
            return;
        }
        CertificateStatusEvent event = CertificateStatusEvent.from(cert);
        log.debug("Detected certificate {} ({}): serial={} status={} seq={}",
                action, event.eventId(), cert.getSerialNumberHex(), cert.getStatus(), cert.getSequence());
        publisher.publishEvent(event);
    }
}

