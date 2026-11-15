import { useState, useEffect, useCallback } from 'react'
import {
  Box,
  Title,
  Text,
  Card,
  Grid,
  Button,
  Badge,
  Alert,
  Paper,
  Group,
  Stack,
  Code,
} from '@mantine/core'
import {
  IconRefresh,
  IconArrowsExchange,
  IconCheck,
  IconAlertTriangle,
  IconShieldCheck,
  IconDatabase,
  IconLock,
} from '@tabler/icons-react'
import { getKmsSyncStatus, syncAllKmsCertificates } from '../api/client'
import type { KmsSyncStatus } from '../types/api'

export function OutboxMonitorPage() {
  const [status, setStatus] = useState<KmsSyncStatus | null>(null)
  const [loading, setLoading] = useState(false)
  const [syncing, setSyncing] = useState(false)
  const [banner, setBanner] = useState<{
    color: 'teal' | 'red' | 'indigo'
    message: string
  } | null>(null)

  const loadStatus = useCallback(async () => {
    setLoading(true)
    try {
      const res = await getKmsSyncStatus()
      setStatus(res)
    } catch {
      setBanner({
        color: 'red',
        message: 'Failed to load KMS outbox sync status.',
      })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadStatus()
    const timer = setInterval(loadStatus, 5000)
    return () => clearInterval(timer)
  }, [loadStatus])

  const handleSyncAll = async () => {
    setSyncing(true)
    setBanner(null)
    try {
      const res = await syncAllKmsCertificates()
      setBanner({
        color: 'teal',
        message: `${res.message} (${res.count} certificates published to RabbitMQ exchange incert.certificate.status)`,
      })
      await loadStatus()
    } catch {
      setBanner({
        color: 'red',
        message: 'Failed to publish certificate snapshot to RabbitMQ.',
      })
    } finally {
      setSyncing(false)
    }
  }

  return (
    <Box className="page-enter">
      <Group justify="space-between" align="flex-start" mb="lg" wrap="wrap">
        <Box>
          <Title order={3} fw={700}>
            RabbitMQ Transactional Outbox Relay
          </Title>
          <Text fz="sm" c="dimmed" mt={4}>
            Monitors the KMS transactional outbox table (`outbox_event`) and relays
            `CertificateStatusEvent` payloads over mTLS to RabbitMQ (`incert.certificate.status`).
          </Text>
        </Box>

        <Group gap="sm">
          <Button
            variant="light"
            color="indigo"
            leftSection={<IconArrowsExchange size={16} />}
            loading={syncing}
            onClick={handleSyncAll}
          >
            Publish All Certificates to RabbitMQ
          </Button>
          <Button
            variant="default"
            leftSection={<IconRefresh size={16} />}
            loading={loading}
            onClick={loadStatus}
          >
            Refresh
          </Button>
        </Group>
      </Group>

      {banner && (
        <Alert
          color={banner.color}
          icon={
            banner.color === 'teal' ? (
              <IconCheck size={18} />
            ) : (
              <IconAlertTriangle size={18} />
            )
          }
          withCloseButton
          onClose={() => setBanner(null)}
          radius="md"
          mb="md"
        >
          {banner.message}
        </Alert>
      )}

      <Grid gutter="md" mb="lg">
        <Grid.Col span={{ base: 12, sm: 4 }}>
          <Card
            padding="md"
            radius="md"
            style={{
              backgroundColor: 'var(--mantine-color-dark-6)',
              border: '1px solid var(--mantine-color-dark-4)',
            }}
          >
            <Text fz="xs" tt="uppercase" fw={700} c="dimmed">
              Managed Certificates (KMS DB)
            </Text>
            <Text fz="xl" fw={700} mt={4}>
              {status?.totalCertificates ?? '--'}
            </Text>
          </Card>
        </Grid.Col>

        <Grid.Col span={{ base: 12, sm: 4 }}>
          <Card
            padding="md"
            radius="md"
            style={{
              backgroundColor: 'var(--mantine-color-dark-6)',
              border: '1px solid var(--mantine-color-dark-4)',
            }}
          >
            <Text fz="xs" tt="uppercase" fw={700} c="dimmed">
              Pending Outbox Events
            </Text>
            <Text
              fz="xl"
              fw={700}
              c={(status?.pendingOutboxEvents ?? 0) === 0 ? 'teal.4' : 'yellow.4'}
              mt={4}
            >
              {status?.pendingOutboxEvents ?? '--'}
            </Text>
          </Card>
        </Grid.Col>

        <Grid.Col span={{ base: 12, sm: 4 }}>
          <Card
            padding="md"
            radius="md"
            style={{
              backgroundColor: 'var(--mantine-color-dark-6)',
              border: '1px solid var(--mantine-color-dark-4)',
            }}
          >
            <Text fz="xs" tt="uppercase" fw={700} c="dimmed">
              Outbox Relay Status
            </Text>
            <Box mt={6}>
              {status?.synchronized ? (
                <Badge color="teal" size="lg" variant="light">
                  100% RELAYED TO RABBITMQ
                </Badge>
              ) : status ? (
                <Badge color="yellow" size="lg" variant="light">
                  RELAY IN PROGRESS ({status.pendingOutboxEvents} PENDING)
                </Badge>
              ) : (
                <Badge color="gray" size="lg" variant="light">
                  CHECKING...
                </Badge>
              )}
            </Box>
          </Card>
        </Grid.Col>
      </Grid>

      <Card
        padding="lg"
        radius="md"
        style={{
          backgroundColor: 'var(--mantine-color-dark-6)',
          border: '1px solid var(--mantine-color-dark-4)',
        }}
      >
        <Group gap="xs" mb="md">
          <IconShieldCheck size={20} color="#10b981" />
          <Title order={5}>Event-Driven Architecture &amp; Broker Topology</Title>
        </Group>

        <Stack gap="sm">
          <Paper
            p="sm"
            radius="sm"
            style={{
              backgroundColor: 'var(--mantine-color-dark-7)',
              border: '1px solid var(--mantine-color-dark-4)',
            }}
          >
            <Group justify="space-between">
              <Group gap="xs">
                <IconDatabase size={16} color="#818cf8" />
                <Text fz="sm" fw={600}>
                  KMS Dedicated Database
                </Text>
              </Group>
              <Code>kms-mariadb:3306/kms (Host port 3308)</Code>
            </Group>
          </Paper>

          <Paper
            p="sm"
            radius="sm"
            style={{
              backgroundColor: 'var(--mantine-color-dark-7)',
              border: '1px solid var(--mantine-color-dark-4)',
            }}
          >
            <Group justify="space-between">
              <Group gap="xs">
                <IconLock size={16} color="#10b981" />
                <Text fz="sm" fw={600}>
                  RabbitMQ mTLS Transport
                </Text>
              </Group>
              <Code>amqps://rabbitmq:5671 (PKCS#12 Mutual TLS)</Code>
            </Group>
          </Paper>

          <Paper
            p="sm"
            radius="sm"
            style={{
              backgroundColor: 'var(--mantine-color-dark-7)',
              border: '1px solid var(--mantine-color-dark-4)',
            }}
          >
            <Group justify="space-between">
              <Group gap="xs">
                <IconArrowsExchange size={16} color="#38bdf8" />
                <Text fz="sm" fw={600}>
                  Exchange &amp; Routing Key
                </Text>
              </Group>
              <Code>incert.certificate.status → incert.certificate.status.v1</Code>
            </Group>
          </Paper>
        </Stack>
      </Card>
    </Box>
  )
}

