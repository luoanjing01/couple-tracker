import { createContext, useContext, useState, useEffect, ReactNode, useCallback } from 'react'
import api from '../api'
import { io, Socket } from 'socket.io-client'

export interface User {
  id: number
  username: string
  nickname: string
  avatar: string
  gender: string
}

interface AuthContextType {
  user: User | null
  partner: User | null
  token: string | null
  socket: Socket | null
  login: (username: string, password: string) => Promise<void>
  register: (data: RegisterData) => Promise<void>
  logout: () => void
  refreshPartner: () => Promise<void>
  loading: boolean
}

interface RegisterData {
  username: string
  password: string
  nickname: string
  gender?: string
  avatar?: string
}

const AuthContext = createContext<AuthContextType | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [partner, setPartner] = useState<User | null>(null)
  const [token, setToken] = useState<string | null>(localStorage.getItem('token'))
  const [socket, setSocket] = useState<Socket | null>(null)
  const [loading, setLoading] = useState(true)

  // 初始化
  useEffect(() => {
    if (token) {
      api.get('/user/me')
        .then((res: any) => {
          setUser(res.user)
          setPartner(res.partner)
          // 连接WebSocket
          const s = io({ auth: { token } })
          s.on('connect', () => console.log('✅ WebSocket connected'))
          s.on('disconnect', () => console.log('❌ WebSocket disconnected'))
          setSocket(s)
        })
        .catch(() => {
          localStorage.removeItem('token')
          setToken(null)
        })
        .finally(() => setLoading(false))
    } else {
      setLoading(false)
    }
  }, [token])

  const login = useCallback(async (username: string, password: string) => {
    const res: any = await api.post('/auth/login', { username, password })
    localStorage.setItem('token', res.token)
    localStorage.setItem('user', JSON.stringify(res.user))
    setToken(res.token)
    setUser(res.user)

    // 连接WS
    const s = io({ auth: { token: res.token } })
    setSocket(s)

    // 获取伴侣信息
    const me: any = await api.get('/user/me')
    setPartner(me.partner)
  }, [])

  const register = useCallback(async (data: RegisterData) => {
    const res: any = await api.post('/auth/register', data)
    localStorage.setItem('token', res.token)
    setToken(res.token)
    setUser(res.user)
    const s = io({ auth: { token: res.token } })
    setSocket(s)
  }, [])

  const logout = useCallback(() => {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    setToken(null)
    setUser(null)
    setPartner(null)
    if (socket) socket.close()
    setSocket(null)
  }, [socket])

  const refreshPartner = useCallback(async () => {
    const me: any = await api.get('/user/me')
    setPartner(me.partner)
  }, [])

  return (
    <AuthContext.Provider value={{ user, partner, token, socket, login, register, logout, refreshPartner, loading }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
