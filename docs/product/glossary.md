# Glossary — AnVề

> Định nghĩa các thuật ngữ dùng xuyên suốt documentation và (sau này) trong code
> và UI. Khi đặt tên biến, bảng, endpoint hoặc viết copy UI, dùng đúng thuật ngữ
> này để tài liệu và code không trôi dạt. Các khái niệm chưa hiện thực được đánh
> dấu *(Planned)*.

## Sản phẩm và tổ chức

- **AnVề** — tên sản phẩm, không dịch, không version hóa trong UI.
- **Tagline** — “Đường về nhà, gần hơn mỗi ngày.” Hiển thị trên splash screen.
- **Family Circle** *(Implemented — M2)* — nhóm gia đình là đơn vị chia sẻ và ủy quyền
  trung tâm. Thành viên của một Circle thấy nhau theo quyền từng người cấp,
  không phải theo quyền của người tạo nhóm.
- **Member** *(Implemented — M2)* — người dùng đã tham gia một Circle. Mỗi member là
  **chủ thể dữ liệu** của vị trí của chính mình.
- **Quản trị Circle (Circle admin)** *(Planned)* — thành viên giữ vai trò quản
  trị nhóm (mời, giải tán…). Vai trò quản trị **không** kèm quyền đọc dữ liệu
  vị trí của người khác.
- **Invite code / invite link** *(Implemented — M2)* — mã/đường dẫn để tham gia Circle.
  Có hạn dùng, thời hạn hiệu lực; tham gia luôn cần người được mời chủ động
  chấp nhận.
- **Device** *(Implemented — M1)* — một thiết bị Android đăng nhập vào một tài khoản.
  Một tài khoản có thể có nhiều device/phiên đăng nhập, và chủ tài khoản xem
  được danh sách này.
- **MVP** — Minimum Viable Product: tập tính năng nhỏ nhất để gia đình dùng được
  thật. Theo roadmap, MVP ≈ M1–M6.

## Vị trí

- **Location Point** *(Implemented — M3)* — một bản ghi vị trí đơn lẻ: tọa độ, độ chính
  xác, **thời điểm thiết bị ghi nhận** và **thời điểm server nhận** (hai mốc
  phân biệt), nguồn vị trí, cờ nguồn dữ liệu (ghi trực tiếp hoặc đồng bộ bù).
- **Authorized latest location** *(Implemented — M4)* — điểm gần nhất mà
  viewer được phép đọc qua snapshot; bản thân snapshot không chứng minh thiết
  bị nguồn đang online.
- **Last known location** *(Implemented — M4)* — vị trí cuối cùng đã biết của
  một thiết bị, kèm thời điểm. UI luôn gắn nhãn freshness; không gọi điểm cũ
  là vị trí hiện tại.
- **Location age** *(Implemented — M4)* — thời gian từ lúc thiết bị ghi nhận
  đến snapshot server; Android tiếp tục tăng age bằng monotonic clock khi map
  đang hiển thị.
- **GPS accuracy** — độ chính xác (mét) do location provider báo cáo cho một
  Location Point. Khác với freshness (độ mới): một điểm có thể rất chính xác
  nhưng đã cũ, hoặc mới nhưng sai.
- **Location freshness** *(Implemented — M4)* — độ mới theo thời điểm ghi nhận:
  Fresh (≤2 phút), Delayed (≤15 phút), Stale (>15 phút), Unknown khi thiếu
  mẫu hoặc timestamp tương lai. Offline không được suy ra từ việc thiếu GPS.

## Location Confidence

- **Location Confidence Engine** *(Implemented — M4)* — phân loại freshness
  từ `recorded_at` và server time; accuracy hiển thị riêng, không suy diễn
  trạng thái online/offline khi chưa có presence signal.
- **Confidence states** *(Implemented — M4)* — `Fresh`, `Delayed`, `Stale`,
  `Unknown`. `Offline` chỉ dùng sau này khi có authoritative presence signal.
  Đây là trạng thái **của dữ liệu**, không phải đánh giá độ an toàn của con người.
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
- **Ingestion API** *(Implemented — M3)* — endpoint Go nhận Location Point từ
  Android client qua HTTPS, kiểm tra consent và membership rồi lưu idempotent.
- **Live location snapshot** *(Implemented — M4)* — snapshot vị trí mới nhất
  đã được server phân quyền; Android cập nhật bằng HTTPS conditional polling
  với ETag khi màn hình bản đồ ở foreground.
- **Server-push realtime distribution** *(Planned)* — phân phát sự kiện vị trí
  qua SSE/WebSocket; chưa được triển khai trong M4 MVP.
- **FCM (Firebase Cloud Messaging)** — kênh push notification cho sự kiện
  (invite, Place Alert, SOS, journey). **Không** dùng để truyền GPS liên tục.
- **PostGIS** — phần mở rộng không gian của PostgreSQL, dùng cho dữ liệu vị trí
  trong `location_points` (M3) và mô hình quyền audience/epoch (M4).
- **Modular monolith** — kiến trúc backend Go: một binary, các module nội bộ có
  ranh giới rõ. Không microservices cho đến khi quy mô buộc phải đổi.
