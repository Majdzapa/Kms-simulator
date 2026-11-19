package com.company.incert.kms.dto;

import com.company.incert.kms.entity.CertificateAuthority;

import java.time.Instant;

/** Public view of a CA. The private key is never exposed. */
public record CaDto(
        Long id,
        String name,
        String subjectDn,
        Long parentId,
        boolean root,
        String keyAlgorithm,
        String signatureAlgorithm,
        String issuerKeyHashHex,
        String issuerNameHashHex,
        Instant notBefore,
        Instant notAfter,
        Instant createdAt,
        String certificatePem) {

    public static CaDto from(CertificateAuthority ca) {
        return new CaDto(
                ca.getId(),
                ca.getName(),
                ca.getSubjectDn(),
                ca.getParentId(),
                ca.getParentId() == null,
                ca.getKeyAlgorithm(),
                ca.getSignatureAlgorithm(),
                ca.getIssuerKeyHashHex(),
                ca.getIssuerNameHashHex(),
                ca.getNotBefore(),
                ca.getNotAfter(),
                ca.getCreatedAt(),
                ca.getCertificatePem());
    }
}

