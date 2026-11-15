import axios from 'axios'
import type {
  KmsCertificate,
  KmsRevocationReason,
  KmsSyncStatus,
} from '../types/api'

const kmsApi = axios.create({
  baseURL: import.meta.env.VITE_KMS_API_URL || '',
  timeout: 15_000,
  headers: { 'Content-Type': 'application/json' },
})

export async function getKmsCertificates(): Promise<KmsCertificate[]> {
  const { data } = await kmsApi.get<KmsCertificate[]>('/api/kms/certificates')
  return data
}

export async function revokeKmsCertificate(
  serialHex: string,
  reason: KmsRevocationReason | string = 'KEY_COMPROMISE'
): Promise<KmsCertificate> {
  const { data } = await kmsApi.post<KmsCertificate>(
    `/api/kms/certificates/${encodeURIComponent(serialHex)}/revoke?reason=${encodeURIComponent(reason)}`
  )
  return data
}

export async function unrevokeKmsCertificate(
  serialHex: string
): Promise<KmsCertificate> {
  const { data } = await kmsApi.post<KmsCertificate>(
    `/api/kms/certificates/${encodeURIComponent(serialHex)}/unrevoke`
  )
  return data
}

export async function updateKmsCertificateStatus(
  serialHex: string,
  status: 'GOOD' | 'REVOKED' | string,
  reason?: KmsRevocationReason | string,
  revocationTime?: string
): Promise<KmsCertificate> {
  const { data } = await kmsApi.post<KmsCertificate>(
    `/api/kms/certificates/${encodeURIComponent(serialHex)}/status`,
    { status, reason, revocationTime }
  )
  return data
}

export async function getKmsSyncStatus(): Promise<KmsSyncStatus> {
  const { data } = await kmsApi.get<KmsSyncStatus>('/api/kms/audit/sync-status')
  return data
}

export async function syncAllKmsCertificates(): Promise<{
  message: string
  count: number
  timestamp: string
}> {
  const { data } = await kmsApi.post('/api/kms/audit/sync-all')
  return data
}

export async function triggerKmsRevocationJob(): Promise<{ status: string }> {
  const { data } = await kmsApi.post<{ status: string }>(
    '/api/kms/certificates/trigger-revocation'
  )
  return data
}
