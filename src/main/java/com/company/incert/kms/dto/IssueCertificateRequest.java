package com.company.incert.kms.dto;

/**
 * Request to issue an end-entity certificate from a CA.
 * <p>
 * Either {@code csrPem} is provided (the private key stays with the requester, e.g. inside an HSM),
 * or {@code subjectDn} is provided and the KMS generates a key pair and returns the private key once.
 *
 * @param csrPem       PEM PKCS#10 certificate signing request
 * @param subjectDn    subject DN (only when no CSR is supplied)
 * @param keyAlgorithm {@code RSA} (default) or {@code EC} (only when no CSR is supplied)
 * @param keySize      key size (only when no CSR is supplied)
 * @param validityDays validity in days (default 365, capped at the CA expiry when omitted)
 * @param profile      {@code TLS_SERVER}, {@code TLS_CLIENT}, {@code OCSP_SIGNING} or {@code GENERIC} (default)
 */
public record IssueCertificateRequest(
        String csrPem,
        String subjectDn,
        String keyAlgorithm,
        Integer keySize,
        Integer validityDays,
        String profile) {
}

