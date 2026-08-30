/**
 * 情侣自动报备系统 - 后端主服务
 * 功能：用户认证、位置上报、应用使用统计、轨迹记录、实时同步
 */
const express = require('express');
const http = require('http');
const cors = require('cors');
const morgan = require('morgan');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const { Server } = require('socket.io');
const db = require('./database');

const app = express();
const server = http.createServer(app);

// 允许的 CORS origin —— 云端时从环境变量读 Vercel 前端域名
const allowedOrigins = (process.env.CORS_ORIGINS || '*').split(',').map(s => s.trim());

const io = new Server(server, {
  cors: {
    origin: allowedOrigins,
    methods: ['GET', 'POST'],
    credentials: true
  },
  // Render 等云平台需要跨子路径挂载（socket.io 默认在 /socket.io）
  path: process.env.SOCKET_PATH || '/socket.io'
});

// 配置
const PORT = process.env.PORT || 3001;
const JWT_SECRET = process.env.JWT_SECRET || 'couple-tracker-secret-key-2024';

// 中间件
app.use(cors({
  origin: (origin, cb) => {
    if (!origin || allowedOrigins.includes('*') || allowedOrigins.includes(origin)) {
      cb(null, true);
    } else {
      cb(null, true); // 宽松模式，生产可改为 cb(new Error('CORS blocked'))
    }
  },
  credentials: true
}));
app.use(express.json({ limit: '10mb' }));
app.use(morgan('dev'));

// 🔍 健康检查（Render 部署时每 10s 访问一次这个 URL，200 OK = 存活）
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
    version: '1.0.0',
    uptime: process.uptime(),
    env: process.env.RENDER ? 'render' : 'local'
  });
});

