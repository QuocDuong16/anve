# Kiến trúc AnVề

> Tài liệu này là **source of truth** cho kiến trúc logic: ranh giới component
> Android, module Go dự kiến, các luồng dữ liệu chính, security/trust boundaries
> và các quyết định kỹ thuật chưa chốt. Toàn bộ sơ đồ phân biệt rõ phần **đã có
> trong source (M0)** và phần **định hướng (Planned)** — không có component,
> endpoint hay bảng database nào được mô tả như đã tồn tại nếu source chưa có.

## 1. Kiến trúc tổng quan

Kiến trúc hiện tại được giữ nguyên: modular monorepo theo
[ADR 0001](../adr/0001-modular-monorepo-foundation.md) (một repo, một toolchain
pin, hai ứng dụng build độc lập), backend là **modular monolith** — một binary
Go, module nội bộ có ranh giới rõ, không microservices cho đến khi quy mô buộc
phải đổi.

```mermaid
flowchart LR
    subgraph androidI["apps/android — Kotlin/Compose — implemented (M0)"]
        UI["Splash screen — MainActivity, Compose, theme"]
    end
    subgraph serverI["apps/server — Go — implemented (M0)"]
        API["cmd/api — chi router<br/>/healthz · /readyz · /api/v1/version"]
        MIG["cmd/migrate — custom migrator<br/>advisory lock · transaction per file"]
    end
    DB[("PostgreSQL + PostGIS<br/>extensions: postgis · uuid-ossp · pg_trgm")]
    UI -. "business API: planned (M1+)" .-> API
    MIG --> DB
```

Kiến trúc đích khi các milestone M1–M7 hiện thực:

```mermaid
flowchart TB
    subgraph androidT["Android app — planned components"]
        UIF["UI features<br/>Circle · Live Map · History · Alerts · SOS · Diagnostics"]
        LE["Location Engine<br/>state machine, battery-aware"]
        SYNC["SyncManager<br/>durable queue, batch, retry"]
        ROOM[("Room DB")]
        FCMC["FCM client"]
    end
    subgraph serverT["Go backend — planned modules (modular monolith)"]
        AUTH["auth · devices"]
        CIRC["circles · membership"]
        ING["location ingestion"]
        RT["realtime hub<br/>transport: quyết định mở"]
        EVT["places · alerts · SOS · journeys"]
        NOTIF["notification dispatcher"]
        AUDIT["audit · privacy enforcement"]
    end
    PG[("PostgreSQL + PostGIS")]
    FCM["Firebase Cloud Messaging"]
    UIF --> LE
    UIF --> SYNC
    LE --> ROOM
    SYNC --> ROOM
    FCMC --> UIF
    SYNC -. "HTTPS batch" .-> ING
    UIF -. "HTTPS" .-> AUTH
    UIF -. "HTTPS" .-> CIRC
    RT -.-> UIF
    NOTIF --> FCM
    AUTH --> PG
    CIRC --> PG
    ING --> PG
    EVT --> PG
    AUDIT --> PG
```

> Ranh giới và tên module phía trên là **định hướng** để tránh rơi vào trạng
> thái big ball of mud; đặt tên chính xác package khi hiện thực từng milestone,
> theo layout rule của [`../../AGENTS.md`](../../AGENTS.md): business logic nằm
> trong `internal/`, `cmd/` chỉ giữ entrypoint mỏng.

## 2. Ranh giới component Android

Hiện tại (M1–M2, Implemented): `MainActivity`, `ui/splash`, `ui/signin`,
`ui/home` (account, consent, devices), `ui/circle`, `data/auth` (session
storage sealed by Keystore, SessionInterceptor, pending revocation),
`data/circles`, `data/api` (Retrofit/Moshi, ADR 0003), backup/data
extraction rules excluding `sharedpref`, `database` and `file/datastore`.
Chưa có permission location nào trong manifest — Location Engine là M3.

Các ranh giới planned (thứ tự các nhóm, không phải thứ tự thư mục bắt buộc):

