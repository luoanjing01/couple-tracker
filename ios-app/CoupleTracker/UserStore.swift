//
//  UserStore.swift
//  CoupleTracker
//
//  用户与配置持久化仓库（基于 UserDefaults）
//  对应 Android: UserRepository.kt
//  负责：token、当前用户信息、后端地址、采集频率的读写
//

import Foundation

/// 当前登录用户信息（对应 Android UserInfo）
struct UserInfo: Codable, Equatable {
    var id: String
    var username: String
    var nickname: String
    var gender: String        // "female" / "male" / "unknown"
    var avatar: String        // emoji or url
    var coupleCode: String    // 我的配对码
    var partnerId: String?   // 配对人 id（UUID）

    /// 地图/我的页常用展示名
    var displayName: String { nickname.isEmpty ? username : nickname }
}

/// 用户与配置持久化仓库
/// 使用 UserDefaults 存储：token、user_info、采集频率等
final class UserStore {

    static let shared = UserStore()

    private let defaults: UserDefaults

    private struct Keys {
        static let token = "auth_token"
        static let user = "user_info_json"
        static let locIntervalSec = "loc_interval_sec"
        static let appIntervalSec = "app_interval_sec"
        static let lastLocReport = "last_loc_report_status"
        static let lastAppReport = "last_app_report_status"
    }

    // 采集频率上下限（与 Android 端一致）
    static let minLocIntervalSec = 3
    static let maxLocIntervalSec = 60
    static let minAppIntervalSec = 2
    static let maxAppIntervalSec = 30
    static let defaultLocIntervalSec = 8
    static let defaultAppIntervalSec = 4

    private init() {
        self.defaults = UserDefaults.standard
    }

    // MARK: - Token

    func getToken() -> String? {
        return defaults.string(forKey: Keys.token)
    }

    func setToken(_ token: String?) {
        if let t = token, !t.isEmpty {
            defaults.set(t, forKey: Keys.token)
        } else {
            defaults.removeObject(forKey: Keys.token)
        }
    }

    /// 判断一个字符串是否是合法 JWT 格式（三段 base64url 用点号分隔）
    private let jwtRegex = try! NSRegularExpression(
        pattern: "^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$"
    )

    func isValidJwt(_ token: String?) -> Bool {
        guard let t = token, !t.isEmpty else { return false }
        let range = NSRange(t.startIndex..<t.endIndex, in: t)
        return jwtRegex.firstMatch(in: t, range: range) != nil
    }

    /// 清洗非法 token：如果不是合法 JWT 则清除
    func sanitizeToken() {
        let t = getToken()
        if t != nil && !isValidJwt(t) {
            setToken(nil)
        }
    }

    // MARK: - User

    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    func getUser() -> UserInfo? {
        guard let data = defaults.data(forKey: Keys.user) else { return nil }
        return try? decoder.decode(UserInfo.self, from: data)
    }

    func setUser(_ user: UserInfo?) {
        if let u = user {
            if let data = try? encoder.encode(u) {
                defaults.set(data, forKey: Keys.user)
            }
        } else {
            defaults.removeObject(forKey: Keys.user)
        }
    }

    /// 已登录 = 有 user 信息（token 可为 null，因为 RPC 认证不需要 JWT）
    func isLoggedIn() -> Bool {
        sanitizeToken()
        return getUser() != nil
    }

    /// 退出登录：清除所有本地数据
    func logout() {
        for key in [Keys.token, Keys.user, Keys.lastLocReport, Keys.lastAppReport] {
            defaults.removeObject(forKey: key)
        }
        // 清除 HTTP 缓存/Cookie
        URLCache.shared.removeAllCachedResponses()
        HTTPCookieStorage.shared.cookies?.forEach {
            HTTPCookieStorage.shared.deleteCookie($0)
        }
        // 清除 WKWebView 默认存储（需要在主线程）
        DispatchQueue.main.async {
            let store = WKWebsiteDataStore.default()
            let types = WKWebsiteDataStore.allWebsiteDataTypes()
            let date = Date.distantPast
            store.removeData(ofTypes: types, modifiedSince: date, completionHandler: {})
        }
    }

    // MARK: - 上报状态（用于我的页排查）

    var lastLocReportStatus: String {
        get { defaults.string(forKey: Keys.lastLocReport) ?? "等待中..." }
        set { defaults.set(newValue, forKey: Keys.lastLocReport) }
    }

    var lastAppReportStatus: String {
        get { defaults.string(forKey: Keys.lastAppReport) ?? "等待中..." }
        set { defaults.set(newValue, forKey: Keys.lastAppReport) }
    }

    // MARK: - 采集频率

    var locationIntervalSec: Int {
        get {
            let v = (defaults.object(forKey: Keys.locIntervalSec) as? Int)
                ?? UserStore.defaultLocIntervalSec
            return min(max(v, UserStore.minLocIntervalSec), UserStore.maxLocIntervalSec)
        }
        set {
            let v = min(max(newValue, UserStore.minLocIntervalSec), UserStore.maxLocIntervalSec)
            defaults.set(v, forKey: Keys.locIntervalSec)
        }
    }

    var appIntervalSec: Int {
        get {
            let v = (defaults.object(forKey: Keys.appIntervalSec) as? Int)
                ?? UserStore.defaultAppIntervalSec
            return min(max(v, UserStore.minAppIntervalSec), UserStore.maxAppIntervalSec)
        }
        set {
            let v = min(max(newValue, UserStore.minAppIntervalSec), UserStore.maxAppIntervalSec)
            defaults.set(v, forKey: Keys.appIntervalSec)
        }
    }
}

import WebKit
