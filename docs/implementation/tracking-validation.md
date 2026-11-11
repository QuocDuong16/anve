# M7-A — kiểm chứng tracking và diagnostics

Mục tiêu: giải thích được vì sao vị trí chưa cập nhật, kiểm chứng privacy và
phục hồi bằng kịch bản lặp lại được. M7-A không triển khai Safe Journey.

## Các mức bằng chứng

1. `make tracking-validation`: Android lint, JVM tests, debug APK và biên dịch
   instrumentation APK. Đây là kiểm chứng local, chưa chạy trên thiết bị.
2. Android GitHub Actions khởi động emulator API 35 và chạy
   `make android-emulator-validation`. Target này giữ exit code của instrumentation
   và lấy cả ảnh chụp khi có test thất bại. Các kiểm thử Diagnostics lưu ảnh toàn
   màn hình bằng Android UiAutomation; `make android-ui-captures` lấy ảnh vào
   `apps/android/app/build/outputs/ui-validation/` để upload cùng kết quả test.
   Runner chỉ giữ APK sau test trong vòng đời emulator để lấy ảnh, bằng
   `android.injected.androidTest.leaveApksInstalledAfterRun=true`; AGP mặc định
   gỡ app và xóa app-specific external files trước bước `adb pull`.
   Chỉ ghi nhận Emulator Verified khi workflow thực sự chạy thành công trên
   SHA tương ứng. Ảnh dùng dữ liệu giả lập, không có vị trí/tài khoản thật.
3. Field validation: điện thoại vật lý, GPS thật, mạng, pin và hạn chế OEM.
   Không suy ra kết quả của mức này từ APK, JVM hoặc emulator CI.

Môi trường local hiện không có target khi kiểm tra `adb devices -l`. Không
chạy hoặc tuyên bố instrumentation local nếu chỉ có executable `adb`.

## Kịch bản tự động

| Kịch bản | Bằng chứng cần xem |
| --- | --- |
| Mất mạng khi service/provider chạy | `DiagnosticsTest`: thu thập được phân biệt với chờ gửi; nút mở cài đặt mạng |
| Consent OFF, pending và conflict | Lý do không khẳng định server đã xác nhận; không tự bật lại GPS/consent |
| Service có mặt nhưng provider chưa chạy | Không hiển thị như đang thu thập |
| Service dừng thất bại | Trạng thái dừng còn chưa xác nhận, kể cả khi consent cục bộ OFF |
| Chưa có mẫu hoặc timestamp tương lai | Hiển thị thiếu dữ liệu/kiểm tra đồng hồ; không bịa tuổi bằng 0 |
| Queue của hai owner và queue rỗng | Repository JVM test và `LocationQueueDiagnosticsInstrumentedTest` với SQLite thật |
| Tổng attempts lớn hơn Int | SQLite trả Long; lần gửi đầu chưa được tính là retry |
| Đổi tài khoản, sign-out, response cũ đến muộn | `DiagnosticsViewModelTest` và `TrackingManagerTest`: không kế thừa fix/upload/queue/report |
| Trang bị đóng hoặc app dừng hiển thị | Xóa snapshot/report, dừng đọc bổ sung; mở lại đọc mới |
| Push chậm hoặc không đọc được | Queue local hiển thị trước; trạng thái push là chưa xác định, không suy ra delivery |
| Xuất báo cáo | Allowlist không chứa owner, tọa độ, credentials, raw exception hoặc recipient/event IDs |
| UI sáng, tối và font scale 1.3 | Emulator checks thao tác Tổng quan/Chi tiết, recovery/export và lưu ảnh; font scale được mô phỏng trong Compose |

## Kiểm tra thủ công trên điện thoại

Dùng tài khoản và Circle thử nghiệm, với sự đồng ý của tất cả người tham gia.
Ghi phiên bản APK/SHA, Android/OEM, cấu hình test, thời gian bắt đầu/kết thúc và
kết quả quan sát. Không chép token, email, installation ID hoặc tọa độ vào
báo cáo hỗ trợ. Không thu logcat/bugreport đầy đủ làm artifact mặc định.

