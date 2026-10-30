#!/bin/bash
# Box (TVBox) 构建脚本
#
# 关键点：本项目必须用 JDK 17 构建。
# 原因：依赖 com.github.anilbeesetti:nextlib-media3ext:0.7.1 是用 Java 17 编译的
#      （class 文件版本 61），用 JDK 11 运行 kapt 会报：
#      "类文件具有错误的版本 61.0, 应为 55.0"
#
# 用法：
#   ./build.sh                 # 构建 arm64 Generic Normal Debug
#   ./build.sh assembleRelease # 构建全部 flavor 的 release
#   ./clean.sh                 # 清理

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# ---- 环境变量 ----
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk/jdk-17.0.20.1+1/Contents/Home}"

if [ ! -d "$JAVA_HOME" ]; then
  echo "❌ 找不到 JDK 17：$JAVA_HOME"
  echo "   请安装 Temurin 17 并设置 JAVA_HOME，或安装 Android Studio 后用其内置 JBR。"
  exit 1
fi

echo "JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
echo "ANDROID_HOME=$ANDROID_HOME"

# local.properties 不在版本控制里，首次构建自动生成
if [ ! -f local.properties ]; then
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  echo "已生成 local.properties"
fi

# gradlew 需要执行权限
chmod +x gradlew 2>/dev/null || true

TASK="${1:-:app:assembleArm64GenericNormalDebug}"

echo ""
echo "▶ 执行构建任务: $TASK"
echo ""

./gradlew "$TASK" "$@"

echo ""
echo "✅ 构建完成"
find app/build/outputs/apk -name "*.apk" -exec ls -lh {} \; 2>/dev/null || true