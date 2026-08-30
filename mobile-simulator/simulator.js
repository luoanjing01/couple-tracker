/**
 * 📱 移动端模拟器
 * 模拟两台手机（小明 & 小红）实时上报位置和应用使用情况
 * 
 * 功能：
 * 1. 自动登录测试账号获取Token
 * 2. 模拟GPS位置变化（含移动轨迹、静止、移动切换）
 * 3. 模拟应用切换和使用时长
 */
const axios = require('axios');

const API_BASE = process.env.API_BASE || 'http://localhost:3001';

// 预设位置点（北京市区模拟路线）
// 小明路线：家(望京SOHO) -> 地铁 -> 公司(国贸CBD) -> 午饭(三里屯) -> 公司 -> 下班(朝阳公园跑步) -> 家
const XIAOMING_LOCATIONS = [
  { name: '望京SOHO(家)', lat: 39.9948, lng: 116.4749, isMoving: false, address: '北京市朝阳区望京SOHO' },
  { name: '望京地铁站', lat: 39.9928, lng: 116.4697, isMoving: true, address: '望京地铁站' },
  { name: '大望路地铁站', lat: 39.9077, lng: 116.4691, isMoving: true, address: '大望路地铁站' },
  { name: '国贸CBD(公司)', lat: 39.9087, lng: 116.4605, isMoving: false, address: '北京市朝阳区国贸CBD中心' },
  { name: '三里屯太古里', lat: 39.9366, lng: 116.4535, isMoving: true, address: '北京市朝阳区三里屯太古里' },
  { name: '国贸CBD(公司)', lat: 39.9087, lng: 116.4605, isMoving: false, address: '北京市朝阳区国贸CBD中心' },
  { name: '朝阳公园(跑步)', lat: 39.9388, lng: 116.4835, isMoving: true, address: '北京市朝阳公园' },
  { name: '望京SOHO(家)', lat: 39.9948, lng: 116.4749, isMoving: false, address: '北京市朝阳区望京SOHO' }
];

// 小红路线：家(五道口) -> 北京大学 -> 中关村(公司) -> 新中关购物中心 -> 圆明园 -> 家
const XIAOHONG_LOCATIONS = [
  { name: '五道口华清嘉园(家)', lat: 39.9928, lng: 116.3382, isMoving: false, address: '北京市海淀区五道口华清嘉园' },
  { name: '北京大学东门', lat: 39.9977, lng: 116.3130, isMoving: true, address: '北京大学东门地铁站' },
  { name: '中关村软件园(公司)', lat: 40.0325, lng: 116.2944, isMoving: false, address: '北京市海淀区中关村软件园' },
  { name: '新中关购物中心', lat: 39.9836, lng: 116.3166, isMoving: true, address: '北京市海淀区新中关购物中心' },
  { name: '圆明园遗址公园', lat: 40.0090, lng: 116.2986, isMoving: true, address: '北京市海淀区圆明园遗址公园' },
  { name: '五道口华清嘉园(家)', lat: 39.9928, lng: 116.3382, isMoving: false, address: '北京市海淀区五道口华清嘉园' }
];

// 常用APP列表（模拟应用切换）
const APPS = [
  { package: 'com.tencent.mm', name: '微信', category: 'social', minMin: 2, maxMin: 15 },
  { package: 'com.ss.android.ugc.aweme', name: '抖音', category: 'video', minMin: 5, maxMin: 30 },
  { package: 'com.sina.weibo', name: '微博', category: 'social', minMin: 3, maxMin: 12 },
  { package: 'com.taobao.taobao', name: '淘宝', category: 'shopping', minMin: 5, maxMin: 25 },
  { package: 'com.autonavi.minimap', name: '高德地图', category: 'navigation', minMin: 3, maxMin: 20 },
  { package: 'com.netease.cloudmusic', name: '网易云音乐', category: 'music', minMin: 10, maxMin: 45 },
  { package: 'tv.danmaku.bili', name: '哔哩哔哩', category: 'video', minMin: 8, maxMin: 40 },
  { package: 'com.tencent.tmgp.sgame', name: '王者荣耀', category: 'game', minMin: 10, maxMin: 35 },
  { package: 'com.eg.android.AlipayGphone', name: '支付宝', category: 'tools', minMin: 1, maxMin: 5 },
  { package: 'com.android.settings', name: '设置', category: 'system', minMin: 0.5, maxMin: 3 },
  { package: 'com.tencent.mobileqq', name: 'QQ', category: 'social', minMin: 2, maxMin: 10 },
  { package: 'com.meizu.flyme.flymeblog', name: '小红书', category: 'social', minMin: 5, maxMin: 30 },
  { package: 'com.dianping.v1', name: '美团', category: 'life', minMin: 2, maxMin: 15 },
  { package: 'com.android.mms', name: '短信', category: 'system', minMin: 0.5, maxMin: 3 },
  { package: 'com.android.contacts', name: '电话', category: 'system', minMin: 0.5, maxMin: 8 },
];