| Component | Trách nhiệm | Ghi chú |
| --- | --- | --- |
| **UI features** (`ui/` theo feature) | Màn hình cho từng nhóm tính năng; Composable previewable | Không chứa logic thu thập/gửi dữ liệu vị trí |
| **Location Engine** | State machine `IDLE/STATIONARY/MOVING/JOURNEY/DEGRADED/OFFLINE/PAUSED`; Fused Location Provider; foreground service type `location` | Module Kotlin riêng, quan sát được qua Diagnostics; không viết trực tiếp vào network |
| **SyncManager** | Durable queue → batch upload → retry/backoff → idempotency | Dữ liệu đi qua đây, không đi thẳng từ Engine ra network |
| **Room persistence** | Location Point chờ gửi, trạng thái sync | Nằm trong domain `database` — đã bị backup rules loại trừ; chính sách backup từng file quyết định trước khi cho phép rời thiết bị |
| **Network client** | HTTP đến Go API; kết nối realtime | Contract theo [`../../packages/api-contracts/openapi.yaml`](../../packages/api-contracts/openapi.yaml) |
| **FCM client** | Nhận push sự kiện; không nhận GPS | Xem [Push notification flow](#43-push-notification-flow-planned--m6) |

## 3. Module Go

Module nội bộ `internal/` và trạng thái hiện tại (cập nhật sau M1/M2):

| Module | Trách nhiệm | Trạng thái |
| --- | --- | --- |
| `httpapi` + `config` | Router, middleware, error envelope, cấu hình | Implemented (M0–M2) |
| `auth` | Đăng nhập, phiên, thiết bị, consent switch, xóa tài khoản | Implemented (M1) |
| `circles` | Circle, membership, invite, vai trò, giải tán | Implemented (M2) |
| `audit` | Ghi sự kiện privacy trong cùng transaction với nghiệp vụ | Implemented (M1, ownership thống nhất ở ADR 0004) |
| `testdb` | Scratch database cho integration tests | Implemented (test infrastructure) |
| `locations` | Ingestion Location Point (identity, idempotency, validation), truy vấn history | Planned (M3, M5) |
| `realtime` | Hub phân phát vị trí realtime | Planned (M4) |
| `places` / `alerts` | Geofence events, verification, chống nhiễu | Planned (M5) |
| `sos` / `journeys` | Event lifecycle, acknowledge | Planned (M6, M7) |
| `notify` | Dispatcher FCM, lọc người nhận theo quyền | Planned (M6) |
| `audit` | Ghi sự kiện privacy quan trọng | M1 (nền móng) |

Nguyên tắc giữ nguyên: contract-first (đổi OpenAPI trước handler); API server
chạy được không cần database cho đến khi tính năng đầu tiên cần; `log/slog`
JSON; version inject lúc build.

## 4. Luồng dữ liệu

### 4.1 Location ingestion + offline sync (Planned — M3)

```mermaid
sequenceDiagram
    autonumber
    participant E as Location Engine (Android)
    participant Q as Sync Queue — Room (Android)
    participant I as Ingestion API (Go)
    participant P as PostgreSQL/PostGIS

    E->>E: ghi nhận theo engine state (tần suất thích ứng)
    E->>Q: ghi bền vững ngay cả khi offline
    Q->>I: batch upload khi có mạng, retry với backoff
    I->>I: xác thực · authorization · idempotency check
    I->>P: ghi Location Point (device time + server time, cờ backfill)
    I-->>Q: xác nhận → đánh dấu điểm đã gửi
```

Bất biến của luồng này: điểm đã ghi vào Room không bị mất chỉ vì upload thất
bại; gửi lại không sinh trùng (idempotency key); original timestamps được giữ
nguyên — backfill không làm sai thứ tự thời gian; nếu người dùng tắt chia sẻ
hoặc rời Circle, dữ liệu chưa gửi được xử lý theo quy tắc ở
[`product/features.md`](../product/features.md#7-offline-sync) (mặc định loại bỏ
với Circle đã rời).

### 4.2 Realtime distribution (Planned — M4)

```mermaid
sequenceDiagram
    autonumber
    participant I as Ingestion API (Go)
    participant H as Realtime hub (Go)
    participant V as Viewer client (Android)

    I->>H: location mới đã ghi bền vững
    H->>H: lọc người xem còn quyền tại thời điểm này
    H->>V: đẩy qua kết nối realtime (transport: quyết định mở)
    Note over V: UI hiển thị kèm freshness, không bao giờ trình bày như tuyệt đối
```

Ràng buộc: realtime không bao giờ đi qua FCM; người mất quyền (tắt chia sẻ, rời
Circle) bị hub loại khỏi phân phát từ nguồn. Last known location là fallback
hiển thị, không phải kênh realtime.

### 4.3 Push notification flow (Planned — M6)

```mermaid
sequenceDiagram
    autonumber
    participant X as Sự kiện (SOS / Place Alert / Journey)
    participant N as Notification dispatcher (Go)
    participant F as Firebase Cloud Messaging
    participant R as Receiver device (Android)

    X->>N: sự kiện cần thông báo
    N->>N: lọc người nhận theo quyền hiện tại
    N->>F: push — payload không chứa tọa độ
    F->>R: thông báo đến thiết bị
    Note over X,R: ba mốc phân biệt: đã gửi · FCM chấp nhận · người nhận xác nhận
```

FCM chỉ mang **sự kiện**, không mang GPS liên tục. FCM data message có thể dùng
làm tín hiệu "có dữ liệu mới, hãy fetch" — mẫu này không vi phạm ràng buộc nhưng
là quyết định mở (xem §7).

## 5. Security boundaries

Các ranh giới tin cậy và biện pháp tương ứng:

| Ranh giới | Mức tin cậy | Biện pháp |
| --- | --- | --- |
| Android device → Go API | **Không tin cậy** — mọi request từ client đều có thể giả mạo | Authorization per-request từ backend (không dựa UI); validation; idempotency; không tin timestamp client một cách mù quáng (ghi cả hai mốc) |
| Internet → chi router | Bán tin cậy | Không dùng `middleware.RealIP` (spoofing `X-Forwarded-For` — bất biến Implemented); allowlist trusted-proxy là việc của deployment |
| Go API → PostgreSQL | Tin cậy nội bộ | Dev: postgres publish **loopback only** qua network `db-host`; production: database trên internal network, không publish port |
| Go API → FCM | Dịch vụ bên thứ ba | FCM chỉ nhận payload sự kiện, không chứa tọa độ; kênh một chiều từ dispatcher |
| Device storage | Tin cậy thiết bị | Backup/data-extraction rules loại trừ `sharedpref` + `database` (Implemented); chính sách từng file Room quyết định trước khi dữ liệu được backup |
| Logs | — | Không log token, tọa độ nhạy cảm, credentials (quy tắc chung, cả Go và Android) |

## 6. Technical decisions đã chốt (tham chiếu)

- Modular monorepo — [ADR 0001](../adr/0001-modular-monorepo-foundation.md).
- Authentication (Google Sign-In + opaque sessions) — [ADR 0002](../adr/0002-authentication-google-signin-opaque-sessions.md).
- Android JSON (Moshi) — [ADR 0003](../adr/0003-android-json-moshi.md).
- M1/M2 hardening: xóa tài khoản không giải tán Circle có thành viên, cách ly
  identity dev/Google, audit một sự kiện một dòng trong transaction, session
  lifecycle offline, backup exclusion, installation identity, consent ≠
  tracking — [ADR 0004](../adr/0004-m1-m2-hardening.md).
- Custom migrator (không golang-migrate), advisory lock, transaction per file,
  bookkeeping table `schema_migrations` do migrator sở hữu — Implemented, có
  integration test trong CI.
- Runtime images pin bằng digest; Dependabot nâng tag + digest cùng nhau.
- Toolchain pin qua `mise.toml`/`mise.lock` với drift check.

## 7. Các quyết định kỹ thuật chưa chốt

Những quyết định này **cần được chốt trước khi hiện thực milestone tương ứng**
và nên có ADR riêng khi quyết:

1. **Realtime transport** — WebSocket là định hướng; cân nhắc SSE/polling trước
   khi chốt (chi phí vận hành, reconnect, môi trường mạng của người dùng Việt Nam).
2. ~~**Phương thức authentication**~~ — đã chốt: Google Sign-In + opaque
   session ([ADR 0002](../adr/0002-authentication-google-signin-opaque-sessions.md)).
3. **FCM data message làm tín hiệu fetch** — dùng hay không, và cơ chế debounce.
4. **Idempotency key của Location Point** — thiết kế key (device-generated UUID,
   hash của điểm…) và hành vi server khi trùng.
5. **Retention mặc định** — số ngày cụ thể cho location history và event data.
6. **Confidence calibration** — phương pháp nếu tiến tới confidence score.
7. **Production deployment** — target (VPS, cloud, managed Postgres…) chưa quyết;
   ảnh hưởng observability và SLO đo được.
8. **Một-Circle trong MVP** — xác nhận giữ một-Circle hay nới sớm; ảnh hưởng
   schema membership và UX switcher.
9. **Rate limiting SOS/invite** — ngưỡng và cơ chế chống lạm dụng.
10. **Chính sách backup từng file Room** — file nào được phép backup khi nào.
    DataStore đã bị loại trừ hoàn toàn (ADR 0004); chính sách cho từng file
    Room vẫn mở trước M3.
11. **Pending-revocation retry ngoài app start** — hiện chỉ chạy khi app mở;
    WorkManager/background scheduler là ứng viên khi M6 thêm bề mặt FCM.

Mỗi quyết định khi chốt cần ADR mới trong [`../adr/`](../adr/) và cập nhật tài
liệu liên quan ở `docs/product/`.
