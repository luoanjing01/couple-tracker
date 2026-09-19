// ============================================================================
// 包声明：声明本文件所属的包路径 com.coupletracker.android.location
// 包路径对应项目目录结构，便于 Android 系统组织代码与避免类名冲突。
// ============================================================================
package com.coupletracker.android.location

// ============================================================================
// 导入部分：引入本文件需要用到的 Android 系统类和项目内部类
// ============================================================================
import android.Manifest                                  // 权限常量（如 ACCESS_FINE_LOCATION 访问精确位置）
import android.annotation.SuppressLint                    // 用于抑制 Lint 警告（这里用来忽略"未检查权限"警告，因为我们已自行检查）
import android.content.Context                           // Android 上下文，用于访问系统服务（如定位服务）
import android.content.pm.PackageManager                  // 权限检查结果常量（PERMISSION_GRANTED 表示已授权）
import android.location.Location                         // 一个地理位置数据类，含经纬度、精度、速度、时间戳等
import android.location.LocationListener                 // 定位回调接口：当位置变化时系统会回调此接口的方法
import android.location.LocationManager                  // Android 原生定位服务管理器，核心系统服务之一
import android.os.Bundle                                 // Android 数据传递用的键值对容器（onStatusChanged 回调用到）
import android.os.Looper                                 // 消息循环器，用于指定回调线程（这里用主线程）
import androidx.core.content.ContextCompat                // AndroidX 兼容库，统一权限检查 API（兼容低版本系统）
import com.coupletracker.android.data.NetworkModule      // 项目内的网络模块（含 REST 服务和状态 LiveData）
import com.coupletracker.android.data.UserRepository     // 项目内的用户仓库（获取当前登录用户信息）
import kotlinx.coroutines.*                              // Kotlin 协程库，用于异步上报位置到云端（不阻塞主线程）

/**
 * GPS/网络定位追踪器：基于 Android 原生 LocationManager（不依赖GMS）
 *
 * 🔴 为什么不用 FusedLocationProvider？
 *    FusedLocationProvider 属于 Google Mobile Services (GMS)，
 *    国内 iQOO / VIVO / OPPO / 华为 / 小米 等手机默认不带 GMS 包，
 *    导致 requestLocationUpdates 注册成功但回调永不触发，lastLocation.await() 永远 null，
 *    最终 locations 表 0 条记录 → 地图永远"等待位置..."。
 *    改用原生 LocationManager 后，100% 国产机兼容。
 *
 * ✅ 定位策略：GPS_PROVIDER（高精度室外）+ NETWORK_PROVIDER（室内/WiFi/基站）双开，
 *    任一有新结果都回调，取"更新的/更准的"优先。
 * ✅ 默认每 8 秒上报一次（3 秒内位移<5m则跳过，省电省流量）
 * ✅ 启动时立刻读取所有 provider 的 lastKnownLocation 选出最新的 force 上报，
 *    保证地图立刻有位置，不等 provider 下一次扫描。
 */
// ============================================================================
// 类定义：LocationTracker —— GPS/网络定位追踪器
// ----------------------------------------------------------------------------
// 构造参数：
//   context: Android 上下文（Activity/Service 都能传入，用于访问系统服务）
//   scope   : 协程作用域，控制位置上报协程的生命周期（随 Service 销毁自动取消）
// ============================================================================
class LocationTracker(private val context: Context, private val scope: CoroutineScope) {

