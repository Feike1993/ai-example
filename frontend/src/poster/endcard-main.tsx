import './endcard.css'

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RepoEndCard } from './RepoEndCard'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RepoEndCard />
  </StrictMode>,
)
