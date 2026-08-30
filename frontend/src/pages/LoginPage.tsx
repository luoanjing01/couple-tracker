import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import api from '../api'

export default function LoginPage() {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [form, setForm] = useState({
    username: 'xiaoming',
    password: '123456',
    nickname: '',
    confirm: '',
  })
  const [loading, setLoading] = useState(false)
  const { login, register } = useAuth()
  const navigate = useNavigate()
  const { showToast } = useToast()

  const onChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setForm(prev => ({ ...prev, [e.target.name]: e.target.value }))
  }

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLoading(true)
    try {
      if (mode === 'login') {
        await login(form.username, form.password)
        showToast('success', `欢迎回来，${form.username} ❤️`)
        navigate('/')
      } else {
        if (form.password !== form.confirm) {
          showToast('error', '两次输入的密码不一致')
          return
        }
        if (!form.nickname) {
          showToast('error', '请输入昵称')
          return
        }
        await register({
          username: form.username,
          password: form.password,
          nickname: form.nickname,
        })
        showToast('success', '注册成功！快邀请你的TA配对吧 💕')
        navigate('/')
      }
    } catch (err: any) {
      showToast('error', err.error || '操作失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="logo">
          <div className="logo-emoji">💑</div>
        </div>
        <h2>{mode === 'login' ? '欢迎回来' : '创建情侣账号'}</h2>
        <p className="desc">
          {mode === 'login'
            ? '实时掌握彼此的位置动态和手机使用情况'
            : '注册后即可绑定伴侣，开启甜蜜报备模式'
          }
        </p>

        {/* 快速登录按钮 */}
        <div style={{ display: 'flex', gap: 8, marginBottom: 20 }}>
          <button
            type="button"
            className="btn btn-outline btn-sm"
            style={{ flex: 1 }}
            onClick={() => setForm({ ...form, username: 'xiaoming', password: '123456' })}
          >
            👨 小明
          </button>
          <button
            type="button"
            className="btn btn-outline btn-sm"
            style={{ flex: 1 }}
            onClick={() => setForm({ ...form, username: 'xiaohong', password: '123456' })}
          >
            👩 小红
          </button>
        </div>

        <form className="auth-form" onSubmit={onSubmit}>
          <div className="input-group">
            <label>用户名</label>
            <input
              type="text"
              name="username"
              value={form.username}
              onChange={onChange}
              className="input"
              placeholder="请输入用户名"
              required
              minLength={3}
            />
          </div>

          {mode === 'register' && (
            <div className="input-group">
              <label>昵称</label>
              <input
                type="text"
                name="nickname"
                value={form.nickname}
                onChange={onChange}
                className="input"
                placeholder="TA怎么称呼你？"
                required
              />
            </div>
          )}

          <div className="input-group">
            <label>密码</label>
            <input
              type="password"
              name="password"
              value={form.password}
              onChange={onChange}
              className="input"
              placeholder="请输入密码"
              required
              minLength={6}
            />
          </div>

          {mode === 'register' && (
            <div className="input-group">
              <label>确认密码</label>
              <input
                type="password"
                name="confirm"
                value={form.confirm}
                onChange={onChange}
                className="input"
                placeholder="再次输入密码"
                required
                minLength={6}
              />
            </div>
          )}

          <button
            type="submit"
            className="btn btn-primary btn-block"
            disabled={loading}
            style={{ opacity: loading ? 0.7 : 1 }}
          >
            {loading ? (
              <><div className="spinner" style={{ width: 16, height: 16, borderWidth: 2 }}></div> 处理中...</>
            ) : (
              <>{mode === 'login' ? '💕 登录情侣空间' : '🎉 立即注册'}</>
            )}
          </button>
        </form>

        <div className="auth-switch">
          {mode === 'login' ? (
            <>还没有账号？<button onClick={() => setMode('register')}>立即注册</button></>
          ) : (
            <>已有账号？<button onClick={() => setMode('login')}>返回登录</button></>
          )}
        </div>

        <div style={{
          marginTop: 24, padding: 12, background: '#fef3c7',
          borderRadius: 8, fontSize: 12, color: '#92400e', textAlign: 'center'
        }}>
          💡 提示：启动「移动端模拟器」可看到实时数据变化
        </div>
      </div>
    </div>
  )
}
