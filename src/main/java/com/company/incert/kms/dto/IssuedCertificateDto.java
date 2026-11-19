package com.company.incert.kms.dto;

import java.time.Instant;

/**
 * Result of a certificate issuance.
 *
 * @param privateKeyPem only set when the KMS generated the key pair (no CSR supplied); returned once and never stored
 */
public record IssuedCertificateDto(
        Long caId,
        String serialNumberHex,
        String subjectDn,
        String issuerDn,
        String profile,
        Instant notBefore,
        Instant notAfter,
        String certificatePem,
        String chainPem,
        String privateKeyPem) {
}

