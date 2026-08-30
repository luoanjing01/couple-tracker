import { useEffect, useState, useRef, useMemo } from 'react'
import { MapContainer, TileLayer, Marker, Popup, Polyline, useMap, Circle } from 'react-leaflet'
import L from 'leaflet'
import { useAuth, User } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import api from '../api'
import { formatDistance, formatDateTime, formatTime } from '../utils'
import dayjs from 'dayjs'

// ==================== 自定义地图标记 ====================
function createDivIcon(emoji: string, isPartner: boolean, pulse = false) {
  const color = isPartner ? '#667eea' : '#ff6b9d'
  return L.divIcon({
    className: 'custom-marker',
    html: `
      <div style="position: relative;">
        ${pulse ? `<div style="
          position: absolute; width: 48px; height: 48px; border-radius: 50%;
          background: ${color}; opacity: 0.3; top: -6px; left: -6px;
          animation: pulse 1.5s ease-out infinite;
        "></div>` : ''}
        <div style="
          width: 36px; height: 36px; border-radius: 50%;
          background: ${color}; border: 3px solid white;
          box-shadow: 0 4px 12px rgba(0,0,0,0.25);
          display: flex; align-items: center; justify-content: center;
          font-size: 18px; z-index: 2; position: relative;
        ">${emoji}</div>
        <div style="
          position: absolute; bottom: -4px; left: 50%; transform: translateX(-50%);
          width: 0; height: 0;
          border-left: 6px solid transparent; border-right: 6px solid transparent;
          border-top: 8px solid white; filter: drop-shadow(0 2px 2px rgba(0,0,0,0.2));
        "></div>
      </div>
      <style>
        @keyframes pulse {
          0% { transform: scale(1); opacity: 0.4; }
          100% { transform: scale(2); opacity: 0; }
        }
      </style>
    `,
    iconSize: [36, 36],
    iconAnchor: [18, 36],
    popupAnchor: [0, -32],
  })
}

// 地图自动居中组件
function AutoCenter({ myLat, myLng, partnerLat, partnerLng, trackPoints }: any) {
  const map = useMap()
  useEffect(() => {
    const points: [number, number][] = []
    if (myLat && myLng) points.push([myLat, myLng])
    if (partnerLat && partnerLng) points.push([partnerLat, partnerLng])
    if (trackPoints?.length > 1) {
      trackPoints.forEach((p: any) => points.push([p.latitude, p.longitude]))
    }
    if (points.length > 0) {
      if (points.length === 1) {
        map.setView(points[0], 14, { animate: true })
      } else {
        const bounds = L.latLngBounds(points)
        map.fitBounds(bounds, { padding: [60, 60], animate: true, maxZoom: 15 })
      }
    }
  }, [myLat, partnerLat, trackPoints])
  return null
}

// ==================== 主页面 ====================
interface LocationData {
  id: number
  user_id: number
  latitude: number
  longitude: number
  address: string
  accuracy: number
  is_moving: number
  speed: number
  timestamp: string
  nickname: string
  avatar: string
}

export default function MapPage() {
  const { user, partner, socket } = useAuth()
  const { showToast } = useToast()
  const [myLocation, setMyLocation] = useState<LocationData | null>(null)
  const [partnerLocation, setPartnerLocation] = useState<LocationData | null>(null)
  const [distance, setDistance] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  const [showTrack, setShowTrack] = useState(false)
  const [trackTarget, setTrackTarget] = useState<'me' | 'partner'>('partner')
  const [todayTracks, setTodayTracks] = useState<any[]>([])
  const [trackDetail, setTrackDetail] = useState<any>(null)
  const [lastUpdate, setLastUpdate] = useState<string>('')

  // 初始化加载
  useEffect(() => {
    loadLatestLocation()
  }, [partner?.id])

  // 实时接收位置更新
  useEffect(() => {
    if (!socket) return

    const handleLocationUpdate = (data: any) => {
      // 伴侣位置更新
      if (partner && data.userId === partner.id) {
        setPartnerLocation(prev => ({
          ...prev,
          ...data,
          user_id: data.userId,
          is_moving: data.isMoving ? 1 : 0,
          id: prev?.id || Date.now(),
          accuracy: 10,
        } as LocationData))
        setLastUpdate(dayjs().format('HH:mm:ss'))
      }
    }

    const handleSelfUpdate = (data: any) => {
      if (user && data.userId === user.id) {
        setMyLocation(prev => ({
          ...prev,
          ...data,
          user_id: data.userId,
          is_moving: data.isMoving ? 1 : 0,
          id: prev?.id || Date.now(),
          accuracy: 10,
        } as LocationData))
      }
    }

    socket.on('location:update', handleLocationUpdate)
    socket.on('location:self-update', handleSelfUpdate)
    return () => {
      socket.off('location:update', handleLocationUpdate)
      socket.off('location:self-update', handleSelfUpdate)
    }
  }, [socket, partner?.id, user?.id])

  // 距离计算轮询
  useEffect(() => {
    if (!myLocation || !partnerLocation) return
    const id = setInterval(loadLatestLocation, 10000) // 10秒同步一次完整状态
    return () => clearInterval(id)
  }, [myLocation?.latitude, partnerLocation?.latitude])

  const loadLatestLocation = async () => {
    try {
      const res: any = await api.get('/location/latest')
      setMyLocation(res.myLocation)
      setPartnerLocation(res.partnerLocation)
      setDistance(res.distance)
      setLastUpdate(dayjs().format('HH:mm:ss'))
      loadTracks()
    } catch (e: any) {
      // showToast('error', e.error || '加载位置失败')
    } finally {
      setLoading(false)
    }
  }

  const loadTracks = async () => {
    try {
      const targetId = trackTarget === 'me' ? user?.id : partner?.id
      const today = dayjs().format('YYYY-MM-DD')
      const res: any = await api.get(`/tracks/list?userId=${targetId}&date=${today}&limit=10`)
      setTodayTracks(res)
    } catch (e) {}
  }

  const handleViewTrack = async (trackId: number) => {
    try {
      const res: any = await api.get(`/tracks/${trackId}`)
      setTrackDetail(res)
      setShowTrack(true)
      showToast('info', `已加载轨迹，共${res.points.length}个点`)
    } catch (e: any) {
      showToast('error', e.error || '加载轨迹失败')
    }
  }

  const clearTrack = () => {
    setTrackDetail(null)
    setShowTrack(false)
  }

  const center: [number, number] = useMemo(() => {
    if (myLocation) return [myLocation.latitude, myLocation.longitude]
    if (partnerLocation) return [partnerLocation.latitude, partnerLocation.longitude]
    return [39.9042, 116.4074] // 默认北京
  }, [myLocation?.latitude, myLocation?.longitude, partnerLocation?.latitude, partnerLocation?.longitude])

  const trackLatlngs: [number, number][] = useMemo(() => {
    if (!trackDetail) return []
    return trackDetail.points.map((p: any) => [p.latitude, p.longitude])
  }, [trackDetail])

  if (loading) {
    return <div className="loading"><div className="spinner"></div>正在加载位置数据...</div>
  }

  return (
    <div>
      <div className="page-header">
        <div>
          <h2>🗺️ 实时位置地图</h2>
          <div className="subtitle">
            {partner
              ? `正在和 ${partner.nickname}${partner.avatar} 共享位置 · 最后更新 ${lastUpdate || '--'}`
              : '请先完成情侣配对后查看位置'}
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn-outline btn-sm" onClick={loadLatestLocation}>
            🔄 刷新位置
          </button>
        </div>
      </div>

      <div className="map-sidebar">
        {/* 左侧信息面板 */}
        <div style={{ order: 2 }} className="info-panel">
          {/* 距离卡片 */}
          {distance != null && (
            <div className="distance-card">
              <div className="label">💕 你们的距离</div>
              <div>
                <span className="value">{distance < 1000 ? distance.toFixed(0) : (distance / 1000).toFixed(2)}</span>
                <span className="unit">{distance < 1000 ? '米' : '公里'}</span>
              </div>
              <div className="heart">❤️</div>
              <div style={{ fontSize: 12, opacity: 0.85 }}>
                {distance < 500 ? '✨ 好近啊！是不是快见面了？' :
                 distance < 2000 ? '🚶 步行可达的距离' :
                 distance < 10000 ? '🚗 开车一会就到' :
                 distance < 50000 ? '🚇 地铁可以到达' :
                 '✈️ 虽然相隔很远，但心在一起 💕'}
              </div>
            </div>
          )}

          {/* 我的位置 */}
          <UserLocationCard
            title="📍 我的位置"
            user={user as User}
            location={myLocation}
            isPartner={false}
          />

          {/* 伴侣位置 */}
          {partner && (
            <UserLocationCard
              title={`💕 ${partner.nickname}的位置`}
              user={partner}
              location={partnerLocation}
              isPartner={true}
            />
          )}

          {/* 今日轨迹 */}
          <div className="card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <h3 className="section-title" style={{ marginBottom: 0 }}>
                🏃 {trackTarget === 'me' ? '我' : partner?.nickname + '\'s'} 今日轨迹
              </h3>
              <div className="tabs" style={{ width: 'auto', padding: 2 }}>
                <button
                  className={`tab ${trackTarget === 'me' ? 'active' : ''}`}
                  style={{ padding: '4px 10px', fontSize: 12 }}
                  onClick={() => { setTrackTarget('me'); setTimeout(loadTracks, 50) }}
                >我</button>
                <button
                  className={`tab ${trackTarget === 'partner' ? 'active' : ''}`}
                  style={{ padding: '4px 10px', fontSize: 12 }}
                  onClick={() => { setTrackTarget('partner'); setTimeout(loadTracks, 50) }}
                >{partner?.nickname || 'TA'}</button>
              </div>
            </div>

            {showTrack && trackDetail ? (
              <div>
                <button className="btn btn-outline btn-sm" onClick={clearTrack} style={{ marginBottom: 12 }}>
                  ← 返回轨迹列表
                </button>
                <div style={{ padding: 12, background: '#fff5f7', borderRadius: 10 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <div>
                      <div style={{ fontSize: 12, color: '#718096' }}>距离</div>
                      <div style={{ fontWeight: 800, fontSize: 18, color: '#ff6b9d' }}>
                        {formatDistance(trackDetail.track.distance)}
                      </div>
                    </div>
                    <div>
                      <div style={{ fontSize: 12, color: '#718096' }}>移动点数</div>
                      <div style={{ fontWeight: 800, fontSize: 18 }}>{trackDetail.points.length}</div>
                    </div>
                    <div>
                      <div style={{ fontSize: 12, color: '#718096' }}>时长</div>
                      <div style={{ fontWeight: 800, fontSize: 18 }}>
                        {trackDetail.track.end_time
                          ? dayjs(trackDetail.track.end_time).diff(dayjs(trackDetail.track.start_time), 'minute') + '分钟'
                          : '进行中'}
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            ) : (
              <>
                {todayTracks.length === 0 ? (
                  <div className="empty">
                    <div className="icon">🚶</div>
                    <div className="text">今天还没有移动记录</div>
                  </div>
                ) : (
                  <div className="track-list">
                    {todayTracks.map(t => (
                      <div
                        key={t.id}
                        className={`track-item ${trackDetail?.track?.id === t.id ? 'selected' : ''}`}
                        onClick={() => handleViewTrack(t.id)}
                      >
                        <div className="row">
                          <div className="time">
                            {formatTime(t.start_time)} → {t.end_time ? formatTime(t.end_time) : '进行中'}
                          </div>
                          <div className="distance">{formatDistance(t.distance)}</div>
                        </div>
                        <div className="points">
                          {t.total_points} 个位置点 · {formatDateTime(t.start_time)}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>
        </div>

        {/* 右侧地图 */}
        <div style={{ order: 1 }}>
          <div className="map-container">
            <MapContainer
              center={center}
              zoom={14}
              style={{ height: '100%', width: '100%' }}
              scrollWheelZoom
            >
              <TileLayer
                attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
                url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
                maxZoom={19}
              />

              <AutoCenter
                myLat={myLocation?.latitude}
                myLng={myLocation?.longitude}
                partnerLat={partnerLocation?.latitude}
                partnerLng={partnerLocation?.longitude}
                trackPoints={trackDetail?.points}
              />

              {/* 我的位置 */}
              {myLocation && (
                <>
                  <Circle
                    center={[myLocation.latitude, myLocation.longitude]}
                    radius={Math.max(30, myLocation.accuracy || 20)}
                    pathOptions={{ color: '#ff6b9d', fillColor: '#ff6b9d', fillOpacity: 0.15, weight: 1 }}
                  />
                  <Marker
                    position={[myLocation.latitude, myLocation.longitude]}
                    icon={createDivIcon(user?.avatar || '👤', false, !!myLocation.is_moving)}
                  >
                    <Popup>
                      <div style={{ textAlign: 'center', padding: 4 }}>
                        <div style={{ fontSize: 32 }}>{user?.avatar}</div>
                        <strong style={{ fontSize: 15 }}>{user?.nickname} (我)</strong>
                        <br />
                        <span className={`badge ${myLocation.is_moving ? 'badge-warning' : 'badge-success'}`} style={{ marginTop: 6 }}>
                          {myLocation.is_moving ? '🏃 移动中' : '🧎 静止'}
                        </span>
                        <div style={{ marginTop: 8, fontSize: 12, color: '#718096' }}>
                          {myLocation.address || '位置详情'}
                        </div>
                        <div style={{ marginTop: 4, fontSize: 11, color: '#a0aec0' }}>
                          更新于 {formatTime(myLocation.timestamp)}
                        </div>
                      </div>
                    </Popup>
                  </Marker>
                </>
              )}

              {/* 伴侣位置 */}
              {partnerLocation && partner && (
                <>
                  <Circle
                    center={[partnerLocation.latitude, partnerLocation.longitude]}
                    radius={Math.max(30, partnerLocation.accuracy || 20)}
                    pathOptions={{ color: '#667eea', fillColor: '#667eea', fillOpacity: 0.15, weight: 1 }}
                  />
                  <Marker
                    position={[partnerLocation.latitude, partnerLocation.longitude]}
                    icon={createDivIcon(partner.avatar || '👤', true, !!partnerLocation.is_moving)}
                  >
                    <Popup>
                      <div style={{ textAlign: 'center', padding: 4 }}>
                        <div style={{ fontSize: 32 }}>{partner.avatar}</div>
                        <strong style={{ fontSize: 15 }}>{partner.nickname}</strong>
                        <br />
                        <span className={`badge ${partnerLocation.is_moving ? 'badge-warning' : 'badge-success'}`} style={{ marginTop: 6 }}>
                          {partnerLocation.is_moving ? '🏃 移动中' : '🧎 静止'}
                        </span>
                        <div style={{ marginTop: 8, fontSize: 12, color: '#718096' }}>
                          {partnerLocation.address || '位置详情'}
                        </div>
                        <div style={{ marginTop: 4, fontSize: 11, color: '#a0aec0' }}>
                          更新于 {formatTime(partnerLocation.timestamp)}
                        </div>
                      </div>
                    </Popup>
                  </Marker>
                </>
              )}

              {/* 连线（如果双方都有位置） */}
              {myLocation && partnerLocation && (
                <Polyline
                  positions={[
                    [myLocation.latitude, myLocation.longitude],
                    [partnerLocation.latitude, partnerLocation.longitude]
                  ]}
                  pathOptions={{ color: '#ff6b9d', weight: 2, dashArray: '8, 8', opacity: 0.5 }}
                />
              )}

              {/* 轨迹线 */}
              {showTrack && trackLatlngs.length > 1 && (
                <Polyline
                  positions={trackLatlngs}
                  pathOptions={{ color: '#ff6b9d', weight: 5, opacity: 0.8 }}
                />
              )}
            </MapContainer>
          </div>

          {/* 小提示 */}
          <div style={{ marginTop: 12, padding: '10px 14px', background: 'white', borderRadius: 10, border: '1px solid #fce7f3', fontSize: 12, color: '#718096' }}>
            💡 <strong>使用提示：</strong>点击标记可查看详情；移动中标记会有脉冲动画；开启模拟器后位置会实时变化。
            {showTrack && ` 已加载轨迹线（粉色粗线）`}
          </div>
        </div>
      </div>
    </div>
  )
}

// ==================== 用户位置卡片组件 ====================
interface UserLocCardProps {
  title: string
  user: User
  location: LocationData | null
  isPartner: boolean
}
function UserLocationCard({ title, user, location, isPartner }: UserLocCardProps) {
  return (
    <div className="card">
      <div style={{ fontSize: 13, fontWeight: 700, marginBottom: 12, color: '#718096' }}>{title}</div>
      {!location ? (
        <div style={{ padding: 20, textAlign: 'center', color: '#a0aec0', fontSize: 13 }}>
          暂无位置数据
          <div style={{ fontSize: 11, marginTop: 4 }}>请确保手机端服务正常运行</div>
        </div>
      ) : (
        <div className="user-card" style={{ padding: 0, border: 'none' }}>
          <div className={`avatar ${isPartner ? 'partner' : ''}`}>{user?.avatar || '👤'}</div>
          <div className="info">
            <div className="name">
              {user?.nickname}
              {location.is_moving && <span className="moving-icon" style={{ marginLeft: 6 }}>🏃</span>}
            </div>
            <div className="address">📍 {location.address || `${location.latitude.toFixed(5)}, ${location.longitude.toFixed(5)}`}</div>
            <div className="meta">
              <span className={`badge ${location.is_moving ? 'badge-warning' : 'badge-success'}`}>
                {location.is_moving ? '移动中' : '静止'}
              </span>
              {location.speed > 0 && (
                <span className="badge badge-info">{(location.speed * 3.6).toFixed(1)}km/h</span>
              )}
              <span className="badge badge-primary">{formatTime(location.timestamp)}</span>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
