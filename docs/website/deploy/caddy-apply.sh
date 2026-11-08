#!/usr/bin/env bash
# 把 mca.mac-api.xyz 站点块接进现有 Caddyfile，并安全重载 Caddy。
#
# 幂等：已存在就跳过
# 改前备份到 Caddyfile.bak.<时间戳>
# caddy validate 失败自动回滚
# 只 reload，不 restart（不中断现有 mac-api.xyz / aether 连接）

set -euo pipefail

CADDYFILE=/opt/mac-api/Caddyfile
SNIPPET=/opt/mca-site/caddy-mca.snippet
DOMAIN=mca.mac-api.xyz

[ -f "$CADDYFILE" ] || { echo "!! 找不到 $CADDYFILE"; exit 1; }
[ -f "$SNIPPET" ]   || { echo "!! 找不到 $SNIPPET"; exit 1; }

if grep -q "$DOMAIN" "$CADDYFILE"; then
  echo "== $DOMAIN 已存在，跳过修改"
else
  TS=$(date +%Y%m%d%H%M%S)
  cp -a "$CADDYFILE" "$CADDYFILE.bak.$TS"
  echo "== 已备份 -> Caddyfile.bak.$TS"
  {
    echo ""
    cat "$SNIPPET"
  } >> "$CADDYFILE"
  echo "== 已追加站点块"

  if ! docker exec caddy caddy validate --config /etc/caddy/Caddyfile >/tmp/caddy-validate.log 2>&1; then
    echo "!! 配置校验失败，回滚"
    tail -20 /tmp/caddy-validate.log
    cp -a "$CADDYFILE.bak.$TS" "$CADDYFILE"
    echo "== 已回滚"
    exit 1
  fi
  echo "== 校验通过"
fi

docker exec caddy caddy reload --config /etc/caddy/Caddyfile
echo "== Caddy 已重载"
