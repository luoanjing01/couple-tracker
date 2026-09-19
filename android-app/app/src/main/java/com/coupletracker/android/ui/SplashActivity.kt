package com.coupletracker.android.ui

/**
 * ===== 导入依赖说明（面向初学者）=====
 * 1. Android 框架组件：
 *    - Activity：所有界面的基类。
 *    - Intent：用于在组件之间跳转/通信（这里用来跳转界面）。
 *    - Bundle：保存界面状态（系统传入，重写 onCreate 时接收）。
 *    - Handler / Looper：在主线程上执行延时任务。
 * 2. 项目内部模块：
 *    - UserRepository：用户数据访问层，负责读取本地登录状态。
 *    - TrackerService：后台追踪服务，登录后需要拉起。
 * 3. 协程：
 *    - Dispatchers：指定代码运行在哪个线程。
 *    - runBlocking：以阻塞方式等待协程结果（详见下方注释）。
 */
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.service.TrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 启动屏（Splash Screen）。
 *
 * 作用：App 启动时显示一个纯主题背景的过渡界面（无任何布局），同时利用这段时间
 * 在后台读取用户的登录状态，再决定下一步跳转到主界面还是登录界面。
 *
 * 设计要点（面向初学者）：
 * 1. 继承普通的 android.app.Activity（而不是 AppCompatActivity），是为了兼容
 *    AndroidManifest 中配置的 @android:style/Theme.DeviceDefault.Light.NoActionBar
 *    系统主题——AppCompatActivity 不接受这种纯系统主题。
 * 2. 不调用 setContentView，因为启动屏只需要主题背景，不需要 UI 控件。
 * 3. 使用 Handler.postDelayed + runBlocking 的组合，而不是直接用协程作用域，
 *    原因是普通 Activity 不是 LifecycleOwner，无法直接使用 lifecycleScope，
 *    所以这里用最简单的延时回调 + runBlocking 阻塞读取方式实现。
 */
class SplashActivity : Activity() {

    /**
     * Activity 创建时由系统回调。
     *
     * 流程：
     *   1. 延时 500ms 让用户看到启动屏画面；
     *   2. 在 IO 线程同步读取登录状态；
     *   3. 根据登录状态跳转到 MainActivity 或 LoginActivity；
     *   4. 调用 finish() 销毁自己，避免用户按返回键再回到启动屏。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        // 调用父类 onCreate，完成 Activity 的基础初始化（必须保留，否则会抛异常）。
        super.onCreate(savedInstanceState)
        // 在主线程（Looper.getMainLooper()）上延时 500ms 后执行内部代码块。
        // 延时的目的是让启动屏显示一段时间，避免一闪而过。
        Handler(Looper.getMainLooper()).postDelayed({
            // 切到 IO 线程同步读取登录状态。
            // 说明：
            //   - runBlocking 会阻塞当前线程直到内部协程完成，这里用在主线程的延时回调里；
            //     启动屏允许短暂阻塞，500ms 内的 IO 读取不会触发 ANR（应用无响应）。
            //   - Dispatchers.IO 表示在专门的 IO 线程池里执行，避免读写本地存储阻塞主线程。
            val loggedIn = runBlocking(Dispatchers.IO) {
                // UserRepository.get() 获取单例仓库对象；isLoggedIn() 读取本地的登录标记。
                UserRepository.get().isLoggedIn()
            }
            // 根据登录状态决定跳转目标。
            if (loggedIn) {
                // 已登录：尝试拉起后台追踪服务。
                // runCatching 包裹可以捕获异常，避免服务启动失败（如权限缺失）导致整个 App 崩溃；
                // 即使失败也只忽略异常，继续跳转到主界面。
                runCatching { TrackerService.start(this@SplashActivity) }
                // 用 Intent 从当前 SplashActivity 跳转到 MainActivity（主界面）。
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            } else {
                // 未登录：直接跳转到 LoginActivity 让用户完成登录。
                startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
            }
            // 销毁当前 SplashActivity，使它从回退栈中移除——用户按返回键时不会再回到启动屏。
            finish()
        }, SPLASH_DELAY_MS)
    }

    // companion object 中定义的常量属于"类级别"，所有实例共享一份，节省内存。
    // private 表示只在当前文件内可见。
    private companion object {
        // 启动屏显示时长（毫秒）。500L 中的 L 表示这是一个 Long 类型字面量。
        private const val SPLASH_DELAY_MS = 500L
    }
}
