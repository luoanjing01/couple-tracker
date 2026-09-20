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
import java.util.concurrent.CopyOnWriteArrayList          // 线程安全列表：批量缓存位置点（回调线程与上报协程可能不同线程）

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
 * ✅ 定位策略：GPS_PROVIDER（高精度室外）+ NETWORK_PROVIDER（室内/WiFi/基站）
 *    + PASSIVE_PROVIDER（被动定位，零耗电复用其他App的定位结果），取"更新的/更准的"优先。
 *
 * ✅ 轻量化省电方案（对标 Life360 等业界成熟做法）：
 *    1.【动态调频】移动中按用户设置的间隔采集；检测到静止（连续多次位移<30m）
 *      自动把系统定位间隔拉到 5 分钟；恢复移动（位移>50m 或速度>0.5m/s）立刻切回高频。
 *    2.【被动定位】注册 PASSIVE_PROVIDER，白嫖微信/地图等已算好的位置，自己不花电。
 *    3.【批量上报】位置点先缓存在本地，攒满 5 条或距上次上传超 3 分钟才一次性
 *      POST 数组（PostgREST 批量插入），网络唤醒次数降到原来的 1/5 以下；
 *      失败自动保留缓存下次重试，最多缓存 50 条防爆内存。
 * ✅ 精度过滤：accuracy>200m 丢弃；30 秒内已有更准位置则丢弃退步结果
 * ✅ 启动时立刻读取所有 provider 的 lastKnownLocation 选出最新的强制上报，
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
    private var passiveListener: LocationListener? = null   // 被动定位监听器（零耗电：复用其他App已算好的位置）
    private var enabledProviders: List<String> = emptyList() // 当前可用的 provider 列表（重注册时用）

    // —— 轻量化①：动态调频状态 ——
    private var baseIntervalMs = 8000L   // 用户设置的"移动中"采集间隔（由 TrackerService 传入）
    private var isStill = false          // 当前是否判定为静止
    private var stillCount = 0           // 连续静止计数（防抖：防止偶尔不动被误判）
    private var currentIntervalMs = -1L  // 当前实际生效的系统定位间隔（避免重复注册）

    // —— 轻量化②：批量上报缓存 ——
    private val pendingBatch = CopyOnWriteArrayList<com.coupletracker.android.data.LocationRow>()  // 待上传位置缓存
    private var lastFlushAt = 0L         // 上次批量上传时间戳
    @Volatile private var isFlushing = false   // 上传中标记：防止并发重复上传

    companion object {
        private const val STILL_INTERVAL_MS = 300_000L   // 静止时定位间隔：5 分钟（Life360 省电模式同级）
        private const val STILL_DISPLACEMENT_M = 30f     // 静止判定：位移 < 30 米
        private const val STILL_SPEED_MS = 0.3f          // 静止判定：速度 < 0.3 m/s
        private const val STILL_CONFIRM_COUNT = 3        // 连续 3 次静止才确认（防抖）
        private const val MOVING_DISPLACEMENT_M = 50f    // 恢复移动：单次位移 > 50 米立即恢复高频
        private const val BATCH_SIZE = 5                 // 批量上传：攒满 5 条立即传
        private const val BATCH_FLUSH_MS = 180_000L      // 批量上传：距上次超 3 分钟兜底传一次
        private const val MAX_CACHE_SIZE = 50            // 缓存上限（防离线太久撑爆内存）
    }

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
        baseIntervalMs = intervalMs   // 保存用户设置的移动间隔，静止调频以此为基准
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

        // —— 第二/三步：按当前运动状态注册 GPS + NETWORK + PASSIVE 三个 provider ——
        // 间隔由 detectInterval() 决定：移动中用用户设置的间隔，静止时自动拉到 5 分钟
        enabledProviders = providersEnabled
        registerProviders(detectInterval())
    }

    // ========================================================================
    //  轻量化核心①：根据运动状态决定系统定位间隔（动态调频）
    // ========================================================================
    private fun detectInterval(): Long =
        if (isStill) STILL_INTERVAL_MS else baseIntervalMs

    // ========================================================================
    //  轻量化核心②：注册/重注册所有定位 provider
    //  静止/移动状态切换时调用：改 minTime 间隔必须先 removeUpdates 再重新订阅
    // ========================================================================
    @SuppressLint("MissingPermission")
    private fun registerProviders(intervalMs: Long) {
        // 间隔没变且已注册过 → 无需重复操作（重复注册会让回调变密，白费电）
        if (currentIntervalMs == intervalMs && gpsListener != null) return
        currentIntervalMs = intervalMs

        // 先注销旧监听器
        gpsListener?.let { runCatching { locMgr.removeUpdates(it) } }
        netListener?.let { runCatching { locMgr.removeUpdates(it) } }
        passiveListener?.let { runCatching { locMgr.removeUpdates(it) } }

        // 三个 provider 共用一个监听器实例（回调逻辑都是 report）
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) { report(loc, force = false) }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String)  {}
            @Deprecated("deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        gpsListener = listener; netListener = listener; passiveListener = listener

        if (enabledProviders.contains(LocationManager.GPS_PROVIDER)) {
            runCatching { locMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper()) }
        }
        if (enabledProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            runCatching { locMgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper()) }
        }
        // PASSIVE_PROVIDER：被动接收其他 App（微信/地图等）触发的定位结果，系统不会因它额外唤醒 GPS，零耗电
        runCatching { locMgr.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, listener, Looper.getMainLooper()) }
    }

    // ============================================================================
    // stop：停止定位追踪，释放系统资源
    // ----------------------------------------------------------------------------
    // 重要：Service 销毁时必须调用，否则监听器不会被回收，造成电量泄露和内存泄漏
    // ============================================================================
    fun stop() {
        // 三个监听器全部注销；runCatching 防止异常
        gpsListener?.let { runCatching { locMgr.removeUpdates(it) } }; gpsListener = null
        netListener?.let { runCatching { locMgr.removeUpdates(it) } }; netListener = null
        passiveListener?.let { runCatching { locMgr.removeUpdates(it) } }; passiveListener = null
        // 停止前把缓存里没上传的位置点补传一次
        // 注意：Service 销毁时 scope 可能已被取消，这里用 GlobalScope 兜底（fire-and-forget）
        if (pendingBatch.isNotEmpty()) {
            @OptIn(DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { doFlush() }
        }
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
    //  节流过滤 + 静止检测 + 批量缓存上报
    // ========================================================================
    // report 是 LocationTracker 的"出口"方法：所有定位回调最终都汇聚到这里
    // 参数 loc  : 待上报的位置
    // 参数 force: true=强制上报（启动时初次显示用），false=普通回调（要走节流/精度过滤）
    private fun report(loc: Location, force: Boolean) {
        val now = System.currentTimeMillis()

        // ✅ 精度过滤：防止基站/WiFi 定位偏差几百米导致"定位不对"
        val acc = if (loc.hasAccuracy()) loc.accuracy else 999f
        if (!force && acc > 200f) {
            android.util.Log.d("CT-Tracker", "丢弃低精度定位: acc=${acc}m provider=${loc.provider}")
            return
        }
        // 如果已有更准的位置（30秒内），新位置精度差很多则丢弃
        if (!force && lastLocation != null && lastLocation!!.hasAccuracy()) {
            val lastAcc = lastLocation!!.accuracy
            if (acc > lastAcc * 2 && acc > 50f && (now - lastReportAt) < 30_000L) {
                android.util.Log.d("CT-Tracker", "丢弃退步定位: newAcc=${acc}m vs lastAcc=${lastAcc}m")
                return
            }
        }

        // —— 位移节流：短时间内几乎没动 → 省电跳过 ——
        val movedSinceLast = lastLocation?.let { loc.distanceTo(it) } ?: Float.MAX_VALUE
        if (!force && lastLocation != null) {
            val delta = now - lastReportAt
            if (delta < 2000 && movedSinceLast < 3f) return   // 2秒内移动不足3米 → 省电跳过
        }

        // ====================================================================
        // 轻量化①：静止/移动检测 → 动态调整系统定位间隔（Life360 式省电核心）
        //   静止判定：连续 STILL_CONFIRM_COUNT 次位移<30m（且无速度或速度<0.3m/s）
        //   移动恢复：单次位移>50m 或速度>0.5m/s，立即恢复高频
        // ====================================================================
        val slowOrNoSpeed = !loc.hasSpeed() || loc.speed < STILL_SPEED_MS
        if (movedSinceLast < STILL_DISPLACEMENT_M && slowOrNoSpeed) stillCount++ else stillCount = 0
        val movingNow = movedSinceLast > MOVING_DISPLACEMENT_M || (loc.hasSpeed() && loc.speed > 0.5f)
        val decidedStill = if (movingNow) { stillCount = 0; false } else stillCount >= STILL_CONFIRM_COUNT
        if (decidedStill != isStill) {
            isStill = decidedStill
            android.util.Log.d("CT-Tracker", "运动状态切换: isStill=$isStill → 定位间隔调整为 ${detectInterval()}ms")
            registerProviders(detectInterval())   // 状态变了 → 用新间隔重新订阅
        }

        // 通过所有过滤，更新"最近一次"记录
        lastLocation = loc; lastReportAt = now
        val isMoving = (loc.hasSpeed() && loc.speed > 0.5f)

        // ====================================================================
        // 轻量化②：先入本地缓存，攒批后一次性上传（减少网络唤醒次数）
        // ====================================================================
        scope.launch(Dispatchers.IO) {
            val user = UserRepository.get().getUser()
            val userId = user?.id ?: return@launch
            // couple_id 传 null（数据库已允许 null，未配对也记录自己的轨迹）
            pendingBatch += com.coupletracker.android.data.LocationRow(
                user_id       = userId,
                couple_id     = null,
                latitude      = loc.latitude,
                longitude     = loc.longitude,
                accuracy      = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                speed         = if (loc.hasSpeed())  loc.speed.toDouble()  else null,
                battery_level = batteryPct,
                is_moving     = isMoving
            )
            trimCache()

            // 触发上传条件：① 强制（启动时）② 攒满 BATCH_SIZE 条 ③ 距上次上传超 BATCH_FLUSH_MS
            if (force || pendingBatch.size >= BATCH_SIZE || now - lastFlushAt >= BATCH_FLUSH_MS) {
                doFlush()
            }
        }
    }

    // ========================================================================
    //  批量上传执行器：把缓存的位置点一次性 POST 到云端（PostgREST 批量插入）
    //  失败时把数据加回缓存，且不更新 lastFlushAt，下个位置点进来时自动触发重试
    // ========================================================================
    private suspend fun doFlush() {
        if (isFlushing || pendingBatch.isEmpty()) return
        isFlushing = true
        val batch = pendingBatch.toList()   // 拷贝快照
        pendingBatch.clear()
        val resp = runCatching { NetworkModule.restService.reportLocationsBatch(batch) }
        val http = resp.getOrNull()
        NetworkModule.lastLocationReportStatus.value =
            when {
                http == null -> {
                    pendingBatch.addAll(0, batch); trimCache()   // 网络异常 → 加回缓存重试
                    "位置上报异常：${resp.exceptionOrNull()?.message?.take(40).orEmpty()}"
                }
                !http.isSuccessful -> {
                    pendingBatch.addAll(0, batch); trimCache()   // 服务器错误 → 加回缓存重试
                    val errBody = runCatching { http.errorBody()?.string()?.take(60) }.getOrNull().orEmpty()
                    "位置上报失败 HTTP ${http.code()}：$errBody"
                }
                else -> {
                    lastFlushAt = System.currentTimeMillis()   // 只有成功才更新上传时间
                    "位置上报成功 ×${batch.size} · ${String.format("%.4f", batch.last().latitude)},${String.format("%.4f", batch.last().longitude)}"
                }
            }
        isFlushing = false
    }

    // 缓存防爆：超过上限时丢弃最旧的点
    private fun trimCache() { while (pendingBatch.size > MAX_CACHE_SIZE) pendingBatch.removeAt(0) }
}