| Kịch bản | Thao tác | Điều cần quan sát |
| --- | --- | --- |
| Bắt đầu online | Chủ động bật chia sẻ và theo dõi | Consent, quyền, service/provider và mẫu vị trí được phân biệt |
| Màn hình tắt | Theo dõi với nền được cấp; khóa màn hình rồi mở lại | Thời điểm ghi/gửi và sự gián đoạn thực tế; không suy ra từ notification |
| Offline → online | Sau khi đã có checkpoint, tắt rồi bật mạng | Queue chờ, sau đó accepted/duplicate được xóa; không gửi chéo owner |
| Fresh process offline | Đóng process rồi mở lại khi offline | Không tự thu thập khi chưa có checkpoint hiện hành; có hướng khắc phục |
| Thu hồi quyền/định vị | Rút permission hoặc tắt location trong Settings | Thu thập dừng, trạng thái cập nhật sau khi quay lại; không tự reprompt liên tục |
| Battery Saver/OEM | Thử chế độ tiết kiệm pin và hạn chế nền của máy | Ghi hạn chế thực tế; không hứa miễn tối ưu hóa sẽ sửa mọi OEM |
| Process bị kill/reboot | Ghi rõ cách đóng process hoặc khởi động lại máy | Không tự nhận tracking đã phục hồi; yêu cầu người dùng chủ động tiếp tục |
| Consent OFF offline | Tắt consent trước khi sign-out; mở lại và có mạng | Acquisition/upload admission đóng cục bộ, queue purge/outbox và xác nhận server tách biệt |
| Đổi tài khoản | A có queue, sign-out rồi đăng nhập B | Diagnostics và export của B không chứa dấu vết hoạt động của A |
| Export | Mở Xuất báo cáo, đọc preview, hủy/gửi có chủ động | Preview đúng nội dung; hủy không gửi; tài khoản thay đổi làm report mất hiệu lực |

## Đo pin

Đo baseline và tracking trên cùng điện thoại, cùng độ dài phiên và điều kiện
mạng/màn hình/di chuyển có thể so sánh. Ghi mức pin hoặc charge counter đầu/cuối,
thời lượng, chế độ Battery Saver và mọi gián đoạn; lặp lại để đánh giá biến thiên.
Chỉ công bố chênh lệch khi có số đo cùng phương pháp. Bộ đếm GPS/service/upload
hiện có không phải battery overhead; chúng chưa được đưa vào báo cáo Diagnostics
vì lưu theo installation, chưa được cách ly theo tài khoản.

## Prerequisite cho tích hợp thật

- Google OAuth, API base URL và Maps key giới hạn đúng package/signing certificate:
  [setup Android](setup-android.md). CI không có key không chứng minh map tiles
  hay Google Sign-In hoạt động với tài khoản thật.
