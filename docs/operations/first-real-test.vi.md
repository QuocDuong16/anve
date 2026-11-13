# Chuẩn bị bản kiểm thử thật đầu tiên cho AnVề

Ngày chuẩn bị: 13/11/2026. Owner xác nhận **chưa có API HTTPS và dự án
Google Cloud/Firebase**. Đây là hướng dẫn thiết lập, không phải biên bản đã
triển khai. Chưa tạo tài khoản dịch vụ, bật thanh toán, sinh khóa thật hoặc
phát hành app trong thay đổi này.

## Thứ tự thực hiện

1. Chọn máy chủ/vùng dữ liệu và dựng API HTTPS cùng PostgreSQL/PostGIS.
2. Tạo project Google/Firebase và cấu hình Google Sign-In.
3. Tạo upload key; chuẩn bị app trên Play Console và Play App Signing.
4. Giới hạn Maps/Firebase theo đúng package/chứng thư; điền môi trường release.
5. Kiểm thử bằng tài khoản thử trên bản cài thật; sau đó hoàn thiện hồ sơ phát hành.

Website privacy/xóa tài khoản được chuẩn bị song song. Các URL và chính sách
còn nháp không được mô tả là đã công khai hoặc đã được Google duyệt.

## 1. Máy chủ và API HTTPS

Owner chọn nơi đặt máy chủ, vùng dữ liệu và cách vận hành/backup. Cần một
dịch vụ chạy Go API, PostgreSQL hỗ trợ PostGIS và HTTPS với chứng thư hợp lệ.
Website dự kiến ở `anve.niand.id.vn`; có thể dùng một hostname API riêng,
ví dụ `api.anve.niand.id.vn`, **sau khi owner chọn và cấu hình DNS/TLS**.
Không có bản ghi DNS hoặc hạ tầng production nào được tạo bởi hướng dẫn này.

Các giá trị production được giữ trong `.env.prod` trên máy chủ:

| Input | Thiết lập cần có |
| --- | --- |
| `API_DOMAIN` | Hostname API trỏ DNS tới server; Caddy cấp/gia hạn TLS |
| `HTTP_PORT`, `HTTPS_PORT` | Cổng host trong dải 24xxx; mặc định `24080`, `24443` |
| `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` | Tài khoản database riêng; mật khẩu 64 ký tự hex được sinh ngẫu nhiên. Compose tạo `DATABASE_URL` nội bộ. |
| `ENVIRONMENT`, `LISTEN_ADDR`, `AUTH_DEV_LOGIN_ENABLED` | `production`, `0.0.0.0:8080`, `false`; Make guard từ chối giá trị khác |
| `AUTH_GOOGLE_CLIENT_ID` | Web OAuth client ID ở bước 2, trùng audience phía Android |
| `FCM_PROJECT_ID`, `FCM_CREDENTIALS_FILE` | Tùy chọn tới khi Firebase sẵn sàng; credential file server được cấp riêng, không đưa vào Git |
| `LOCATION_RETENTION_DAYS` | Chốt với chính sách thực tế; mặc định mã nguồn là 30 ngày |

Không đưa PostgreSQL hoặc metrics listener ra Internet. HTTP metrics có
listener loopback riêng; xem [runbook](observability.md). Khóa và database URL
không nằm trong Git hay log. Lưu image/commit triển khai, vùng dữ liệu,
quyền truy cập, thời hạn log/backup và thử quy trình phục hồi/xóa sau restore.

Production đã có Compose stack riêng. Sau khi có máy Linux cài Docker Engine và
Compose plugin, trỏ DNS `A` (và `AAAA` nếu dùng IPv6) của hostname API tới máy
chủ; mở inbound TCP `24080/24443` và UDP `24443` nếu muốn HTTP/3. Nếu truy cập
Internet qua router, chuyển tiếp cổng công khai TCP `80 → 24080`, TCP `443 →
24443` (và UDP `443 → 24443` nếu bật HTTP/3). Caddy cần các cổng công khai
80/443 để tự xin/gia hạn chứng thư; nếu nhà mạng hoặc proxy không chuyển tiếp
được, cần cấu hình DNS challenge hoặc reverse proxy phù hợp. Với kết nối trực
tiếp tới cổng host thay vì chuyển tiếp chuẩn, URL API phải ghi rõ `:24443`.
Sao chép cấu hình mẫu,
điền OAuth Web client ID và mật khẩu database, rồi khóa quyền đọc file:

```bash
cp .env.prod.example .env.prod
openssl rand -hex 32  # đặt kết quả vào POSTGRES_PASSWORD trong .env.prod
chmod 600 .env.prod
make docker-config     # kiểm tra cấu hình, không in secret
make docker-up         # build, migrate, bật API và Caddy/HTTPS
```

`make docker-up` là lệnh production; `make docker-up-dev` giữ stack phát triển
localhost. Production chỉ publish Caddy trên `24080/24443`; API và Postgres
không có cổng host, Postgres nằm trên Docker network nội bộ. Caddy tự xin/gia
hạn chứng thư khi DNS, firewall và chuyển tiếp cổng công khai đúng.
`make docker-down` dừng dịch vụ nhưng giữ volume dữ liệu. Không dùng
`make docker-clean-dev` với mục đích xóa dữ liệu
production.

