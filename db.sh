#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

case "${1:-up}" in
  init)
    if [[ -e .env ]]; then
      echo '.env 已存在，保留原配置。'
      exit 0
    fi
    # 仅生成本地配置，不打印密码；noclobber 防止并发覆盖已有凭据。
    umask 077
    demo_db_password="$(openssl rand -hex 24)"
    demo_root_password="$(openssl rand -hex 24)"
    set -o noclobber
    cat > .env <<EOF
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3307
MYSQL_DATABASE=codex_demo
MYSQL_USER=codex_demo
MYSQL_PASSWORD=$demo_db_password
MYSQL_ROOT_PASSWORD=$demo_root_password
EOF
    echo '已创建 .env（随机密码，权限仅当前用户可读写）。'
    ;;
  up)
    docker compose up -d --wait --wait-timeout 180 mysql
    ;;
  stop)
    docker compose stop mysql
    ;;
  status)
    docker compose ps mysql
    ;;
  client)
    docker compose exec mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"'
    ;;
  *)
    echo '用法：./db.sh {init|up|stop|status|client}' >&2
    exit 2
    ;;
esac
