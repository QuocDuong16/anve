# Non-functional requirements — AnVề

> Tài liệu này là **source of truth** cho các quality attributes của AnVề: định
> nghĩa, cách đo, và **proposed quality targets**. Mọi con số mục tiêu bên dưới
> đều là **proposed targets — chưa đo đạc trên hệ thống thật**; chúng tồn tại để
> định hướng thiết kế và làm chuẩn khi bắt đầu đo, không phải cam kết hiệu năng
> đã đạt được. Cập nhật số liệu thực tế khi field validation (M7) có kết quả.

## 1. Sáu metric phân biệt — không gộp chung

Cả documentation lẫn code KHÔNG được gộp các khái niệm sau vào một "GPS realtime"
chung chung:

| Metric | Định nghĩa | Đo ở đâu | Ảnh hưởng bởi |
| --- | --- | --- | --- |
| **GPS accuracy** | Sai số (mét) provider báo cáo cho một Location Point | Thiết bị nguồn | Vị trí (trong nhà/xe), chip, chế độ location |
| **Location freshness** | Tuổi dữ liệu tại lúc xem: từ lúc thiết bị ghi nhận đến lúc hiển thị | Client người xem | Chu kỳ thu thập, mạng, sync queue, transport |
| **Server ingestion latency** | Thời gian từ request đến lúc Location Point được ghi bền vững và có thể truy vấn | Go backend | DB write, index, batch |
| **Realtime distribution latency** | Thời gian từ lúc server ghi xong đến lúc người xem nhận qua kết nối realtime | Go backend → client người xem | Transport (WebSocket/polling), số kết nối |
| **Notification delivery** | Thời gian từ lúc sự kiện (SOS/Place Alert) được ghi đến lúc thông báo đến thiết bị nhận | Backend + FCM + thiết bị nhận | FCM, trạng thái thiết bị nhận |
| **Battery overhead** | Mức tiêu thụ pin thêm do AnVề, so sánh với thiết bị không chạy AnVề | Thiết bị nguồn (field) | Location Engine, tần suất wake, network |

Ví dụ vì sao phải tách: một marker có thể "mới" (freshness cao) nhưng sai
(accuracy kém); hoặc server ingestion rất nhanh nhưng người xem vẫn thấy dữ liệu
cũ vì transport polling chậm.

## 2. Quality attributes

Mỗi mục gồm: định nghĩa, cách đo, và proposed target.

### 2.1 Reliability

- **Định nghĩa:** dữ liệu đã ghi bền vững trên thiết bị không mất vì upload thất
  bại; hệ thống phục hồi đúng sau restart/reboot/mất mạng.
- **Cách đo:** test tự động cho offline queue (mất mạng → sync → không mất/không
  trùng); crash/reboot recovery test; tỉ lệ Location Point gửi thành công/tổng
  điểm ghi nhận.
- **Proposed target:** 0 điểm đã ghi bền vững bị mất; sync eventually-consistent
  khi có mạng.

### 2.2 Availability