FCM có thể để trống trong `.env.prod` khi chưa cấu hình Firebase; push sẽ không
hoạt động. Khi bật FCM, đặt project ID và đường dẫn tới credential file nằm
ngoài checkout. Vì API chạy bằng user không đặc quyền trong container, đặt
file trong thư mục riêng (`chmod 700`) và file JSON chỉ đọc (`chmod 444`);
đường dẫn cần là đường dẫn tuyệt đối. Không commit file này. Lệnh
deploy không tự tạo backup: trước khi nhận dữ liệu thật, cấu hình backup mã hóa
off-host, giới hạn thời gian lưu cả backup và log, rồi diễn tập phục hồi/xóa.
Không đưa PostgreSQL hoặc metrics listener ra Internet; xem
[runbook metrics](observability.md).

Kiểm chứng từ bên ngoài: HTTPS hợp lệ; `/healthz` và `/readyz` trả 200 khi
database/migrations sẵn sàng; `/api/v1/auth/config` phản ánh cấu hình Google
thật. Xác nhận endpoint dev-login không được mount, database/metrics không
truy cập công khai. Health check không thay thế kiểm thử đăng nhập/tính năng.

## 2. Project và Google Sign-In

Owner tạo Google Cloud project do mình quản lý; có thể thêm Firebase vào cùng
project để tránh nhầm project/sender/audience. Ghi lại **project ID** và cấp
quyền tối thiểu cho người vận hành. Không cần gửi quyền tài khoản Google cá
nhân hay mật khẩu cho agent.

Cấu hình thông tin ứng dụng/OAuth consent và người dùng thử theo trạng thái
project. Tạo OAuth client loại **Web application** để làm server audience:

- Server: `AUTH_GOOGLE_CLIENT_ID`.
- Android release/GitHub: `ANDROID_GOOGLE_WEB_CLIENT_ID` →
  `ANVE_GOOGLE_WEB_CLIENT_ID`.

Đăng ký Android OAuth client cho package/chứng thư của bản thực sự cài:
`com.anve.app` cho release, `com.anve.app.debug` nếu kiểm thử debug. Lấy
fingerprint từ chứng thư tương ứng, không dùng fingerprint khóa tạm của CI.
AnVề dùng Credential Manager/Google ID token và Go API để xác thực;
không chuyển sang Firebase Authentication hay nhúng OAuth client secret vào app.