// APP类别图标（中文）
const CATEGORY_LABELS = {
  social: '💬 社交',
  video: '🎬 视频',
  music: '🎵 音乐',
  game: '🎮 游戏',
  shopping: '🛒 购物',
  navigation: '🧭 导航',
  tools: '🔧 工具',
  life: '🍔 生活',
  system: '⚙️ 系统',
  other: '📦 其他'
};

// 用户模拟器类
class PhoneSimulator {
  constructor(username, password, nickname, locationRoute) {
    this.username = username;
    this.password = password;
    this.nickname = nickname;
    this.locationRoute = locationRoute;
    this.token = null;
    this.userId = null;

    // 当前位置状态
    this.currentRouteIndex = Math.floor(Math.random() * locationRoute.length);
    this.baseLocation = locationRoute[this.currentRouteIndex];
    this.currentLat = this.baseLocation.lat;
    this.currentLng = this.baseLocation.lng;
    this.isMoving = this.baseLocation.isMoving;
    this.moveProgress = 0;

    // 当前APP状态
    this.currentApp = null;
    this.appEndTime = 0;
  }

  // 登录
  async login() {
    try {
      const res = await axios.post(`${API_BASE}/api/auth/login`, {
        username: this.username,
        password: this.password
      });
      this.token = res.data.token;
      this.userId = res.data.user.id;
      console.log(`✅ [${this.nickname}] 登录成功，用户ID: ${this.userId}`);
      return true;
    } catch (e) {
      console.error(`❌ [${this.nickname}] 登录失败:`, e.response?.data?.error || e.message);
      return false;
    }
  }

  // 上报位置
  async reportLocation() {
    if (!this.token) return;

    try {
      await axios.post(
        `${API_BASE}/api/location/report`,
        {
          latitude: this.currentLat,
          longitude: this.currentLng,
          address: this.baseLocation.address,
          accuracy: this.isMoving ? 10 + Math.random() * 20 : 5,
          isMoving: this.isMoving,
          speed: this.isMoving ? (3 + Math.random() * 8) : 0 // m/s
        },
        { headers: { Authorization: `Bearer ${this.token}` } }
      );

      const speedKmh = (this.isMoving ? (3 + Math.random() * 8) * 3.6 : 0).toFixed(1);
      const latStr = this.currentLat.toFixed(6);
      const lngStr = this.currentLng.toFixed(6);
      console.log(`📍 [${this.nickname}] 位置上报: ${this.baseLocation.name} (${latStr}, ${lngStr}) 速度:${speedKmh}km/h 移动:${this.isMoving ? '🏃' : '🧎'}`);
    } catch (e) {
      console.error(`❌ [${this.nickname}] 位置上报失败:`, e.message);
    }
  }

  // 模拟位置变化
  tickLocation() {
    // 如果在移动，沿路径向下一个点前进（插值）
    if (this.isMoving) {
      this.moveProgress += 0.05 + Math.random() * 0.05;
      if (this.moveProgress >= 1) {
        // 到达下一个点
        this.currentRouteIndex = (this.currentRouteIndex + 1) % this.locationRoute.length;
        this.baseLocation = this.locationRoute[this.currentRouteIndex];
        this.moveProgress = 0;
        this.isMoving = this.baseLocation.isMoving;
        this.currentLat = this.baseLocation.lat;
        this.currentLng = this.baseLocation.lng;
      } else {
        // 插值计算当前位置
        const nextIndex = (this.currentRouteIndex + 1) % this.locationRoute.length;
        const nextLoc = this.locationRoute[nextIndex];
        this.currentLat = this.baseLocation.lat + (nextLoc.lat - this.baseLocation.lat) * this.moveProgress;
        this.currentLng = this.baseLocation.lng + (nextLoc.lng - this.baseLocation.lng) * this.moveProgress;
        // 添加随机抖动模拟GPS
        this.currentLat += (Math.random() - 0.5) * 0.0003;
        this.currentLng += (Math.random() - 0.5) * 0.0003;
      }
    } else {
      // 静止时偶尔切换移动/静止状态，添加小抖动
      this.currentLat += (Math.random() - 0.5) * 0.00005;
      this.currentLng += (Math.random() - 0.5) * 0.00005;

      // 静止时有2%概率切换到下一个点（模拟开始移动）
      if (Math.random() < 0.02) {
        this.currentRouteIndex = (this.currentRouteIndex + 1) % this.locationRoute.length;
        this.baseLocation = this.locationRoute[this.currentRouteIndex];
        this.moveProgress = 0;
        this.isMoving = this.baseLocation.isMoving;
        this.currentLat = this.baseLocation.lat;
        this.currentLng = this.baseLocation.lng;
      }
    }
  }

