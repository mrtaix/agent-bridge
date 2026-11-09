# Agent Bridge — trình duyệt ẩn + agent-browser cho Android

Một APK nhỏ chạy nền trên điện thoại, cho phép **agent tự điều khiển trình duyệt** mà
không cần Chrome, không cần adb, không cần root.

Bên trong APK có:

- **WebView ẩn** (Chromium engine của Android System WebView) — không hiện cửa sổ.
- **Cổng CDP** `127.0.0.1:9222` — Chrome DevTools Protocol của WebView đó.
- **Cổng lệnh** `127.0.0.1:9224` — chạy CLI [agent-browser](https://agent-browser.dev)
  (Apache-2.0) dưới UID app, có token.

## Vì sao phải chạy trong app

`agent-browser` cần tạo unix socket cho daemon. Android chặn `shell`/`adb` tạo
`sock_file` (SELinux), còn Chrome trên Android không mở cổng DevTools trừ khi có adb
hoặc app debuggable. App trong repo này `targetSdk 28` nên chạy ở domain
`untrusted_app_27`: được exec file trong data dir và được tạo socket. Nhờ vậy toàn bộ
đường đi nằm gọn trong tiến trình app.

## Yêu cầu

- Android 8.0 trở lên, ARM64, có Android System WebView (mặc định có sẵn).
- Không cần root, không cần Chrome, không cần bật gỡ lỗi USB.

## Cài đặt

### Cách 1 — dùng APK có sẵn (nhanh nhất)

1. Tải `com.harness.agentbridge.apk` ở mục **Releases**.
2. Mở file để cài (cho phép "Cài đặt ứng dụng không rõ nguồn gốc" nếu máy hỏi).
3. Mở app **Agent Bridge** → bấm **Start bridge**. App sẽ hiện `status`, `cdp`, `cmd`
   và **token**.
4. Bấm Home — bridge vẫn chạy nền (foreground service).

Hoặc cài bằng dòng lệnh nếu có shell:

```sh
pm install -r com.harness.agentbridge.apk
am start-foreground-service -n com.harness.agentbridge/.BridgeService
```

### Cách 2 — tự build từ mã nguồn

Cần JDK 17+, Android SDK (aapt2, d8, apksigner) — trên máy Android có thể dùng
Termux/PRoot với `openjdk-17`, `aapt2`, `d8`, `apksigner`.

```sh
sh fetch-agent-browser.sh      # tải binary agent-browser từ npm vào assets/
sh build.sh                    # ra build/com.harness.agentbridge.apk
pm install -r -g build/com.harness.agentbridge.apk
```

`build.sh` không dùng Gradle: `aapt2` → `javac` → `d8` → `apksigner`, ký bằng
debug keystore (`~/.android/debug.keystore`, tự tạo nếu chưa có).

## Lấy token

Token 32 ký tự hex, in ra ở màn hình app, ở logcat, và ghi ra
`/storage/emulated/0/HARNESS/.harness/bridge-token.txt` nếu app có quyền ghi bộ nhớ.

```sh
logcat -d -s AgentBridge:I | grep -o 'AB-TOKEN: [0-9a-f]*' | tail -1
cat /storage/emulated/0/HARNESS/.harness/bridge-token.txt
```

## Dùng

Gọi thẳng cổng lệnh bằng client Python (`bridge.py`, chỉ dùng thư viện chuẩn):

```sh
python3 bridge.py <token> open https://github.com
python3 bridge.py <token> get title
python3 bridge.py <token> read
python3 bridge.py <token> click "a"
python3 bridge.py --token-from-logcat get url     # tự lấy token từ logcat
```

Hoặc dùng wrapper `webagent.sh` (tự bật service, tự lấy token):

```sh
W=./webagent.sh
sh $W start
sh $W open https://github.com
sh $W get title
sh $W read
sh $W ping          # kiểm tra sống
sh $W reset         # dựng lại WebView + daemon
sh $W restart       # force-stop rồi start
```

Mọi subcommand khác của agent-browser đi qua nguyên vẹn: `snapshot`, `type`, `scroll`,
`find`, `eval`… Xem `sh $W --help`.

Giao thức cổng 9224: một dòng JSON gửi lên, một dòng JSON trả về.

```
→ {"token":"…","args":["open","https://example.com"]}
← {"code":0,"out":"✓ Example Domain\n  https://example.com/","err":""}
```

Lệnh phụ của app: `__ping` (kiểm tra), `__reset` (dựng lại WebView + daemon).

## Đã đo

| Việc | Kết quả |
|---|---|
| `agent-browser --version` | `0.41.2` |
| `open https://github.com` | ✓ 3–16s, `read` ra đủ nội dung trang |
| `open` youtube / facebook / vnexpress | 7s / 5s / 9s |
| `click "a"` | điều hướng đúng |
| URL chết `http://10.255.255.1` | fail sau ~34s, tự hồi, lệnh sau vẫn chạy |
| `screenshot` | ✗ `Page.captureScreenshot` timeout |

## Chống treo

- Lệnh quá 45s → app gọi `webView.stopLoading()` để cắt trang đang tải.
- Lỗi transport (`Failed to read`, `os error`, `timed out`) hoặc quá 120s →
  `pkill -f agent-browser` rồi `connect` lại, các lệnh khác xếp hàng qua một lock.
- Watchdog 30s gọi `/json/version`; 3 lần trượt liên tiếp thì dựng lại WebView.
- Nên thêm app vào device-idle whitelist:
  `cmd deviceidle whitelist +com.harness.agentbridge`.

## Giới hạn

- **Không chụp screenshot được**: WebView ẩn không có surface nên Chromium không tổng
  hợp frame. Cần ảnh thì dùng Chrome thật qua adb, hoặc một công cụ chụp màn hình.
- Viewport cố định 1080×1920, không theo màn hình thật.
- Không tải file, không mở popup hệ thống, không dùng extension.
- Cổng 9224 nằm trên loopback: app khác có quyền INTERNET đều kết nối được, nên có
  token. Xoá dữ liệu app để đổi token.
- Không có session/cookie của Chrome — đăng nhập phải làm trong chính WebView ẩn.

## Bảo mật

- Bridge chỉ nghe trên loopback, mọi lệnh cần token.
- Đừng commit token hay thư mục `.harness/`.
- WebView chạy không sandbox riêng của trình duyệt: chỉ mở trang bạn tin.

## Giấy phép

Mã nguồn trong repo: MIT. Binary `agent-browser` tải từ npm: Apache-2.0
(xem https://agent-browser.dev).
