package com.company.incert.kms.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A user-created certificate authority (self-signed root or subordinate) that can issue certificates.
 * The private key is stored as PKCS#8 PEM: this is a simulator, not a hardened key store.
 */
@Entity
@Table(name = "certificate_authority",
        uniqueConstraints = @UniqueConstraint(name = "uq_ca_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CertificateAuthority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "subject_dn", nullable = false, length = 512)
    private String subjectDn;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "key_algorithm", nullable = false, length = 16)
    private String keyAlgorithm;

    @Column(name = "signature_algorithm", nullable = false, length = 32)
    private String signatureAlgorithm;

    @Column(name = "issuer_key_hash_hex", nullable = false, length = 40)
    private String issuerKeyHashHex;

    @Column(name = "issuer_name_hash_hex", nullable = false, length = 40)
    private String issuerNameHashHex;

    @Column(name = "certificate_pem", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String certificatePem;

    @Column(name = "private_key_pem", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String privateKeyPem;

    @Column(name = "not_before", nullable = false)
    private Instant notBefore;

    @Column(name = "not_after", nullable = false)
    private Instant notAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

