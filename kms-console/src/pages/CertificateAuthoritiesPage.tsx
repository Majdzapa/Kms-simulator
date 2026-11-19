import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Badge,
  Box,
  Button,
  Code,
  CopyButton,
  Group,
  Modal,
  NumberInput,
  SegmentedControl,
  Select,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Title,
  Tooltip,
  ActionIcon,
  Paper,
} from '@mantine/core'
import {
  IconAlertCircle,
  IconCertificate,
  IconCheck,
  IconCopy,
  IconDownload,
  IconPlus,
  IconRefresh,
  IconShieldPlus,
} from '@tabler/icons-react'
import {
  createCertificateAuthority,
  getCertificateAuthorities,
  issueKmsCertificate,
} from '../api/client'
import type {
  IssuedCertificate,
  KmsCertificateAuthority,
  KmsCertificateProfile,
} from '../types/api'

const PROFILES: { value: KmsCertificateProfile; label: string }[] = [
  { value: 'OCSP_SIGNING', label: 'OCSP Signing (delegated responder certificate)' },
  { value: 'TLS_SERVER', label: 'TLS Server' },
  { value: 'TLS_CLIENT', label: 'TLS Client' },
  { value: 'GENERIC', label: 'Generic (digitalSignature)' },
]

const RSA_SIZES = ['2048', '3072', '4096']
const EC_SIZES = ['256', '384']

function formatDate(iso: string): string {
  return new Date(iso).toISOString().replace('T', ' ').substring(0, 19) + ' UTC'
}

function errorMessage(err: any, fallback: string): string {
  return err?.response?.data?.error || err?.response?.data?.message || err?.message || fallback
}