Hướng dẫn chi tiết của repo: [Android setup](../implementation/setup-android.md).
Nguồn Google: [Sign in with Google](https://developer.android.com/identity/sign-in/credential-manager-siwg),
[client authentication](https://developers.google.com/android/guides/client-auth).

## 3. Upload key và Play App Signing

Hướng đã chọn: Google quản lý **app-signing key** thông qua Play App Signing.
Vẫn cần một **upload key** do owner quản lý để ký AAB trước khi gửi Console.

Owner có thể tạo keystore PKCS12 bằng công cụ ký của Android Studio hoặc
`keytool` trên máy tin cậy. Để khóa ở ngoài checkout, đặt mật khẩu qua prompt/
secret manager, giữ bản sao khôi phục riêng và ghi alias, loại keystore, ngày
hết hạn, SHA-256 chứng thư upload. Không gửi keystore hoặc mật khẩu vào chat.
[Release procedure](android-release.md) liệt kê chính xác các GitHub environment
variables/secrets và guard mà repo yêu cầu.

Nếu chưa có upload key, owner có thể chạy trên máy tin cậy có toolchain của
repo. Các lệnh này hỏi mật khẩu tương tác, không chứa mật khẩu trong arguments:

```bash
mkdir -p "$HOME/.local/share/anve-signing"
chmod 700 "$HOME/.local/share/anve-signing"
mise exec -- keytool -genkeypair -storetype PKCS12 \
  -keystore "$HOME/.local/share/anve-signing/anve-upload.p12" \
  -alias anve-upload -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=NianD"
chmod 600 "$HOME/.local/share/anve-signing/anve-upload.p12"
mise exec -- keytool -list -v \
  -keystore "$HOME/.local/share/anve-signing/anve-upload.p12" -alias anve-upload
```

Không chạy lại để thay một khóa đã dùng trên Console. Sao lưu keystore và mật
khẩu qua kênh riêng; chép **SHA-256 của certificate**, không lấy SHA-256 file
keystore, làm pin upload. Mẫu trên chưa được chạy để tạo khóa thật cho owner.

Tạo app trên Play Console với package release `com.anve.app`; kiểm tra owner
muốn giữ package này trước lần upload đầu tiên. Đăng ký Play App Signing theo
hướng Google sinh khóa. Lưu riêng fingerprint chứng thư **app signing** do
Console cung cấp và fingerprint **upload**; chúng phục vụ hai mục đích khác nhau.
Các chứng thư thực tế có thể thay đổi theo lựa chọn/key upgrade trong Console.

Pipeline kiểm chứng chữ ký AAB bằng fingerprint upload. OAuth/Maps/Firebase
phải nhận diện chứng thư ký bản được cài: bản qua Play dùng app-signing
certificate; bản sideload dùng chứng thư ký APK đó. Sau khi có fingerprint
Console, cập nhật provider trước khi kiểm thử bản cài qua Play.

Nguồn: [Android app signing](https://developer.android.com/studio/publish/app-signing),
[Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756).

## 4. Maps và Firebase

**Maps:** bật Maps SDK for Android trong project; Google yêu cầu project đã
bật billing cho quy trình này. Owner xem chi phí/billing trước khi bật; repo
không tạo billing account hay tự bật thanh toán. Tạo API key và giới hạn
application theo package Android + SHA-1 của chứng thư bản cài, đồng thời giới
hạn API chỉ cho SDK cần dùng. Lưu qua `ANDROID_MAPS_API_KEY` trong môi trường
release. Key client sẽ nằm trong app nên hạn chế ở provider vẫn bắt buộc.
[Hướng dẫn chính thức](https://developers.google.com/maps/documentation/android-sdk/get-api-key).

**Firebase:** thêm app Android `com.anve.app` vào đúng project. Ghi lại app ID,
project ID, API key và sender ID; điền các trường `ANDROID_FIREBASE_*` theo
[bảng mapping release](android-release.md). Nếu thử debug, đăng ký package debug
riêng. AnVề khởi tạo Firebase bằng các input này, không yêu cầu thêm
`google-services.json` hoặc thay đổi Gradle theo ví dụ của app khác.
[Firebase Android setup](https://firebase.google.com/docs/android/setup).

Server gửi FCM cần `FCM_PROJECT_ID` và ADC có quyền gửi phù hợp. Trên hạ tầng
hỗ trợ, dùng danh tính workload; nếu cần file credentials, owner giữ ngoài repo
và mount read-only từ secret storage rồi đặt `GOOGLE_APPLICATION_CREDENTIALS`
tới đường dẫn bên trong container. Chỉ thêm project ID vào `.env` không cấp
quyền gửi. Service-account key không được đưa vào APK hoặc GitHub artifact.
[FCM HTTP v1 authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api).

## 5. Build và kiểm thử thật

Sau khi cấu hình có bằng chứng, tạo/protect GitHub environment
`android-release`, điền mapping và chọn versionCode/versionName hợp lệ.
Thực hiện [release procedure](android-release.md) trên commit main đã qua CI;
workflow chỉ tạo AAB/bản ghi để owner review, chưa upload lên Play.

Với bản test owner đã cho phép phân phối, dùng track kiểm thử phù hợp trong
Console và tuân thủ điều kiện Console đang áp dụng cho tài khoản/app đó.
Không coi kiểm thử bằng Internal App Sharing là bằng chứng của chữ ký/luồng
phân phối test track; ghi đúng kênh cài và chứng thư thực tế.
[Internal App Sharing có chứng thư riêng](https://support.google.com/googleplay/android-developer/answer/9844679).

Chuẩn bị điện thoại Android có Google Play services và hai tài khoản thử:

1. Cài bản đúng kênh/version, ghi fingerprint và source/AAB digest; đăng nhập
   Google thật, kiểm tra phiên/đăng xuất và thu hồi thiết bị.
2. Tạo nhóm thử, cấp quyền xem rõ ràng; kiểm tra bản đồ có tile/marker và
   thu hồi quyền loại bỏ dữ liệu ở lần đọc được phép tiếp theo.
3. Thiết lập thông báo, gửi SOS thử: phân biệt server nhận, FCM chấp nhận,
   thiết bị nhận/hiển thị và người nhận xác nhận. Thu hồi phải chặn truy cập.
4. Chạy ma trận quyền, tắt màn hình, mất mạng, consent OFF, process/reboot/OEM,
   GPS và đo pin theo [tracking validation](../implementation/tracking-validation.md).
5. Kiểm tra xóa lịch sử/tài khoản, nhận/xác minh/xử lý yêu cầu qua support,
   và URL privacy/xóa tài khoản công khai trước khi hoàn thiện hồ sơ Console.

Chỉ đưa báo cáo đã loại tọa độ, token và định danh tài khoản thật vào repo.
Ghi lỗi và pending đúng thực tế; CI emulator không thay thế các bước trên.

## Thông tin owner có thể cung cấp tiếp

- Host/vùng dữ liệu dự định và URL API khi đã chọn.
- Google/Firebase project ID và Web OAuth client ID (định danh công khai).
- Alias/loại keystore và fingerprint chứng thư công khai; **không gửi file khóa**.
- Model điện thoại, phiên bản Android và kênh cài bản test.
- Quyết định retention/log/backup, độ tuổi/quốc gia phân phối và xử lý support.

Các secret thực tế được nhập trực tiếp vào nơi quản lý secrets phù hợp, không
dán vào phần trả lời. Khi các đầu vào sẵn sàng, ghi kết quả vào
[M8-D handoff](../release/m8d-owner-handoff.md), không biến hướng dẫn này thành
bằng chứng đã chạy bằng cách chỉ đánh dấu checklist.
