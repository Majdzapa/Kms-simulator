package com.company.incert.kms.service;

import com.company.incert.kms.entity.Certificate;
import com.company.incert.kms.entity.CertificateStatus;
import com.company.incert.kms.entity.RevocationReason;
import com.company.incert.kms.repository.CertificateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Core service managing certificate lifecycles and status transitions.
 * Outbox events are emitted automatically by {@code CertificateStatusEntityListener}
 * whenever a certificate is persisted or its status/sequence changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RevocationService {

    private final CertificateRepository certRepository;

    @Transactional(readOnly = true)
    public List<Certificate> listAll() {
        return certRepository.findAllByOrderBySerialNumberHexAsc();
    }

    @Transactional(readOnly = true)
    public Optional<Certificate> findBySerial(String serialHex) {
        return findCertificateBySerial(serialHex);
    }

    private Optional<Certificate> findCertificateBySerial(String serialHex) {
        if (serialHex == null || serialHex.isBlank()) {
            return Optional.empty();
        }
        String clean = serialHex.trim().toLowerCase().replace(":", "");
        if (clean.startsWith("0x")) {
            clean = clean.substring(2);
        }
        if (clean.length() % 2 != 0) {
            clean = "0" + clean;
        }
        Optional<Certificate> exact = certRepository.findBySerialNumberHex(clean);
        if (exact.isPresent()) {
            return exact;
        }
        try {
            String canonical = java.util.HexFormat.of()
                    .formatHex(new java.math.BigInteger(1, java.util.HexFormat.of().parseHex(clean)).toByteArray());
            if (!canonical.equals(clean)) {
                Optional<Certificate> byCanonical = certRepository.findBySerialNumberHex(canonical);
                if (byCanonical.isPresent()) {
                    return byCanonical;
                }
            }
            if (!canonical.startsWith("00")) {
                return certRepository.findBySerialNumberHex("00" + canonical);
            }
        } catch (IllegalArgumentException ignored) {
            // Not valid hex; return empty so caller throws CertificateNotFoundException
        }
        return Optional.empty();
    }

    @Transactional
    public Certificate revoke(String serialHex, RevocationReason reason) {
        Certificate cert = findCertificateBySerial(serialHex)
                .orElseThrow(() -> new CertificateNotFoundException("Certificate not found: " + serialHex));

        if (cert.getStatus() == CertificateStatus.REVOKED) {
            log.warn("Certificate {} already revoked, skipping", serialHex);
            return cert;
        }

        cert.setStatus(CertificateStatus.REVOKED);
        cert.setRevocationTime(Instant.now());
        cert.setRevocationReason(reason != null ? reason : RevocationReason.KEY_COMPROMISE);
        cert.setSequence(cert.getSequence() + 1);

        Certificate saved = certRepository.save(cert);
        log.info("Revoked certificate serial={} reason={} seq={}", serialHex, reason, saved.getSequence());
        return saved;
    }

    @Transactional
    public Certificate unrevoke(String serialHex) {
        Certificate cert = findCertificateBySerial(serialHex)
                .orElseThrow(() -> new CertificateNotFoundException("Certificate not found: " + serialHex));

        if (cert.getRevocationReason() != RevocationReason.CERTIFICATE_HOLD) {
            throw new IllegalStateException(
                    "Can only unrevoke CERTIFICATE_HOLD, current reason: " + cert.getRevocationReason());
        }

        cert.setStatus(CertificateStatus.GOOD);
        cert.setRevocationTime(null);
        cert.setRevocationReason(null);
        cert.setSequence(cert.getSequence() + 1);

        Certificate saved = certRepository.save(cert);
        log.info("Unrevoked certificate serial={} seq={}", serialHex, saved.getSequence());
        return saved;
    }

    @Transactional
    public Certificate updateStatus(String serialHex, CertificateStatus targetStatus,
                                   RevocationReason reason, Instant revocationTime) {
        Certificate cert = findCertificateBySerial(serialHex)
                .orElseThrow(() -> new CertificateNotFoundException("Certificate not found: " + serialHex));

        Instant finalRevTime = null;
        RevocationReason finalReason = null;

        if (targetStatus == CertificateStatus.REVOKED) {
            finalRevTime = revocationTime != null ? revocationTime : Instant.now();
            finalReason = reason != null ? reason : RevocationReason.UNSPECIFIED;
        } else if (targetStatus == CertificateStatus.CERTIFICATE_HOLD) {
            finalRevTime = revocationTime != null ? revocationTime : Instant.now();
            finalReason = RevocationReason.CERTIFICATE_HOLD;
        }

        cert.setStatus(targetStatus);
        cert.setRevocationTime(finalRevTime);
        cert.setRevocationReason(finalReason);
        cert.setSequence(cert.getSequence() + 1);

        Certificate saved = certRepository.save(cert);
        log.info("Updated certificate status serial={} status={} reason={} seq={}",
                serialHex, targetStatus, finalReason, saved.getSequence());
        return saved;
    }

    @Transactional
    public int syncAll() {
        List<Certificate> certs = certRepository.findAll();
        int queued = 0;
        for (Certificate cert : certs) {
            cert.setSequence(cert.getSequence() + 1);
            certRepository.save(cert);
            queued++;
        }
        log.info("Re-queued sync events for all {} certificates", queued);
        return queued;
    }

    public static class CertificateNotFoundException extends RuntimeException {
        public CertificateNotFoundException(String message) {
            super(message);
        }
    }
}
