//
//  AppsViewController.swift
//  CoupleTracker
//
//  应用使用页：显示自己和伴侣的 APP 使用时长
//  对应 Android: AppScreen.kt
//  - 调 APIClient.getAppUsage 拉 7 天数据
//  - 按日期分组 + 按应用聚合
//

import UIKit

class AppsViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {

    private let tableView = UITableView(frame: .zero, style: .insetGrouped)
    private let refreshControl = UIRefreshControl()
    private let emptyLabel = UILabel()

    /// 按日期分组的应用使用数据
    /// 结构：[(date: String, items: [(appName, totalSec)])]
    private var grouped: [(date: String, items: [(name: String, sec: Int)])] = []

    private let pinkColor = UIColor(red: 231/255, green: 84/255, blue: 128/255, alpha: 1)
    private let blueColor = UIColor(red: 102/255, green: 126/255, blue: 234/255, alpha: 1)

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "应用"
        view.backgroundColor = UIColor(red: 0xFC/255, green: 0xE7/255, blue: 0xF3/255, alpha: 1)

        setupTableView()
        setupEmptyLabel()
        loadData()
    }

    private func setupTableView() {
        tableView.translatesAutoresizingMaskIntoConstraints = false
        tableView.dataSource = self
        tableView.delegate = self
        tableView.register(AppUsageCell.self, forCellReuseIdentifier: "AppUsageCell")
        tableView.rowHeight = 64
        tableView.estimatedRowHeight = 64
        tableView.backgroundColor = .clear
        refreshControl.addTarget(self, action: #selector(loadData), for: .valueChanged)
        tableView.refreshControl = refreshControl
        view.addSubview(tableView)
        NSLayoutConstraint.activate([
            tableView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
    }

    private func setupEmptyLabel() {
        emptyLabel.text = "暂无应用使用记录\n登录并配对后，可以看到你和TA的 APP 使用情况"
        emptyLabel.numberOfLines = 0
        emptyLabel.font = .systemFont(ofSize: 14)
        emptyLabel.textColor = .darkGray
        emptyLabel.textAlignment = .center
        emptyLabel.translatesAutoresizingMaskIntoConstraints = false
        emptyLabel.isHidden = true
        view.addSubview(emptyLabel)
        NSLayoutConstraint.activate([
            emptyLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            emptyLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            emptyLabel.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            emptyLabel.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24)
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
                let rows = try await APIClient.shared.getAppUsage(userId: me.id, rangeDays: 7)
                await MainActor.run {
                    self.grouped = self.aggregate(rows: rows)
                    self.refreshControl.endRefreshing()
                    self.tableView.reloadData()
                    self.emptyLabel.isHidden = !self.grouped.isEmpty
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

    /// 按日期 + 应用聚合
    private func aggregate(rows: [AppUsageRow]) -> [(date: String, items: [(name: String, sec: Int)])] {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        fmt.timeZone = .current

        var byDate: [String: [String: Int]] = [:]
        var dateOrder: [String] = []
        for r in rows {
            let dateStr: String
            if let raw = r.created_at ?? r.window_start,
               let d = ISO8601DateFormatter().date(from: raw) {
                dateStr = fmt.string(from: d)
            } else {
                dateStr = "未知日期"
            }
            if byDate[dateStr] == nil {
                byDate[dateStr] = [:]
                dateOrder.append(dateStr)
            }
            let key = (r.app_name?.isEmpty ?? true) ? r.package_name : r.app_name!
            byDate[dateStr]![key, default: 0] += r.usage_seconds
        }

        return dateOrder.map { d in
            (date: d, items: (byDate[d] ?? [:])
                .sorted { $0.value > $1.value }
                .map { (name: $0.key, sec: $0.value) })
        }
    }

    // MARK: - UITableViewDataSource

    func numberOfSections(in tableView: UITableView) -> Int {
        return grouped.count
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return grouped[section].items.count
    }

    func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        return grouped[section].date
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "AppUsageCell", for: indexPath) as! AppUsageCell
        let item = grouped[indexPath.section].items[indexPath.row]
        cell.configure(name: item.name, seconds: item.sec, accent: blueColor)
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
    }
}

// MARK: - 单元格

private final class AppUsageCell: UITableViewCell {

    private let iconView = UILabel()
    private let nameLabel = UILabel()
    private let timeLabel = UILabel()
    private let barView = UIView()
    private let barContainer = UIView()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: .default, reuseIdentifier: reuseIdentifier)
        iconView.font = .systemFont(ofSize: 22)
        iconView.text = "📱"
        iconView.translatesAutoresizingMaskIntoConstraints = false

        nameLabel.font = .systemFont(ofSize: 15, weight: .medium)
        nameLabel.textColor = UIColor(red: 45/255, green: 55/255, blue: 72/255, alpha: 1)

        timeLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        timeLabel.textColor = UIColor(red: 102/255, green: 126/255, blue: 234/255, alpha: 1)
        timeLabel.textAlignment = .right

        barContainer.backgroundColor = UIColor.systemGray6
        barContainer.layer.cornerRadius = 2
        barContainer.translatesAutoresizingMaskIntoConstraints = false
        barView.backgroundColor = UIColor(red: 102/255, green: 126/255, blue: 234/255, alpha: 1)
        barView.layer.cornerRadius = 2
        barView.translatesAutoresizingMaskIntoConstraints = false
        barContainer.addSubview(barView)
        barContainer.sendSubviewToBack(barView)

        let stack = UIStackView(arrangedSubviews: [nameLabel, timeLabel])
        stack.axis = .horizontal
        stack.alignment = .center
        stack.distribution = .equalSpacing

        let vStack = UIStackView(arrangedSubviews: [stack, barContainer])
        vStack.axis = .vertical
        vStack.spacing = 4
        vStack.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(iconView)
        contentView.addSubview(vStack)
        NSLayoutConstraint.activate([
            iconView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
            iconView.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            iconView.widthAnchor.constraint(equalToConstant: 28),
            vStack.leadingAnchor.constraint(equalTo: iconView.trailingAnchor, constant: 12),
            vStack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
            vStack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 10),
            vStack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -10),
            barContainer.heightAnchor.constraint(equalToConstant: 4),
            barView.topAnchor.constraint(equalTo: barContainer.topAnchor),
            barView.bottomAnchor.constraint(equalTo: barContainer.bottomAnchor),
            barView.leadingAnchor.constraint(equalTo: barContainer.leadingAnchor)
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(name: String, seconds: Int, accent: UIColor) {
        nameLabel.text = name
        timeLabel.text = formatDuration(seconds)
        barView.frame.size.width = 0
        // 简单的进度条：按 6 小时封顶（21600s）
        let max: CGFloat = 21600
        let ratio = min(1, CGFloat(seconds) / max)
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.barView.backgroundColor = accent
            self.barContainer.layoutIfNeeded()
            let w = self.barContainer.bounds.width * ratio
            self.barView.frame.size = CGSize(width: w, height: self.barContainer.bounds.height)
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
