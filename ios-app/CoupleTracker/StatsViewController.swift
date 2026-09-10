//
//  StatsViewController.swift
//  CoupleTracker
//
//  日统计页：日活跃 / 应用使用总时长 / 在线小时数等
//  对应 Android: StatsScreen.kt
//  - 拉 7 天的 app_usage，按日期聚合总数
//  - 拉 7 天的 locations，按日期聚合上报次数
//

import UIKit

class StatsViewController: UIViewController, UICollectionViewDataSource,
                            UICollectionViewDelegateFlowLayout {

    private let collectionView: UICollectionView
    private let refreshControl = UIRefreshControl()
    private let emptyLabel = UILabel()

    private var stats: [DayStat] = []
    private let pinkColor = UIColor(red: 231/255, green: 84/255, blue: 128/255, alpha: 1)

    struct DayStat {
        let date: String
        var totalSec: Int = 0
        var reportCount: Int = 0
        var topApp: String?
        var topAppSec: Int = 0
    }

    // MARK: - 初始化

    override init(nibName: nil, bundle: nil) {
        let layout = UICollectionViewFlowLayout()
        layout.scrollDirection = .vertical
        layout.minimumLineSpacing = 12
        layout.minimumInteritemSpacing = 12
        layout.sectionInset = UIEdgeInsets(top: 12, left: 16, bottom: 12, right: 16)
        self.collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "统计"
        view.backgroundColor = UIColor(red: 0xFC/255, green: 0xE7/255, blue: 0xF3/255, alpha: 1)

        setupCollectionView()
        setupEmptyLabel()
        loadData()
    }

    private func setupCollectionView() {
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.register(DayStatCell.self, forCellWithReuseIdentifier: "DayStatCell")
        collectionView.backgroundColor = .clear
        refreshControl.addTarget(self, action: #selector(loadData), for: .valueChanged)
        collectionView.refreshControl = refreshControl
        view.addSubview(collectionView)
        NSLayoutConstraint.activate([
            collectionView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
    }

    private func setupEmptyLabel() {
        emptyLabel.text = "暂无统计数据\n登录后即可看到每日活跃数据"
        emptyLabel.numberOfLines = 0
        emptyLabel.font = .systemFont(ofSize: 14)
        emptyLabel.textColor = .darkGray
        emptyLabel.textAlignment = .center
        emptyLabel.translatesAutoresizingMaskIntoConstraints = false
        emptyLabel.isHidden = true
        view.addSubview(emptyLabel)
        NSLayoutConstraint.activate([
            emptyLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            emptyLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor)
        ])
    }

    // MARK: - 加载数据

    @objc private func loadData() {
        guard let me = UserStore.shared.getUser() else {
            refreshControl.endRefreshing()
            return
        }
        Task { [weak self] in
            guard let self = self else { return }
            do {
                async let appRows = APIClient.shared.getAppUsage(userId: me.id, rangeDays: 7)
                async let locRows = APIClient.shared.getCoupleLocations(coupleId: me.id, limit: 200)
                let (apps, locs) = try await (appRows, locRows)
                await MainActor.run {
                    self.stats = self.aggregate(apps: apps, locations: locs)
                    self.refreshControl.endRefreshing()
                    self.collectionView.reloadData()
                    self.emptyLabel.isHidden = !self.stats.isEmpty
                }
            } catch {
                await MainActor.run {
                    self.refreshControl.endRefreshing()
                    self.emptyLabel.text = "❌ 加载失败：\(error.localizedDescription.prefix(60))"
                    self.emptyLabel.isHidden = false
                }
            }
        }
    }

    private func aggregate(apps: [AppUsageRow], locations: [LocationRow]) -> [DayStat] {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        fmt.timeZone = .current
        let iso = ISO8601DateFormatter()

        var byDate: [String: DayStat] = [:]
        var order: [String] = []
        // 初始化最近 7 天
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date())
        for i in 0..<7 {
            if let d = cal.date(byAdding: .day, value: -i, to: today) {
                let s = fmt.string(from: d)
                byDate[s] = DayStat(date: s)
                order.append(s)
            }
        }

        for r in apps {
            let dateStr: String
            if let raw = r.created_at ?? r.window_start, let d = iso.date(from: raw) {
                dateStr = fmt.string(from: d)
            } else {
                continue
            }
            guard var s = byDate[dateStr] else { continue }
            s.totalSec += r.usage_seconds
            let appKey = (r.app_name?.isEmpty ?? true) ? r.package_name : r.app_name!
            // 简单 top app 跟踪
            if r.usage_seconds > s.topAppSec {
                s.topApp = appKey
                s.topAppSec = r.usage_seconds
            }
            byDate[dateStr] = s
        }

        for r in locations {
            guard let raw = r.created_at, let d = iso.date(from: raw) else { continue }
            let dateStr = fmt.string(from: d)
            guard var s = byDate[dateStr] else { continue }
            s.reportCount += 1
            byDate[dateStr] = s
        }

        return order.compactMap { byDate[$0] }
    }

    // MARK: - UICollectionViewDataSource

    func collectionView(_ collectionView: UICollectionView,
                        numberOfItemsInSection section: Int) -> Int {
        return stats.count
    }

    func collectionView(_ collectionView: UICollectionView,
                        cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "DayStatCell", for: indexPath) as! DayStatCell
        cell.configure(stat: stats[indexPath.item], accent: pinkColor)
        return cell
    }

    // MARK: - UICollectionViewDelegateFlowLayout

    func collectionView(_ collectionView: UICollectionView,
                        layout collectionViewLayout: UICollectionViewLayout,
                        sizeForItemAt indexPath: IndexPath) -> CGSize {
        let w = view.bounds.width - 32 // 16 inset * 2
        // 两列布局（iPhone X+ 横屏可能宽，但当前只支持竖屏）
        return CGSize(width: w, height: 120)
    }
}