  // 上报当前前台APP
  async reportForegroundApp() {
    if (!this.token) return;
    const now = Date.now();

    // 检查是否需要切换APP
    if (!this.currentApp || now > this.appEndTime) {
      // 随机选一个新APP
      const app = APPS[Math.floor(Math.random() * APPS.length)];
      this.currentApp = app;
      const durationMinutes = app.minMin + Math.random() * (app.maxMin - app.minMin);
      this.appEndTime = now + durationMinutes * 60 * 1000;

      try {
        await axios.post(
          `${API_BASE}/api/app-usage/foreground`,
          {
            packageName: app.package,
            appName: app.name,
            appCategory: app.category
          },
          { headers: { Authorization: `Bearer ${this.token}` } }
        );

        const remainMin = ((this.appEndTime - now) / 60000).toFixed(1);
        console.log(`📱 [${this.nickname}] 打开: ${CATEGORY_LABELS[app.category] || ''} ${app.name} (预计使用${remainMin}分钟)`);
      } catch (e) {
        console.error(`❌ [${this.nickname}] APP上报失败:`, e.message);
      }
    }
  }

  // 心跳
  async heartbeat() {
    if (!this.token || !this.currentApp) return;
    try {
      await axios.post(
        `${API_BASE}/api/app-usage/heartbeat`,
        {},
        { headers: { Authorization: `Bearer ${this.token}` } }
      );
    } catch (e) {
      // 忽略心跳错误
    }
  }

  // 启动模拟
  start() {
    console.log(`\n🚀 启动 [${this.nickname}] 的手机模拟器...`);

    // 位置上报（每5秒一次，模拟高频GPS）
    setInterval(() => {
      this.tickLocation();
      this.reportLocation();
    }, 5000);

    // APP状态检查（每3秒一次）
    setInterval(() => {
      this.reportForegroundApp();
    }, 3000);

    // 心跳（每分钟一次）
    setInterval(() => {
      this.heartbeat();
    }, 60000);
  }
}

// ============ 主函数 ============
async function main() {
  console.log(`
╔══════════════════════════════════════════════════════════╗
║   📱 情侣报备系统 - 移动端模拟器                            ║
╠══════════════════════════════════════════════════════════╣
║   模拟两台手机实时上报数据至后端服务                          ║
║   后端地址: ${API_BASE}
╚══════════════════════════════════════════════════════════╝
  `);

  const xiaoming = new PhoneSimulator('xiaoming', '123456', '小明 👨', XIAOMING_LOCATIONS);
  const xiaohong = new PhoneSimulator('xiaohong', '123456', '小红 👩', XIAOHONG_LOCATIONS);

  // 先登录
  console.log('\n⏳ 正在登录测试账号...');
  const ok1 = await xiaoming.login();
  const ok2 = await xiaohong.login();

  if (!ok1 || !ok2) {
    console.error('\n❌ 登录失败，请检查后端服务是否已启动');
    console.error('💡 提示: 请先在 backend 目录执行 npm install && npm start');
    process.exit(1);
  }

  // 先发一次初始位置和初始APP
  await xiaoming.reportLocation();
  await xiaohong.reportLocation();
  await xiaoming.reportForegroundApp();
  await xiaohong.reportForegroundApp();

  // 启动定时任务
  xiaoming.start();
  xiaohong.start();

  console.log('\n✅ 模拟启动完成！正在实时采集数据...');
  console.log('💡 现在可以打开前端网页查看实时效果');
  console.log('💡 前端地址: http://localhost:5173 (如果已启动)');
  console.log('\n----------------------------------------------------------\n');
}

main().catch(console.error);