function downloadText(content: string, filename: string) {
  const blob = new Blob([content], { type: 'application/x-pem-file' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function PemBlock({ label, pem, filename }: { label: string; pem: string; filename: string }) {
  return (
    <Box>
      <Group justify="space-between" mb={4}>
        <Text fz="sm" fw={600}>
          {label}
        </Text>
        <Group gap={4}>
          <CopyButton value={pem}>
            {({ copied, copy }) => (
              <Tooltip label={copied ? 'Copied' : 'Copy'}>
                <ActionIcon variant="subtle" onClick={copy}>
                  {copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
                </ActionIcon>
              </Tooltip>
            )}
          </CopyButton>
          <Tooltip label="Download">
            <ActionIcon variant="subtle" onClick={() => downloadText(pem, filename)}>
              <IconDownload size={16} />
            </ActionIcon>
          </Tooltip>
        </Group>
      </Group>
      <Code block style={{ maxHeight: 180, overflow: 'auto', fontSize: 11 }}>
        {pem}
      </Code>
    </Box>
  )
}

export function CertificateAuthoritiesPage() {
  const [cas, setCas] = useState<KmsCertificateAuthority[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  // Create CA
  const [createOpen, setCreateOpen] = useState(false)
  const [caName, setCaName] = useState('')
  const [caSubject, setCaSubject] = useState('')
  const [caParent, setCaParent] = useState<string | null>(null)
  const [caKeyAlg, setCaKeyAlg] = useState<'RSA' | 'EC'>('RSA')
  const [caKeySize, setCaKeySize] = useState<string>('3072')
  const [caValidity, setCaValidity] = useState<number | string>('')
  const [createError, setCreateError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  // Issue certificate
  const [issueOpen, setIssueOpen] = useState(false)
  const [issueCaId, setIssueCaId] = useState<string | null>(null)
  const [issueMode, setIssueMode] = useState<'csr' | 'generate'>('csr')
  const [csrPem, setCsrPem] = useState('')
  const [issueSubject, setIssueSubject] = useState('')
  const [issueKeyAlg, setIssueKeyAlg] = useState<'RSA' | 'EC'>('RSA')
  const [issueKeySize, setIssueKeySize] = useState<string>('3072')
  const [issueProfile, setIssueProfile] = useState<KmsCertificateProfile>('OCSP_SIGNING')
  const [issueValidity, setIssueValidity] = useState<number | string>('')
  const [issueError, setIssueError] = useState<string | null>(null)
  const [issuing, setIssuing] = useState(false)
  const [issued, setIssued] = useState<IssuedCertificate | null>(null)

  const caById = useMemo(() => new Map(cas.map((c) => [c.id, c])), [cas])
  const caOptions = cas.map((c) => ({ value: String(c.id), label: `${c.name} (${c.subjectDn})` }))

  const fetchCas = async () => {
    setLoading(true)
    setError(null)
    try {
      setCas(await getCertificateAuthorities())
    } catch (err: any) {
      setError(errorMessage(err, 'Failed to load certificate authorities'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchCas()
  }, [])

  const openCreate = () => {
    setCaName('')
    setCaSubject('')
    setCaParent(null)
    setCaKeyAlg('RSA')
    setCaKeySize('3072')
    setCaValidity('')
    setCreateError(null)
    setCreateOpen(true)
  }

  const handleCreate = async () => {
    setCreating(true)
    setCreateError(null)
    try {
      const ca = await createCertificateAuthority({
        name: caName.trim(),
        subjectDn: caSubject.trim(),
        keyAlgorithm: caKeyAlg,
        keySize: Number(caKeySize),
        validityDays: caValidity === '' ? undefined : Number(caValidity),
        parentCaId: caParent ? Number(caParent) : null,
      })
      setSuccess(`Certificate authority '${ca.name}' created.`)
      setCreateOpen(false)
      await fetchCas()
    } catch (err: any) {
      setCreateError(errorMessage(err, 'Failed to create certificate authority'))
    } finally {
      setCreating(false)
    }
  }

  const openIssue = (caId?: number) => {
    setIssueCaId(caId !== undefined ? String(caId) : cas.length === 1 ? String(cas[0].id) : null)
    setIssueMode('csr')
    setCsrPem('')
    setIssueSubject('')
    setIssueKeyAlg('RSA')
    setIssueKeySize('3072')
    setIssueProfile('OCSP_SIGNING')
    setIssueValidity('')
    setIssueError(null)
    setIssued(null)
    setIssueOpen(true)
  }

  const handleIssue = async () => {
    if (!issueCaId) {
      setIssueError('Select the issuing CA')
      return
    }
    setIssuing(true)
    setIssueError(null)
    try {
      const result = await issueKmsCertificate(Number(issueCaId), {
        csrPem: issueMode === 'csr' ? csrPem.trim() : undefined,
        subjectDn: issueMode === 'generate' ? issueSubject.trim() : undefined,
        keyAlgorithm: issueMode === 'generate' ? issueKeyAlg : undefined,
        keySize: issueMode === 'generate' ? Number(issueKeySize) : undefined,
        validityDays: issueValidity === '' ? undefined : Number(issueValidity),
        profile: issueProfile,
      })
      setIssued(result)
    } catch (err: any) {
      setIssueError(errorMessage(err, 'Failed to issue certificate'))
    } finally {
      setIssuing(false)
    }
  }

  return (
    <Box className="page-enter">
      <Group justify="space-between" align="flex-start" mb="lg">
        <Box>
          <Group gap="sm">
            <IconCertificate size={30} color="#818cf8" />
            <Title order={3} fw={700}>
              Certificate Authorities
            </Title>
          </Group>
          <Text fz="sm" c="dimmed" mt={4}>
            Create your own CAs and issue certificates from a CSR. Nothing is pre-created: every issued certificate
            is published to the OCSP responder through the outbox.
          </Text>
        </Box>
        <Group gap="sm">
          <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={fetchCas} disabled={loading}>
            Refresh
          </Button>
          <Button variant="default" leftSection={<IconShieldPlus size={16} />} onClick={() => openIssue()}
            disabled={cas.length === 0}>
            Issue certificate
          </Button>
          <Button leftSection={<IconPlus size={16} />} onClick={openCreate}
            style={{ background: 'linear-gradient(135deg, #6366f1, #8b5cf6)' }}>
            Create CA
          </Button>
        </Group>
      </Group>

      {success && (
        <Alert color="teal" icon={<IconCheck size={16} />} withCloseButton onClose={() => setSuccess(null)} mb="lg">
          {success}
        </Alert>
      )}
      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />} title="Error" mb="lg">
          {error}
        </Alert>
      )}

      <Paper p="md" radius="md" withBorder>
        {cas.length === 0 && !loading ? (
          <Stack align="center" py="xl" gap="xs">
            <Text fw={600}>No certificate authority yet</Text>
            <Text fz="sm" c="dimmed">
              Create a root CA, then issue certificates from it (for example an OCSP signing certificate from the
              responder's CSR).
            </Text>
            <Button mt="sm" leftSection={<IconPlus size={16} />} onClick={openCreate}>
              Create CA
            </Button>
          </Stack>
        ) : (
          <Table highlightOnHover verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Name</Table.Th>
                <Table.Th>Subject</Table.Th>
                <Table.Th>Type</Table.Th>
                <Table.Th>Key</Table.Th>
                <Table.Th>Valid until</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {cas.map((ca) => (
                <Table.Tr key={ca.id}>
                  <Table.Td fw={600}>{ca.name}</Table.Td>
                  <Table.Td>
                    <Text fz="xs" ff="monospace">
                      {ca.subjectDn}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    {ca.root ? (
                      <Badge color="violet" variant="light">
                        Root
                      </Badge>
                    ) : (
                      <Badge color="indigo" variant="light">
                        Sub of {caById.get(ca.parentId as number)?.name ?? `#${ca.parentId}`}
                      </Badge>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Text fz="xs">{ca.signatureAlgorithm}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text fz="xs">{formatDate(ca.notAfter)}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4} justify="flex-end">
                      <Tooltip label="Issue certificate from this CA">
                        <ActionIcon variant="subtle" onClick={() => openIssue(ca.id)}>
                          <IconShieldPlus size={16} />
                        </ActionIcon>
                      </Tooltip>
                      <Tooltip label="Download CA certificate (import it as trust anchor in the OCSP client)">
                        <ActionIcon variant="subtle" onClick={() => downloadText(ca.certificatePem, `${ca.name}.pem`)}>
                          <IconDownload size={16} />
                        </ActionIcon>
                      </Tooltip>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Paper>

      {/* Create CA */}
      <Modal opened={createOpen} onClose={() => setCreateOpen(false)} title="Create certificate authority" size="lg">
        <Stack>
          {createError && (
            <Alert color="red" icon={<IconAlertCircle size={16} />}>
              {createError}
            </Alert>
          )}
          <TextInput label="Name" placeholder="Incert Root CA" value={caName}
            onChange={(e) => setCaName(e.currentTarget.value)} required />
          <TextInput label="Subject DN" placeholder="CN=Incert Root CA,O=Incert,C=CH" value={caSubject}
            onChange={(e) => setCaSubject(e.currentTarget.value)} required />
          <Select label="Issuing CA" description="Leave empty to create a self-signed root CA"
            placeholder="None (self-signed root)" data={caOptions} value={caParent} onChange={setCaParent} clearable />
          <Group grow>
            <Select label="Key algorithm" data={['RSA', 'EC']} value={caKeyAlg}
              onChange={(v) => {
                const alg = (v as 'RSA' | 'EC') ?? 'RSA'
                setCaKeyAlg(alg)
                setCaKeySize(alg === 'RSA' ? '3072' : '256')
              }} allowDeselect={false} />
            <Select label={caKeyAlg === 'RSA' ? 'Key size (bits)' : 'Curve size (bits)'}
              data={caKeyAlg === 'RSA' ? RSA_SIZES : EC_SIZES} value={caKeySize}
              onChange={(v) => setCaKeySize(v ?? caKeySize)} allowDeselect={false} />
            <NumberInput label="Validity (days)" placeholder={caParent ? '1825' : '3650'} min={1}
              value={caValidity} onChange={setCaValidity} />
          </Group>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setCreateOpen(false)}>
              Cancel
            </Button>
            <Button onClick={handleCreate} loading={creating} disabled={!caName.trim() || !caSubject.trim()}>
              Create CA
            </Button>
          </Group>
        </Stack>
      </Modal>

      {/* Issue certificate */}
      <Modal opened={issueOpen} onClose={() => setIssueOpen(false)} title="Issue certificate" size="xl">
        {issued ? (
          <Stack>
            <Alert color="teal" icon={<IconCheck size={16} />}>
              Certificate {issued.serialNumberHex} issued ({issued.profile}) and queued for the OCSP responder.
            </Alert>
            <PemBlock label="Certificate" pem={issued.certificatePem} filename={`${issued.serialNumberHex}.pem`} />
            <PemBlock label="Certificate + CA chain" pem={issued.chainPem} filename={`${issued.serialNumberHex}-chain.pem`} />
            {issued.privateKeyPem && (
              <>
                <Alert color="orange" icon={<IconAlertCircle size={16} />}>
                  This private key was generated by the KMS and is shown only once. It is not stored.
                </Alert>
                <PemBlock label="Private key (PKCS#8)" pem={issued.privateKeyPem}
                  filename={`${issued.serialNumberHex}-key.pem`} />
              </>
            )}
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setIssued(null)}>
                Issue another
              </Button>
              <Button onClick={() => setIssueOpen(false)}>Close</Button>
            </Group>
          </Stack>
        ) : (
          <Stack>
            {issueError && (
              <Alert color="red" icon={<IconAlertCircle size={16} />}>
                {issueError}
              </Alert>
            )}
            <Select label="Issuing CA" data={caOptions} value={issueCaId} onChange={setIssueCaId}
              placeholder="Select CA" required />
            <SegmentedControl value={issueMode} onChange={(v) => setIssueMode(v as 'csr' | 'generate')}
              data={[
                { value: 'csr', label: 'From CSR (recommended)' },
                { value: 'generate', label: 'Generate key pair in KMS' },
              ]} />
            {issueMode === 'csr' ? (
              <Textarea label="PKCS#10 CSR (PEM)"
                description="For example the CSR generated by the OCSP responder's HSM (Signing Certificates > Generate CSR)"
                placeholder={'-----BEGIN CERTIFICATE REQUEST-----\n...\n-----END CERTIFICATE REQUEST-----'}
                value={csrPem} onChange={(e) => setCsrPem(e.currentTarget.value)}
                autosize minRows={6} maxRows={12} styles={{ input: { fontFamily: 'monospace', fontSize: 11 } }} />
            ) : (
              <>
                <TextInput label="Subject DN" placeholder="CN=service.incert.internal,O=Incert,C=CH"
                  value={issueSubject} onChange={(e) => setIssueSubject(e.currentTarget.value)} />
                <Group grow>
                  <Select label="Key algorithm" data={['RSA', 'EC']} value={issueKeyAlg}
                    onChange={(v) => {
                      const alg = (v as 'RSA' | 'EC') ?? 'RSA'
                      setIssueKeyAlg(alg)
                      setIssueKeySize(alg === 'RSA' ? '3072' : '256')
                    }} allowDeselect={false} />
                  <Select label="Key size (bits)" data={issueKeyAlg === 'RSA' ? RSA_SIZES : EC_SIZES}
                    value={issueKeySize} onChange={(v) => setIssueKeySize(v ?? issueKeySize)} allowDeselect={false} />
                </Group>
              </>
            )}
            <Group grow>
              <Select label="Profile" data={PROFILES} value={issueProfile} allowDeselect={false}
                onChange={(v) => setIssueProfile((v as KmsCertificateProfile) ?? 'GENERIC')} />
              <NumberInput label="Validity (days)" placeholder="365" min={1}
                value={issueValidity} onChange={setIssueValidity} />
            </Group>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setIssueOpen(false)}>
                Cancel
              </Button>
              <Button onClick={handleIssue} loading={issuing}
                disabled={!issueCaId || (issueMode === 'csr' ? !csrPem.trim() : !issueSubject.trim())}>
                Issue certificate
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </Box>
  )
}

