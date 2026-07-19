#!/bin/sh

set -eu

service_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
maven_bin=${MAVEN_BIN:-/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn}

: "${RISK_WARNING_BACKFILL_END_DATE:?必须设置回填结束交易日}"
: "${RISK_WARNING_BACKFILL_CONFIRMATION:?必须显式设置确认令牌}"
: "${RISK_WARNING_DERIVED_GATEWAY_BASE_URL:?必须配置规范化历史衍生网关}"

case "${RISK_WARNING_BACKFILL_MODE:-staged}" in
  sample|staged|full)
    ;;
  *)
    echo "RISK_WARNING_BACKFILL_MODE 仅支持 sample、staged 或 full" >&2
    exit 2
    ;;
esac

if [ ! -x "$maven_bin" ]; then
  echo "找不到可执行 Maven：$maven_bin" >&2
  exit 2
fi

export RISK_WARNING_ENABLED=true
export RISK_WARNING_BACKFILL_ENABLED=true
export RISK_WARNING_BACKFILL_COMMAND_ENABLED=true
export RISK_WARNING_BACKFILL_MODE=${RISK_WARNING_BACKFILL_MODE:-staged}

cd "$service_dir"
exec "$maven_bin" -DskipTests spring-boot:run \
  -Dspring-boot.run.main-class=com.jx.tracker.risk.backfill.RiskBackfillCommandApplication \
  -Dspring-boot.run.arguments=--spring.main.web-application-type=none
