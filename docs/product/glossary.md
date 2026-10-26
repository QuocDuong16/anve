# Glossary — AnVề

> Định nghĩa các thuật ngữ dùng xuyên suốt documentation và (sau này) trong code
> và UI. Khi đặt tên biến, bảng, endpoint hoặc viết copy UI, dùng đúng thuật ngữ
> này để tài liệu và code không trôi dạt. Các khái niệm chưa hiện thực được đánh
> dấu *(Planned)*.

## Sản phẩm và tổ chức

- **AnVề** — tên sản phẩm, không dịch, không version hóa trong UI.
- **Tagline** — “Đường về nhà, gần hơn mỗi ngày.” Hiển thị trên splash screen.
- **Family Circle** *(Planned)* — nhóm gia đình là đơn vị chia sẻ và ủy quyền
  trung tâm. Thành viên của một Circle thấy nhau theo quyền từng người cấp,
  không phải theo quyền của người tạo nhóm.
- **Member** *(Planned)* — người dùng đã tham gia một Circle. Mỗi member là
  **chủ thể dữ liệu** của vị trí của chính mình.
- **Quản trị Circle (Circle admin)** *(Planned)* — thành viên giữ vai trò quản
  trị nhóm (mời, giải tán…). Vai trò quản trị **không** kèm quyền đọc dữ liệu
  vị trí của người khác.
- **Invite code / invite link** *(Planned)* — mã/đường dẫn để tham gia Circle.
  Có hạn dùng, thời hạn hiệu lực; tham gia luôn cần người được mời chủ động
  chấp nhận.
- **Device** *(Planned)* — một thiết bị Android đăng nhập vào một tài khoản.
  Một tài khoản có thể có nhiều device/phiên đăng nhập, và chủ tài khoản xem
  được danh sách này.
- **MVP** — Minimum Viable Product: tập tính năng nhỏ nhất để gia đình dùng được
  thật. Theo roadmap, MVP ≈ M1–M6.

## Vị trí

- **Location Point** *(Planned)* — một bản ghi vị trí đơn lẻ: tọa độ, độ chính
  xác, **thời điểm thiết bị ghi nhận** và **thời điểm server nhận** (hai mốc
  phân biệt), nguồn vị trí, cờ nguồn dữ liệu (ghi trực tiếp hoặc đồng bộ bù).
- **Live location** *(Planned)* — vị trí gần nhất còn đang được cập nhật qua
  kết nối realtime. Chỉ tồn tại khi thiết bị nguồn online và đang chia sẻ.
- **Last known location** *(Planned)* — vị trí cuối cùng đã biết của một thiết
  bị, kèm thời điểm. **Không đồng nhất với live location**; UI phải gắn nhãn
  tuổi dữ liệu.
- **Location age** *(Planned)* — khoảng thời gian từ lúc thiết bị ghi nhận đến
  lúc người xem nhìn thấy.
- **GPS accuracy** — độ chính xác (mét) do location provider báo cáo cho một
  Location Point. Khác với freshness (độ mới): một điểm có thể rất chính xác
  nhưng đã cũ, hoặc mới nhưng sai.
- **Location freshness** *(Planned)* — độ mới của dữ liệu theo tuổi + trạng
  thái kết nối + tình trạng đồng bộ. Khác với GPS accuracy.

## Location Confidence

- **Location Confidence Engine** *(Planned)* — hệ thống tổng hợp location age,
  accuracy, trạng thái kết nối, permission, đồng bộ… thành trạng thái độ tin cậy
  dữ liệu hiển thị cho người xem.
- **Confidence states** *(Planned)* — `Fresh`, `Delayed`, `Stale`, `Offline`,
  `Unknown`. Là trạng thái **của dữ liệu**, không phải đánh giá độ an toàn của
  con người.
- **Confidence score** *(Proposed)* — điểm số định lượng cho độ tin cậy dữ liệu.
  Chỉ cân nhắc sau khi có phương pháp calibration/validation; trước đó chỉ dùng
  confidence states.

## Location Engine

- **Location Engine** *(Implemented — M3)* — module Kotlin thu thập vị trí trên
  thiết bị, thích ứng theo trạng thái di chuyển, tôn trọng pin và giới hạn OS.
- **Smart Tracking** *(Implemented — M3)* — hành vi thích ứng của Location
  Engine: ưu tiên cập nhật khi di chuyển, giảm truy cập vị trí khi đứng yên.
