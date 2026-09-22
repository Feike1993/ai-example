import type { ProductionUser } from '../industrial/lib/auth'
import { AccountMenu } from './AccountMenu'

type SceneToolbarProps = {
  scene: 'learning' | 'industrial'
  user: ProductionUser | null
  onLogin: () => void
  onLogout: () => void
}

/** 教学场与工业场共用的轻量场景切换条。 */
export function SceneToolbar({ scene, user, onLogin, onLogout }: SceneToolbarProps) {
  return (
    <div className="scene-toolbar">
      <nav className="scene-switch" aria-label="场景切换">
        <a className={scene === 'learning' ? 'is-active' : ''} href="/index.html">
          <span className="scene-switch-dot" />
          教学场景
        </a>
        <a className={scene === 'industrial' ? 'is-active' : ''} href="/industrial.html">
          <span className="scene-switch-dot" />
          工业级场景
        </a>
      </nav>
      <div className="scene-toolbar-actions">
        <a className="scene-settings-link" href="/industrial.html?section=modelSettings">
          <span aria-hidden="true">⚙</span>
          模型与服务设置
        </a>
        <span className="scene-toolbar-divider" />
        <AccountMenu user={user} onLogin={onLogin} onLogout={onLogout} />
      </div>
    </div>
  )
}