// ============= 启动时：给已有用户补 6 位配对码 =============
function genCoupleCode() {
  const chars = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  let s = '';
  for (let i = 0; i < 6; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}
try {
  const all = db.prepare('SELECT * FROM users').all();
  for (const u of all) {
    if (!u.couple_code) {
      let code = genCoupleCode();
      // 保证唯一
      while (db.prepare('SELECT id FROM users WHERE couple_code = ?').get(code)) code = genCoupleCode();
      db.prepare('UPDATE users SET couple_code = ? WHERE id = ?').run(code, u.id);
    }
  }
} catch (e) {
  console.warn('[init-couple-code]', e.message);
}

// ============= JWT 认证中间件 =============
function authenticateToken(req, res, next) {
  const authHeader = req.headers['authorization'];
  const token = authHeader && authHeader.startsWith('Bearer ') ? authHeader.split(' ')[1] : null;

  if (!token) return res.status(401).json({ error: '未提供认证令牌' });

  jwt.verify(token, JWT_SECRET, (err, user) => {
    if (err) return res.status(403).json({ error: '令牌无效或已过期' });
    req.user = user;
    next();
  });
}

// ============= 工具函数 =============
// 计算两点之间的距离（Haversine公式，单位：米）
function calculateDistance(lat1, lon1, lat2, lon2) {
  const R = 6371000; // 地球半径(米)
  const φ1 = lat1 * Math.PI / 180;
  const φ2 = lat2 * Math.PI / 180;
  const Δφ = (lat2 - lat1) * Math.PI / 180;
  const Δλ = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(Δφ / 2) ** 2 + Math.cos(φ1) * Math.cos(φ2) * Math.sin(Δλ / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// 获取情侣伴侣ID
function getPartnerId(userId) {
  const couple = db.prepare(`
    SELECT id, user1_id, user2_id, status FROM couples
    WHERE (user1_id = ? OR user2_id = ?) AND status = 'accepted'
  `).get(userId, userId);

  if (!couple) return null;
  return couple.user1_id === userId ? couple.user2_id : couple.user1_id;
}

// ============= 用户认证API =============
// 注册
app.post('/api/auth/register', (req, res) => {
  const { username, password, nickname, gender, avatar } = req.body;
  if (!username || !password || !nickname) {
    return res.status(400).json({ error: '用户名、密码和昵称为必填项' });
  }
  if (password.length < 6) {
    return res.status(400).json({ error: '密码至少6位' });
  }

  try {
    const hashedPwd = bcrypt.hashSync(password, 10);
    // 生成唯一6位配对码
    let coupleCode = genCoupleCode();
    while (db.prepare('SELECT id FROM users WHERE couple_code = ?').get(coupleCode)) {
      coupleCode = genCoupleCode();
    }
    const result = db.prepare(`
      INSERT INTO users (username, password, nickname, avatar, gender, couple_code)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(username, hashedPwd, nickname, avatar || '', gender || 'unknown', coupleCode);

    const token = jwt.sign({ userId: result.lastInsertRowid, username }, JWT_SECRET, { expiresIn: '30d' });
    res.json({
      token,
      user: {
        id: result.lastInsertRowid, username, nickname,
        avatar: avatar || '', gender: gender || 'unknown',
        coupleCode
      }
    });
  } catch (e) {
    if (e.message.includes('UNIQUE')) {
      res.status(400).json({ error: '用户名已存在' });
    } else {
      res.status(500).json({ error: e.message });
    }
  }
});

// 登录
app.post('/api/auth/login', (req, res) => {
  const { username, password } = req.body;
  if (!username || !password) {
    return res.status(400).json({ error: '用户名和密码必填' });
  }

  const user = db.prepare('SELECT * FROM users WHERE username = ?').get(username);
  if (!user) return res.status(401).json({ error: '用户名或密码错误' });
  if (!bcrypt.compareSync(password, user.password)) {
    return res.status(401).json({ error: '用户名或密码错误' });
  }

  // 确保有coupleCode
  let coupleCode = user.couple_code;
  if (!coupleCode) {
    coupleCode = genCoupleCode();
    while (db.prepare('SELECT id FROM users WHERE couple_code = ?').get(coupleCode)) coupleCode = genCoupleCode();
    db.prepare('UPDATE users SET couple_code = ? WHERE id = ?').run(coupleCode, user.id);
  }

  const token = jwt.sign({ userId: user.id, username: user.username }, JWT_SECRET, { expiresIn: '30d' });
  res.json({
    token,
    user: {
      id: user.id,
      username: user.username,
      nickname: user.nickname,
      avatar: user.avatar || '',
      gender: user.gender,
      coupleCode
    }
  });
});

// 获取当前用户信息及伴侣信息（扩展：附带coupleCode）
app.get('/api/user/me', authenticateToken, (req, res) => {
  const pickUser = (u) => u ? {
    id: u.id, username: u.username, nickname: u.nickname,
    avatar: u.avatar || '', gender: u.gender,
    coupleCode: u.couple_code || genCoupleCode(),
    createdAt: u.created_at
  } : null;

  let user = db.prepare('SELECT * FROM users WHERE id = ?').get(req.user.userId);
  if (user && !user.couple_code) {
    const c = genCoupleCode();
    db.prepare('UPDATE users SET couple_code = ? WHERE id = ?').run(c, user.id);
    user.couple_code = c;
  }
  const partnerId = getPartnerId(req.user.userId);
  let partner = null;
  if (partnerId) {
    partner = db.prepare('SELECT * FROM users WHERE id = ?').get(partnerId);
    if (partner && !partner.couple_code) {
      const c = genCoupleCode();
      db.prepare('UPDATE users SET couple_code = ? WHERE id = ?').run(c, partner.id);
      partner.couple_code = c;
    }
  }
  res.json({ user: pickUser(user), partner: pickUser(partner) });
});

// ============= 情侣配对API =============
// 发起配对请求（通过用户名查找）
app.post('/api/couples/request', authenticateToken, (req, res) => {
  const { partnerUsername } = req.body;
  if (!partnerUsername) return res.status(400).json({ error: '请输入伴侣用户名' });

  const partner = db.prepare('SELECT id FROM users WHERE username = ?').get(partnerUsername);
  if (!partner) return res.status(404).json({ error: '用户不存在' });
  if (partner.id === req.user.userId) return res.status(400).json({ error: '不能和自己配对' });

  // 检查是否已有配对
  const existing = db.prepare(`
    SELECT * FROM couples WHERE status = 'accepted'
    AND (user1_id = ? OR user2_id = ?)
  `).get(req.user.userId, req.user.userId);
  if (existing) return res.status(400).json({ error: '您已有配对关系' });

  // 检查是否已有待处理请求
  const pending = db.prepare(`
    SELECT * FROM couples WHERE status = 'pending'
    AND ((user1_id = ? AND user2_id = ?) OR (user1_id = ? AND user2_id = ?))
  `).get(req.user.userId, partner.id, partner.id, req.user.userId);

  if (pending) {
    // 如果对方已发来请求，直接接受
    if (pending.user1_id === partner.id) {
      db.prepare('UPDATE couples SET status = ?, paired_at = CURRENT_TIMESTAMP WHERE id = ?')
        .run('accepted', pending.id);
      // 通知对方
      const partnerSockets = getUserSockets(partner.id);
      partnerSockets.forEach(s => s.emit('couple:accepted', { by: req.user.userId }));
      return res.json({ message: '配对成功！', status: 'accepted' });
    }
    return res.status(400).json({ error: '已有待处理的配对请求' });
  }

  db.prepare(`INSERT INTO couples (user1_id, user2_id, status) VALUES (?, ?, 'pending')`)
    .run(req.user.userId, partner.id);

  // 通知对方有新请求
  const partnerSockets = getUserSockets(partner.id);
  partnerSockets.forEach(s => s.emit('couple:request', { from: req.user.userId }));

  res.json({ message: '配对请求已发送' });
});

// 接受/拒绝配对
app.post('/api/couples/respond', authenticateToken, (req, res) => {
  const { requestId, accept } = req.body;
  const couple = db.prepare('SELECT * FROM couples WHERE id = ? AND user2_id = ? AND status = ?')
    .get(requestId, req.user.userId, 'pending');

  if (!couple) return res.status(404).json({ error: '请求不存在' });

  const status = accept ? 'accepted' : 'rejected';
  db.prepare('UPDATE couples SET status = ?, paired_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END WHERE id = ?')
    .run(status, accept ? 1 : 0, requestId);

  if (accept) {
    const partnerSockets = getUserSockets(couple.user1_id);
    partnerSockets.forEach(s => s.emit('couple:accepted', { by: req.user.userId }));
  }

  res.json({ message: accept ? '配对成功！' : '已拒绝配对' });
});

// 获取配对列表
app.get('/api/couples/requests', authenticateToken, (req, res) => {
  const requests = db.prepare(`
    SELECT c.id, c.status, c.created_at,
           u.id as user1_id, u.nickname as user1_nickname, u.avatar as user1_avatar
    FROM couples c
    JOIN users u ON c.user1_id = u.id
    WHERE c.user2_id = ? AND c.status = 'pending'
  `).all(req.user.userId);
  res.json(requests);
});

// ===== 通过 6 位配对码直接互相绑定（Android端用）=====
app.post('/api/couples/pair-by-code', authenticateToken, (req, res) => {
  const { code } = req.body;
  if (!code) return res.status(400).json({ error: '请输入配对码' });
  const target = db.prepare('SELECT * FROM users WHERE UPPER(couple_code) = ?')
    .get(String(code).trim().toUpperCase());
  if (!target) return res.status(404).json({ error: '配对码无效，未找到该用户' });
  if (target.id === req.user.userId) return res.status(400).json({ error: '不能与自己配对' });

  // 检查双方是否已配对
  for (const uid of [req.user.userId, target.id]) {
    const existing = db.prepare(`
      SELECT * FROM couples WHERE status='accepted' AND (user1_id=? OR user2_id=?)
    `).get(uid, uid);
    if (existing) return res.status(400).json({ error: '已有已接受的配对关系，需先解除' });
  }
  // 检查有没有互相发过pending：若有则直接改为accepted
  let couple = db.prepare(`
    SELECT * FROM couples WHERE status='pending'
    AND ((user1_id=? AND user2_id=?) OR (user1_id=? AND user2_id=?))
  `).get(req.user.userId, target.id, target.id, req.user.userId);
  if (couple) {
    db.prepare('UPDATE couples SET status=?, paired_at=CURRENT_TIMESTAMP WHERE id=?')
      .run('accepted', couple.id);
  } else {
    const r = db.prepare(`
      INSERT INTO couples (user1_id, user2_id, status, paired_at, created_at)
      VALUES (?, ?, 'accepted', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
    `).run(req.user.userId, target.id);
    couple = { id: r.lastInsertRowid };
  }
  // 通知对方
  for (const uid of [target.id]) {
    getUserSockets(uid).forEach(s => s.emit('couple:accepted', { by: req.user.userId }));
  }
  res.json({ success: true, message: '配对成功 💕', status: 'accepted' });
});

// ============= 位置相关API =============
// 上报当前位置
app.post('/api/location/report', authenticateToken, (req, res) => {
  const { latitude, longitude, address, accuracy, isMoving, speed } = req.body;
  if (latitude == null || longitude == null) {
    return res.status(400).json({ error: '经纬度必填' });
  }

  db.prepare(`
    INSERT INTO locations (user_id, latitude, longitude, address, accuracy, is_moving, speed)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `).run(req.user.userId, latitude, longitude, address || '', accuracy || 0, isMoving ? 1 : 0, speed || 0);

  // 处理轨迹记录
  handleTrackRecording(req.user.userId, latitude, longitude, !!isMoving);

  // 实时推送给伴侣
  const partnerId = getPartnerId(req.user.userId);
  if (partnerId) {
    const userInfo = db.prepare('SELECT id, nickname, avatar FROM users WHERE id = ?').get(req.user.userId);
    const locationData = {
      userId: req.user.userId,
      nickname: userInfo.nickname,
      avatar: userInfo.avatar,
      latitude,
      longitude,
      address,
      isMoving: !!isMoving,
      speed,
      timestamp: new Date().toISOString()
    };
    const partnerSockets = getUserSockets(partnerId);
    partnerSockets.forEach(s => s.emit('location:update', locationData));
    // 推给自己其他设备
    const selfSockets = getUserSockets(req.user.userId);
    selfSockets.forEach(s => s.emit('location:self-update', locationData));
  }

  res.json({ success: true, distance: null });
});

// 获取用户最新位置（自己和伴侣）
app.get('/api/location/latest', authenticateToken, (req, res) => {
  const myLocation = db.prepare(`
    SELECT l.*, u.nickname, u.avatar
    FROM locations l JOIN users u ON l.user_id = u.id
    WHERE l.user_id = ?
    ORDER BY l.timestamp DESC LIMIT 1
  `).get(req.user.userId);

  const partnerId = getPartnerId(req.user.userId);
  let partnerLocation = null;
  let distance = null;

  if (partnerId) {
    partnerLocation = db.prepare(`
      SELECT l.*, u.nickname, u.avatar
      FROM locations l JOIN users u ON l.user_id = u.id
      WHERE l.user_id = ?
      ORDER BY l.timestamp DESC LIMIT 1
    `).get(partnerId);

    if (myLocation && partnerLocation) {
      distance = calculateDistance(
        myLocation.latitude, myLocation.longitude,
        partnerLocation.latitude, partnerLocation.longitude
      );
    }
  }

  res.json({ myLocation, partnerLocation, distance });
});

// 获取历史位置记录
app.get('/api/location/history', authenticateToken, (req, res) => {
  const { userId, limit = 100, startDate, endDate } = req.query;
  const targetUserId = userId || req.user.userId;

  let query = `
    SELECT * FROM locations WHERE user_id = ?
  `;
  const params = [targetUserId];

  if (startDate) { query += ' AND timestamp >= ?'; params.push(startDate); }
  if (endDate) { query += ' AND timestamp <= ?'; params.push(endDate); }

  query += ' ORDER BY timestamp DESC LIMIT ?';
  params.push(parseInt(limit));

  const records = db.prepare(query).all(...params);
  res.json(records.reverse());
});

// ============= 轨迹相关API =============
// 处理轨迹记录逻辑
const activeTracks = new Map(); // userId -> {trackId, lastLat, lastLng, totalDistance, pointCount}
function handleTrackRecording(userId, lat, lng, isMoving) {
  const active = activeTracks.get(userId);

  if (isMoving) {
    if (!active) {
      // 开始新轨迹
      const result = db.prepare(`
        INSERT INTO tracks (user_id, start_time, start_latitude, start_longitude, total_points)
        VALUES (?, CURRENT_TIMESTAMP, ?, ?, 1)
      `).run(userId, lat, lng);

      db.prepare(`INSERT INTO track_points (track_id, latitude, longitude) VALUES (?, ?, ?)`)
        .run(result.lastInsertRowid, lat, lng);

      activeTracks.set(userId, {
        trackId: result.lastInsertRowid,
        lastLat: lat,
        lastLng: lng,
        totalDistance: 0,
        pointCount: 1
      });
    } else {
      // 继续轨迹，添加轨迹点
      const distSegment = calculateDistance(active.lastLat, active.lastLng, lat, lng);
      // 移动超过5米才记录点（避免GPS漂移）
      if (distSegment >= 5) {
        const newDist = active.totalDistance + distSegment;
        const newCount = active.pointCount + 1;

        db.prepare(`
          UPDATE tracks SET end_time = CURRENT_TIMESTAMP, end_latitude = ?, end_longitude = ?,
          distance = ?, total_points = ? WHERE id = ?
        `).run(lat, lng, newDist, newCount, active.trackId);

        db.prepare(`INSERT INTO track_points (track_id, latitude, longitude) VALUES (?, ?, ?)`)
          .run(active.trackId, lat, lng);

        activeTracks.set(userId, {
          ...active,
          lastLat: lat, lastLng: lng,
          totalDistance: newDist,
          pointCount: newCount
        });
      }
    }
  } else if (active) {
    // 停止移动，结束轨迹
    db.prepare(`
      UPDATE tracks SET end_time = CURRENT_TIMESTAMP, end_latitude = ?, end_longitude = ?,
      distance = ?, total_points = ? WHERE id = ?
    `).run(lat, lng, active.totalDistance, active.pointCount, active.trackId);
    activeTracks.delete(userId);
  }
}

// 获取轨迹列表
app.get('/api/tracks/list', authenticateToken, (req, res) => {
  const { userId, limit = 20, date } = req.query;
  const targetUserId = userId || req.user.userId;

  let query = 'SELECT * FROM tracks WHERE user_id = ?';
  const params = [targetUserId];

  if (date) {
    query += ' AND DATE(start_time) = ?';
    params.push(date);
  }
  query += ' ORDER BY start_time DESC LIMIT ?';
  params.push(parseInt(limit));

  const tracks = db.prepare(query).all(...params);
  res.json(tracks);
});

// 获取单条轨迹详情（所有点）
app.get('/api/tracks/:id', authenticateToken, (req, res) => {
  const track = db.prepare('SELECT * FROM tracks WHERE id = ?').get(req.params.id);
  if (!track) return res.status(404).json({ error: '轨迹不存在' });
  if (track.user_id !== req.user.userId && track.user_id !== getPartnerId(req.user.userId)) {
    return res.status(403).json({ error: '无权查看此轨迹' });
  }

  const points = db.prepare('SELECT * FROM track_points WHERE track_id = ? ORDER BY timestamp ASC').all(track.id);
  res.json({ track, points });
});

// ============= 应用使用统计API =============
const currentForegroundApps = new Map(); // userId -> {packageName, appName, startTime}

// 上报应用使用（切换前台应用时调用）
app.post('/api/app-usage/foreground', authenticateToken, (req, res) => {
  const { packageName, appName, appCategory } = req.body;
  if (!packageName || !appName) return res.status(400).json({ error: '包名和应用名必填' });

  const userId = req.user.userId;
  const previous = currentForegroundApps.get(userId);
  const now = new Date();

  // 结束上一个应用
  if (previous && previous.packageName !== packageName) {
    const duration = Math.floor((now - new Date(previous.startTime)) / 1000);
    if (duration > 0) {
      db.prepare(`
        INSERT INTO app_usage (user_id, package_name, app_name, app_category, start_time, end_time, duration_seconds)
        VALUES (?, ?, ?, ?, ?, ?, ?)
      `).run(userId, previous.packageName, previous.appName, previous.appCategory || 'other',
             previous.startTime.toISOString(), now.toISOString(), duration);

      // 更新每日统计
      updateDailyStats(userId, duration);
    }
  }

  // 开始新应用
  currentForegroundApps.set(userId, {
    packageName, appName,
    appCategory: appCategory || 'other',
    startTime: now
  });

  // 实时推送给伴侣
  const partnerId = getPartnerId(userId);
  if (partnerId) {
    const userInfo = db.prepare('SELECT id, nickname, avatar FROM users WHERE id = ?').get(userId);
    const partnerSockets = getUserSockets(partnerId);
    partnerSockets.forEach(s => s.emit('app:foreground', {
      userId,
      nickname: userInfo.nickname,
      avatar: userInfo.avatar,
      packageName,
      appName,
      appCategory: appCategory || 'other',
      timestamp: now.toISOString()
    }));
  }

  res.json({ success: true });
});

// 心跳刷新当前应用时长（建议每分钟调用一次）
app.post('/api/app-usage/heartbeat', authenticateToken, (req, res) => {
  const userId = req.user.userId;
  const current = currentForegroundApps.get(userId);
  if (!current) return res.json({ current: null });

  const now = new Date();
  const currentDuration = Math.floor((now - new Date(current.startTime)) / 1000);

  res.json({
    current: { ...current, currentDuration },
    timestamp: now.toISOString()
  });
});

// 获取伴侣当前使用的应用
app.get('/api/app-usage/current', authenticateToken, (req, res) => {
  const { userId } = req.query;
  const targetUserId = userId || req.user.userId;
  const current = currentForegroundApps.get(parseInt(targetUserId));

  if (!current) {
    // 尝试从数据库查最新记录
    const latest = db.prepare(`
      SELECT * FROM app_usage WHERE user_id = ? ORDER BY end_time DESC LIMIT 1
    `).get(targetUserId);
    return res.json({ current: null, latest });
  }

  const now = new Date();
  const currentDuration = Math.floor((now - new Date(current.startTime)) / 1000);
  res.json({ current: { ...current, currentDuration } });
});

// 获取应用使用统计（按日期）
app.get('/api/app-usage/stats', authenticateToken, (req, res) => {
  const { userId, date, groupBy = 'app' } = req.query;
  const targetUserId = userId || req.user.userId;
  const targetDate = date || new Date().toISOString().split('T')[0];

  // 首先保存当前前台应用的已使用时长
  const current = currentForegroundApps.get(parseInt(targetUserId));
  let pendingDuration = 0;
  if (current) {
    pendingDuration = Math.floor((Date.now() - new Date(current.startTime).getTime()) / 1000);
  }

  // 按应用汇总
  const byApp = db.prepare(`
    SELECT 
      package_name, app_name, app_category,
      SUM(duration_seconds) as total_duration,
      COUNT(*) as session_count
    FROM app_usage 
    WHERE user_id = ? AND DATE(start_time) = ?
    GROUP BY package_name
    ORDER BY total_duration DESC
  `).all(targetUserId, targetDate);

  // 如果有当前应用，累加其时间
  if (current) {
    const existing = byApp.find(a => a.package_name === current.packageName);
    if (existing) {
      existing.total_duration += pendingDuration;
    } else {
      byApp.unshift({
        package_name: current.packageName,
        app_name: current.appName,
        app_category: current.appCategory,
        total_duration: pendingDuration,
        session_count: 1
      });
    }
  }

  // 按时段汇总（每小时）
  const byHour = db.prepare(`
    SELECT 
      CAST(strftime('%H', start_time) AS INTEGER) as hour,
      SUM(duration_seconds) as total_duration
    FROM app_usage 
    WHERE user_id = ? AND DATE(start_time) = ?
    GROUP BY strftime('%H', start_time)
    ORDER BY hour ASC
  `).all(targetUserId, targetDate);

  // 加入当前时段的未保存时长
  if (current && pendingDuration > 0) {
    const currentHour = new Date().getHours();
    const todayStr = new Date().toISOString().split('T')[0];
    if (targetDate === todayStr) {
      const hourEntry = byHour.find(h => h.hour === currentHour);
      if (hourEntry) hourEntry.total_duration += pendingDuration;
      else byHour.push({ hour: currentHour, total_duration: pendingDuration });
    }
  }

  // 总使用时长
  const totalScreenTime = byApp.reduce((sum, a) => sum + a.total_duration, 0);

  res.json({
    date: targetDate,
    totalScreenTime,
    byApp: byApp.sort((a, b) => b.total_duration - a.total_duration),
    byHour: byHour.sort((a, b) => a.hour - b.hour),
    currentForeground: current ? { ...current, currentDuration: pendingDuration } : null
  });
});

// 获取日期范围的使用趋势
app.get('/api/app-usage/trend', authenticateToken, (req, res) => {
  const { userId, days = 7 } = req.query;
  const targetUserId = userId || req.user.userId;
  const numDays = Math.min(parseInt(days), 30);

  const rows = db.prepare(`
    SELECT 
      DATE(start_time) as date,
      SUM(duration_seconds) as total_duration,
      COUNT(DISTINCT package_name) as app_count
    FROM app_usage 
    WHERE user_id = ? AND start_time >= DATE('now', ?)
    GROUP BY DATE(start_time)
    ORDER BY date ASC
  `).all(targetUserId, `-${numDays - 1} days`);

  // 补充缺失的日期
  const result = [];
  for (let i = numDays - 1; i >= 0; i--) {
    const d = new Date();
    d.setDate(d.getDate() - i);
    const dateStr = d.toISOString().split('T')[0];
    const existing = rows.find(r => r.date === dateStr);
    result.push({
      date: dateStr,
      total_duration: existing ? existing.total_duration : 0,
      app_count: existing ? existing.app_count : 0
    });
  }

  res.json(result);
});

// ============= 辅助函数 - 每日统计更新 =============
function updateDailyStats(userId, durationToAdd) {
  const today = new Date().toISOString().split('T')[0];
  db.prepare(`
    INSERT INTO daily_stats (user_id, date, total_screen_time, app_launch_count)
    VALUES (?, ?, ?, 1)
    ON CONFLICT(user_id, date) DO UPDATE SET
      total_screen_time = total_screen_time + excluded.total_screen_time,
      app_launch_count = app_launch_count + 1
  `).run(userId, today, durationToAdd);
}

// ============= Socket.IO 实时通信 =============
const userSockets = new Map(); // userId -> Set<socketId>
function getUserSockets(userId) {
  const ids = userSockets.get(userId) || new Set();
  const result = [];
  ids.forEach(id => {
    const s = io.sockets.sockets.get(id);
    if (s) result.push(s);
  });
  return result;
}

io.on('connection', (socket) => {
  const token = socket.handshake.auth.token;
  if (!token) return socket.disconnect(true);

  try {
    const decoded = jwt.verify(token, JWT_SECRET);
    const userId = decoded.userId;

    // 注册用户socket
    if (!userSockets.has(userId)) userSockets.set(userId, new Set());
    userSockets.get(userId).add(socket.id);
    socket.userId = userId;

    socket.on('disconnect', () => {
      const sockets = userSockets.get(userId);
      if (sockets) {
        sockets.delete(socket.id);
        if (sockets.size === 0) userSockets.delete(userId);
      }
    });

    // 发送在线状态给伴侣
    const partnerId = getPartnerId(userId);
    if (partnerId) {
      const partnerSockets = getUserSockets(partnerId);
      partnerSockets.forEach(s => s.emit('presence:online', { userId, online: true }));
    }
  } catch (e) {
    socket.disconnect(true);
  }
});

// ============= 健康检查 =============
app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString(), version: '1.0.0' });
});

// 启动服务
server.listen(PORT, () => {
  console.log(`
╔══════════════════════════════════════════════════════════╗
║   💕 情侣自动报备系统后端服务启动成功                      ║
╠══════════════════════════════════════════════════════════╣
║   📡 API 地址:    http://localhost:${PORT}                     ║
║   🔌 WebSocket:   ws://localhost:${PORT}                       ║
║   🏥 健康检查:    http://localhost:${PORT}/api/health          ║
╠══════════════════════════════════════════════════════════╣
║   👤 测试账号:                                            ║
║      小明: xiaoming / 123456                              ║
║      小红: xiaohong / 123456                              ║
║      (两人已自动配对为情侣 ❤️)                             ║
╚══════════════════════════════════════════════════════════╝
  `);
});
