# Roadmap — AnVề

> Tài liệu này là **source of truth** cho kế hoạch milestone và trạng thái thực
> tế của từng mốc. Cấu trúc M0–M8 là **proposed milestone structure** định hướng
> từ product brief; trạng thái của từng mốc phải được xác minh bằng source code
> và CI — không tự đánh dấu hoàn thành chỉ vì tài liệu được cập nhật.

## Nguyên tắc chuyển milestone

- Một milestone chỉ chuyển sang hoàn thành khi các **exit criteria** của nó có
  bằng chứng tồn tại trong repo (code, test, CI, hoặc xác minh runtime được ghi
  rõ ngày xác minh).
- CI PASS chứng minh build/test/lint sạch. Bằng chứng hành vi Android trên thiết
  bị thật (nền, pin, reboot, OEM restrictions) phải được ghi riêng — CI không
  thay thế field validation.
- Phụ thuộc giữa các milestone là thứ tự kỹ thuật bắt buộc, không phải thứ tự
  mong muốn.

## Tổng quan

| Milestone | Phạm vi | Trạng thái |
| --- | --- | --- |
| [M0 — Foundation](#m0--foundation) | Android, Go, database, CI, migration, toolchain | **Implemented — Verified** (mức nền tảng) |
| [M1 — Auth & Privacy foundation](#m1--authentication-device-identity-privacy-foundation) | Authentication, device identity, privacy foundation | **Implemented — Verified** (backend + client logic; chờ field validation) |
| [M2 — Family Circle](#m2--family-circle--membership-authorization) | Circle, membership, authorization | **Implemented — Verified** (backend + client logic; chờ field validation) |
| [M3 — Location Engine](#m3--native-location-engine-smart-tracking-offline-queue) | Location Engine, Smart Tracking, offline queue, ingestion | **Implemented — Automated Verified** (runtime HTTP smoke; Pending Field Validation) |
| [M4 — Live Map](#m4--live-map-realtime-location-confidence-engine) | Live Map, realtime, confidence engine | Planned |
| [M5 — History & Place Alerts](#m5--location-history--place-alerts) | Location History, Place Alerts | Planned |
| [M6 — SOS & Push](#m6--sos-firebase-push-event-acknowledgement) | SOS, Firebase push, event acknowledgement | Planned |
| [M7 — Safe Journey & Diagnostics](#m7--safe-journey-diagnostics-field-validation) | Safe Journey, diagnostics, field validation | Planned |
| [M8 — Release hardening](#m8--release-hardening-google-play-readiness) | Release hardening, Google Play readiness | Planned |

## M0 — Foundation

**Scope:** chứng minh nền tảng kỹ thuật end-to-end trước khi viết tính năng sản
phẩm đầu tiên: Android build APK từ CLI, Go service trả `/healthz`, database từ
một lệnh, CI kiểm tra độc lập hai ứng dụng (tham chiếu
[ADR 0001](../adr/0001-modular-monorepo-foundation.md)).

**Status: Implemented — Verified (mức nền tảng).**

Bằng chứng đã xác minh:

- CI xanh tại HEAD `de54074e1547630bc9656cf17703cf0100f2f3bb` (kiểm tra ngày
  2026-10-25): workflow **Go** (gofmt, tidy, vet, golangci-lint, govulncheck,
  test với race detector, build) và workflow **Android** (unit test, lint,
  build debug APK + upload artifact).
- Job **database-integration** trong CI chạy migrator integration test trên
  PostgreSQL/PostGIS thật (service `postgis/postgis:17-3.6` pin bằng digest):
  up → up → down → up, rollback migration lỗi, kiểm tra extensions.
- **Runtime smoke test full Docker Compose stack — đã xác minh ngày 2026-10-25**
  (trước đó chưa được kiểm tra trong review): `make docker-up` → postgres
  healthy → migrate one-shot chạy xong → `GET /healthz`, `GET /readyz`,
  `GET /api/v1/version` trả 200 với JSON đúng contract → extensions
  (`postgis`, `pg_trgm`, `uuid-ossp`) tồn tại trong database → `make docker-down`
  sạch sẽ.

Còn thiếu (chặn việc coi M0 là "hoàn thành tuyệt đối"):

- **Chưa có field validation trên thiết bị Android thật** — APK build thành công
  trong CI không chứng minh hành vi trên điện thoại. M0 chỉ có splash screen nên
  rủi ro thấp, nhưng nguyên tắc này áp dụng cho mọi milestone sau.
- Chưa có bất kỳ tính năng sản phẩm nào (đúng theo kế hoạch — không phải thiếu sót).

**Exit criteria M0:** ✅ Android build từ CLI · ✅ Go `/healthz` · ✅ database từ
một lệnh · ✅ CI độc lập hai ứng dụng · ✅ smoke test stack. (Mức "nền tảng đã
xác minh"; field validation là nội dung thường trực của các milestone sau.)

## M1 — Authentication, device identity, privacy foundation

**Scope:** tài khoản, phiên đăng nhập, thiết bị đăng ký, khung authorization,
sự kiện consent đầu tiên (bật/tắt chia sẻ), audit log nền móng.

**Status: Implemented — Verified** (backend + Android client logic; bằng
chứng dưới đây). Quyết định kỹ thuật: Google Sign-In + opaque session do
backend cấp, token lưu hash, xác minh mỗi request — ADR 0002.

Bằng chứng đã xác minh (chi tiết ở [`../implementation/progress.md`](../implementation/progress.md)):

- Unit tests (service + HTTP handlers) và integration tests trên
  PostgreSQL/PostGIS thật: đăng nhập/rotation (đúng 1 winner khi refresh
  đồng thời), thu hồi phiên tức thì, cross-user device isolation (BOLA),
  predicate hết hạn, cascade xóa tài khoản, audit row sống sót sau xóa —
  chạy trong CI job `database-integration`.
- Consent switch (mặc định TẮT) + audit trong cùng transaction — có test.
- Smoke test runtime trên Compose stack local (2026-10-25): sign-in → me →
  consent → devices → refresh (token cũ chết ngay) → 401 envelope.
- Android: `make android-check` xanh (lint + unit tests qua MockWebServer +
  APK debug); release build (minified) xanh.
- Hardening 2026-10-26 (ADR 0004): xóa tài khoản không còn giải tán Circle
  có thành viên (`CIRCLE_HAS_MEMBERS` + transaction race-safe); dev login chỉ
  mount khi `ENVIRONMENT=development` và identity dev/Google cách ly hai
  chiều; audit một sự kiện một dòng trong transaction; Android không xóa
  session khi xóa tài khoản thất bại, đăng xuất offline có pending-revocation
  và UI nói rõ giới hạn; backup/transfer loại trừ `file/datastore`. Chi tiết
  và bằng chứng:
  [`../implementation/progress.md`](../implementation/progress.md).
- Final hardening 2026-10-26 (ADR 0005): listener mặc định loopback,
  non-development bắt buộc `LISTEN_ADDR`, dev login cần thêm allowlist địa
  chỉ nguồn (Compose chỉ publish loopback — `compose-lint` bảo vệ); pending
  revocation là hàng đợi mã hóa có giới hạn (8 bản ghi × 7 ngày) với retry
  độc lập/backoff; thông báo đăng xuất offline sống ở app scope và hiển thị
  trên màn hình đăng nhập, không bị mất khi navigation.

Còn thiếu (giữ milestone ở mức này, không nâng lên "hoàn thành tuyệt đối"):

- **Field validation trên thiết bị Android thật** chưa có.
- **Google Sign-In với OAuth client thật** chưa xác minh — chờ tạo Google
  Cloud OAuth client (external blocker, đã có hướng dẫn
  [`../implementation/setup-android.md`](../implementation/setup-android.md));
  server hiện fail-closed, dev login phục vụ phát triển.

**Exit criteria M1:** ✅ đăng nhập/đăng xuất hoạt động · ✅ liệt kê/thu hồi
thiết bị của chính mình · ✅ mọi endpoint mới qua authorization middleware ·
✅ consent bật/tắt có audit · ✅ OpenAPI trước handler (contract-first). ⏳
field validation trên thiết bị thật (thường trực, như mọi milestone).

**Phụ thuộc:** M0.

## M2 — Family Circle & membership authorization

**Scope:** tạo Circle, invite code/link, chấp nhận/từ chối, danh sách thành
viên, rời nhóm, quyền xem theo chủ thể dữ liệu.

**Status: Implemented — Verified** (backend + Android client logic).

Bằng chứng: contract-first (OpenAPI 21 paths); migration `0003_circles` với
bất biến one-circle-per-user bằng unique index; `internal/circles` thực thi
authorization ở tầng store (outsider → 404, member thiếu role → 403); invite
single-use hết hạn 48h, redeem atomic (FOR UPDATE, đúng 1 winner khi đồng thời
— có test); chủ nhóm rời nhóm phải dissolve tường minh (cascade toàn bộ);
tham gia KHÔNG tự bật chia sẻ vị trí. Integration tests chạy trong CI
(`database-integration`), smoke test HTTP trên stack local. Android: tab Nhóm
với tạo/tham gia bằng mã mời, thành viên, mã mời (chủ nhóm), rời/giải tán;
`make android-check` xanh.

Hardening 2026-10-26 (ADR 0004): chủ Circle còn thành viên **không thể** xóa
tài khoản — server trả `CIRCLE_HAS_MEMBERS` thay vì cascade âm thầm phá nhóm
của người khác; Circle rỗng bị xóa trong cùng transaction với audit
`circle.dissolved`; deletion race-safe với redeem (khóa circle + retry
deadlock, có integration test 5 round). Audit mỗi mutation đúng một dòng
trong transaction, không còn duplicate giữa store và service.

Final hardening 2026-10-26 (ADR 0005 §4): deletion khóa **account row**
trước khi quét Circle, nên race với **tạo Circle** được xử lý tất định —
test dùng lock-wait barrier chứng minh create-thắng (dissolution audit đúng
một lần), delete-thắng (create fail FK, không có `circle.created`) và
deadlock được retry với đúng một audit row.

Còn thiếu: field validation trên thiết bị thật; invite **link** (deep link)
chưa làm — hiện nhập mã thủ công (đã ghi nhận ở progress.md).

**Exit criteria M2:** ✅ không ai vào nhóm khi chưa tự chấp nhận (redeem là
hành động chủ động của người được mời) · ✅ rời nhóm chấm dứt toàn bộ quyền
(có test) · ✅ authorization Circle test ở tầng API (authorization matrix).

**Phụ thuộc:** M1.

## M3 — Native Location Engine, Smart Tracking, offline queue

**Scope:** Location Engine Kotlin (các trạng thái
`IDLE/STATIONARY/MOVING/JOURNEY/DEGRADED/OFFLINE/PAUSED`), foreground service +
background location đúng quy trình, ingestion API, Room durable queue, batch +
retry + idempotency, location record có identity/timestamp/idempotency.

**Status: Implemented — Automated Verified; Pending Field Validation.**
Quyết định và threat model: [ADR 0006](../adr/0006-m3-location-engine.md).

Bằng chứng đã xác minh (chi tiết ở
[`../implementation/progress.md`](../implementation/progress.md)):

- Contract-first: `POST /api/v1/locations/batch` (OpenAPI 0.4.0), atomic
  batch 1–200 events, error codes ổn định (`LOCATION_BATCH_INVALID`,
  `LOCATION_EVENT_TOO_OLD`, `LOCATION_CONSENT_REQUIRED`,
  `LOCATION_CIRCLE_REQUIRED`, `DEVICE_NOT_AUTHORIZED`,
  `LOCATION_TEMPORARILY_UNAVAILABLE`).
- Migration `0004_locations`: PostGIS `GEOGRAPHY(Point, 4326)`, unique
  `(user_id, event_id)`, indexes cho user/device/capture-time/GIST,
  FK cascade tới users/devices.
- `internal/locations`: validation (ranges, NaN/Inf, timestamp, batch
  bounds), authorization trong transaction (user share lock → session/device
  → membership), idempotent insert, retention purge; integration tests trên
  PostgreSQL/PostGIS gồm concurrent same-event, consent revocation lock
  ordering, device revocation ordering, account deletion cascade, backfill
  order, retention.
- Android: Fused Location Provider sau interface, policy thích ứng có
  hysteresis/accuracy/stale/out-of-order rejection, foreground service type
  `location` + notification + stop action, permission flow theo bước với
  Android 10 runtime và Android 11+ Settings flow, Room queue owner-scoped
  (5.000 điểm/7 ngày), WorkManager batch sync (100/batch, 5 batch và tối đa
  64 request/pass) với bounded validation isolation. Consent OFF được giữ
  trong outbox mã hóa owner-scoped; các mutation dùng expected version/CAS.
- Runtime (2026-10-26, local binary + PostgreSQL/PostGIS): dev login →
  Circle → consent ON → batch 200 accepted → retry trả duplicates → batch
  không tăng row; foreign device claim 403; no-circle 409; consent OFF rồi
  batch 409 và không thêm row; account deletion cascade hết row; retention
  worker xóa đúng dòng 2 ngày tuổi khi API khởi động với
  `LOCATION_RETENTION_DAYS=1`.

Còn thiếu (giữ milestone ở mức này, **không** gọi là hoàn thành tuyệt đối):

- **Field validation trên thiết bị thật** (Pixel/One UI/HyperOS): screen-off,
  Battery Saver, Doze, process kill, reboot, GPS unavailable, permission
  revoked, network loss, `adb reverse`/emulator connectivity, notification
  visibility và OEM FGS kills.
- **Chưa đo battery overhead** — các ngưỡng interval/displacement là
  engineering defaults cần calibration; không có tuyên bố tiết kiệm pin.
- Room queue không được mã hóa thêm ở tầng ứng dụng (ADR 0006 §6); chưa có
  history UI/retention tùy chọn (M5).

**Exit criteria:** ⏳ chia sẻ hoạt động khi màn hình tắt (field); ✅ dữ liệu
bền vững + idempotent + event-time order (automated); ✅ tọa độ không vào
log; ✅ server từ chối khi consent/Circle/device/session không hợp lệ.
Hardening regression evidence and final command results are recorded in
[`../implementation/progress.md`](../implementation/progress.md). Field
validation là điều kiện bắt buộc trước khi tuyên bố M3 Device Verified.

**Phụ thuộc:** M1–M2 (authorization để ai được gửi location).

## M4 — Live Map, realtime location, confidence engine

**Scope:** Google Maps, marker thành viên, realtime distribution (transport là
quyết định mở), last known location, trạng thái Fresh/Delayed/Stale/Offline/Unknown.

**Exit criteria đề xuất:** người xem thấy tuổi dữ liệu; dừng chia sẻ làm người
xem mất hiển thị trong thời gian ngắn, được server đảm bảo.

**Phụ thuộc:** M3.

## M5 — Location History & Place Alerts

**Scope:** lịch sử theo ngày, polyline, phân biệt live/backfill, retention,
xóa history, geofence + hysteresis, thông báo đến/rời.

**Exit criteria đề xuất:** authorization history per-request; retention chạy tự
động; Place Alert không lặp, event time đúng.

**Phụ thuộc:** M3 (bắt buộc), M4 (map rendering), M6 nếu dùng FCM cho thông báo
(in-app trước được).

## M6 — SOS, Firebase push, event acknowledgement

**Scope:** SOS event lifecycle, chống chạm nhầm, FCM, ba mốc trạng thái
(gửi → push chấp nhận → người nhận xác nhận), queue sự kiện khi offline.

**Exit criteria đề xuất:** ba mốc hiển thị đúng; chỉ người có quyền nhận thông
báo; không có tọa độ trong FCM payload.

**Phụ thuộc:** M2 (quyền nhận), M3 (vị trí đính kèm).

## M7 — Safe Journey, diagnostics, field validation

**Scope:** Safe Journey (có thể lùi nếu cần giảm scope — quyết định khi đến mốc),
Tracking Health & Diagnostics, quy trình field validation trên nhiều thiết bị
thật (battery, OEM restrictions, reboot).

**Exit criteria đề xuất:** diagnostics trả lời được "vì sao tracking không
chạy"; ngưỡng cảnh báo hành trình được kiểm chứng trên dữ liệu thực địa; báo
cáo battery overhead có số đo.

**Phụ thuộc:** M3–M6.

## M8 — Release hardening, Google Play readiness

**Scope:** hardening (security review, error budget, observability đầy đủ),
chuẩn bị data safety form, permission declarations cho background location,
release pipeline.

**Lưu ý:** mục tiêu là **chuẩn bị** đủ điều kiện nộp Google Play; tài liệu không
tuyên bố kết quả duyệt — kết quả thuộc về quá trình review của Google.

**Phụ thuộc:** toàn bộ các mốc trước.

## Các mục Proposed (chưa vào roadmap chốt)

- **Confidence score định lượng** — cần giả thuyết, inputs, phương pháp
  calibration/validation trên dữ liệu thực địa trước khi hiển thị số liệu cho
  người dùng (xem [features.md](features.md#5-location-confidence-engine)).
- **Nhiều Circle cho một người dùng** — mở rộng sau MVP; MVP giữ một-Circle.
- **Kênh realtime thay thế** (SSE, polling) — chỉ xem xét khi WebSocket chưa chốt
  vì ràng buộc thực tế; quyết định ở
  [`../architecture/overview.md`](../architecture/overview.md#7-các-quyết-định-kỹ-thuật-chưa-chốt).
