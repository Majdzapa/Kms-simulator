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

