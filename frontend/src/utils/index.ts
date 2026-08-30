/**
 * 工具函数
 */
import dayjs from 'dayjs'

// 格式化时长（秒 -> 中文描述）
export function formatDuration(seconds: number): string {
  if (seconds < 60) return `${Math.floor(seconds)}秒`
  if (seconds < 3600) {
    const m = Math.floor(seconds / 60)
    const s = Math.floor(seconds % 60)
    return s > 0 ? `${m}分${s}秒` : `${m}分钟`
  }
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return m > 0 ? `${h}小时${m}分` : `${h}小时`
}

// 格式化时长（精简版，用于图表轴）
export function formatDurationShort(seconds: number): string {
  if (seconds < 3600) return `${Math.floor(seconds / 60)}分`
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return m > 0 ? `${h}h${m}m` : `${h}h`
}

// 格式化距离（米 -> 中文）
export function formatDistance(meters: number): string {
  if (meters == null) return '--'
  if (meters < 1000) return `${meters.toFixed(0)}米`
  return `${(meters / 1000).toFixed(2)}公里`
}

// 格式化时间
export function formatTime(date: string | Date): string {
  return dayjs(date).format('HH:mm:ss')
}

export function formatDateTime(date: string | Date): string {
  return dayjs(date).format('MM-DD HH:mm')
}

export function formatDate(date: string | Date): string {
  return dayjs(date).format('YYYY-MM-DD')
}

// 相对时间（刚刚，X分钟前）
export function timeAgo(date: string | Date): string {
  const diff = dayjs().diff(dayjs(date), 'second')
  if (diff < 60) return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)}分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)}小时前`
  return `${Math.floor(diff / 86400)}天前`
}

// 应用类别标签
export const CATEGORY_LABELS: Record<string, string> = {
  social: '💬 社交',
  video: '🎬 视频',
  music: '🎵 音乐',
  game: '🎮 游戏',
  shopping: '🛒 购物',
  navigation: '🧭 导航',
  tools: '🔧 工具',
  life: '🍔 生活',
  system: '⚙️ 系统',
  other: '📦 其他',
}

// 获取APP图标（emoji映射）
export function getAppIcon(appName: string, packageName?: string): string {
  const name = appName.toLowerCase()
  const pkg = (packageName || '').toLowerCase()
  if (name.includes('微信') || pkg.includes('mm')) return '💬'
  if (name.includes('抖音') || pkg.includes('aweme')) return '🎵'
  if (name.includes('微博') || pkg.includes('weibo')) return '📢'
  if (name.includes('淘宝') || pkg.includes('taobao')) return '🛍️'
  if (name.includes('地图') || name.includes('导航') || pkg.includes('autonavi') || pkg.includes('baidu.map')) return '🧭'
  if (name.includes('音乐') || name.includes('网易云') || pkg.includes('cloudmusic')) return '🎧'
  if (name.includes('b站') || name.includes('哔哩') || pkg.includes('bili')) return '📺'
  if (name.includes('王者') || name.includes('荣耀') || pkg.includes('sgame')) return '⚔️'
  if (name.includes('支付宝') || pkg.includes('alipay')) return '💰'
  if (name.includes('设置') || pkg.includes('settings')) return '⚙️'
  if (name.includes('qq') || pkg.includes('mobileqq')) return '🐧'
  if (name.includes('小红书') || pkg.includes('flymeblog') || pkg.includes('xingin')) return '📒'
  if (name.includes('美团') || pkg.includes('dianping')) return '🍔'
  if (name.includes('短信') || pkg.includes('mms')) return '✉️'
  if (name.includes('电话') || pkg.includes('contacts') || pkg.includes('dialer')) return '📞'
  if (name.includes('京东') || pkg.includes('jd')) return '📦'
  if (name.includes('知乎') || pkg.includes('zhihu')) return '💡'
  return '📱'
}

// 获取背景渐变色（根据APP名生成稳定色）
export function getAppColor(appName: string): string {
  const gradients = [
    'linear-gradient(135deg, #fef3c7, #fde68a)',
    'linear-gradient(135deg, #dbeafe, #bfdbfe)',
    'linear-gradient(135deg, #dcfce7, #bbf7d0)',
    'linear-gradient(135deg, #fce7f3, #fbcfe8)',
    'linear-gradient(135deg, #f3e8ff, #e9d5ff)',
    'linear-gradient(135deg, #fed7aa, #fdba74)',
    'linear-gradient(135deg, #cffafe, #a5f3fc)',
    'linear-gradient(135deg, #fee2e2, #fecaca)',
    'linear-gradient(135deg, #e0e7ff, #c7d2fe)',
    'linear-gradient(135deg, #ccfbf1, #99f6e4)',
  ]
  let hash = 0
  for (let i = 0; i < appName.length; i++) {
    hash = appName.charCodeAt(i) + ((hash << 5) - hash)
  }
  return gradients[Math.abs(hash) % gradients.length]
}
