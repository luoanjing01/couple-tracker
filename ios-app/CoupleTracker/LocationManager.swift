//
//  LocationManager.swift
//  CoupleTracker
//
//  CLLocationManager 包装类
//  对应 Android: LocationTracker.kt + TrackerService.kt 的定位部分
//  职责：申请定位权限、持续定位、定时上报位置到 Supabase
//

import Foundation
import CoreLocation
import UIKit

/// 位置上报管理器
/// 1. 申请 WhenInUse + Always 权限
/// 2. 允许后台模式：location background mode
/// 3. 按 UserStore.locationIntervalSec 定时上报位置
final class LocationManager: NSObject, CLLocationManagerDelegate {

    static let shared = LocationManager()

    private let manager = CLLocationManager()
    private var reportTimer: DispatchSourceTimer?
    private var isReporting = false

    /// 最近一次有效位置（避免重复上报同一坐标）
    private var lastReported: CLLocation?

    private override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.pausesLocationUpdatesAutomatically = false
        manager.activityType = .other
        if #available(iOS 11.0, *) {
            manager.allowsBackgroundLocationUpdates = true
        }
        if #available(iOS 14.0, *) {
            // iOS14+ 显示后台定位指示器
            manager.showsBackgroundLocationIndicator = true
        }
    }

    // MARK: - 权限状态

    var authorizationStatus: CLAuthorizationStatus {
        return manager.authorizationStatus
    }

    /// 是否有"始终允许"权限（后台定位需要）
    var hasAlwaysPermission: Bool {
        let s = manager.authorizationStatus
        return s == .authorizedAlways
    }

    /// 是否有 WhenInUse 权限
    var hasWhenInUsePermission: Bool {
        let s = manager.authorizationStatus
        return s == .authorizedWhenInUse || s == .authorizedAlways
    }

    /// 申请 WhenInUse 权限（首次启动调用）
    func requestWhenInUse() {
        manager.requestWhenInUseAuthorization()
    }

    /// 申请 Always 权限（需先拿到 WhenInUse）
    func requestAlways() {
        // iOS13+: 先 WhenInUse → 用户再升 Always；iOS12 直接 requestAlways
        manager.requestAlwaysAuthorization()
    }

    // MARK: - 启动 / 停止

    /// 启动定位 + 定时上报
    /// 在用户已登录 + 至少 WhenInUse 权限时调用
    func start() {
        guard UserStore.shared.isLoggedIn() else { return }
        guard hasWhenInUsePermission else {
            requestWhenInUse()
            return
        }
        manager.startUpdatingLocation()
        manager.startMonitoringSignificantLocationChanges()
        startReportTimer()
    }

    /// 停止定位 + 上报
    func stop() {
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        reportTimer?.cancel()
        reportTimer = nil
    }

    /// 重启（用户调整频率后调用）
    func restart() {
        stop()
        start()
    }

    // MARK: - 上报逻辑

    private func startReportTimer() {
        reportTimer?.cancel()
        let interval = TimeInterval(UserStore.shared.locationIntervalSec)
        // DispatchSource timer 在后台也能稳定触发（Timer 在某些后台场景会暂停）
        let queue = DispatchQueue.global(qos: .utility)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: interval)
        timer.setEventHandler { [weak self] in
            self?.reportCurrentLocation()
        }
        timer.resume()
        reportTimer = timer
    }

    /// 上报当前位置到 Supabase
    private func reportCurrentLocation() {
        guard !isReporting else { return }
        guard let user = UserStore.shared.getUser() else { return }
        guard let loc = manager.location else {
            UserStore.shared.lastLocReportStatus = "⏳ 等待 GPS..."
            return
        }

        // 精度过滤：horizontalAccuracy < 0 表示无效；> 100m 精度太差不上报
        guard loc.horizontalAccuracy >= 0, loc.horizontalAccuracy < 150 else {
            UserStore.shared.lastLocReportStatus = "⏳ GPS 精度差 (\(Int(loc.horizontalAccuracy))m)"
            return
        }

        // 距离过滤：和上次上报点 < 5 米则跳过（节省请求）
        if let last = lastReported, loc.distance(from: last) < 5 {
            return
        }

        isReporting = true
        let battery = UIDevice.current.batteryLevel
        let batteryInt: Int? = (battery >= 0) ? Int(battery * 100) : nil
        let row = LocationRow(
            user_id: user.id,
            couple_id: nil, // 后端 RLS 用 user_id 关联，不需要 couple_id
            latitude: loc.coordinate.latitude,
            longitude: loc.coordinate.longitude,
            accuracy: loc.horizontalAccuracy,
            speed: loc.speed >= 0 ? loc.speed : nil,
            battery_level: batteryInt,
            is_moving: (loc.speed > 0.5)
        )

        Task {
            do {
                try await APIClient.shared.reportLocation(row)
                await MainActor.run {
                    self.lastReported = loc
                    let ts = DateFormatter.localizedString(
                        from: Date(), dateStyle: .none, timeStyle: .medium
                    )
                    UserStore.shared.lastLocReportStatus = "✅ 成功 \(ts)"
                }
            } catch {
                await MainActor.run {
                    UserStore.shared.lastLocReportStatus = "❌ \(error.localizedDescription.prefix(40))"
                }
            }
            self.isReporting = false
        }
    }

    // MARK: - CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let s = manager.authorizationStatus
        switch s {
        case .authorizedWhenInUse:
            // WhenInUse 后台会暂停；引导用户去 Always
            start()
        case .authorizedAlways:
            start()
        case .denied, .restricted:
            UserStore.shared.lastLocReportStatus = "❌ 定位权限被拒绝"
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // 位置缓存更新即可；真正上报在 timer 里做（按用户设定的频率）
        _ = locations.last
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        UserStore.shared.lastLocReportStatus = "❌ 定位失败: \(error.localizedDescription.prefix(40))"
    }
}
