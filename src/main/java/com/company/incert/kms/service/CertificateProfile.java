package com.company.incert.kms.service;

/** Certificate profiles the KMS can issue. They drive keyUsage / extendedKeyUsage and other extensions. */
public enum CertificateProfile {
    /** TLS server authentication (EKU serverAuth); SAN is copied from the CSR when present. */
    TLS_SERVER,
    /** TLS client authentication (EKU clientAuth); SAN is copied from the CSR when present. */
    TLS_CLIENT,
    /** Delegated OCSP responder certificate (EKU OCSPSigning + id-pkix-ocsp-nocheck). */
    OCSP_SIGNING,
    /** Plain digitalSignature certificate without extended key usage. */
    GENERIC;

    public static CertificateProfile parse(String value) {
        if (value == null || value.isBlank()) {
            return GENERIC;
        }
        try {
            return valueOf(value.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown certificate profile '" + value
                    + "'. Valid profiles: TLS_SERVER, TLS_CLIENT, OCSP_SIGNING, GENERIC");
        }
    }
}

