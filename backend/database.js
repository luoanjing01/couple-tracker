/**
 * 轻量级 JSON 文件数据库 - 兼容 better-sqlite3 的基础API
 * 用于演示项目，避免原生编译问题
 * 
 * API 兼容：
 *   db.prepare(sql) -> Statement
 *   Statement.run(...params) -> { lastInsertRowid, changes }
 *   Statement.get(...params) -> row
 *   Statement.all(...params) -> row[]
 *   db.pragma(str) -> 忽略
 *   db.exec(sql) -> 执行多条建表SQL
 */
const fs = require('fs');
const path = require('path');
const bcrypt = require('bcryptjs');

class JsonDatabase {
  constructor(filePath) {
    this.filePath = filePath;
    /** @type {Record<string, any[]>} */
    this.tables = {};
    /** @type {Record<string, number>} */
    this.autoIncrements = {};
    this._autoSaveTimer = null;

    if (fs.existsSync(filePath)) {
      try {
        const data = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
        this.tables = data.tables || {};
        this.autoIncrements = data.autoIncrements || {};
        console.log('✅ 从文件加载数据库:', filePath);
      } catch (e) {
        console.warn('⚠️ 数据库文件损坏，重新初始化:', e.message);
      }
    }
  }

  pragma() { /* 忽略 */ }

  _save() {
    // 防抖保存，1秒内多次修改合并一次
    if (this._autoSaveTimer) clearTimeout(this._autoSaveTimer);
    this._autoSaveTimer = setTimeout(() => {
      try {
        const dir = path.dirname(this.filePath);
        if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
        fs.writeFileSync(
          this.filePath,
          JSON.stringify({ tables: this.tables, autoIncrements: this.autoIncrements }, null, 2),
          'utf-8'
        );
      } catch (e) {
        console.error('❌ 保存数据库失败:', e.message);
      }
    }, 1000);
  }