- **Engine states** *(Implemented — M3)* — hai trục độc lập: motion
  `IDLE`, `STATIONARY`, `MOVING`, `JOURNEY`; operational `ACTIVE`, `DEGRADED`,
  `OFFLINE`, `PAUSED`. `JOURNEY` là hook cho M7.
- **Foreground service (location)** *(Implemented — M3)* — Android service với
  type `location`, persistent notification + stop action, `START_NOT_STICKY`;
  bắt buộc theo Android khi theo dõi nền và là cơ chế transparency.
- **Battery Saver / Doze / OEM restrictions** — các cơ chế của Android và nhà
  sản xuất giới hạn background execution. Location Engine phải xử lý đúng, và
  **không** cam kết GPS đều đặn trong mọi điều kiện.

## Offline & đồng bộ

- **Offline Sync** *(Implemented — M3)* — tập cơ chế đảm bảo dữ liệu đã ghi
  bền vững không mất khi upload thất bại; WorkManager unique + network
  constraint, retry/backoff.
- **Sync Queue** *(Implemented — M3)* — hàng đợi bền vững (Room) các Location
  Point chờ gửi, owner-scoped, tối đa 5.000 điểm/7 ngày. Ghi trước, gửi sau.
- **Backfill / đồng bộ bù** *(Implemented — M3)* — gửi lại sau offline các
  điểm đã ghi trong lúc mất mạng; server giữ nguyên `recordedAt` gốc.
- **Idempotency** *(Implemented — M3)* — gửi lại cùng `(user, eventId)` không
  sinh bản ghi trùng; server trả các id đó trong `duplicates`.
- **Deduplication** *(Implemented — M3)* — unique `(user_id, event_id)` ở
  PostgreSQL cùng `ON CONFLICT DO NOTHING`; client cũng IGNORE eventId trùng.
- **Batch upload** *(Implemented — M3)* — tối đa 100 điểm/request ở client,
  server nhận tối đa 200; batch atomic.

## Sự kiện và tính năng

- **Place** *(Planned)* — địa điểm người dùng định nghĩa (Nhà, Trường học, Công
  ty, tùy chỉnh): tọa độ + bán kính geofence.
- **Geofence** *(Planned)* — vùng tròn quanh một Place; sự kiện enter/exit do
  Android Geofencing API phát hiện (được OS batch delivery — có thể trễ), kết
  hợp server-side verification khi cần.
- **Place Alert** *(Planned)* — thông báo đến/rời một Place gửi cho người được
  phép nhận. Bao gồm **event time** (lúc xảy ra) và **delivery time** (lúc thông
  báo đến) — hai mốc phân biệt.
- **SOS Event** *(Planned)* — sự kiện cấp cứu chủ động của một thành viên, có
  lifecycle state, vị trí đính kèm, danh sách người nhận.
- **Acknowledge** *(Planned)* — hành động người nhận xác nhận đã biết một SOS.
- **Safe Journey** *(Planned)* — hành trình chủ động từ điểm đi đến điểm đến,
  có người theo dõi được chọn, kết thúc bằng đến nơi hoặc hủy/auto-expire.
- **Journey state** *(Planned)* — trạng thái Location Engine khi một Safe
  Journey đang chạy, ưu tiên chiến lược cập nhật cao hơn.

## Hạ tầng

- **Contract-first** — quy tắc repo: đổi
  [`packages/api-contracts/openapi.yaml`](../../packages/api-contracts/openapi.yaml)
  trước khi đổi handler. Xem [`../../AGENTS.md`](../../AGENTS.md).
- **Ingestion API** *(Planned)* — endpoint Go nhận Location Point từ Android
  client qua HTTPS.
- **Realtime distribution** *(Planned)* — việc backend phân phát vị trí mới đến
  người xem (định hướng WebSocket; transport cuối cùng là quyết định mở).
- **FCM (Firebase Cloud Messaging)** — kênh push notification cho sự kiện
  (invite, Place Alert, SOS, journey). **Không** dùng để truyền GPS liên tục.
- **PostGIS** — phần mở rộng không gian của PostgreSQL, dùng cho truy vấn vị trí
  (đã cài đặt trong migration `0001_extensions`; các bảng nghiệp vụ chưa có).
- **Modular monolith** — kiến trúc backend Go: một binary, các module nội bộ có
  ranh giới rõ. Không microservices cho đến khi quy mô buộc phải đổi.
