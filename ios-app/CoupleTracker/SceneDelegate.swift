//
//  SceneDelegate.swift
//  CoupleTracker
//
//  窗口设置：根据登录状态决定显示登录页或主页
//  对应 Android: SplashActivity.kt 的分流逻辑
//

import UIKit

class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }

        let window = UIWindow(windowScene: windowScene)
        self.window = window

        // 根据登录状态分流：未登录 → Login；已登录 → MainTabBar
        let root: UIViewController
        if UserStore.shared.isLoggedIn() {
            root = MainTabBarController()
            // 已登录：尝试启动定位上报
            LocationManager.shared.start()
        } else {
            root = LoginViewController()
        }
        window.rootViewController = root
        window.makeKeyAndVisible()
    }

    // MARK: - 便捷切换 root（登录/退出登录时调用）

    /// 切换到主界面（登录成功后）
    func showMain() {
        guard let window = window else { return }
        let vc = MainTabBarController()
        // 简单 crossfade
        if let snap = window.snapshotView(afterScreenUpdates: true) {
            vc.view.addSubview(snap)
            window.rootViewController = vc
            window.makeKeyAndVisible()
            UIView.animate(withDuration: 0.3, animations: {
                snap.alpha = 0
            }, completion: { _ in
                snap.removeFromSuperview()
            })
        } else {
            window.rootViewController = vc
            window.makeKeyAndVisible()
        }
        LocationManager.shared.start()
    }

    /// 切换到登录页（退出登录后）
    func showLogin() {
        guard let window = window else { return }
        LocationManager.shared.stop()
        let vc = LoginViewController()
        vc.modalPresentationStyle = .fullScreen
        window.rootViewController = vc
        window.makeKeyAndVisible()
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        // 后台时若已 Always 授权，继续定位
        if LocationManager.shared.hasAlwaysPermission {
            LocationManager.shared.start()
        }
    }
}