- Firebase app/project/sender/API configuration và credential server:
  [Firebase setup](setup-android.md#firebase-configuration-for-m6). Không commit credentials. Fake provider
  chỉ chứng minh logic authorization/outbox, không chứng minh gửi FCM thật.
- Không dùng FCM để chở tọa độ. Provider acceptance, thiết bị nhận và người dùng
  acknowledge là ba mốc riêng.

## Ghi kết quả

Mỗi lần validation ghi: SHA/APK, môi trường, kịch bản, điều kiện, expected,
observed, artifact đã lọc và hạn chế. Nếu không thực hiện, ghi **Not run**;
nếu thiếu cấu hình/thiết bị, ghi prerequisite còn thiếu. Không điền số GPS,
latency hay pin giả lập thành kết quả đo thật.

## Bằng chứng local — 2026-11-11

- `make quality-check android-test-build`: PASS. Android JVM có 293 tests,
  không failure/error; lint, debug APK và instrumentation APK compile PASS.
- `mise exec -- actionlint .github/workflows/android.yml`: PASS.
- Full gate ban đầu phát hiện `GO-2026-6859` trên Go 1.27.3. Đã đồng bộ Go
  1.27.4 trong mise/go.mod/Docker builder và lockfile; govulncheck sau đó không
  còn reachable vulnerability. Ba advisory trong required modules không được
  gọi vẫn được scanner báo riêng. Docker image digest được đọc từ registry;
  không có tuyên bố đã build/chạy Docker image mới.
- `adb devices -l`: không có target; instrumentation execution, native
  screenshot comparison và field validation **Not run locally**. Hosted CI
  được ghi riêng bên dưới. Mockup C chỉ được duyệt về bố cục, không phải
  runtime evidence.
- Impeccable plan/component review còn **pending** vì review server trả
  `no reviewer can open a browser in this session`. Không tự tạo approval.
  Session ID: `5297b254ace168458f8ce46520c388bcfa0c890f120185a615b7a0ed7d4f5dd1`.
  Manifest: `.impeccable/review/components.json` (0 raster assets, native
  controls/text/chrome). Resume bằng `impeccable component-review serve --session`
  với đầy đủ ID trên khi có browser cho người duyệt.
- Finish reviewer trả **recapture**: cần ảnh native overview/details, dark
  theme, font scale 1.3, export review/failure và permission/offline/error
  states trước khi đánh giá fidelity/craft. M7-A chưa đóng visual review.
- Tại mốc kiểm chứng local này, thay đổi chưa được commit/push. Bằng chứng
  hosted CI sau đó được ghi riêng bên dưới.

## GitHub Actions — vòng kiểm chứng đầu tiên

PR [#15](https://github.com/QuocDuong16/anve/pull/15), commit
`bea35e5dc25fefbceae96185f0801e9308804145`:
[Go](https://github.com/QuocDuong16/anve/actions/runs/42967459507) PASS,
gồm race tests và PostgreSQL/PostGIS. [Android](https://github.com/QuocDuong16/anve/actions/runs/42967459524)
phát hiện hai test Diagnostics tìm node “Xuất báo cáo” chưa được tạo ngoài
viewport của LazyColumn. Báo cáo instrumentation: 13 tests, 2 failures.

Đã sửa bằng `performScrollToNode` trên chính LazyColumn thay vì tìm trước một
node offscreen bằng `performScrollTo`. Đồng thời bổ sung ảnh toàn màn hình,
empty/loading/permission/consent/error/export states và giữ partial captures
trước khi emulator tắt. Không đổi timeout hoặc bỏ assertion. Cần workflow mới
trên commit sửa lỗi để xác nhận kết quả.

Vòng tiếp theo trên `1719cba871e93cb641018462d8fb4c7027636a1b`:
[Go](https://github.com/QuocDuong16/anve/actions/runs/42968947923) PASS;
[Android instrumentation](https://github.com/QuocDuong16/anve/actions/runs/42968947397)
đã chạy **19 tests, 0 failures/errors**. Job vẫn thất bại ở bước lấy ảnh vì AGP
đã gỡ APK và xóa thư mục ảnh trước khi `adb pull`. Runner được sửa để giữ APK
trong vòng đời emulator; không đổi assertions hay timeout. Kết quả toàn bộ
workflow và artifact ảnh sau bản sửa phải được xác nhận ở run mới.

## GitHub Actions — emulator và artifact đã PASS

Commit `c2854d2709f2a38a44bff2c9101eb9966cafb7bb`:
[Go](https://github.com/QuocDuong16/anve/actions/runs/42970233522) và
[Android](https://github.com/QuocDuong16/anve/actions/runs/42970233684) đều PASS.
Go gồm race tests và PostgreSQL/PostGIS; Android gồm lint/JVM/debug, minified
release và **19 instrumentation tests, 0 failures/errors** trên API 35 emulator.
Artifact `android-instrumentation-results` có XML/HTML results và **11 ảnh native**
(overview/details, light/dark, font scale 1.3, loading, empty, permission denied,
consent pending, read/export error, preview). Đây là fixture tổng hợp trong app
native, không phải GPS, tài khoản backend hoặc báo cáo người dùng thật.

Đã mở và kiểm tra các ảnh: không blank/black hay sai state. Ảnh dark phát hiện
chữ/action teal trên nền tối/cảnh báo thiếu tương phản. Bản sửa dùng các cặp
container/content có sẵn của theme, giữ bố cục C và khôi phục màu icon status
bar khi rời màn hình. Bản sửa cần CI/capture trên commit mới; không dùng kết
quả `c2854d2` để tự chứng nhận thay đổi sau đó. Formal plan/component review
vẫn pending như ghi ở trên; chưa có quyết định **ship** của finish reviewer.

Runner giữ APK trong vòng đời emulator để thu ảnh trước khi hủy máy; script
vẫn trả mã lỗi instrumentation khi test hỏng và chỉ trả thành công khi cả
test lẫn thu ảnh thành công. Không tăng timeout hay bỏ assertion để qua gate.


## Kiểm chứng bản sửa tương phản và yêu cầu recapture

Commit `91cbb7c9bd3a5a05286e6bbe78248cd9e17e5fba`:
[Go](https://github.com/QuocDuong16/anve/actions/runs/42971957080) và
[Android](https://github.com/QuocDuong16/anve/actions/runs/42971957143) PASS.
Artifact có 19 tests, 0 failures/errors/skips và 11 ảnh mới. Đã xem toàn bộ ảnh;
dark heading/action/export buttons dùng cặp màu mới, không còn vấn đề tương
phản đã xác định ở ảnh trước.

Reviewer độc lập mở đủ 11 ảnh và trả **recapture**, không phải verdict source:
ảnh 320×640 hợp lệ cho màn nhỏ nhưng thiếu khung comparison 412×915 dp và
`hero-repro.png`. Diff chuẩn hóa khác viewport (`overall=0.6259`) không chứng
minh fidelity. Không kéo giãn/upscale/stitch ảnh để tạo bằng chứng.

Workflow bổ sung `make android-validation-avd` sau khi tạo AVD, trước khi boot:
842×1870 px, 327 dpi (làm tròn thành 412×915 dp; framebuffer cần chiều rộng chẵn). Metadata artifact ghi SHA,
`wm size`, `wm density`, system font scale và các Compose fixture overrides.
Phải xác nhận kích thước PNG thật từ run mới rồi tạo hero/final diff và review
trên bộ ảnh đó. Formal plan/component review vẫn pending, không tự tạo receipt.

`DESIGN.md` và `.impeccable/design.json` được trích từ palette/theme Compose hiện
có; không đổi nhận diện hoặc coi preview tài liệu là runtime evidence.


## Capture đồng bộ với rendered frame

Commit `d8481301fe7b3857f847208985671c7d772ba3f5` qua
[Go](https://github.com/QuocDuong16/anve/actions/runs/42974051997) và
[Android](https://github.com/QuocDuong16/anve/actions/runs/42974051990), gồm
19/19 instrumentation tests. Metadata checkout là merge ref
`b604bb4b9d62ff71856fb63982a9871eb77dbeb9`, có cùng Git tree
`54ea54b1b635f0ae08568afac5ba2f4babac307a` với head PR.
Ảnh thật 842×1870 px, 327 dpi, system font scale 1.0; dark/font130 fixture
override Compose scale 1.3. Comp gốc 841×1870 được giữ nguyên.

Khi mở toàn bộ ảnh, một số frame thiếu chữ trên app bar dù phần nội dung đã
hiện. Không gửi chúng như bằng chứng UI đã ổn định. Capture helper bổ sung
đợi frame commit và accessibility idle trước `UiAutomation.takeScreenshot`;
không thêm sleep tùy ý hoặc tăng timeout của workflow. Nếu frame không commit,
test thất bại rõ ràng. Cần run/captures mới để xác nhận sửa bước đồng bộ này.
Tham khảo API [frame commit callback](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable)).

CI xanh ở các mốc trên là bằng chứng tự động, không thay thế finish review.
Kết quả cuối cùng của PR được lưu trong checks/artifacts và phần validation của
[PR #15](https://github.com/QuocDuong16/anve/pull/15).


## Capture API tương thích Compose

Các run sau bước đợi frame vẫn qua 19 instrumentation tests nhưng ảnh
`UiAutomation` có lúc thiếu app bar hoặc còn nền window. Chuyển từ renderer
`swiftshader_indirect` đã deprecated sang `swiftshader` không giải quyết hết
triệu chứng; không coi thay đổi renderer là root-cause fix đã được xác nhận.

Helper mới dùng `captureToImage()` của bản Compose đang ghim trên từng root
window trước khi đọc full display. Artifact lưu riêng ảnh `compose-...-window-N`
và ảnh toàn màn hình; đây là hai phép chụp native, không resize hoặc ghép ảnh.
API Compose quản lý drawing/force-redraw theo test clock; chữ, controls và
sharesheet vẫn là UI native. Bản thử có tham số timeout không tồn tại trong
bản thư viện đã ghim đã bị loại và compile lại bằng API tương thích.

`make android-test-build` PASS sau chỉnh API. Execution và độ hợp lệ ảnh phải
được xác nhận trên run mới. Không tuyên bố đã đóng Impeccable finish review.
