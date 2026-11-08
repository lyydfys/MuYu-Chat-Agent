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
| `/opt/mca-site/html/` | 站点文件（从本仓库构建）|
| `/opt/mca-site/nginx.conf` | nginx 配置（本目录 `nginx.conf` 的副本）|
| `/opt/mca-site/docker-compose.yml` | compose（本目录同名文件的副本）|
| `/opt/mac-api/Caddyfile` | Caddy 配置，含 `mca.mac-api.xyz` 块 |

## 发布流程

在本仓库执行：

```bash
# 1. 改完站点后重新生成压缩 CSS
cd docs/website && node scripts/minify-css.mjs && node scripts/check-site.mjs

# 2. 构建最小部署包（只含浏览器会请求的文件，排除 assets/device、preview、retired）
python .tmp/build-bundle.py        # 或手工 tar

# 3. 上传并切换
scp -i ~/.ssh/mac_vps_ed25519 mca-site.tar root@43.108.89.68:/tmp/
ssh -i ~/.ssh/mac_vps_ed25519 root@43.108.89.68 \
  'cd /opt/mca-site && tar -xf /tmp/mca-site.tar -C html'

# 4. 验证
ssh -i ~/.ssh/mac_vps_ed25519 root@43.108.89.68 'bash /opt/mca-site/check.sh'
```

nginx 无需重启：`html/` 是只读挂载的目录，换文件即生效。

## 首次部署（本目录文件的作用）

| 文件 | 用途 |
|---|---|
| `docker-compose.yml` | 建容器 |
| `nginx.conf` | nginx 站点配置 |
| `caddy-mca.snippet` | 追加进 `/opt/mac-api/Caddyfile` 的站点块 |
| `caddy-apply.sh` | 幂等追加 + 备份 + `caddy validate` 失败自动回滚 + 只 reload |
| `check.sh` | 端点自检：状态码、Content-Type、缓存头、404 |

```bash
# 服务器上
cd /opt/mca-site
docker compose up -d
bash caddy-apply.sh
bash check.sh
```

## 两个已踩过的坑（改配置前必读）

### 1. 单独写 `types {}` 会清空 MIME 表

```nginx
types { application/manifest+json webmanifest; }   # ✗ 替换整张表
```

这会让**所有**文件变成 `application/octet-stream`，浏览器拒认 HTML/CSS/JS。
必须先 `include mime.types;` 再补单条：

```nginx
include mime.types;
types { application/manifest+json webmanifest; }   # ✓
```

### 2. `try_files ... /404.html` 会把缺失页返回 200

```nginx
try_files $uri $uri/ /404.html;   # ✗ 缺失路径返回 200
try_files $uri $uri/ =404;        # ✓ 真 404，再由 error_page 渲染
```

同理，`location = /404.html { internal; }` 会让直接访问 `/404.html` 返回 404；
错误页应能被直接访问。

### 3. 从 Windows 上传配置必须去掉 BOM

PowerShell `Out-File -Encoding utf8` 会写入 UTF-8 BOM，nginx 会报
`unknown directive "﻿#"`。用 Python 去 BOM 并把 CRLF 转 LF 后再上传。

## 证书

Caddy 自动签发 Let's Encrypt 证书（HTTP-01）。要求 `mca.mac-api.xyz` 的 A 记录先指向
`43.108.89.68`，否则签发会失败。DNS 生效后无需人工操作，Caddy 自动完成。