    // 系统定位服务管理器。by lazy 表示"首次访问时才初始化"，避免对象创建时立即占用资源
    // getSystemService 拿到的是系统单例，强转为 LocationManager 类型
    private val locMgr by lazy { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    // —— GPS + Network 双监听器 ——
    // 用 nullable 是因为：未启动定位时它们为 null；启动后才赋值实际监听器对象
    private var gpsListener: LocationListener? = null   // GPS 卫星定位监听器（高精度，室外好用）
    private var netListener: LocationListener? = null   // 网络定位监听器（基于 WiFi/基站，室内兜底）

    // 记录上一次上报的位置和时刻，用于节流（避免短时间内重复上报几乎相同的位置）
    private var lastLocation: Location? = null   // 最近一次成功上报的位置
    private var lastReportAt = 0L                // 最近一次上报的时间戳（毫秒），初始 0 表示从未上报
    @Volatile private var batteryPct: Int? = null   // 电量百分比缓存；@Volatile 保证多线程可见性（写线程和读线程可能不同）

    /** TrackerService 每10秒把电量缓存到这里，上报位置时顺便带上 */
    fun setBatteryCache(pct: Int) { batteryPct = pct }

    // ============================================================================
    // 权限检查：判断当前 App 是否已获得定位权限
    // 返回 true 表示至少有粗略或精确定位权限之一，可以开始定位
    // ============================================================================
    fun hasPermission(): Boolean {
        // 检查"精确定位"权限（GPS 级别，精度可达几米）
        val fine   = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        // 检查"粗略定位"权限（网络/WiFi 级别，精度几十到几百米）
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        // 两者只要有一个授权即可启动定位（精度不同但都能拿到位置）
        return fine || coarse
    }

    // ============================================================================
    // start：启动定位追踪的核心方法
    // ----------------------------------------------------------------------------
    // @SuppressLint("MissingPermission")：告诉编译器不要报"缺少权限"警告
    //     ——因为我们已在 hasPermission() 里自行检查过了
    // suspend：声明为挂起函数，可在协程中调用，避免阻塞主线程
    // 参数 intervalMs：位置更新的最小时间间隔（毫秒），默认 8000ms = 8 秒
    // ============================================================================
    @SuppressLint("MissingPermission")
    suspend fun start(intervalMs: Long = 8000L) {
        // —— 安全检查：如果没有定位权限，立刻退出并提示用户去授权 ——
        if (!hasPermission()) {
            // 通过 NetworkModule 的状态 LiveData 把提示文字推送到 UI 层显示
            NetworkModule.lastLocationReportStatus.value =
                "⚠️ 无定位权限：系统设置 → CoupleTracker → 权限 → 定位 → 选择【始终允许】"
            return
        }
        // —— 检查哪些定位提供者（provider）可用：GPS 卫星、网络 ——
        // 用 mutableListOf 收集可用的 provider 名字，后续根据它决定订阅哪个
        val providersEnabled = mutableListOf<String>()
        // runCatching 包裹：防止某些手机系统 API 抛出异常导致 App 崩溃
        //    （部分国产 ROM 在权限/服务异常时返回 null 或抛 SecurityException）
        runCatching {
            // 判断 GPS_PROVIDER（卫星定位）是否已开启（用户在系统设置里打开了 GPS 开关）
            if (locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER))     providersEnabled += LocationManager.GPS_PROVIDER
        }
        runCatching {
            // 判断 NETWORK_PROVIDER（基于 WiFi/基站定位）是否可用
            if (locMgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providersEnabled += LocationManager.NETWORK_PROVIDER
        }
        // 如果两种 provider 都不可用（用户没开 GPS 也没开网络定位），给出明确提示
        if (providersEnabled.isEmpty()) {
            NetworkModule.lastLocationReportStatus.value =
                "⚠️ 定位功能未开启：请打开手机「位置信息/GPS」开关（仅权限授权还不够）"
        }

        // —— 第一步：启动时立刻读取 lastKnownLocation（所有provider，取最新），force 上报 ——
        //    但如果超过 2 分钟就跳过（避免显示几小时前的旧位置）
        // 目的：让地图立刻有位置显示，不必等系统第一次扫描完成（首次扫描可能要十几秒）
        runCatching { pickBestLastKnown() }.getOrNull()?.let {
            // 计算缓存位置的"年龄"：当前时间戳 - 位置记录的时间戳
            val age = System.currentTimeMillis() - it.time
            // 120_000L = 120000 毫秒 = 2 分钟。下划线只是数字分隔符，便于阅读
            if (age < 120_000L) report(it, force = true)   // force=true 表示强制上报，跳过节流
        }

        // —— 第二步：订阅 GPS_PROVIDER 定期更新（高精度室外）——
        // 只在 GPS provider 可用时才订阅，避免无效注册浪费资源
        if (providersEnabled.contains(LocationManager.GPS_PROVIDER)) {
            // 创建一个匿名对象实现 LocationListener 接口的 4 个回调方法
            gpsListener = object : LocationListener {
                // 核心回调：系统每次拿到新位置时调用
                override fun onLocationChanged(loc: Location) { report(loc, force = false) }
                // 当用户关闭 GPS 时触发（这里留空，不做额外处理）
                override fun onProviderDisabled(provider: String) {}
                // 当用户打开 GPS 时触发（这里留空）
                override fun onProviderEnabled(provider: String)  {}
                // provider 状态变化（旧 API，Android 6.0 后基本不再回调，但接口要求必须实现）
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            runCatching {
                // 向系统注册位置更新请求
                locMgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,  // 指定使用 GPS 卫星定位
                    intervalMs,    // minTime: 最少间隔 ms（系统可能延后到这个时间才回调，省电）
                    0f,            // minDistance: 0 米（我们自行在 report() 里过滤）
                    gpsListener!!, // 监听器回调对象（!! 表示断言非空，因为我们刚赋值）
                    Looper.getMainLooper()   // 指定在主线程回调，避免线程安全问题
                )
            }
        }

        // —— 第三步：订阅 NETWORK_PROVIDER 定期更新（室内/WiFi/基站兜底）——
        // 室内通常收不到 GPS 卫星信号，此时网络定位是兜底方案
        if (providersEnabled.contains(LocationManager.NETWORK_PROVIDER)) {
            // 创建网络定位监听器（结构同上，不再赘述）
            netListener = object : LocationListener {
                override fun onLocationChanged(loc: Location) { report(loc, force = false) }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String)  {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            runCatching {
                locMgr.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,   // 指定使用网络定位
                    intervalMs,
                    0f,
                    netListener!!,
                    Looper.getMainLooper()
                )
            }
        }
    }

    // ============================================================================
    // stop：停止定位追踪，释放系统资源
    // ----------------------------------------------------------------------------
    // 重要：Service 销毁时必须调用，否则监听器不会被回收，造成电量泄露和内存泄漏
    // ============================================================================
    fun stop() {
        // gpsListener 不为空时调用 removeUpdates 注销监听器；runCatching 防止异常
        gpsListener?.let { runCatching { locMgr.removeUpdates(it) } }; gpsListener = null
        netListener?.let { runCatching { locMgr.removeUpdates(it) } }; netListener = null
    }

    // ========================================================================
    //  工具：从所有 provider 读取 lastKnownLocation，挑"最新且有经纬度"的
    // ========================================================================
    // 说明：lastKnownLocation 是系统缓存的"最近一次"位置，调用立刻返回，不需要等待扫描
    //       但可能很旧（几小时前的位置），所以 pickBestLastKnown 后还要在调用处检查时效
    @SuppressLint("MissingPermission")
    private fun pickBestLastKnown(): Location? {
        var best: Location? = null   // 候选最优位置，初始为 null
        // 从三种 provider 各尝试读取一次缓存位置，失败则返回 null（用 runCatching 包裹）
        // listOfNotNull 会自动过滤掉 null 元素，最终 candidates 是一个非空位置列表
        val candidates = listOfNotNull(
            runCatching { locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)     }.getOrNull(),   // GPS 缓存位置
            runCatching { locMgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),   // 网络缓存位置
            runCatching { locMgr.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()    // 被动位置（其他 App 触发的位置更新）
        )
        // 遍历所有候选位置，按 time（时间戳）挑选最新的
        for (l in candidates) {
            if (l == null) continue              // 空元素跳过（理论上 listOfNotNull 已过滤）
            if (best == null) best = l           // 第一个候选直接作为 best
            else if (l.time > best.time) best = l   // 后续的 time 更大（更新）就替换 best
        }
        return best   // 返回最新的缓存位置；如果三个 provider 都没有缓存，返回 null
    }

    // ========================================================================
    //  节流 + 上报云端
    // ========================================================================
    // report 是 LocationTracker 的"出口"方法：所有定位回调最终都汇聚到这里
    // 参数 loc  : 待上报的位置
    // 参数 force: true=强制上报（启动时初次显示用），false=普通回调（要走节流/精度过滤）
    private fun report(loc: Location, force: Boolean) {
        // 记录"当前时刻"，后续多处节流判断都要用
        val now = System.currentTimeMillis()

        // ✅ 精度过滤：防止基站/WiFi 定位偏差几百米导致"定位不对"
        //    accuracy > 200m 的直接丢弃（除非是启动时的 force 上报且无其他位置）
        // loc.hasAccuracy()：判断该位置是否包含精度信息（有些 provider 不提供精度）
        // 如果没有精度信息，用 999f（一个很大的值）当作"精度很差"处理
        val acc = if (loc.hasAccuracy()) loc.accuracy else 999f
        // 普通回调（非 force）且精度大于 200 米 → 丢弃，不浪费流量上报差位置
        if (!force && acc > 200f) {
            // 打印调试日志到 logcat，便于排查"为什么不上报"
            android.util.Log.d("CT-Tracker", "丢弃低精度定位: acc=${acc}m provider=${loc.provider}")
            return   // 直接返回，不继续上报
        }
        // 如果已有更准的位置（30秒内），新位置精度差很多则丢弃
        // 场景：刚才 GPS 给了 5m 精度的位置，现在网络给个 100m 的，没必要覆盖
        if (!force && lastLocation != null && lastLocation!!.hasAccuracy()) {
            val lastAcc = lastLocation!!.accuracy   // 上次位置的精度（米）
            // 三个条件同时满足才丢弃：新精度比上次差 2 倍以上 且 新精度大于 50m 且 距上次上报不到 30 秒
            if (acc > lastAcc * 2 && acc > 50f && (now - lastReportAt) < 30_000L) {
                android.util.Log.d("CT-Tracker", "丢弃退步定位: newAcc=${acc}m vs lastAcc=${lastAcc}m")
                return
            }
        }

        // —— 位移节流：短时间内几乎没动 → 省电跳过 ——
        if (!force && lastLocation != null) {
            val delta = now - lastReportAt                // 距上次上报的毫秒数
            val moved = loc.distanceTo(lastLocation!!)    // 计算两次位置之间的直线距离（米）
            if (delta < 2000 && moved < 3f) return   // 2秒内移动不足3米 → 省电跳过
        }
        // 通过所有过滤，更新"最近一次"记录，准备上报
        lastLocation = loc; lastReportAt = now
        // 判断是否在移动：speed>0.5 m/s（约 1.8 km/h，人正常步行的速度）视为移动中
        val isMoving = (loc.hasSpeed() && loc.speed > 0.5f)

        // —— 异步上报到云端：用协程在 IO 线程执行，避免阻塞主线程 ——
        // Dispatchers.IO：Kotlin 协程的 IO 调度器，专门用于磁盘/网络等阻塞 IO 操作
        scope.launch(Dispatchers.IO) {
            // 从本地仓库获取当前登录用户（如果未登录则 userId 为 null，直接返回不上报）
            val user = UserRepository.get().getUser()
            val userId = user?.id ?: return@launch   // return@launch 表示从协程中返回（结束协程）
            // ✅ couple_id 传 null：未配对用户也能写库（之前 FK 已删除）
            //   —— 数据库表结构已修改，couple_id 字段允许 null
            val resp = runCatching {
                // 调用后端 REST 接口上报位置
                NetworkModule.restService.reportLocation(
                    // 构造一条位置记录数据对象 LocationRow
                    com.coupletracker.android.data.LocationRow(
                        user_id       = userId,                                    // 用户 ID
                        couple_id     = null,                                      // 配对 ID（未配对传 null）
                        latitude      = loc.latitude,                             // 纬度
                        longitude     = loc.longitude,                            // 经度
                        accuracy      = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,  // 精度（米），无则 null
                        speed         = if (loc.hasSpeed())  loc.speed.toDouble()  else null,       // 速度（m/s），无则 null
                        battery_level = batteryPct,                                // 电量百分比（可能为 null）
                        is_moving     = isMoving                                   // 是否在移动
                    )
                )
            }
            val http = resp.getOrNull()   // 取出成功时的 Response 对象，失败时为 null
            // 根据 HTTP 响应结果，更新 UI 上的状态文字（LiveData 推送，UI 自动刷新）
            NetworkModule.lastLocationReportStatus.value =
                when {
                    // 情况1：抛异常（网络错误、JSON 解析错误等）
                    http == null -> "位置上报异常：${resp.exceptionOrNull()?.message?.take(40).orEmpty()}"
                    // 情况2：HTTP 状态码非 2xx（如 401 未登录、500 服务器错误）
                    !http.isSuccessful -> {
                        // 读取错误响应体前 60 字符，便于排查
                        val errBody = runCatching { http.errorBody()?.string()?.take(60) }.getOrNull().orEmpty()
                        "位置上报失败 HTTP ${http.code()}：$errBody"
                    }
                    // 情况3：成功，显示经纬度（保留 4 位小数）
                    else -> "位置上报成功 · ${String.format("%.4f",loc.latitude)},${String.format("%.4f",loc.longitude)}"
                }
        }
    }
}
