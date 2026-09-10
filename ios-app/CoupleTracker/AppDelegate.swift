//
//  AppDelegate.swift
//  CoupleTracker
//
//  App 入口：后台定位初始化、电池监测、设备方向锁定
//  对应 Android: TrackerApp.kt + TrackerService 启动逻辑
//

import UIKit
import CoreLocation

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        // 电池监测：上报电池电量时需要
        UIDevice.current.isBatteryMonitoringEnabled = true

        // 屏幕常亮（情侣在地图页时；后台自动停止）
        // 这里只设置全局默认；具体页面可调整
        // application.isIdleTimerDisabled = true

        // 提前预热定位管理器，触发权限弹窗（如果已经登录过）
        // Scene 启动后会根据登录状态决定是否 start()
        if UserStore.shared.isLoggedIn() {
            LocationManager.shared.start()
        }

        return true
    }

    // MARK: - Scene support

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }

    // MARK: - 后台进入/恢复

    func applicationDidEnterBackground(_ application: UIApplication) {
        // 后台时若已 Always 授权，继续上报
        // iOS13+ allowsBackgroundLocationUpdates 必须有 always 权限
        if LocationManager.shared.hasAlwaysPermission {
            LocationManager.shared.start()
        }
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        // 回前台：刷新一次位置 + 重新启动 timer（防止系统挂起 timer）
        LocationManager.shared.restart()
    }
}
