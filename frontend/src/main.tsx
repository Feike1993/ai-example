import '@mantine/core/styles.css'
import '@mantine/notifications/styles.css'
import '@mantine/code-highlight/styles.css'
import 'highlight.js/styles/github.css'
import './styles.css'

import { CodeHighlightAdapterProvider, createHighlightJsAdapter } from '@mantine/code-highlight'
import { MantineProvider } from '@mantine/core'
import { Notifications } from '@mantine/notifications'
import hljs from 'highlight.js/lib/core'
import java from 'highlight.js/lib/languages/java'
import json from 'highlight.js/lib/languages/json'
import typescript from 'highlight.js/lib/languages/typescript'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.tsx'
import { theme } from './theme.ts'

hljs.registerLanguage('json', json)
hljs.registerLanguage('java', java)
hljs.registerLanguage('typescript', typescript)
const highlightJsAdapter = createHighlightJsAdapter(hljs)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider theme={theme} forceColorScheme="light">
      <Notifications position="top-right" />
      <CodeHighlightAdapterProvider adapter={highlightJsAdapter}>
        <App />
      </CodeHighlightAdapterProvider>
    </MantineProvider>
  </StrictMode>,
)
