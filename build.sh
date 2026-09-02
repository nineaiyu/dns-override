#!/usr/bin/env bash
# DNS Override 构建脚本
#
# 用法：
#   ./build.sh              # 构建 release APK（默认用 debug keystore 签名，仅本地验证）
#   ./build.sh debug        # 构建 debug APK
#   ./build.sh release      # 构建 release APK
#   ./build.sh keystore     # 生成专用 keystore（口令随机生成并写入 keystore.properties）
#   ./build.sh install      # 构建并安装到已连接设备
#   ./build.sh test         # 仅运行单元测试
#   ./build.sh lint         # Android Lint
#   ./build.sh clean        # 清理构建产物
set -euo pipefail

cd "$(dirname "$0")"

ACTION="${1:-release}"
PROJECT_DIR="$(pwd)"
KEYSTORE_FILE="$PROJECT_DIR/release.keystore"
KEYSTORE_PROPS="$PROJECT_DIR/keystore.properties"
APK_DIR="app/build/outputs/apk"

print_help() {
    cat <<EOF
DNS Override 构建脚本

用法:
  ./build.sh              构建 release APK（默认用 debug keystore 签名）
  ./build.sh debug        构建 debug APK
  ./build.sh release      构建 release APK
  ./build.sh keystore     生成专用 keystore（随机口令，写入 keystore.properties）
  ./build.sh install      构建并安装到已连接设备
  ./build.sh test         仅运行单元测试
  ./build.sh lint         Android Lint
  ./build.sh clean        清理构建产物

首次发布建议:
  ./build.sh keystore     # 生成专用 keystore
  ./build.sh release      # 用该 keystore 签名构建

注意:
  release.keystore 与 keystore.properties 均已加入 .gitignore，切勿提交。
  忘记 keystore 口令将无法发布同包名更新，请务必离线备份。
EOF
}

ensure_gradle_wrapper() {
    if [ ! -f "./gradlew" ]; then
        echo "错误：未找到 gradlew，请在项目根目录执行"
        exit 1
    fi
    chmod +x ./gradlew
}

# 生成 release keystore。口令使用 CSPRNG 随机生成，**不硬编码**，
# 并写入 keystore.properties（已 gitignore）。
generate_release_keystore() {
    if [ -f "$KEYSTORE_FILE" ]; then
        echo "已存在 release.keystore，跳过生成"
        return
    fi
    if ! command -v keytool >/dev/null 2>&1; then
        echo "错误：未找到 keytool，请先安装 JDK 并配置 JAVA_HOME"
        exit 1
    fi

    local store_pass key_pass
    store_pass="$(LC_ALL=C tr -dc 'A-Za-z0-9!@#%^&*_-' </dev/urandom | head -c 24)"
    key_pass="$(LC_ALL=C tr -dc 'A-Za-z0-9!@#%^&*_-' </dev/urandom | head -c 24)"

    echo "生成专用 release keystore..."
    keytool -genkey -v \
        -keystore "$KEYSTORE_FILE" \
        -alias dns_override \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10950 \
        -storetype PKCS12 \
        -storepass "$store_pass" \
        -keypass "$key_pass" \
        -dname "CN=DNS Override, OU=Dev, O=DNSOverride, L=NA, ST=NA, C=CN"

    umask 077
    cat > "$KEYSTORE_PROPS" <<EOF
DNSOVERRIDE_STORE_FILE=release.keystore
DNSOVERRIDE_STORE_PASSWORD=$store_pass
DNSOVERRIDE_KEY_ALIAS=dns_override
DNSOVERRIDE_KEY_PASSWORD=$key_pass
EOF
    chmod 600 "$KEYSTORE_PROPS"

    echo "已生成 release.keystore 与 keystore.properties（权限 600）"
    echo "警告：请立即离线备份这两个文件，丢失后无法发布同包名更新。"
}

build_debug() {
    ensure_gradle_wrapper
    echo "构建 debug APK..."
    ./gradlew assembleDebug
    echo "完成：$APK_DIR/debug/app-debug.apk"
}

build_release() {
    ensure_gradle_wrapper
    echo "构建 release APK..."
    ./gradlew assembleRelease
    echo "完成：$APK_DIR/release/app-release.apk"
}

build_and_install() {
    ensure_gradle_wrapper
    echo "构建并安装到设备..."
    ./gradlew installDebug
    echo "已安装 debug 版本到设备"
}

run_tests() {
    ensure_gradle_wrapper
    echo "运行单元测试..."
    ./gradlew testDebugUnitTest
    echo "测试通过"
}

run_lint() {
    ensure_gradle_wrapper
    echo "运行 Android Lint..."
    ./gradlew lintDebug
    echo "完成：app/build/reports/lint-results-debug.html"
}

clean_all() {
    ensure_gradle_wrapper
    ./gradlew clean
    echo "已清理构建产物"
}

case "$ACTION" in
    debug) build_debug ;;
    release) build_release ;;
    keystore) generate_release_keystore ;;
    install) build_and_install ;;
    test) run_tests ;;
    lint) run_lint ;;
    clean) clean_all ;;
    help|-h|--help) print_help ;;
    *)
        echo "未知命令：$ACTION"
        echo ""
        print_help
        exit 1
        ;;
esac
