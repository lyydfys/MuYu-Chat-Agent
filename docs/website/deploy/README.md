# MCA 官网部署

线上地址：**https://mca.mac-api.xyz**

## 拓扑

```
浏览器 ──HTTPS──> Caddy (mac-api 服务器, TLS/HTTP3/zstd+gzip)
                    └─ reverse_proxy mca-site:80
                         └─ nginx:1.25-alpine (只读挂载 /opt/mca-site/html)
```

沿用服务器上 `aether-site` 的既有模式：不映射宿主机端口、接入已有 `mac-api_mac-net` 内网、
内存上限 64M、`read_only` + tmpfs。

## 服务器上的路径

| 路径 | 内容 |
|---|---|
| `/opt/mca-site/html/` | 站点文件（从本仓库部署）|
| `/opt/mca-site/html/downloads/` | APK 与 `manifest.json` |
| `/opt/mca-site/html/data/` | `release.json`、`releases.json` |
| `/opt/mca-site/sync-release.sh` | 发布同步脚本（本目录同名文件的副本）|
| `/opt/mca-site/nginx.conf` | nginx 配置 |
| `/opt/mca-site/docker-compose.yml` | compose |
| `/opt/mac-api/Caddyfile` | Caddy 配置，含 `mca.mac-api.xyz` 块 |

---

## 发布新版本：现在自动同步

`/etc/cron.d/mca-sync` 每 6 小时跑一次 `/opt/mca-site/sync-release.sh`：

```
:17 */6 * * * root /opt/mca-site/sync-release.sh >> /var/log/mca-sync.log 2>&1
```

它做的事：

1. 查 GitHub API 最新的已发布（非 draft）release
2. 与 `downloads/manifest.json` 里的版本比较，相同就退出
3. 下载新 APK 到 `.part`
4. **校验字节数 + GitHub 公布的 sha256**（任一不符就中止，旧版继续服务）
5. 原子改名
6. 用 `<!--rel:field-->` 标记重写 `index.html` 的版本字段
7. 用模式重写 4 处 `/downloads/MCA-v*.apk` URL
8. 重新生成 `data/release.json`、`data/releases.json`、`manifest.json`

手动操作：

```bash
/opt/mca-site/sync-release.sh          # 正常同步
DRY=1 /opt/mca-site/sync-release.sh    # 只看会做什么
FORCE=1 /opt/mca-site/sync-release.sh  # 强制重新钉版
tail -f /var/log/mca-sync.log
```

**为什么是拉取而不是 GitHub Actions**：这样 GitHub 不持有服务器 SSH 私钥，也不暴露公网写接口。
代价是最长 6 小时延迟，对官网足够。

---

## 部署站点改动（非版本更新）

```bash
cd docs/website
node scripts/minify-css.mjs && node scripts/check-site.mjs

python .tmp/build-bundle.py        # 生成 .dsh-tmp/deploy/（含 data/，排除素材）

cd .dsh-tmp && tar -cf mca-site.tar -C deploy .
scp -i ~/.ssh/mac_vps_ed25519 mca-site.tar root@43.108.89.68:/tmp/
ssh -i ~/.ssh/mac_vps_ed25519 root@43.108.89.68 \
  'cd /opt/mca-site && tar -xf /tmp/mca-site.tar -C html && bash check.sh'
```

### ⚠️ 部署前必读：版本号会被覆盖

**服务器是发布事实（版本号、大小、SHA-256、APK URL）的权威来源**，因为这些字段由
`sync-release.sh` 在服务器上原地改写。仓库里的 `index.html` 会因此落后于线上。

如果直接上传本地 `index.html`，就会把线上已经同步的新版本**覆盖回旧版本**——这个坑已经踩过：
一次部署把 v0.4.3 覆盖成了 v0.4.1。

**部署前先把发布状态拉回本地：**

```bash
scp root@43.108.89.68:/opt/mca-site/html/index.html        docs/website/index.html
scp root@43.108.89.68:/opt/mca-site/html/data/*.json      docs/website/data/
scp root@43.108.89.68:/opt/mca-site/html/downloads/manifest.json docs/website/data/
```

或使用服务器上的 `deploy/pull-release-state.sh`。

部署后核对：

```bash
bash /tmp/verify-sync.sh    # 页面字段 / manifest / URL / 更新日志数据一致性
```

---

## 更新日志页

`#changelog` 章节由 `build-changelog-page.py` 从 `data/releases.json` 生成，包裹在
`<!--changelog:begin--> ... <!--changelog:end-->` 之间，可整块替换。

- 最新 4 个版本显示完整说明，更早的收进紧凑索引
- Markdown 在构建时转换（标题、列表、表格、代码块、加粗、链接）
- 表格在自己的盒子里横向滚动，长 token 会断行——否则 320px 会整页横向溢出（已修）
- 数据由 `sync-release.sh` 自动刷新，不会变旧

重新生成：

```bash
python build-changelog-data.py    # 从 GitHub 拉 18 个 release 写到 data/releases.json
python build-changelog-page.py    # 渲染进 index.html
```

---

## 三个已踩过的坑（改配置前必读）

### 1. 单独写 `types {}` 会清空 MIME 表

```nginx
types { application/manifest+json webmanifest; }   # ✗ 替换整张表
```

这会让**所有**文件变成 `application/octet-stream`，浏览器拒认 HTML/CSS/JS。
必须先 `include mime.types;` 再补单条。

### 2. `try_files ... /404.html` 会把缺失页返回 200

```nginx
try_files $uri $uri/ /404.html;   # ✗ 缺失路径返回 200
try_files $uri $uri/ =404;        # ✓ 真 404，再由 error_page 渲染
```

### 3. 从 Windows 上传必须去掉 BOM 并把 CRLF 转 LF

PowerShell `Out-File -Encoding utf8` 写入 UTF-8 BOM，nginx 会报 `unknown directive "﻿#"`，
容器无限重启。用 Python 预处理后再上传。

### 4. `rm -rf html` 后必须重建容器

bind mount 指向被删除的 inode，容器看到空目录，nginx 报 `directory index forbidden`（全站 403）。

```bash
docker compose down && docker compose up -d
```

### 5. 容器 `read_only: true` 时不能新增挂载点

```
mkdir .../usr/share/nginx/html/downloads: read-only file system
```

APK 只能放在 `html/downloads/` 内随站点目录一起挂载。

---

## 证书

Caddy 自动签发 Let's Encrypt 证书（HTTP-01），要求 `mca.mac-api.xyz` 的 A 记录指向
`43.108.89.68`。已生效。
