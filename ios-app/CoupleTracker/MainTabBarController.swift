//
//  MainTabBarController.swift
//  CoupleTracker
//
//  4-tab 底部栏：地图 / 应用 / 统计 / 我的
//  对应 Android: MainActivity 的 Tab 枚举 + NavigationBar
//

import UIKit

final class MainTabBarController: UITabBarController {

    override func viewDidLoad() {
        super.viewDidLoad()

        // 主题色
        let pink = UIColor(red: 231/255, green: 84/255, blue: 128/255, alpha: 1)
        let blue = UIColor(red: 102/255, green: 126/255, blue: 234/255, alpha: 1)

        // 各 Tab 的 ViewController
        let mapVC   = MapViewController()
        let appsVC  = AppsViewController()
        let statsVC = StatsViewController()
        let profileVC = ProfileViewController()

        // 各 Tab 都套一个 UINavigationController（顶部留个标题）
        let mapNav   = wrap(mapVC, title: "地图", icon: "🗺️", selIcon: "🗺️")
        let appsNav  = wrap(appsVC, title: "应用", icon: "🌐", selIcon: "🌐")
        let statsNav = wrap(statsVC, title: "统计", icon: "📊", selIcon: "📊")
        let profileNav = wrap(profileVC, title: "我的", icon: "👤", selIcon: "👤")

        viewControllers = [mapNav, appsNav, statsNav, profileNav]

        // 底部 tab 样式
        tabBar.barTintColor = .white
        tabBar.tintColor = pink
        tabBar.unselectedItemTintColor = UIColor(red: 160/255, green: 174/255, blue: 192/255, alpha: 1)
        // iOS15+ 需要 standardAppearance 才能控制不透明背景
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = .white
        tabBar.standardAppearance = appearance
        if #available(iOS 15.0, *) {
            tabBar.scrollEdgeAppearance = appearance
        }

        selectedIndex = 0
    }

    private func wrap(_ vc: UIViewController, title: String, icon: String, selIcon: String) -> UINavigationController {
        let nav = UINavigationController(rootViewController: vc)
        nav.tabBarItem = UITabBarItem(
            title: title,
            image: emojiToImage(icon, size: 22, selected: false),
            selectedImage: emojiToImage(selIcon, size: 22, selected: true)
        )
        nav.navigationBar.barTintColor = .white
        nav.navigationBar.tintColor = UIColor(red: 231/255, green: 84/255, blue: 128/255, alpha: 1)
        return nav
    }

    /// Emoji 转 UIImage（避免 SF Symbol 依赖，与 Android emoji 风格一致）
    private func emojiToImage(_ emoji: String, size: CGFloat, selected: Bool) -> UIImage? {
        let scale = UIScreen.main.scale
        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: size, height: size + 4)
        )
        let img = renderer.image { ctx in
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: size),
                .foregroundColor: selected
                    ? UIColor(red: 231/255, green: 84/255, blue: 128/255, alpha: 1)
                    : UIColor(red: 160/255, green: 174/255, blue: 192/255, alpha: 1)
            ]
            let str = emoji as NSString
            str.draw(at: CGPoint(x: 0, y: 0), withAttributes: attrs)
        }
        let _ = scale
        return img.withRenderingMode(.alwaysOriginal)
    }
}