  exec(sqlStatements) {
    // 解析多个 CREATE TABLE IF NOT EXISTS 语句
    const statements = sqlStatements
      .split(';')
      .map(s => s.trim())
      .filter(s => s.length > 0 && s.toUpperCase().startsWith('CREATE TABLE'));

    for (const sql of statements) {
      // 提取表名
      const tableMatch = sql.match(/CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["`]?(\w+)["`]?\s*\(/i);
      if (!tableMatch) continue;
      const tableName = tableMatch[1];
      if (!this.tables[tableName]) {
        this.tables[tableName] = [];
        this.autoIncrements[tableName] = 0;
        console.log('  📋 创建表:', tableName);
      }
    }

    // 索引语句忽略
    const indexStatements = sqlStatements
      .split(';')
      .map(s => s.trim())
      .filter(s => s.toUpperCase().startsWith('CREATE INDEX'));
    if (indexStatements.length > 0) {
      console.log(`  🚀 跳过 ${indexStatements.length} 条索引创建 (JSON数据库自动支持过滤)`);
    }
    this._save();
  }

  prepare(sql) {
    return new Statement(this, sql);
  }
}

class Statement {
  constructor(db, sql) {
    this.db = db;
    this.sql = sql.trim();
    this._parse();
  }

  _parse() {
    const sql = this.sql;
    const upper = sql.toUpperCase();

    if (upper.startsWith('INSERT')) {
      this.type = 'insert';
      const m = sql.match(/INSERT\s+(?:OR\s+\w+\s+)?INTO\s+["`]?(\w+)["`]?\s*\(([^)]+)\)\s*VALUES\s*\(([^)]+)\)/i);
      if (m) {
        this.table = m[1];
        this.columns = m[2].split(',').map(s => s.trim().replace(/["`]/g, ''));
        this.valuesTemplate = m[3].split(',').map(s => s.trim());
      }
    } else if (upper.startsWith('UPDATE')) {
      this.type = 'update';
      const tableMatch = sql.match(/UPDATE\s+["`]?(\w+)["`]?\s+SET/i);
      if (tableMatch) this.table = tableMatch[1];
      // 解析 SET col = ?, col2 = ? ...
      const setMatch = sql.match(/SET\s+(.+?)(?:\s+WHERE|$)/i);
      if (setMatch) {
        this.setClauses = setMatch[1].split(',').map(clause => {
          const [col, val] = clause.split('=').map(s => s.trim());
          return { column: col.replace(/["`]/g, ''), valueTemplate: val };
        });
      }
      this.whereClause = this._extractWhere(sql);
    } else if (upper.startsWith('DELETE')) {
      this.type = 'delete';
      const tableMatch = sql.match(/DELETE\s+FROM\s+["`]?(\w+)["`]?/i);
      if (tableMatch) this.table = tableMatch[1];
      this.whereClause = this._extractWhere(sql);
    } else if (upper.startsWith('SELECT')) {
      this.type = 'select';
      // 解析表名 (FROM/JOIN)
      const fromMatch = sql.match(/FROM\s+["`]?(\w+)["`]?/i);
      if (fromMatch) this.table = fromMatch[1];
      // JOIN
      this.joins = [];
      const joinRegex = /(?:LEFT|RIGHT|INNER|CROSS)?\s*JOIN\s+["`]?(\w+)["`]?\s+ON\s+([^\s]+(?:\s*=\s*[^\s]+)+)/gi;
      let jm;
      while ((jm = joinRegex.exec(sql)) !== null) {
        this.joins.push({ table: jm[1], on: jm[2].trim() });
      }
      this.whereClause = this._extractWhere(sql);
      this.isCount = /COUNT\s*\(/i.test(sql);
      this.isDistinct = /DISTINCT\s+/i.test(sql);
      this.selectedCols = this._extractSelectedCols(sql);
      this.orderBy = this._extractOrderBy(sql);
      this.limit = this._extractLimit(sql);
      this.groupBy = this._extractGroupBy(sql);
    }
  }

  _extractWhere(sql) {
    const m = sql.match(/WHERE\s+(.+?)(?:\s+ORDER\s+BY|\s+GROUP\s+BY|\s+LIMIT|$)/i);
    if (!m) return null;
    const whereStr = m[1].trim();
    // 简单条件解析：支持 col = ? 以及 col1 = ? AND col2 = ? (OR同理)
    return whereStr;
  }

  _extractOrderBy(sql) {
    const m = sql.match(/ORDER\s+BY\s+(.+?)(?:\s+LIMIT|$)/i);
    if (!m) return null;
    const parts = m[1].trim().split(',').map(p => {
      const [col, dir = 'ASC'] = p.trim().split(/\s+/);
      return { column: col.replace(/["`]/g, ''), direction: dir.toUpperCase() };
    });
    return parts;
  }

  _extractLimit(sql) {
    const m = sql.match(/LIMIT\s+(\d+)(?:\s+OFFSET\s+(\d+))?/i);
    if (!m) return null;
    return { count: parseInt(m[1]), offset: m[2] ? parseInt(m[2]) : 0 };
  }

