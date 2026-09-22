import { Avatar, Button, Group, Menu, Text } from '@mantine/core'
import type { ProductionUser } from '../industrial/lib/auth'

type AccountMenuProps = {
  user: ProductionUser | null
  onLogin: () => void
  onLogout: () => void
}

/** 教学场与工业场共用的右上角身份入口。JWT 仍只保存在当前浏览器会话。 */
export function AccountMenu({ user, onLogin, onLogout }: AccountMenuProps) {
  if (!user) {
    return <Button variant="subtle" size="sm" onClick={onLogin}>登录</Button>
  }

  return (
    <Menu width={260} position="bottom-end" shadow="lg" offset={10}>
      <Menu.Target>
        <Button variant="subtle" className="account-trigger">
          <Group gap="xs">
            <Avatar radius="xl" size="sm" color="teal">{user.username.slice(0, 1).toUpperCase()}</Avatar>
            <span>{user.username}</span>
          </Group>
        </Button>
      </Menu.Target>
      <Menu.Dropdown className="account-dropdown">
        <div className="account-summary">
          <Text className="account-summary-label">当前登录用户</Text>
          <Group gap="sm" mt={10} wrap="nowrap">
            <Avatar radius="xl" size="md" color="teal">{user.username.slice(0, 1).toUpperCase()}</Avatar>
            <div>
              <Text className="account-summary-name">{user.username}</Text>
              <Text className="account-summary-meta">{user.tenant}</Text>
            </div>
          </Group>
          <div className="account-role-list">
            {user.roles.map((role) => <span key={role}>{role}</span>)}
          </div>
        </div>
        <Menu.Divider />
        <Menu.Item color="red" className="account-logout" onClick={onLogout}>退出登录</Menu.Item>
      </Menu.Dropdown>
    </Menu>
  )
}
