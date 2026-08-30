import { ReactNode, useState } from 'react'
import { NavLink, useNavigate, Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

interface Props { children: ReactNode }

export default function Layout({ children }: Props) {
  const { user, partner, logout } = useAuth()
  const navigate = useNavigate()
  const { showToast } = useToast()
  const [showPairModal, setShowPairModal] = useState(false)

  const handleLogout = () => {
    logout()
    showToast('success', '已退出登录')
    navigate('/login')
  }

  const navItems = [
    { to: '/', icon: '🗺️', label: '位置地图' },
    { to: '/apps', icon: '📱', label: '应用使用' },
    { to: '/stats', icon: '📊', label: '使用统计' },
  ]

  return (
    <div className="app-container">
      {/* 侧边栏 */}
      <aside className="sidebar">
        <div className="sidebar-brand">
          <span className="logo">💕</span>
          <h1>情侣空间</h1>
        </div>

        <nav className="sidebar-nav">
          {navItems.map(item => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
            >
              <span className="icon">{item.icon}</span>
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>

        <div className="sidebar-user">
          <div className="avatar">{user?.avatar || '👤'}</div>
          <div className="info">
            <div className="name">{user?.nickname}</div>
            <div className="status">🟢 在线</div>
          </div>
          <button className="logout-btn" onClick={handleLogout} title="退出登录">↩️</button>
        </div>
      </aside>

      {/* 主内容 */}
      <main className="main-content">
        {!partner && (
          <div className="card pair-card" style={{ marginBottom: 20 }}>
            <div className="emoji">💘</div>
            <h3>您还没有绑定情侣</h3>
            <p className="desc">快邀请TA加入，开启甜蜜的实时报备之旅吧！</p>
            <p className="desc" style={{ fontSize: 12 }}>
              💡 测试账号：小明(xiaoming/123456) 和 小红(xiaohong/123456) 已自动配对
            </p>
            <button className="btn btn-primary" onClick={() => setShowPairModal(true)}>
              💌 发起配对
            </button>
          </div>
        )}
        {children}
      </main>

      {/* 配对弹窗 (简化) */}
      {showPairModal && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, padding: 20
        }} onClick={() => setShowPairModal(false)}>
          <div className="card" style={{ width: 400, maxWidth: '100%' }} onClick={e => e.stopPropagation()}>
            <h3 style={{ marginBottom: 16 }}>💌 发起情侣配对</h3>
            <p style={{ color: '#718096', marginBottom: 16, fontSize: 13 }}>
              请输入对方的用户名，发送配对请求。
            </p>
            <Link to="/login" onClick={() => setShowPairModal(false)}>
              <button className="btn btn-primary btn-block">
                → 切换到对方账号登录查看效果
              </button>
            </Link>
            <button className="btn btn-outline btn-block" style={{ marginTop: 8 }} onClick={() => setShowPairModal(false)}>
              关闭
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
