package com.coupletracker.android.receiver

/**
 * ===== 导入依赖说明（面向初学者）=====
 * 1. Android 框架组件：
 *    - BroadcastReceiver：用于接收系统/应用发出的广播（如开机完成、应用更新完成）。
 *    - Context：应用上下文，用于启动服务、获取资源等。
 *    - Intent：广播中携带的意图对象，包含动作（action）等信息。
 * 2. 项目内部模块：
 *    - UserRepository：读取本地登录状态。
 *    - TrackerService：开机或更新后需要拉起的追踪服务。
 * 3. 协程：
 *    - CoroutineScope：创建一个协程作用域。
 *    - Dispatchers.IO：在 IO 线程池执行任务。
 *    - launch：启动一个协程。
 */
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.service.TrackerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机自启 + 应用升级后自启的广播接收器。
 *
 * 作用：当手机开机完成、解锁完成或本 App 被升级替换时，系统会发送对应广播。
 * 本接收器收到广播后，在用户已登录的前提下，自动拉起 TrackerService 前台服务，
 * 保证追踪能力在设备重启或应用更新后仍然持续运行。
 *
 * 设计要点（面向初学者）：
 * 1. 继承自 BroadcastReceiver，必须实现 onReceive 方法。
 * 2. 在 AndroidManifest 中需要声明对应的 <receiver> 并添加对应的 <intent-filter>
 *    （如 BOOT_COMPLETED、LOCKED_BOOT_COMPLETED、MY_PACKAGE_REPLACED）。
 * 3. 因为广播 onReceive 在主线程执行且时间有限，不能直接做磁盘 IO，
 *    所以这里启动一个协程在 IO 线程上读取登录状态再决定是否启动服务。
 */
class BootReceiver : BroadcastReceiver() {
    /**
     * 系统广播到达时回调。
     *
     * @param context 应用上下文，用于启动服务
     * @param intent  系统发来的广播意图，含 action（动作名）
     */
    override fun onReceive(context: Context, intent: Intent) {
        // 取出广播动作名；如果没有动作（理论上不应发生），直接返回不做处理。
        val action = intent.action ?: return
        // 只关心三种广播动作：
        //   - ACTION_BOOT_COMPLETED：设备完全开机完成（用户解锁后）。
        //   - ACTION_LOCKED_BOOT_COMPLETED：直接启动模式下的开机完成（用户尚未解锁也能收到）。
        //   - ACTION_MY_PACKAGE_REPLACED：本 App 被新版本替换安装完成。
        if (action == Intent.ACTION_BOOT_COMPLETED
            || action == Intent.ACTION_LOCKED_BOOT_COMPLETED
            || action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            // 在 IO 线程上启动一个协程执行耗时操作（读登录状态 + 启动服务）。
            // 注意：BroadcastReceiver 的 onReceive 在主线程执行，不能直接做磁盘 IO，
            // 否则会阻塞主线程导致 ANR；这里通过协程切到 IO 线程规避。
            CoroutineScope(Dispatchers.IO).launch {
                // 只有用户已登录，才需要拉起追踪服务，避免未登录用户被无谓地启动服务。
                if (UserRepository.get().isLoggedIn()) {
                    // runCatching 捕获可能抛出的异常（如缺少权限、服务被系统限制等），
                    // 避免异常冒泡到协程未捕获处理器导致崩溃。
                    runCatching { TrackerService.start(context) }
                }
            }
        }
    }
}
