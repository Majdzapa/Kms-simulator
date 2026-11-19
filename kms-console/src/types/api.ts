export type KmsRevocationReason =
  | 'UNSPECIFIED'
  | 'KEY_COMPROMISE'
  | 'CA_COMPROMISE'
  | 'AFFILIATION_CHANGED'
  | 'SUPERSEDED'
  | 'CESSATION_OF_OPERATION'
  | 'CERTIFICATE_HOLD'
  | 'PRIVILEGE_WITHDRAWN'
  | 'AA_COMPROMISE'

export interface KmsCertificate {
  id: number
  serialNumberHex: string
  issuerDn: string
  issuerKeyHashHex: string
  issuerNameHashHex: string
  subjectDn: string
  notBefore: string
  notAfter: string
  status: 'GOOD' | 'REVOKED'
  revocationTime: string | null
  revocationReason: KmsRevocationReason | null
  pemEncoded: string
  sequence: number
  updatedAt: string
}

export interface KmsSyncStatus {
  totalCertificates: number
  pendingOutboxEvents: number
  synchronized: boolean
  timestamp: string
}

export type KmsCertificateProfile =
  | 'GENERIC'
  | 'TLS_SERVER'
  | 'TLS_CLIENT'
  | 'OCSP_SIGNING'

export interface KmsCertificateAuthority {
  id: number
  name: string
  subjectDn: string
  parentId: number | null
  root: boolean
  keyAlgorithm: 'RSA' | 'EC'
  signatureAlgorithm: string
  issuerKeyHashHex: string
  issuerNameHashHex: string
  notBefore: string
  notAfter: string
  createdAt: string
  certificatePem: string
}

export interface CreateCaRequest {
  name: string
  subjectDn: string
  keyAlgorithm?: 'RSA' | 'EC'
  keySize?: number
  validityDays?: number
  parentCaId?: number | null
}

export interface IssueCertificateRequest {
  csrPem?: string
  subjectDn?: string
  keyAlgorithm?: 'RSA' | 'EC'
  keySize?: number
  validityDays?: number
  profile?: KmsCertificateProfile
}

export interface IssuedCertificate {
  caId: number
  serialNumberHex: string
  subjectDn: string
  issuerDn: string
  profile: KmsCertificateProfile
  notBefore: string
  notAfter: string
  certificatePem: string
  chainPem: string
  /** Only present when the KMS generated the key pair; shown once and never stored. */
  privateKeyPem: string | null
}

