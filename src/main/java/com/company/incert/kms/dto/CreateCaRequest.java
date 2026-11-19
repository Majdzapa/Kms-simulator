package com.company.incert.kms.dto;

/**
 * Request to create a certificate authority.
 *
 * @param name         unique display name
 * @param subjectDn    RFC 4514/RFC 2253 subject DN, e.g. {@code CN=Incert Root CA,O=Incert,C=CH}
 * @param keyAlgorithm {@code RSA} (default) or {@code EC}
 * @param keySize      RSA: 2048, 3072 (default) or 4096. EC: 256 (default) or 384
 * @param validityDays validity in days (default 3650 for a root, 1825 for a subordinate CA)
 * @param parentCaId   issuing CA id; omit to create a self-signed root CA
 */
public record CreateCaRequest(
        String name,
        String subjectDn,
        String keyAlgorithm,
        Integer keySize,
        Integer validityDays,
        Long parentCaId) {
}