  _extractGroupBy(sql) {
    const m = sql.match(/GROUP\s+BY\s+(.+?)(?:\s+ORDER\s+BY|\s+LIMIT|$)/i);
    if (!m) return null;
    return m[1].trim().split(',').map(s => s.trim().replace(/["`]/g, ''));
  }

  _extractSelectedCols(sql) {
    const m = sql.match(/SELECT\s+(?:DISTINCT\s+)?(.+?)\s+FROM\s/i);
    if (!m) return null;
    const cols = m[1].split(',').map(s => s.trim());
    if (cols.includes('*')) return ['*'];
    return cols.map(c => {
      // 处理函数: SUM(duration_seconds) as total_duration
      const asMatch = c.match(/(.+?)\s+(?:AS\s+)?(\w+)$/i);
      if (asMatch) {
        return { expr: asMatch[1].trim(), alias: asMatch[2] };
      }
      return { expr: c, alias: c.split('.').pop() };
    });
  }

  _replaceParams(template, params) {
    let idx = 0;
    if (Array.isArray(template)) {
      return template.map(t => {
        if (t === '?' || t === '?,'.slice(0, -1) && t === '?') {
          return params[idx++];
        }
        // 如果模板是字符串（如 CURRENT_TIMESTAMP）直接返回
        if (t !== '?') {
          if (/^['"].*['"]$/.test(t)) return t.slice(1, -1);
          if (/^CURRENT_TIMESTAMP$/i.test(t)) return new Date().toISOString();
          if (/^\d+$/.test(t)) return parseInt(t);
          if (t === '?') return params[idx++];
        }
        return t === '?' ? params[idx++] : t;
      });
    }
    return template;
  }

  _parseWhereCondition(whereStr, params) {
    if (!whereStr) return () => true;
    let pIdx = 0;

    // 拆分 AND / OR
    // 先处理 AND (最常见)
    const orParts = whereStr.split(/\s+OR\s+/i);
    const orFilters = orParts.map(orPart => {
      const andParts = orPart.split(/\s+AND\s+/i);
      const andFilters = andParts.map(cond => {
        cond = cond.trim();
        // col = ? 或 col = value
        const equalMatch = cond.match(/^["`]?([\w.]+)["`]?\s*=\s*(.+)$/);
        if (equalMatch) {
          const colRaw = equalMatch[1];
          const col = colRaw.includes('.') ? colRaw.split('.').pop() : colRaw;
          let val = equalMatch[2].trim();
          if (val === '?') {
            val = params[pIdx++];
          } else if (/^['"].*['"]$/.test(val)) {
            val = val.slice(1, -1);
          } else if (val === '?' || val.includes('?')) {
            val = params[pIdx++];
          } else if (/^-?\d+(\.\d+)?$/.test(val)) {
            val = Number(val);
          }
          return (row) => row[col] === val;
        }
        // DATE(col) = ?
        const dateMatch = cond.match(/^DATE\s*\(\s*["`]?([\w.]+)["`]?\s*\)\s*(>=|<=|=|>|<)\s*(.+)$/i);
        if (dateMatch) {
          const col = dateMatch[1].split('.').pop();
          const op = dateMatch[2];
          let val = dateMatch[3].trim();
          if (val.startsWith(`'`)) val = val.slice(1, -1);
          if (val === '?') val = params[pIdx++];
          if (val.startsWith('DATE(')) {
            // DATE('now', '-7 days') 类似函数 - 简化处理
            const dayMatch = val.match(/DATE\s*\(\s*'now'?\s*,\s*'-\s*(\d+)\s+days'\s*\)/i);
            if (dayMatch) {
              const d = new Date();
              d.setDate(d.getDate() - parseInt(dayMatch[1]));
              val = d.toISOString().split('T')[0];
            } else {
              val = new Date().toISOString().split('T')[0];
            }
          }
          return (row) => {
            const rowDate = row[col] ? String(row[col]).split('T')[0] : '';
            switch (op) {
              case '=': return rowDate === val;
              case '>': return rowDate > val;
              case '<': return rowDate < val;
              case '>=': return rowDate >= val;
              case '<=': return rowDate <= val;
            }
            return false;
          };
        }
        // col >= ? / col <= ?
        const cmpMatch = cond.match(/^["`]?([\w.]+)["`]?\s*(>=|<=|>|<)\s*(.+)$/);
        if (cmpMatch) {
          const col = cmpMatch[1].split('.').pop();
          const op = cmpMatch[2];
          let val = cmpMatch[3].trim();
          if (val === '?') val = params[pIdx++];
          if (/^-?\d+(\.\d+)?$/.test(val)) val = Number(val);
          return (row) => {
            const rv = row[col];
            if (typeof rv === 'number' && typeof val === 'number') {
              switch (op) {
                case '>': return rv > val; case '<': return rv < val;
                case '>=': return rv >= val; case '<=': return rv <= val;
              }
            }
            return false;
          };
        }
        return () => true;
      });
      return (row) => andFilters.every(f => f(row));
    });

    return (row) => orFilters.some(f => f(row));
  }

  _collectBaseRows(params) {
    let rows = this.db.tables[this.table] ? [...this.db.tables[this.table]] : [];
    const filter = this._parseWhereCondition(this.whereClause, params);
    rows = rows.filter(filter);

    // JOIN处理 (简单等值JOIN)
    for (const join of this.joins || []) {
      const joinRows = this.db.tables[join.table] || [];
      // 解析 ON tableA.col = tableB.col
      const onParts = join.on.split(/\s*=\s*/).map(s => s.trim().split('.').pop());
      const leftCol = onParts[0];
      const rightCol = onParts[1];
      rows = rows.flatMap(leftRow => {
        const matched = joinRows.filter(jr => jr[rightCol] === leftRow[leftCol]);
        if (matched.length === 0) {
          // LEFT JOIN: 保留左行
          if (/LEFT/i.test(this.sql)) return [{ ...leftRow }];
          return [];
        }
        return matched.map(jr => ({ ...leftRow, ...jr }));
      });
    }

    return rows;
  }

  _applyAggregates(rows, params) {
    if (!this.groupBy && !this.isCount) return rows;

    if (this.isCount && !this.groupBy) {
      // SELECT COUNT(*)
      if (this.selectedCols) {
        const col = this.selectedCols[0];
        if (col && /COUNT\s*\(\s*\*/i.test(col.expr)) {
          return [{ [col.alias || 'COUNT(*)']: rows.length }];
        }
        // COUNT(DISTINCT col)
        const distMatch = (col?.expr || '').match(/COUNT\s*\(\s*DISTINCT\s+([\w.]+)\s*\)/i);
        if (distMatch) {
          const c = distMatch[1].split('.').pop();
          const set = new Set(rows.map(r => r[c]).filter(v => v != null));
          return [{ [col.alias || `COUNT(DISTINCT ${c})`]: set.size }];
        }
      }
      return [{ count: rows.length }];
    }

    // GROUP BY
    if (this.groupBy) {
      const groups = new Map();
      for (const row of rows) {
        const key = this.groupBy.map(g => String(row[g] ?? '')).join('|||');
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(row);
      }
      const result = [];
      for (const [, grpRows] of groups) {
        const aggRow = {};
        // 复制group by列
        for (const g of this.groupBy) aggRow[g] = grpRows[0][g];

        // 处理select聚合
        if (this.selectedCols) {
          for (const col of this.selectedCols) {
            if (typeof col === 'string') continue;
            const expr = col.expr;

            // SUM(col)
            const sumMatch = expr.match(/SUM\s*\(\s*["`]?([\w.]+)["`]?\s*\)/i);
            if (sumMatch) {
              const c = sumMatch[1].split('.').pop();
              aggRow[col.alias] = grpRows.reduce((s, r) => s + (Number(r[c]) || 0), 0);
              continue;
            }
            // COUNT(col) / COUNT(*)
            const countMatch = expr.match(/COUNT\s*\(\s*(DISTINCT\s+)?(.+?)\s*\)/i);
            if (countMatch) {
              const isDistinct = !!countMatch[1];
              const colName = countMatch[2].trim();
              if (colName === '*') aggRow[col.alias] = grpRows.length;
              else {
                const c = colName.split('.').pop();
                if (isDistinct) {
                  aggRow[col.alias] = new Set(grpRows.map(r => r[c]).filter(v => v != null)).size;
                } else {
                  aggRow[col.alias] = grpRows.filter(r => r[c] != null).length;
                }
              }
              continue;
            }
            // strftime('%H', col) - 按小时
            const strfMatch = expr.match(/strftime\s*\(\s*'([^']+)'\s*,\s*["`]?([\w.]+)["`]?\s*\)/i);
            if (strfMatch) {
              const fmt = strfMatch[1];
              const c = strfMatch[2].split('.').pop();
              const date = new Date(grpRows[0][c]);
              if (fmt === '%H') aggRow[col.alias] = date.getHours();
              else if (fmt === '%Y-%m-%d') aggRow[col.alias] = date.toISOString().split('T')[0];
              continue;
            }
            // 普通列（取第一个）
            const c = expr.split('.').pop();
            aggRow[col.alias] = grpRows[0][c];
          }
        }
        result.push(aggRow);
      }
      return result;
    }

    return rows;
  }

  run(...params) {
    // 展平参数（better-sqlite3 支持 run([...params]) 这种数组参数）
    if (params.length === 1 && Array.isArray(params[0])) params = params[0];

    if (this.type === 'insert') {
      const values = this._replaceParams(this.valuesTemplate, params);
      const row = { id: ++this.db.autoIncrements[this.table] };
      for (let i = 0; i < this.columns.length; i++) {
        row[this.columns[i]] = values[i];
      }
      if (!this.db.tables[this.table]) this.db.tables[this.table] = [];
      this.db.tables[this.table].push(row);
      this.db._save();
      return { lastInsertRowid: row.id, changes: 1 };
    }

    if (this.type === 'update') {
      const rows = this._collectBaseRows(params);
      for (const row of rows) {
        for (const clause of this.setClauses || []) {
          let val = clause.valueTemplate;
          if (val === '?') val = params.shift();
          else if (/^CURRENT_TIMESTAMP$/i.test(val)) val = new Date().toISOString();
          else if (/^['"].*['"]$/.test(val)) val = val.slice(1, -1);
          else if (/^-?\d+(\.\d+)?$/.test(val)) val = Number(val);
          else if (/^CASE\s+WHEN/i.test(val)) {
            // CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END
            const caseMatch = val.match(/CASE\s+WHEN\s+\?\s+THEN\s+(.+?)\s+ELSE\s+(.+?)\s+END/i);
            if (caseMatch) {
              const cond = params.shift();
              if (cond) {
                const t = caseMatch[1].trim();
                if (/CURRENT_TIMESTAMP/i.test(t)) val = new Date().toISOString();
                else val = t === 'NULL' ? null : t;
              } else {
                val = caseMatch[2].trim() === 'NULL' ? null : caseMatch[2].trim();
              }
            }
          }
          row[clause.column] = val;
        }
      }
      this.db._save();
      return { lastInsertRowid: undefined, changes: rows.length };
    }

    if (this.type === 'delete') {
      const filter = this._parseWhereCondition(this.whereClause, params);
      const table = this.db.tables[this.table] || [];
      const before = table.length;
      this.db.tables[this.table] = table.filter(r => !filter(r));
      const changes = before - this.db.tables[this.table].length;
      this.db._save();
      return { lastInsertRowid: undefined, changes };
    }

    return { lastInsertRowid: undefined, changes: 0 };
  }

  get(...params) {
    if (params.length === 1 && Array.isArray(params[0])) params = params[0];
    let rows = this._collectBaseRows(params);
    rows = this._applyAggregates(rows, params);
    if (this.orderBy && !this.groupBy) {
      rows.sort((a, b) => {
        for (const o of this.orderBy) {
          let av = a[o.column], bv = b[o.column];
          if (typeof av === 'number' && typeof bv === 'number') {
            const d = av - bv; if (d !== 0) return o.direction === 'ASC' ? d : -d;
          }
          av = String(av); bv = String(bv);
          const d = av.localeCompare(bv); if (d !== 0) return o.direction === 'ASC' ? d : -d;
        }
        return 0;
      });
    }
    if (this.orderBy && this.groupBy) {
      // 对group by结果排序
      rows.sort((a, b) => {
        for (const o of this.orderBy) {
          const col = o.column;
          if (col.includes('.')) continue;
          let av = a[col], bv = b[col];
          if (typeof av === 'number' && typeof bv === 'number') {
            const d = av - bv; if (d !== 0) return o.direction === 'ASC' ? d : -d;
          }
          av = String(av ?? ''); bv = String(bv ?? '');
          const d = av.localeCompare(bv); if (d !== 0) return o.direction === 'ASC' ? d : -d;
        }
        return 0;
      });
    }
    if (this.limit) {
      rows = rows.slice(this.limit.offset, this.limit.offset + this.limit.count);
    }
    return rows[0];
  }

  all(...params) {
    if (params.length === 1 && Array.isArray(params[0])) params = params[0];
    let rows = this._collectBaseRows(params);
    rows = this._applyAggregates(rows, params);
    if (this.orderBy) {
      rows.sort((a, b) => {
        for (const o of this.orderBy) {
          let av = a[o.column], bv = b[o.column];
          if (typeof av === 'number' && typeof bv === 'number') {
            const d = av - bv; if (d !== 0) return o.direction === 'ASC' ? d : -d;
          }
          av = String(av ?? ''); bv = String(bv ?? '');
          const d = av.localeCompare(bv); if (d !== 0) return o.direction === 'ASC' ? d : -d;
        }
        return 0;
      });
    }
    if (this.limit) {
      rows = rows.slice(this.limit.offset, this.limit.offset + this.limit.count);
    }
    return rows;
  }
}

// ==================== 导出实例（与原 better-sqlite3 模块兼容） ====================
// 优先用 RENDER_DATA_DIR（Render 免费版持久化目录），否则本地 ./couple_tracker.json
const fs2 = require('fs');
const os = require('os');
let dbDir;
const renderDataDir = process.env.RENDER_DATA_DIR || '/var/data';
if (process.env.RENDER === 'true' && fs2.existsSync(renderDataDir)) {
  dbDir = renderDataDir;
  console.log('☁️ 云端部署 detected，数据库放在持久化目录:', dbDir);
} else {
  dbDir = __dirname;
}
const dbPath = path.join(dbDir, 'couple_tracker.json');
const db = new JsonDatabase(dbPath);

// 初始化表（与 better-sqlite3 版本保持一致）
db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    password TEXT NOT NULL,
    nickname TEXT NOT NULL,
    avatar TEXT DEFAULT '',
    gender TEXT DEFAULT 'unknown',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
  );
  CREATE TABLE IF NOT EXISTS couples (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user1_id INTEGER NOT NULL,
    user2_id INTEGER NOT NULL,
    status TEXT DEFAULT 'pending',
    paired_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
  );
  CREATE TABLE IF NOT EXISTS locations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    address TEXT DEFAULT '',
    accuracy REAL DEFAULT 0,
    is_moving INTEGER DEFAULT 0,
    speed REAL DEFAULT 0,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
  );
  CREATE TABLE IF NOT EXISTS tracks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME,
    start_latitude REAL,
    start_longitude REAL,
    end_latitude REAL,
    end_longitude REAL,
    distance REAL DEFAULT 0,
    total_points INTEGER DEFAULT 0
  );
  CREATE TABLE IF NOT EXISTS track_points (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    track_id INTEGER NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
  );
  CREATE TABLE IF NOT EXISTS app_usage (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    package_name TEXT NOT NULL,
    app_name TEXT NOT NULL,
    app_category TEXT DEFAULT 'other',
    start_time DATETIME NOT NULL,
    end_time DATETIME,
    duration_seconds INTEGER DEFAULT 0,
    is_foreground INTEGER DEFAULT 1
  );
  CREATE TABLE IF NOT EXISTS daily_stats (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    date TEXT NOT NULL,
    total_screen_time INTEGER DEFAULT 0,
    app_launch_count INTEGER DEFAULT 0
  );
`);

// 初始化测试用户
function initTestUsers() {
  const users = [
    { username: 'xiaoming', password: '123456', nickname: '小明', gender: 'male', avatar: '👨' },
    { username: 'xiaohong', password: '123456', nickname: '小红', gender: 'female', avatar: '👩' }
  ];

  const insertUser = db.prepare(`
    INSERT OR IGNORE INTO users (username, password, nickname, avatar, gender)
    VALUES (?, ?, ?, ?, ?)
  `);
  // 注：我们的轻量数据库不支持 INSERT OR IGNORE，所以先检查
  const findUser = db.prepare('SELECT id FROM users WHERE username = ?');
  const getUserId = (n) => findUser.get(n)?.id;

  users.forEach(u => {
    if (!getUserId(u.username)) {
      const hashedPwd = bcrypt.hashSync(u.password, 10);
      db.prepare(`INSERT INTO users (username, password, nickname, avatar, gender) VALUES (?, ?, ?, ?, ?)`)
        .run(u.username, hashedPwd, u.nickname, u.avatar, u.gender);
    }
  });

  const u1 = getUserId('xiaoming');
  const u2 = getUserId('xiaohong');

  if (u1 && u2) {
    const couple = db.prepare(`
      SELECT id FROM couples 
      WHERE (user1_id = ? AND user2_id = ?) OR (user1_id = ? AND user2_id = ?)
    `).get(u1, u2, u2, u1);
    if (!couple) {
      db.prepare(`INSERT INTO couples (user1_id, user2_id, status, paired_at) VALUES (?, ?, 'accepted', ?)`)
        .run(u1, u2, new Date().toISOString());
      console.log('✅ 已创建测试情侣配对：小明 ❤️ 小红');
    }
  }

  console.log('✅ 测试用户已创建：xiaoming/123456, xiaohong/123456');
}

initTestUsers();

module.exports = db;
