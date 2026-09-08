import '@mantine/core/styles.css'
import '@mantine/notifications/styles.css'
import './industrial.css'

import { MantineProvider } from '@mantine/core'
import { Notifications } from '@mantine/notifications'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { theme } from '../theme'
import { IndustrialApp } from './IndustrialApp'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider theme={theme} forceColorScheme="light">
      <Notifications position="top-right" />
      <IndustrialApp />
    </MantineProvider>
  </StrictMode>,
)
