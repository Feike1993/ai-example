import './poster.css'

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { OpenSourcePoster } from './OpenSourcePoster'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <OpenSourcePoster />
  </StrictMode>,
)
