import {
  Box,
  Title,
  Text,
  Card,
  Grid,
  Paper,
  Badge,
  Group,
  Stack,
  Code,
  Table,
  Divider,
} from '@mantine/core'
import {
  IconBook,
  IconServer,
  IconArrowsExchange,
  IconTerminal2,
  IconShieldLock,
} from '@tabler/icons-react'

const COMMAND_STEPS = [
  {
    step: '1. Create Shared Docker Network (if not already created by OCSP)',
    cmd: 'docker network inspect ocsp-net >/dev/null 2>&1 || docker network create ocsp-net',
  },
  {
    step: '2. Package KMS Simulator Spring Boot JAR (Java 25)',
    cmd: 'mvn clean package -DskipTests',
  },
  {
    step: '3. Build & Launch KMS Stack (kms-mariadb, kms-simulator, kms-console)',
    cmd: 'docker compose up -d --build --wait',
  },
  {
    step: '4. Publish All 50 Certificates to RabbitMQ Outbox (Initial Sync)',
    cmd: 'curl -s -X POST http://localhost:8090/kms/audit/sync-all',
  },
]

export function DeploymentDocsPage() {
  return (
    <Box className="page-enter">
      <Box mb="lg">
        <Group gap="xs" mb={4}>
          <IconBook size={22} color="#8b5cf6" />
          <Title order={3} fw={700}>
            KMS Architecture &amp; Deployment Guide
          </Title>
        </Group>
        <Text fz="sm" c="dimmed">
          Complete reference for deploying the standalone KMS Simulator stack and
          integrating with the OCSP Responder via RabbitMQ mTLS.
        </Text>
      </Box>

      {/* Architecture Cards */}
      <Grid gutter="md" mb="lg">
        <Grid.Col span={{ base: 12, md: 4 }}>
          <Card
            padding="md"
            radius="md"
            h="100%"
            style={{
              backgroundColor: 'var(--mantine-color-dark-6)',
              border: '1px solid var(--mantine-color-dark-4)',
            }}
          >
            <Group gap="xs" mb="xs">
              <IconServer size={18} color="#8b5cf6" />
              <Text fw={700} fz="sm">
                1. Standalone Microservice Stack
              </Text>
            </Group>
            <Text fz="xs" c="dimmed">
              Deployed independently from OCSP with its own dedicated MariaDB
              database (<Code>kms-mariadb:3308</Code>), Spring Boot 4 backend (
              <Code>kms-simulator:8090</Code>), and React 18 UI console (
              <Code>kms-console:3001</Code>).
            </Text>
          </Card>
        </Grid.Col>

        <Grid.Col span={{ base: 12, md: 4 }}>
          <Card
            padding="md"
            radius="md"
            h="100%"
            style={{
              backgroundColor: 'var(--mantine-color-dark-6)',
              border: '1px solid var(--mantine-color-dark-4)',
            }}
          >
            <Group gap="xs" mb="xs">
              <IconArrowsExchange size={18} color="#10b981" />
              <Text fw={700} fz="sm">
                2. Transactional Outbox Pattern
              </Text>
            </Group>
            <Text fz="xs" c="dimmed">
              Every revocation, hold, or reinstatement atomically updates the{' '}
              <Code>certificate</Code> table, increments the monotonic{' '}
              <Code>sequence</Code> counter, and inserts an{' '}
              <Code>outbox_event</Code> row relayed every 500ms to RabbitMQ.
            </Text>
          </Card>
        </Grid.Col>

        <Grid.Col span={{ base: 12, md: 4 }}>
          <Card
            padding="md"
            radius="md"
            h="100%"
            style={{
              backgroundColor: 'var(--mantine-color-dark-6)',
              border: '1px solid var(--mantine-color-dark-4)',
            }}
          >
            <Group gap="xs" mb="xs">
              <IconShieldLock size={18} color="#38bdf8" />
              <Text fw={700} fz="sm">
                3. Zero REST Calls from OCSP
              </Text>
            </Group>
            <Text fz="xs" c="dimmed">
              OCSP Responder never queries KMS over HTTP. All certificate status
              synchronization flows strictly one-way over RabbitMQ mTLS (
              <Code>amqps://rabbitmq:5671</Code>) via exchange{' '}
              <Code>incert.certificate.status</Code>.
            </Text>
          </Card>
        </Grid.Col>
      </Grid>

      {/* Step-by-Step Deployment */}
      <Card
        padding="lg"
        radius="md"
        mb="lg"
        style={{
          backgroundColor: 'var(--mantine-color-dark-6)',
          border: '1px solid var(--mantine-color-dark-4)',
        }}
      >
        <Group gap="xs" mb="md">
          <IconTerminal2 size={19} color="#818cf8" />
          <Title order={5}>Step-by-Step Deployment Commands</Title>
        </Group>

        <Stack gap="md">
          {COMMAND_STEPS.map((item) => (
            <Box key={item.step}>
              <Text fz="xs" fw={600} c="dimmed" mb={6}>
                {item.step}
              </Text>
              <Paper
                p="sm"
                radius="sm"
                style={{
                  backgroundColor: '#080c14',
                  border: '1px solid var(--mantine-color-dark-4)',
                }}
              >
                <Text
                  fz="xs"
                  ff="'JetBrains Mono', monospace"
                  c="indigo.2"
                  style={{ wordBreak: 'break-all' }}
                >
                  $ {item.cmd}
                </Text>
              </Paper>
            </Box>
          ))}
        </Stack>
      </Card>

      {/* Service Ports & Endpoints Table */}
      <Card
        padding="lg"
        radius="md"
        style={{
          backgroundColor: 'var(--mantine-color-dark-6)',
          border: '1px solid var(--mantine-color-dark-4)',
        }}
      >
        <Title order={5} mb="md">
          Container Topology &amp; Endpoints
        </Title>
        <Table striped highlightOnHover withTableBorder>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Container</Table.Th>
              <Table.Th>Role</Table.Th>
              <Table.Th>Host Port</Table.Th>
              <Table.Th>Network</Table.Th>
              <Table.Th>Status</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            <Table.Tr>
              <Table.Td>
                <Code>kms-console</Code>
              </Table.Td>
              <Table.Td>React 18 + Mantine UI &amp; Nginx Proxy</Table.Td>
              <Table.Td>
                <Code>http://localhost:3001</Code>
              </Table.Td>
              <Table.Td>
                <Code>ocsp-net</Code>
              </Table.Td>
              <Table.Td>
                <Badge color="teal" variant="light" size="sm">
                  Active
                </Badge>
              </Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Td>
                <Code>kms-simulator</Code>
              </Table.Td>
              <Table.Td>Spring Boot 4 REST API &amp; Outbox Relay</Table.Td>
              <Table.Td>
                <Code>http://localhost:8090</Code>
              </Table.Td>
              <Table.Td>
                <Code>ocsp-net</Code>
              </Table.Td>
              <Table.Td>
                <Badge color="teal" variant="light" size="sm">
                  Active
                </Badge>
              </Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Td>
                <Code>kms-mariadb</Code>
              </Table.Td>
              <Table.Td>MariaDB 10.11 (Database: kms)</Table.Td>
              <Table.Td>
                <Code>localhost:3308</Code>
              </Table.Td>
              <Table.Td>
                <Code>ocsp-net</Code>
              </Table.Td>
              <Table.Td>
                <Badge color="teal" variant="light" size="sm">
                  Active
                </Badge>
              </Table.Td>
            </Table.Tr>
          </Table.Tbody>
        </Table>

        <Divider my="md" color="dark.4" />
        <Text fz="xs" c="dimmed">
          Full documentation is also available in <Code>README.md</Code> at the
          root of the <Code>kms-simulator</Code> repository.
        </Text>
      </Card>
    </Box>
  )
}
