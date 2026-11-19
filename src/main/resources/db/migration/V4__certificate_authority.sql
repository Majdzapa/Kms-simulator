-- V4__certificate_authority.sql
-- User-managed Certificate Authorities (root and subordinate) used to issue certificates.
-- No CA or certificate is pre-seeded: everything is created through the KMS API / console.

CREATE TABLE certificate_authority (
    id                   BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name                 VARCHAR(128)  NOT NULL,
    subject_dn           VARCHAR(512)  NOT NULL,
    parent_id            BIGINT UNSIGNED NULL COMMENT 'Issuing CA; NULL for a self-signed root',
    key_algorithm        VARCHAR(16)   NOT NULL COMMENT 'RSA or EC',
    signature_algorithm  VARCHAR(32)   NOT NULL,
    issuer_key_hash_hex  VARCHAR(40)   NOT NULL COMMENT 'Hex SHA-1 of this CA public key (RFC 6960 issuerKeyHash)',
    issuer_name_hash_hex VARCHAR(40)   NOT NULL COMMENT 'Hex SHA-1 of this CA subject DN (RFC 6960 issuerNameHash)',
    certificate_pem      MEDIUMTEXT    NOT NULL,
    private_key_pem      MEDIUMTEXT    NOT NULL COMMENT 'PKCS#8 PEM. Simulator only: not for production use',
    not_before           DATETIME(3)   NOT NULL,
    not_after            DATETIME(3)   NOT NULL,
    created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    UNIQUE KEY uq_ca_name (name),
    INDEX idx_ca_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Certificate authorities managed by the KMS simulator';

ALTER TABLE certificate
    ADD COLUMN ca_id BIGINT UNSIGNED NULL COMMENT 'Issuing CA when issued by this KMS',
    ADD INDEX idx_certificate_ca (ca_id);

