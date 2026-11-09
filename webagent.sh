#!/system/bin/sh
# webagent — một lệnh cho agent-browser chạy trong APK Agent Bridge.
#
#   sh drafts/agent-bridge/webagent.sh open https://example.com
#   sh drafts/agent-bridge/webagent.sh get url
#   sh drafts/agent-bridge/webagent.sh click "a"
#   sh drafts/agent-bridge/webagent.sh read
#   sh drafts/agent-bridge/webagent.sh start      # bật service
#   sh drafts/agent-bridge/webagent.sh restart    # force-stop + start
#   sh drafts/agent-bridge/webagent.sh ping       # kiểm tra sống
#   sh drafts/agent-bridge/webagent.sh reset      # dựng lại WebView + daemon
#
# Không cần adb. Cần quyền shell cho start/restart (Shizuku hoặc Wireless ADB).

DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
TOKEN_FILE=/storage/emulated/0/HARNESS/.harness/bridge-token.txt
PKG=com.harness.agentbridge
SVC=$PKG/.BridgeService

token() {
    c=$(cat "$TOKEN_FILE" 2>/dev/null | tr -d '\r\n')
    if [ -z "$c" ]; then
        c=$(logcat -d -s AgentBridge:I 2>/dev/null | grep -o 'AB-TOKEN: [0-9a-f]*' | tail -1 | awk '{print $2}')
    fi
    printf '%s' "$c"
}

start_svc() {
    am start-foreground-service -n "$SVC" >/dev/null 2>&1
    n=0
    while [ $n -lt 20 ]; do
        sleep 1
        if [ -n "$(token)" ]; then return 0; fi
        n=$((n + 1))
    done
    return 0
}

cmd=$1
[ -n "$cmd" ] || { sed -n '2,16p' "$0"; exit 1; }
shift

case "$cmd" in
    start)   start_svc; echo "started"; exit 0 ;;
    restart) am force-stop "$PKG" >/dev/null 2>&1; sleep 1; start_svc; echo "restarted"; exit 0 ;;
    ping)    python3 "$DIR/bridge.py" "$(token)" __ping; exit $? ;;
    reset)   python3 "$DIR/bridge.py" "$(token)" __reset; exit $? ;;
esac

T=$(token)
if [ -z "$T" ]; then
    start_svc
    T=$(token)
fi
if [ -z "$T" ]; then
    echo "no token: bật service trước (sh $0 start) hoặc cài lại APK" >&2
    exit 2
fi

exec python3 "$DIR/bridge.py" "$T" "$cmd" "$@"
