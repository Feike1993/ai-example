import '../styles.css'
import './promo.css'

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import PromoApp from './PromoApp'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <PromoApp />
  </StrictMode>,
)
