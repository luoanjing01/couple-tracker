// ============================================================================
// 包声明：声明本文件所属的包 com.coupletracker.android
// 这是整个 Android 应用的根包，Application 类通常放在这里，
// 因为 AndroidManifest.xml 中需要通过全限定类名引用它。
// ============================================================================
package com.coupletracker.android

// ----------------------------------------------------------------------------
// 导入区域：引入本文件需要用到的各种类
// ----------------------------------------------------------------------------

// Android 框架相关：Application 基类、通知渠道、通知管理器、系统版本
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
// 项目内数据层：网络模块（封装 Supabase/HTTP）和用户仓库（管理登录态/token）
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
// 协程相关：Dispatchers.IO 表示在 IO 线程执行；runBlocking 用于阻塞式调用
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * ============================================================================
 * 情侣报备 APP - Application 入口
 * ============================================================================
 *
 * 【初学者导读】
 * 1. Application 类是整个 App 的"全局上下文"，在进程启动时最先被创建，
 *    生命周期与进程相同，因此适合放"只执行一次"的初始化逻辑。
 * 2. 继承自 Android 框架的 Application，需要再 AndroidManifest.xml 的
 *    <application android:name=".TrackerApp"> 中注册才会生效。
 * 3. 职责：初始化网络模块、用户仓库、前台服务通知渠道。
 *
 * 【注意】onCreate 在主线程执行，耗时操作要避免，否则会拖慢启动。
 * ============================================================================
 */
class TrackerApp : Application() {

    /**
     * --------------------------------------------------------------------------
     * App 进程启动入口：系统创建 Application 后立即回调此方法。
     * --------------------------------------------------------------------------
     *
     * 执行顺序：
     *   1. 先调用 super.onCreate() —— 让父类完成它自己的初始化
     *   2. 把自身引用保存到 companion object 的 instance 字段，方便全局获取
     *   3. 创建前台服务所需的通知渠道（Android 8.0+ 强制要求）
     *   4. 初始化用户仓库和网络模块（顺序：仓库在前，因为网络模块可能依赖用户态）
     *   5. 清洗旧版本残留的"假 token"（修复升级安装导致 401 的历史 bug）
     * --------------------------------------------------------------------------
     */
    override fun onCreate() {
        // 必须先调用父类方法，确保 Android 框架内部状态正确
        super.onCreate()

        // 把本 Application 实例存到静态字段，方便其他地方通过 TrackerApp.instance 拿到 context
        instance = this

        // 1. 创建前台服务通知渠道（Android 8.0+ 必须显式创建才能发通知）
        createNotificationChannels()

        // 2. 初始化网络模块和用户仓库
        //    UserRepository.init 内部会读取 DataStore 中保存的登录态/token
        //    NetworkModule.init 会构造 Retrofit/OkHttp 等依赖
        UserRepository.init(this)
        NetworkModule.init()

        // 3. ✅ 主动清洗旧版本残留的假 token（如 "rpc_auth_xxx"）
        //    升级安装时 DataStore 保留旧数据，脏 token 会导致所有请求 401
        //    用 runBlocking 同步等待完成 —— 启动期一次性清理，后续无需再清
        //    Dispatchers.IO 让磁盘 IO 在后台线程跑，避免阻塞主线程
        runBlocking(Dispatchers.IO) {
            UserRepository.get().sanitizeToken()
        }
    }

    /**
     * --------------------------------------------------------------------------
     * 创建前台服务专用的通知渠道
     * --------------------------------------------------------------------------
     *
     * 【为什么需要通知渠道？】
     * Android 8.0 (API 26) 之后，所有通知必须挂在某个"渠道"上，
     * 否则通知根本不会显示。前台服务（如长期上报位置的 Service）
     * 也必须有一条常驻通知，因此 App 启动时就要先把渠道建好。
     *
     * 【设计要点】
     * - IMPORTANCE_LOW：低优先级，不弹通知、不响铃、不震动，
     *   用户不会被打扰，但通知会出现在通知栏中（满足前台服务的合规要求）。
     * - 仅当渠道不存在时才创建：避免重复创建覆盖用户已修改的设置。
     * --------------------------------------------------------------------------
     */
    private fun createNotificationChannels() {
        // 仅 Android 8.0 (O) 及以上版本需要显式创建渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 通过系统服务拿到 NotificationManager（通知管理器）
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // 渠道 ID 来自 strings.xml，全局唯一，重启后还能用同一个 ID 引用
            val channelId = getString(R.string.tracker_channel_id)

            // 先查询是否已经存在该渠道；如果已存在就不重复创建（避免覆盖用户设置）
            val existing = nm.getNotificationChannel(channelId)
            if (existing == null) {
                // 构造一个新渠道：参数 = (渠道ID, 用户可见名称, 重要性等级)
                val channel = NotificationChannel(
                    channelId,
                    getString(R.string.tracker_channel_name),  // 渠道在系统设置中显示的名称
                    NotificationManager.IMPORTANCE_LOW  // 低重要性：不弹出、不响铃
                ).apply {
                    // 渠道描述，用户在系统设置里能看到
                    description = getString(R.string.tracker_channel_desc)
                    // 不显示桌面图标角标（小红点）
                    setShowBadge(false)
                    // 关闭通知灯
                    enableLights(false)
                    // 关闭震动
                    enableVibration(false)
                }
                // 把构建好的渠道注册到系统，从此该 channelId 就可用了
                nm.createNotificationChannel(channel)
            }
        }
    }

    /**
     * --------------------------------------------------------------------------
     * 伴生对象：相当于 Java 的 static 区，存放全局可访问的成员
     * --------------------------------------------------------------------------
     *
     * 【instance 字段说明】
     * - lateinit var：延迟初始化，在 onCreate 中赋值，之后只读
     * - private set：外部只能读不能写，避免被意外覆盖
     * - 访问方式：TrackerApp.instance 直接拿到 Application 上下文
     *
     * 【使用场景举例】
     *   没有 Context 的地方需要 context 时，可以这么写：
     *   val ctx = TrackerApp.instance
     * --------------------------------------------------------------------------
     */
    companion object {
        lateinit var instance: TrackerApp
            private set  // setter 私有：只能在类内部（onCreate 里）赋值
    }
}
