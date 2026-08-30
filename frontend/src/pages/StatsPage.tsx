import { useEffect, useMemo, useState } from 'react'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, BarChart, Bar, PieChart, Pie, Cell,
  Legend, AreaChart, Area
} from 'recharts'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import api from '../api'
import {
  formatDuration, formatDurationShort, getAppIcon,
  getAppColor, CATEGORY_LABELS
} from '../utils'
import dayjs from 'dayjs'

interface HourStat { hour: number; total_duration: number }
interface TrendItem { date: string; total_duration: number; app_count: number }

export default function StatsPage() {
  const { user, partner } = useAuth()
  const { showToast } = useToast()
  const [viewTarget, setViewTarget] = useState<'partner' | 'me' | 'both'>('both')
  const [selectedDate, setSelectedDate] = useState(dayjs().format('YYYY-MM-DD'))
  const [trendDays, setTrendDays] = useState(7)

  // 我的数据
  const [myHourStats, setMyHourStats] = useState<HourStat[]>([])
  const [myAppStats, setMyAppStats] = useState<any[]>([])
  const [myTotal, setMyTotal] = useState(0)
  const [myTrend, setMyTrend] = useState<TrendItem[]>([])

  // 伴侣数据
  const [partnerHourStats, setPartnerHourStats] = useState<HourStat[]>([])
  const [partnerAppStats, setPartnerAppStats] = useState<any[]>([])
  const [partnerTotal, setPartnerTotal] = useState(0)
  const [partnerTrend, setPartnerTrend] = useState<TrendItem[]>([])

  const [loading, setLoading] = useState(true)

  const targetId = viewTarget === 'me' ? user?.id : partner?.id

  // 加载数据
  const loadDayStats = async (userId: number | undefined, date: string, prefix: 'my' | 'partner') => {
    if (!userId) return
    try {
      const res: any = await api.get(`/app-usage/stats?userId=${userId}&date=${date}`)
      if (prefix === 'my') {
        setMyHourStats(res.byHour || [])
        setMyAppStats(res.byApp || [])
        setMyTotal(res.totalScreenTime || 0)
      } else {
        setPartnerHourStats(res.byHour || [])
        setPartnerAppStats(res.byApp || [])
        setPartnerTotal(res.totalScreenTime || 0)
      }
    } catch (e: any) {
      console.error(e)
    }
  }

  const loadTrend = async (userId: number | undefined, days: number, setter: any) => {
    if (!userId) return
    try {
      const res: any = await api.get(`/app-usage/trend?userId=${userId}&days=${days}`)
      setter(res)
    } catch (e) {}
  }

  const loadAll = () => {
    setLoading(true)
    Promise.all([
      loadDayStats(user?.id, selectedDate, 'my'),
      loadDayStats(partner?.id, selectedDate, 'partner'),
      loadTrend(user?.id, trendDays, setMyTrend),
      loadTrend(partner?.id, trendDays, setPartnerTrend),
    ]).then(() => setLoading(false))
  }

  useEffect(() => {
    loadAll()
  }, [selectedDate, trendDays, user?.id, partner?.id])

  // 填充0小时
  const fillHours = (arr: HourStat[]) => {
    const map = new Map(arr.map(h => [h.hour, h.total_duration]))
    return Array.from({ length: 24 }, (_, i) => ({
      hour: i,
      total_duration: map.get(i) || 0
    }))
  }

  // 合并趋势数据（对比）
  const combinedTrend = useMemo(() => {
    if (!myTrend.length && !partnerTrend.length) return []
    const allDates = new Set<string>()
    myTrend.forEach(t => allDates.add(t.date))
    partnerTrend.forEach(t => allDates.add(t.date))
    return Array.from(allDates).sort().map(date => {
      const m = myTrend.find(t => t.date === date)
      const p = partnerTrend.find(t => t.date === date)
      return {
        date: dayjs(date).format('MM-DD'),
        [user?.nickname || '我']: m ? Math.round(m.total_duration / 60) : 0, // 分钟
        [partner?.nickname || 'TA']: p ? Math.round(p.total_duration / 60) : 0,
      }
    })
  }, [myTrend, partnerTrend, user?.nickname, partner?.nickname])

  // 饼图数据
  const myPieData = useMemo(() => myAppStats.slice(0, 8).map(a => ({
    name: a.app_name,
    value: a.total_duration,
    pkg: a.package_name,
  })), [myAppStats])

  const partnerPieData = useMemo(() => partnerAppStats.slice(0, 8).map(a => ({
    name: a.app_name,
    value: a.total_duration,
    pkg: a.package_name,
  })), [partnerAppStats])

  const COLORS = ['#ff6b9d', '#667eea', '#48bb78', '#ed8936', '#f56565', '#38b2ac', '#d53f8c', '#718096']
  const pieColor = (name: string) => {
    const hash = name.split('').reduce((s, c) => s + c.charCodeAt(0), 0)
    return COLORS[hash % COLORS.length]
  }

  // 小时柱图颜色（热力感）
  const hourFillColor = (val: number, max: number) => {
    if (max === 0) return '#fce7f3'
    const ratio = val / max
    if (ratio > 0.8) return '#e75480'
    if (ratio > 0.5) return '#ff6b9d'
    if (ratio > 0.2) return '#f9a8d4'
    return '#fce7f3'
  }

  if (loading) {
    return <div className="loading"><div className="spinner"></div>加载统计数据...</div>
  }

  // 计算统计指标
  const myTopApp = myAppStats[0]
  const partnerTopApp = partnerAppStats[0]
  const myPeakHour = myHourStats.slice().sort((a, b) => b.total_duration - a.total_duration)[0]
  const partnerPeakHour = partnerHourStats.slice().sort((a, b) => b.total_duration - a.total_duration)[0]

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>📊 每日使用统计</h2>
          <div className="subtitle">屏幕时长、使用趋势、时段分析，全方位了解彼此的手机使用习惯</div>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          {partner && (
            <div className="tabs" style={{ width: 260 }}>
              <button className={`tab ${viewTarget === 'me' ? 'active' : ''}`} onClick={() => setViewTarget('me')}>
                {user?.avatar} 我
              </button>
              <button className={`tab ${viewTarget === 'partner' ? 'active' : ''}`} onClick={() => setViewTarget('partner')}>
                {partner?.avatar} {partner?.nickname}
              </button>
              <button className={`tab ${viewTarget === 'both' ? 'active' : ''}`} onClick={() => setViewTarget('both')}>
                💕 对比
              </button>
            </div>
          )}
        </div>
      </div>

      {/* 顶部概览卡片 */}
      <div className="stats-summary">
        <div className="card summary-card pink">
          <div className="label">🕐 {user?.nickname} 今日总屏幕时间</div>
          <div className="value">
            {formatDurationShort(myTotal)}
            <span className="value-small">/ {myTotal > 0 ? (myTotal / 3600).toFixed(1) + 'h' : '--'}</span>
          </div>
          <div className="icon">📱</div>
        </div>
        {partner && (
          <div className="card summary-card blue">
            <div className="label">🕐 {partner.nickname} 今日总屏幕时间</div>
            <div className="value">
              {formatDurationShort(partnerTotal)}
              <span className="value-small">/ {partnerTotal > 0 ? (partnerTotal / 3600).toFixed(1) + 'h' : '--'}</span>
            </div>
            <div className="icon">💕</div>
          </div>
        )}
        <div className="card summary-card green">
          <div className="label">🏆 最常用APP</div>
          <div className="value" style={{ fontSize: 20 }}>
            {myTopApp ? (
              <>
                <span style={{ marginRight: 6 }}>{getAppIcon(myTopApp.app_name, myTopApp.package_name)}</span>
                {myTopApp.app_name}
              </>
            ) : '—'}
          </div>
          <div style={{ fontSize: 12, color: '#718096', marginTop: 4 }}>
            {myTopApp ? formatDuration(myTopApp.total_duration) : ''}
          </div>
          <div className="icon">🏆</div>
        </div>
        <div className="card summary-card orange">
          <div className="label">⚡ 最活跃时段</div>
          <div className="value" style={{ fontSize: 20 }}>
            {myPeakHour ? `${String(myPeakHour.hour).padStart(2, '0')}:00` : '—'}
          </div>
          <div style={{ fontSize: 12, color: '#718096', marginTop: 4 }}>
            {myPeakHour ? `${formatDuration(myPeakHour.total_duration)}` : ''}
          </div>
          <div className="icon">⚡</div>
        </div>
      </div>

      {/* 筛选栏 */}
      <div className="card" style={{ marginBottom: 20, display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontWeight: 600, fontSize: 13 }}>📅 选择日期：</span>
          <input
            type="date"
            className="input"
            style={{ padding: '6px 12px', width: 160 }}
            value={selectedDate}
            onChange={e => setSelectedDate(e.target.value)}
          />
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontWeight: 600, fontSize: 13 }}>📈 趋势天数：</span>
          <div className="tabs" style={{ width: 200 }}>
            {[7, 14, 30].map(n => (
              <button
                key={n}
                className={`tab ${trendDays === n ? 'active' : ''}`}
                onClick={() => setTrendDays(n)}
              >{n}天</button>
            ))}
          </div>
        </div>
        <div style={{ flex: 1 }}></div>
        <button className="btn btn-outline btn-sm" onClick={loadAll}>🔄 刷新</button>
      </div>

      {/* ============ 对比模式：双列展示 ============ */}
      {viewTarget === 'both' && partner ? (
        <>
          {/* 24小时使用分布 */}
          <div className="stats-grid" style={{ marginBottom: 20 }}>
            <div className="card chart-card">
              <h3 className="section-title">⏰ {user?.nickname} · 24小时使用分布</h3>
              <HourBarsChart data={fillHours(myHourStats)} color="#ff6b9d" />
            </div>
            <div className="card chart-card">
              <h3 className="section-title">⏰ {partner?.nickname} · 24小时使用分布</h3>
              <HourBarsChart data={fillHours(partnerHourStats)} color="#667eea" />
            </div>
          </div>

          {/* 近N天趋势对比 */}
          <div className="card chart-card" style={{ marginBottom: 20 }}>
            <h3 className="section-title">📈 近{trendDays}天屏幕时间对比（分钟）</h3>
            <div style={{ height: 300, padding: '20px 0' }}>
              {combinedTrend.length === 0 ? (
                <div className="empty"><div className="icon">📊</div><div className="text">暂无趋势数据</div></div>
              ) : (
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={combinedTrend} margin={{ top: 10, right: 30, left: 10, bottom: 10 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#fce7f3" />
                    <XAxis dataKey="date" tick={{ fontSize: 12 }} stroke="#a0aec0" />
                    <YAxis tick={{ fontSize: 12 }} stroke="#a0aec0" label={{ value: '分钟', angle: -90, position: 'insideLeft', fontSize: 12 }} />
                    <Tooltip
                      contentStyle={{ borderRadius: 10, border: '1px solid #fce7f3' }}
                      formatter={(value: any, name: any) => [`${value} 分钟`, name]}
                    />
                    <Legend />
                    <Line
                      type="monotone"
                      dataKey={user?.nickname || '我'}
                      stroke="#ff6b9d"
                      strokeWidth={3}
                      dot={{ fill: '#ff6b9d', r: 4 }}
                      activeDot={{ r: 6 }}
                    />
                    <Line
                      type="monotone"
                      dataKey={partner?.nickname || 'TA'}
                      stroke="#667eea"
                      strokeWidth={3}
                      dot={{ fill: '#667eea', r: 4 }}
                      activeDot={{ r: 6 }}
                    />
                  </LineChart>
                </ResponsiveContainer>
              )}
            </div>
          </div>

          {/* APP使用饼图对比 */}
          <div className="stats-grid" style={{ marginBottom: 20 }}>
            <div className="card chart-card">
              <h3 className="section-title">🥧 {user?.nickname} · APP使用分布（Top 8）</h3>
              <AppPieChart data={myPieData} />
            </div>
            <div className="card chart-card">
              <h3 className="section-title">🥧 {partner?.nickname} · APP使用分布（Top 8）</h3>
              <AppPieChart data={partnerPieData} />
            </div>
          </div>

          {/* 应用排行榜对比 */}
          <div className="stats-grid">
            <div className="card">
              <h3 className="section-title">🏆 {user?.nickname} · 应用使用排行</h3>
              <AppRankingList stats={myAppStats} />
            </div>
            <div className="card">
              <h3 className="section-title">🏆 {partner?.nickname} · 应用使用排行</h3>
              <AppRankingList stats={partnerAppStats} />
            </div>
          </div>
        </>
      ) : (
        /* ============ 单人模式 ============ */
        <>
          {viewTarget === 'me' ? (
            <SinglePersonStats
              user={user}
              hourStats={fillHours(myHourStats)}
              appStats={myAppStats}
              total={myTotal}
              trend={myTrend}
              trendDays={trendDays}
              topApp={myTopApp}
              peakHour={myPeakHour}
            />
          ) : partner ? (
            <SinglePersonStats
              user={partner}
              hourStats={fillHours(partnerHourStats)}
              appStats={partnerAppStats}
              total={partnerTotal}
              trend={partnerTrend}
              trendDays={trendDays}
              topApp={partnerTopApp}
              peakHour={partnerPeakHour}
            />
          ) : (
            <div className="empty">
              <div className="icon">💔</div>
              <div className="text">暂无伴侣数据，请先完成情侣配对</div>
            </div>
          )}
        </>
      )}
    </div>
  )
}