- **Định nghĩa:** backend phục vụ các endpoint cốt lõi khi được deploy.
- **Cách đo:** health probe (`/healthz`, `/readyz`) + uptime monitoring khi có
  production deployment (chưa quyết định — [`../architecture/overview.md`](../architecture/overview.md#7-các-quyết-định-kỹ-thuật-chưa-chốt)).
- **Proposed target:** chưa đặt số cho đến khi có môi trường production; mọi SLO
  đặt ra phải kèm cách đo thật.

### 2.3 Data integrity

- **Định nghĩa:** Location Point có identity, timestamp, idempotency; thứ tự
  thời gian không bị bóp méo; điểm trùng bị loại.
- **Cách đo:** constraint/idempotency test ở tầng API; kiểm tra out-of-order
  backfill; timestamp thiết bị vs server được ghi riêng.
- **Proposed target:** không bản ghi trùng; không điểm có timestamp sau hiện tại
  hợp lệ hóa được; hai mốc thời gian (thiết bị/server) luôn cùng tồn tại.

### 2.4 Security

- **Định nghĩa:** authorization per-request từ backend; token không lộ; không
  log tọa độ/token/credentials; dependency được quét (govulncheck, gosec đã có
  trong CI M0 — Implemented).
- **Cách đo:** CI security scans (đã có); threat model review per feature;
  secret scanning.
- **Proposed target:** không endpoint nghiệp vụ nào thiếu authorization check
  có test; 0 vulnerability critical/high còn mở.

### 2.5 Privacy

- **Định nghĩa:** đúng mô hình consent/retention/deletion ở
  [`privacy-and-safety.md`](privacy-and-safety.md).
- **Cách đo:** audit event coverage test; kiểm tra retention job; review rằng
  mọi đường đọc dữ liệu đều qua authorization.
- **Proposed target:** 100% sự kiện privacy quan trọng có audit record; thu hồi
  quyền có hiệu lực phía server dưới thời gian ngắn (proposed: phút — đo được
  bằng test).

### 2.6 Battery consumption

- **Định nghĩa:** overhead pin trên thiết bị nguồn (xem metric #6).
- **Cách đo:** field measurement trên thiết bị thật — so sánh drain theo giờ khi
  bật/tắt chia sẻ (battery stats/battery historian); theo engine state
  (STATIONARY phải gần như không wake GPS).
- **Proposed target:** chỉ đặt sau field validation; nguyên tắc chốt từ bây giờ:
  đứng yên không được tiêu pin đáng kể hơn so với không dùng app.

### 2.7 Location freshness

- **Định nghĩa:** metric #2 — tuổi dữ liệu khi người xem nhìn.
- **Cách đo:** đo end-to-end bằng timestamp thiết bị trên client người xem;
  phân tích theo trạng thái (di chuyển/đứng yên/offline).
- **Proposed target:** trong trạng thái MOVING và có kết nối — dữ liệu hiển thị
  không cũ hơn mức cần để hỗ trợ trường hợp gia đình (số cụ thể proposed, chốt
  sau khi đo ingestion + distribution thật).

### 2.8 Offline resilience

- **Định nghĩa:** trải nghiệm khi thiết bị nguồn và/hoặc người xem mất mạng.
- **Cách đo:** test kịch bản máy bay; đo thời gian backfill sau khi có mạng;
  UI hiển thị đúng trạng thái offline.
- **Proposed target:** không mất dữ liệu (trùng reliability); UI chuyển sang
  last known + nhãn tuổi trong vài giây sau khi mất kết nối.

### 2.9 Latency

- **Định nghĩa:** tổng hợp metric #3, #4, #5 — đo riêng từng cái.
- **Cách đo:** instrumentation phía server (structured logging đã có — `slog`
  JSON, Implemented ở M0) + client-side timestamp so sánh.
- **Proposed target:** đặt per-metric khi có môi trường đo; không đặt một con
  số "realtime" chung.

### 2.10 Observability

- **Định nghĩa:** đủ dấu hiệu để trả lời "vì sao tracking không hoạt động" mà
  không cần đọc code (Diagnostics M7) và để vận hành backend.
- **Cách đo:** danh sách dấu hiệu bắt buộc trong
  [Diagnostics](features.md#12-tracking-health--diagnostics); backend: structured
  logs (đã có), metrics/tracing theo mốc triển khai.
- **Proposed target:** mỗi failure mode của Location Engine có một dấu hiệu
  quan sát được tương ứng.

### 2.11 Maintainability

- **Định nghĩa:** contract-first, module boundaries rõ, toolchain pin, tài liệu
  không trôi dạt khỏi code.
- **Cách đo:** đã có ở M0 (Implemented): OpenAPI validation, toolchain drift
  check, hai CI workflow độc lập, ADR.
- **Proposed target:** mọi thay đổi API đi qua contract trước; documentation
  được cập nhật cùng change (đã là quy tắc AGENTS.md).

### 2.12 Scalability

- **Định nghĩa:** hệ thống phục vụ quy mô gia đình ngay bây giờ, có hướng mở
  rộng khi cần mà không phải viết lại (modular monolith, PostGIS query đúng
  index, không phụ thuộc thứ gì đó không scale).
- **Cách đo:** load test ingestion khi có môi trường; review query plan PostGIS.
- **Proposed target:** không đặt số; nguyên tắc: mỗi quyết định schema/API phải
  không chặn tăng quy mô theo bậc độ lớn (một vài nghìn gia đình) mà không cần
  redesign.

## 3. Quy tắc sử dụng các con số trong tài liệu

1. Mọi con số mục tiêu phải được gắn nhãn **proposed target** cho đến khi có
   dữ liệu đo thật, và ghi rõ ngày/cách đo khi chuyển thành số thực tế.
2. Không trình bày kết quả đo lab như hiệu năng production; không trình bày
   mục tiêu như cam kết.
3. Khi một target được thay, ghi cả số cũ để nhìn thấy lịch sử hiệu chỉnh.
4. Giới hạn hệ điều hành (Doze, Battery Saver, OEM) có thể làm vi phạm bất kỳ
   target nào phía client — targets phía client luôn đọc kèm điều kiện này.
