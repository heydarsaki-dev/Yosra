#!/data/data/com.termux/files/usr/bin/bash
# نصب بی‌صدای آخرین APK یسرا از طریق ADB وایرلس
APK=$(ls -t "$(dirname "$0")"/Yosra-v*.apk 2>/dev/null | head -1)
[ -z "$APK" ] && { echo "❌ هیچ APKای پیدا نشد — اول ./build.sh را اجرا کن"; exit 1; }

PORT_FILE="$(dirname "$0")/.adb_port"
PORT=$(cat "$PORT_FILE" 2>/dev/null || echo 41737)

adb connect "127.0.0.1:$PORT" >/dev/null 2>&1
ST=$(adb -s "127.0.0.1:$PORT" get-state 2>/dev/null)

if [ "$ST" != "device" ]; then
    echo "🔎 پورت عوض شده — اسکن می‌کنم..."
    PORTS=$(python3 - <<'EOF'
import socket, concurrent.futures
def probe(p):
    s = socket.socket(); s.settimeout(0.15)
    try:
        s.connect(("127.0.0.1", p)); s.close(); return p
    except Exception:
        return None
skip = {4096, 5037, 10808}
out = []
with concurrent.futures.ThreadPoolExecutor(max_workers=500) as ex:
    for r in ex.map(probe, range(1024, 65536)):
        if r and r not in skip:
            out.append(r)
print(" ".join(map(str, out)))
EOF
)
    for p in $PORTS; do
        adb connect "127.0.0.1:$p" >/dev/null 2>&1
        ST=$(adb -s "127.0.0.1:$p" get-state 2>/dev/null)
        if [ "$ST" = "device" ]; then
            PORT=$p
            echo "$p" > "$PORT_FILE"
            break
        fi
    done
fi

if [ "$ST" = "device" ]; then
    adb -s "127.0.0.1:$PORT" install -r "$APK" \
        && echo "✅ نصب شد: $(basename "$APK")" \
        || echo "❌ نصب ناموفق"
else
    echo "❌ وصل نشد — «اشکال‌زدایی بی‌سیم» روشن است؟"
fi
