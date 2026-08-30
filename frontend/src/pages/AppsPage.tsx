import { useEffect, useState } from 'react'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import api from '../api'
import { formatDuration, CATEGORY_LABELS, getAppIcon, getAppColor, formatDate } from '../utils'
import dayjs from 'dayjs'

interface CurrentApp {
  packageName: string
  appName: string
  appCategory: string
  startTime: string
  currentDuration: number
}

interface AppStat {
  package_name: string
  app_name: string
  app_category: string
  total_duration: number
  session_count: number
}

export default function AppsPage() {
  const { user, partner, socket } = useAuth()
  const { showToast } = useToast()
  const [viewTarget, setViewTarget] = useState<'partner' | 'me'>('partner')
  const [currentApp, setCurrentApp] = useState<CurrentApp | null>(null)
  const [partnerCurrentApp, setPartnerCurrentApp] = useState<CurrentApp | null>(null)
  const [appStats, setAppStats] = useState<AppStat[]>([])
  const [totalScreenTime, setTotalScreenTime] = useState(0)
  const [selectedDate, setSelectedDate] = useState(dayjs().format('YYYY-MM-DD'))
  const [loading, setLoading] = useState(true)
  const timerRef = useRef<number | null>(null)

  // 加载当前APP + 今日统计
  const loadData = async () => {
    try {
      const targetId = viewTarget === 'me' ? user?.id : partner?.id
      if (!targetId) return

      // 当前前台APP
      const curRes: any = await api.get(`/app-usage/current?userId=${targetId}`)
      if (viewTarget === 'me') setCurrentApp(curRes.current)
      else setPartnerCurrentApp(curRes.current)

      // 今日使用统计
      const statsRes: any = await api.get(
        `/app-usage/stats?userId=${targetId}&date=${selectedDate}`
      )
      setAppStats(statsRes.byApp || [])
      setTotalScreenTime(statsRes.totalScreenTime || 0)
    } catch (e: any) {
      // showToast('error', e.error || '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadData()
  }, [viewTarget, selectedDate, partner?.id, user?.id])

  // 轮询（5秒更新一次）
  useEffect(() => {
    const id = window.setInterval(loadData, 5000)
    return () => window.clearInterval(id)
  }, [viewTarget, selectedDate, partner?.id])

  // 实时接收APP切换推送
  useEffect(() => {
    if (!socket) return
    const handler = (data: any) => {
      if (partner && data.userId === partner.id) {
        setPartnerCurrentApp({
          packageName: data.packageName,
          appName: data.appName,
          appCategory: data.appCategory,
          startTime: data.timestamp,
          currentDuration: 0
        })
        if (viewTarget === 'partner') {
          showToast('info', `💕 ${partner.nickname} 打开了 ${data.appName}`)
        }
      }
    }
    socket.on('app:foreground', handler)
    return () => socket.off('app:foreground', handler)
  }, [socket, partner?.id, viewTarget])

  // 倒计时更新当前APP使用时长
  useEffect(() => {
    if (timerRef.current) window.clearInterval(timerRef.current)
    timerRef.current = window.setInterval(() => {
      setCurrentApp(prev => prev ? {
        ...prev,
        currentDuration: Math.floor((Date.now() - new Date(prev.startTime).getTime()) / 1000)
      } : prev)
      setPartnerCurrentApp(prev => prev ? {
        ...prev,
        currentDuration: Math.floor((Date.now() - new Date(prev.startTime).getTime()) / 1000)
      } : prev)
    }, 1000)
    return () => { if (timerRef.current) window.clearInterval(timerRef.current) }
  }, [currentApp?.startTime, partnerCurrentApp?.startTime])

  const maxDuration = appStats.length > 0 ? appStats[0].total_duration : 1
  const displayApp = viewTarget === 'me' ? currentApp : partnerCurrentApp
  const targetUser = viewTarget === 'me' ? user : partner

  // 日期选择列表（近7天）
  const recentDates = Array.from({ length: 7 }, (_, i) => {
    const d = dayjs().subtract(i, 'day')
    return { value: d.format('YYYY-MM-DD'), label: i === 0 ? '今天' : i === 1 ? '昨天' : d.format('MM-DD ddd').replace('Mon','周一').replace('Tue','周二').replace('Wed','周三').replace('Thu','周四').replace('Fri','周五').replace('Sat','周六').replace('Sun','周日') }
  })

  if (loading) {
    return <div className="loading"><div className="spinner"></div>加载应用使用数据...</div>
  }

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>📱 应用使用情况</h2>
          <div className="subtitle">
            实时查看对方当前在玩什么，用了多久，以及今日的应用使用排行
          </div>
        </div>
        <div className="tabs" style={{ width: 220 }}>
          <button
            className={`tab ${viewTarget === 'me' ? 'active' : ''}`}
            onClick={() => setViewTarget('me')}
          >
            {user?.avatar || '👤'} 我
          </button>
          <button
            className={`tab ${viewTarget === 'partner' ? 'active' : ''}`}
            onClick={() => setViewTarget('partner')}
          >
            {partner?.avatar || '💕'} {partner?.nickname || 'TA'}
          </button>
        </div>
      </div>

      <div className="app-layout">
        {/* 左侧：当前APP + 日期选择 */}
        <div className="info-panel" style={{ gap: 16 }}>
          {/* 当前前台APP */}
          <div className="current-app-card">
            <div className="title">✨ {targetUser?.nickname} 正在使用的应用</div>
            {!displayApp ? (
              <div style={{ padding: 40, textAlign: 'center', opacity: 0.9 }}>
                <div style={{ fontSize: 48, marginBottom: 12 }}>💤</div>
                <div style={{ fontSize: 14 }}>TA现在没有在使用手机哦</div>
                <div style={{ fontSize: 12, marginTop: 6, opacity: 0.8 }}>可能在休息、学习...或者在想你💕</div>
              </div>
            ) : (
              <>
                <div className="header">
                  <div className="app-icon">{getAppIcon(displayApp.appName, displayApp.packageName)}</div>
                  <div className="app-info">
                    <h3>{displayApp.appName}</h3>
                    <div className="category">{CATEGORY_LABELS[displayApp.appCategory] || '📦 其他'}</div>
                  </div>
                </div>
                <div className="timer">
                  <div className="timer-label">⏱️ 本次已使用</div>
                  <div className="timer-value">
                    {formatDurationHMS(displayApp.currentDuration)}
                  </div>
                </div>
              </>
            )}
          </div>

          {/* 今日总结卡片 */}
          <div className="card">
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
              <h3 className="section-title" style={{ marginBottom: 0 }}>
                📅 {targetUser?.nickname}的屏幕时间
              </h3>
            </div>
            <div style={{ marginBottom: 16 }}>
              <select
                className="input"
                value={selectedDate}
                onChange={e => setSelectedDate(e.target.value)}
                style={{ padding: '8px 12px' }}
              >
                {recentDates.map(d => (
                  <option key={d.value} value={d.value}>{d.label} ({d.value})</option>
                ))}
              </select>
            </div>
            <div style={{
              padding: 20, textAlign: 'center',
              background: 'linear-gradient(135deg, #fef3c7, #fde68a)',
              borderRadius: 12
            }}>
              <div style={{ fontSize: 12, color: '#92400e', marginBottom: 6 }}>
                🕐 {formatDate(selectedDate)} 总屏幕时长
              </div>
              <div style={{ fontSize: 32, fontWeight: 800, color: '#78350f' }}>
                {formatDuration(totalScreenTime)}
              </div>
              <div style={{ fontSize: 12, color: '#92400e', marginTop: 8 }}>
                {appStats.length} 个应用 · {appStats.reduce((s, a) => s + a.session_count, 0)} 次启动
              </div>
            </div>
          </div>

          {/* 类别分布 */}
          <div className="card">
            <h3 className="section-title">🏷️ 类别分布</h3>
            <CategoryBreakdown stats={appStats} />
          </div>
        </div>

        {/* 右侧：应用排行榜 */}
        <div className="card" style={{ padding: 24 }}>
          <h3 className="section-title">
            🏆 {targetUser?.nickname} · {selectedDate === dayjs().format('YYYY-MM-DD') ? '今日' : selectedDate} 应用使用排行
          </h3>

          {appStats.length === 0 ? (
            <div className="empty">
              <div className="icon">📱</div>
              <div className="text">这一天还没有使用记录</div>
            </div>
          ) : (
            <div className="app-list">
              {appStats.map((app, idx) => (
                <div key={app.package_name} className="app-item">
                  <div className="rank">{idx + 1}</div>
                  <div
                    className="app-icon-sm"
                    style={{ background: getAppColor(app.app_name) }}
                  >
                    {getAppIcon(app.app_name, app.package_name)}
                  </div>
                  <div className="info">
                    <div className="name">{app.app_name}</div>
                    <div className="category">
                      {CATEGORY_LABELS[app.app_category] || '其他'} · {app.session_count}次启动
                    </div>
                  </div>
                  <div className="duration">
                    <div className="time">{formatDuration(app.total_duration)}</div>
                    <div className="bar">
                      <div
                        className="bar-fill"
                        style={{ width: `${(app.total_duration / maxDuration) * 100}%` }}
                      ></div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

// 秒数 -> HH:MM:SS
function formatDurationHMS(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  const pad = (n: number) => String(n).padStart(2, '0')
  if (h > 0) return `${pad(h)}:${pad(m)}:${pad(s)}`
  return `${pad(m)}:${pad(s)}`
}

// 类别分布组件
import { useRef } from 'react'
function CategoryBreakdown({ stats }: { stats: AppStat[] }) {
  const grouped: Record<string, number> = {}
  stats.forEach(a => {
    const cat = a.app_category || 'other'
    grouped[cat] = (grouped[cat] || 0) + a.total_duration
  })
  const entries = Object.entries(grouped).sort((a, b) => b[1] - a[1])
  const total = entries.reduce((s, [, v]) => s + v, 0) || 1

  if (entries.length === 0) {
    return <div className="empty" style={{ padding: '20px 0' }}>
      <div className="text">暂无分类数据</div>
    </div>
  }

  const colors: Record<string, string> = {
    social: '#ff6b9d',
    video: '#667eea',
    music: '#48bb78',
    game: '#f56565',
    shopping: '#ed8936',
    navigation: '#38b2ac',
    tools: '#718096',
    life: '#d53f8c',
    system: '#a0aec0',
    other: '#cbd5e0'
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div style={{ display: 'flex', height: 10, borderRadius: 5, overflow: 'hidden', background: '#f7fafc' }}>
        {entries.map(([cat, val]) => (
          <div
            key={cat}
            style={{
              width: `${(val / total) * 100}%`,
              background: colors[cat] || '#cbd5e0',
              minWidth: val / total > 0.01 ? undefined : 0
            }}
            title={`${CATEGORY_LABELS[cat] || cat} ${formatDuration(val)}`}
          />
        ))}
      </div>
      {entries.map(([cat, val]) => (
        <div key={cat} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}>
          <span style={{ width: 10, height: 10, borderRadius: 2, background: colors[cat] || '#cbd5e0', flexShrink: 0 }}></span>
          <span style={{ flex: 1 }}>{CATEGORY_LABELS[cat] || cat}</span>
          <span style={{ fontWeight: 700, color: '#2d3748' }}>{formatDuration(val)}</span>
          <span style={{ color: '#a0aec0', width: 40, textAlign: 'right' }}>
            {((val / total) * 100).toFixed(0)}%
          </span>
        </div>
      ))}
    </div>
  )
}