// ==================== 子组件 ====================
// 单人完整统计
function SinglePersonStats({
  user, hourStats, appStats, total, trend, trendDays, topApp, peakHour
}: any) {
  const pieData = appStats.slice(0, 8).map((a: any) => ({
    name: a.app_name, value: a.total_duration, pkg: a.package_name
  }))
  const trendData = trend.map((t: any) => ({
    date: dayjs(t.date).format('MM-DD'),
    分钟: Math.round(t.total_duration / 60)
  }))
  const COLORS = ['#ff6b9d', '#667eea', '#48bb78', '#ed8936', '#f56565', '#38b2ac', '#d53f8c', '#718096']

  return (
    <>
      {/* 趋势图 */}
      <div className="card chart-card" style={{ marginBottom: 20 }}>
        <h3 className="section-title">📈 {user?.nickname} · 近{trendDays}天使用趋势</h3>
        <div style={{ height: 280, padding: '20px 0' }}>
          {trendData.length === 0 ? (
            <div className="empty"><div className="icon">📉</div><div className="text">暂无趋势数据</div></div>
          ) : (
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={trendData}>
                <defs>
                  <linearGradient id="colorTime" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#ff6b9d" stopOpacity={0.4} />
                    <stop offset="95%" stopColor="#ff6b9d" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#fce7f3" />
                <XAxis dataKey="date" stroke="#a0aec0" tick={{ fontSize: 12 }} />
                <YAxis stroke="#a0aec0" label={{ value: '分钟', angle: -90, position: 'insideLeft', fontSize: 12 }} />
                <Tooltip contentStyle={{ borderRadius: 10, border: '1px solid #fce7f3' }} formatter={(v: any) => [`${v} 分钟`, '使用时长']} />
                <Area type="monotone" dataKey="分钟" stroke="#ff6b9d" strokeWidth={3} fillOpacity={1} fill="url(#colorTime)" />
              </AreaChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>

      {/* 时段分布 + 饼图 */}
      <div className="stats-grid" style={{ marginBottom: 20 }}>
        <div className="card chart-card">
          <h3 className="section-title">⏰ {user?.nickname} · 24小时使用分布</h3>
          <HourBarsChart data={hourStats} />
        </div>
        <div className="card chart-card">
          <h3 className="section-title">🥧 APP使用分布（Top 8）</h3>
          <AppPieChart data={pieData} />
        </div>
      </div>

      {/* 完整排行榜 */}
      <div className="card">
        <h3 className="section-title">🏆 {user?.nickname} · 完整应用使用排行</h3>
        <AppRankingList stats={appStats} />
      </div>
    </>
  )
}

// 时段柱状图（自定义颜色热力感）
function HourBarsChart({ data, color = '#ff6b9d' }: { data: HourStat[]; color?: string }) {
  const max = Math.max(...data.map(h => h.total_duration), 1)
  return (
    <div style={{ padding: '10px 0' }}>
      <div className="hour-bars">
        {data.map(h => {
          const height = max > 0 ? Math.max(2, (h.total_duration / max) * 100) : 2
          return (
            <div
              key={h.hour}
              className="hour-bar"
              style={{
                height: `${height}%`,
                background: h.total_duration > 0 ? color : '#f3f4f6',
                opacity: h.total_duration > 0 ? 0.3 + 0.7 * (h.total_duration / max) : 1
              }}
            >
              <div className="tooltip">
                {String(h.hour).padStart(2, '0')}:00-{String(h.hour + 1).padStart(2, '0')}:00<br />
                {formatDuration(h.total_duration)}
              </div>
            </div>
          )
        })}
      </div>
      <div className="hour-labels">
        {data.filter((_, i) => i % 3 === 0).map(h => (
          <div key={h.hour} className="hour-label" style={{ flex: 3 }}>
            {String(h.hour).padStart(2, '0')}h
          </div>
        ))}
      </div>
    </div>
  )
}

// 饼图
function AppPieChart({ data }: { data: { name: string; value: number; pkg: string }[] }) {
  const COLORS = ['#ff6b9d', '#667eea', '#48bb78', '#ed8936', '#f56565', '#38b2ac', '#d53f8c', '#718096']
  if (data.length === 0) {
    return <div className="empty" style={{ height: 260 }}><div className="icon">📱</div><div className="text">暂无使用数据</div></div>
  }
  const total = data.reduce((s, d) => s + d.value, 0) || 1
  return (
    <div style={{ height: 300, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              cx="50%" cy="50%"
              innerRadius={45}
              outerRadius={85}
              paddingAngle={2}
              dataKey="value"
              labelLine={false}
            >
              {data.map((_, idx) => (
                <Cell key={idx} fill={COLORS[idx % COLORS.length]} />
              ))}
            </Pie>
            <Tooltip
              formatter={(value: any) => formatDuration(value as number)}
              contentStyle={{ borderRadius: 8, fontSize: 12 }}
            />
          </PieChart>
        </ResponsiveContainer>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 6, overflow: 'auto' }}>
        {data.map((d, idx) => (
          <div key={d.name} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11 }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: COLORS[idx % COLORS.length], flexShrink: 0 }}></span>
            <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{getAppIcon(d.name, d.pkg)} {d.name}</span>
            <span style={{ color: '#a0aec0' }}>{((d.value / total) * 100).toFixed(0)}%</span>
          </div>
        ))}
      </div>
    </div>
  )
}

// 排行榜列表
function AppRankingList({ stats }: { stats: any[] }) {
  if (!stats || stats.length === 0) {
    return <div className="empty"><div className="icon">📱</div><div className="text">暂无使用记录</div></div>
  }
  const max = stats[0]?.total_duration || 1
  return (
    <div className="app-list">
      {stats.map((app, idx) => (
        <div key={app.package_name} className="app-item">
          <div className="rank">{idx + 1}</div>
          <div className="app-icon-sm" style={{ background: getAppColor(app.app_name) }}>
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
              <div className="bar-fill" style={{ width: `${(app.total_duration / max) * 100}%` }}></div>
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}
