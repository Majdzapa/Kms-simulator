import {
  Routes,
  Route,
  NavLink as RouterNavLink,
  useLocation,
} from 'react-router-dom'
import {
  AppShell,
  NavLink,
  Group,
  Text,
  Badge,
  Box,
  Stack,
  Divider,
  Anchor,
} from '@mantine/core'
import {
  IconCertificate,
  IconArrowsExchange,
  IconBook,
  IconExternalLink,
  IconKey,
} from '@tabler/icons-react'
import { KmsCertificatesPage } from './pages/KmsCertificatesPage'
import { OutboxMonitorPage } from './pages/OutboxMonitorPage'
import { DeploymentDocsPage } from './pages/DeploymentDocsPage'

const NAV_ITEMS = [
  {
    to: '/',
    label: 'Certificate Inventory',
    description: 'Issue, revoke, hold & reinstate PKI certificates',
    icon: IconCertificate,
  },
  {
    to: '/outbox',
    label: 'RabbitMQ Outbox Relay',
    description: 'Transactional outbox & event stream sync',
    icon: IconArrowsExchange,
  },
  {
    to: '/docs',
    label: 'Architecture & Deployment',
    description: 'How to build, deploy & connect KMS to RabbitMQ',
    icon: IconBook,
  },
]

export default function App() {
  const location = useLocation()

  return (
    <AppShell
      header={{ height: 60 }}
      navbar={{ width: 270, breakpoint: 'sm' }}
      padding="lg"
      styles={{
        main: {
          backgroundColor: '#080c14',
          minHeight: '100vh',
        },
        header: {
          backgroundColor: '#0c1220',
          borderBottom: '1px solid rgba(255,255,255,0.07)',
        },
        navbar: {
          backgroundColor: '#0c1220',
          borderRight: '1px solid rgba(255,255,255,0.07)',
        },
      }}
    >
      {/* Top Header */}
      <AppShell.Header px="lg">
        <Group h="100%" justify="space-between">
          <Group gap="sm">
            <Box
              style={{
                width: 34,
                height: 34,
                borderRadius: 8,
                background: 'linear-gradient(135deg, #8b5cf6, #6366f1)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <IconKey size={19} color="#fff" />
            </Box>
            <Box>
              <Group gap="xs">
                <Text fw={700} fz="sm" c="white">
                  Incert KMS Simulator Console
                </Text>
                <Badge size="xs" color="violet" variant="light">
                  Standalone App
                </Badge>
              </Group>
              <Text fz={11} c="dimmed">
                PKI Authority &amp; RabbitMQ Event Publisher
              </Text>
            </Box>
          </Group>

          <Group gap="sm">
            <Badge color="teal" variant="dot" size="md">
              RabbitMQ Outbox Active (mTLS)
            </Badge>
          </Group>
        </Group>
      </AppShell.Header>

      {/* Left Sidebar */}
      <AppShell.Navbar p="md">
        <Stack justify="space-between" h="100%">
          <Box>
            <Text
              fz={10}
              fw={700}
              tt="uppercase"
              c="dimmed"
              mb="xs"
              px="xs"
              style={{ letterSpacing: '0.08em' }}
            >
              KMS Management
            </Text>

            <Stack gap={4}>
              {NAV_ITEMS.map((item) => {
                const Icon = item.icon
                const active =
                  item.to === '/'
                    ? location.pathname === '/'
                    : location.pathname.startsWith(item.to)
                return (
                  <NavLink
                    key={item.to}
                    component={RouterNavLink}
                    to={item.to}
                    active={active}
                    label={item.label}
                    description={item.description}
                    leftSection={<Icon size={18} />}
                    color="violet"
                    variant="light"
                    style={{ borderRadius: 8 }}
                  />
                )
              })}
            </Stack>
          </Box>

          <Box>
            <Divider my="sm" color="dark.5" />
            <Text
              fz={10}
              fw={700}
              tt="uppercase"
              c="dimmed"
              mb="xs"
              px="xs"
              style={{ letterSpacing: '0.08em' }}
            >
              External Portals
            </Text>
            <Stack gap={6} px="xs">
              <Anchor
                href="http://localhost:8090/swagger-ui.html"
                target="_blank"
                fz="xs"
                c="dimmed"
              >
                <Group gap={6}>
                  <IconExternalLink size={13} />
                  <span>KMS OpenAPI / Swagger (:8090)</span>
                </Group>
              </Anchor>
              <Anchor
                href="http://localhost:15672"
                target="_blank"
                fz="xs"
                c="dimmed"
              >
                <Group gap={6}>
                  <IconExternalLink size={13} />
                  <span>RabbitMQ Broker UI (:15672)</span>
                </Group>
              </Anchor>
              <Anchor
                href="http://localhost:3000"
                target="_blank"
                fz="xs"
                c="dimmed"
              >
                <Group gap={6}>
                  <IconExternalLink size={13} />
                  <span>OCSP Admin Console (:3000)</span>
                </Group>
              </Anchor>
            </Stack>
          </Box>
        </Stack>
      </AppShell.Navbar>

      {/* Main Content */}
      <AppShell.Main>
        <Routes>
          <Route path="/" element={<KmsCertificatesPage />} />
          <Route path="/outbox" element={<OutboxMonitorPage />} />
          <Route path="/docs" element={<DeploymentDocsPage />} />
        </Routes>
      </AppShell.Main>
    </AppShell>
  )
}