// MARK: - DayStatCell

private final class DayStatCell: UICollectionViewCell {

    private let cardView = UIView()
    private let dateLabel = UILabel()
    private let totalLabel = UILabel()
    private let totalDesc = UILabel()
    private let reportLabel = UILabel()
    private let reportDesc = UILabel()
    private let topAppLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        cardView.backgroundColor = .white
        cardView.layer.cornerRadius = 16
        cardView.layer.masksToBounds = true
        cardView.layer.shadowColor = UIColor.black.cgColor
        cardView.layer.shadowOffset = CGSize(width: 0, height: 2)
        cardView.layer.shadowOpacity = 0.05
        cardView.layer.shadowRadius = 6
        cardView.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(cardView)

        dateLabel.font = .systemFont(ofSize: 15, weight: .bold)
        dateLabel.textColor = UIColor(red: 45/255, green: 55/255, blue: 72/255, alpha: 1)

        totalLabel.font = .systemFont(ofSize: 22, weight: .heavy)
        totalLabel.textColor = UIColor(red: 231/255, green: 84/255, blue: 128/255, alpha: 1)
        totalDesc.text = "总使用时长"
        totalDesc.font = .systemFont(ofSize: 11)
        totalDesc.textColor = UIColor.systemGray

        reportLabel.font = .systemFont(ofSize: 22, weight: .heavy)
        reportLabel.textColor = UIColor(red: 102/255, green: 126/255, blue: 234/255, alpha: 1)
        reportDesc.text = "位置上报次数"
        reportDesc.font = .systemFont(ofSize: 11)
        reportDesc.textColor = UIColor.systemGray

        topAppLabel.font = .systemFont(ofSize: 11)
        topAppLabel.textColor = UIColor.systemGray

        let hStack = UIStackView(arrangedSubviews: [
            stackCol(totalLabel, totalDesc),
            stackCol(reportLabel, reportDesc)
        ])
        hStack.axis = .horizontal
        hStack.distribution = .fillEqually

        let vStack = UIStackView(arrangedSubviews: [dateLabel, hStack, topAppLabel])
        vStack.axis = .vertical
        vStack.spacing = 10
        vStack.translatesAutoresizingMaskIntoConstraints = false
        vStack.isLayoutMarginsRelativeArrangement = true
        vStack.layoutMargins = UIEdgeInsets(top: 14, left: 16, bottom: 14, right: 16)
        cardView.addSubview(vStack)

        NSLayoutConstraint.activate([
            cardView.topAnchor.constraint(equalTo: contentView.topAnchor),
            cardView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
            cardView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            cardView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            vStack.topAnchor.constraint(equalTo: cardView.topAnchor),
            vStack.bottomAnchor.constraint(equalTo: cardView.bottomAnchor),
            vStack.leadingAnchor.constraint(equalTo: cardView.leadingAnchor),
            vStack.trailingAnchor.constraint(equalTo: cardView.trailingAnchor)
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    private func stackCol(_ a: UIView, _ b: UIView) -> UIStackView {
        let s = UIStackView(arrangedSubviews: [a, b])
        s.axis = .vertical
        s.spacing = 4
        return s
    }

    func configure(stat: StatsViewController.DayStat, accent: UIColor) {
        dateLabel.text = stat.date
        totalLabel.text = formatDuration(stat.totalSec)
        reportLabel.text = "\(stat.reportCount)"
        if let top = stat.topApp, stat.topAppSec > 0 {
            topAppLabel.text = "📱 最常用：\(top) (\(formatDuration(stat.topAppSec)))"
        } else {
            topAppLabel.text = "暂无应用数据"
        }
    }

    private func formatDuration(_ sec: Int) -> String {
        let h = sec / 3600
        let m = (sec % 3600) / 60
        if h > 0 { return "\(h)h \(m)m" }
        if m > 0 { return "\(m)m" }
        return "\(sec)s"
    }
}
