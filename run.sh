#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# 只为本脚本选择 JDK 25，不修改终端或其他项目的默认 Java。
demo_java_home="${DEMO_JAVA_HOME:-/Users/rwr/tools/codex-demo-jdk25/Contents/Home}"
if [[ ! -x "$demo_java_home/bin/java" ]]; then
  echo "没有找到 JDK 25，请通过 DEMO_JAVA_HOME 指定安装目录。" >&2
  exit 1
fi
export JAVA_HOME="$demo_java_home"
export PATH="$JAVA_HOME/bin:$PATH"
if [[ "$(java -XshowSettings:properties -version 2>&1)" != *"java.specification.version = 25"* ]]; then
  echo "本示例要求 JDK 25，请检查 DEMO_JAVA_HOME。" >&2
  exit 1
fi

# 支持 ./run.sh package 打包；不带参数时直接启动服务。
if [[ $# -eq 0 ]]; then
  exec mvn -s .mvn/settings.xml spring-boot:run
else
  exec mvn -s .mvn/settings.xml "$@"
fi
