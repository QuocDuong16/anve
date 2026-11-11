# M7-B — Safe Journey: chuẩn bị triển khai

**Status: first manual release Implemented — Automated Verified (`bcde472`);
native rendering approved; formal completion waived by user, field validation pending.** Quyết định bản đầu ở
[ADR 0012](../adr/0012-safe-journey-lifecycle.md), evidence hiện tại ở
[M7-B validation](m7b-validation.md). Phần dưới giữ checklist chuẩn bị; không
phải bằng chứng CI/field validation đã pass. Các yêu cầu đã chốt nằm ở
[features](../product/features.md#11-safe-journey),
[user journeys](../product/user-journeys.md#8-safe-journey) và
[roadmap](../product/roadmap.md#m7--safe-journey-diagnostics-field-validation).

## Điều kiện bắt đầu

- M7-A được kiểm chứng bằng Go/Android CI trên đúng commit, gồm emulator,
  SQLite và các thao tác Diagnostics; xem [tracking validation](tracking-validation.md).
- Có artifact native và review rõ giới hạn. Phân biệt kết quả CI/emulator với
  Maps/Firebase thật và field measurements trên điện thoại.
- Giữ các bất biến consent, capture checkpoint, owner binding và tắt acquisition
  khi bị thu hồi. Hàng đợi/outbox chưa rõ kết quả không được trình bày là thành công.
- Có quy trình thu thập dữ liệu thực địa. Chưa có calibration thì không chốt hoặc
  phát hành cảnh báo hành trình ngừng cập nhật dựa trên ngưỡng suy đoán.

## Phạm vi sản phẩm đã xác định

Chủ hành trình chủ động chọn điểm đến và từng người thân được theo dõi. Người
được chọn thấy tiến trình theo dữ liệu được phép đọc và nhận sự kiện bắt đầu,
kết thúc/đến nơi hoặc hủy. Trong hành trình, Location Engine có chiến lược cập
nhật ưu tiên; kết thúc hoặc hết hạn phải chấm dứt ưu tiên đó.

Mất GPS hoặc ngừng di chuyển không chứng minh tai nạn. Cảnh báo bất thường chỉ
dùng quy tắc đã kiểm chứng và ngôn ngữ như “hành trình ngừng cập nhật — hãy liên
hệ”. FCM không chở tọa độ, và provider acceptance không chứng minh delivery hay
người nhận đã đọc.

## Các quyết định cần chốt trước contract

| Chủ đề | Quyết định còn mở |
| --- | --- |
| Lifecycle | Trạng thái server-authoritative, giới hạn hành trình đồng thời, auto-expiry, hành vi đổi điểm đến |
| Audience | Journey grant độc lập hay cần location grant hiện hành; không được tự cấp quyền chỉ vì cùng Circle |
| Arrival | Người dùng xác nhận, phát hiện từ mẫu vị trí, hay kết hợp; accuracy/dwell/gap và điều kiện xác nhận cần bằng chứng |
| Offline | Bắt đầu offline có được phép không; nội dung và expiry outbox; reconciliation khi create/cancel có kết quả chưa rõ |
| Retention | Thời hạn metadata, điểm hành trình, sự kiện và deletion fences; quan hệ với xóa history/retention của chủ dữ liệu |
| Stale alerts | Inputs, ngưỡng, calibration và điều kiện không phát cảnh báo; chưa cam kết ETA hoặc latency |

Các chủ đề trong bảng đã được giải quyết cho bản đầu ở ADR 0012: xác nhận
đến nơi thủ công, một active journey, exact existing audience grants, online
start, expiry 15 phút–8 giờ ở API, retention tối đa 7 ngày và không có cảnh báo
stale tự động. Những khả năng tự động còn lại tiếp tục Planned.

## Trình tự triển khai

1. Chốt lifecycle/audience/offline/retention trong ADR và cập nhật tài liệu
   sản phẩm, privacy. Đặc biệt xử lý consent OFF, mất membership, account switch,
   revoke recipient, expiry và kết thúc trong khi một request đang in flight.
2. Thiết kế OpenAPI trước handlers: expected-owner binding, authorization mỗi
   lần đọc/ghi, stable operation ID và reconciliation cho unknown outcomes.
   Schema mới dùng migration append-only, có down migration phù hợp.
3. Backend: lifecycle và quyền trong transaction; audit và bounded notification
   outbox; test idempotency, concurrency, deletion/retention và expiry với PostGIS.
4. Android: owner-scoped state/outbox, explicit admission cho JOURNEY, phản hồi
   theo server và phục hồi sau process death. `MotionState.JOURNEY` hiện chỉ là
   policy hook; không coi sự tồn tại của enum là Safe Journey đã có.
5. Dùng **Impeccable bắt buộc** cho UI: xác định luồng tạo/theo dõi/kết thúc,
   các trạng thái permission/offline/expiry/error, tạo comp-first và duyệt ảnh
   trước khi code Compose. Giữ hệ thống Material và nhận diện AnVề hiện có.
6. Chạy JVM/backend tests, emulator interaction tests và native captures trong
   GitHub Actions. Kiểm chứng Firebase thật và điện thoại riêng; không dùng mock
   để tuyên bố GPS, arrival, latency, pin hoặc OEM đã được xác nhận.

## Exit criteria cần kiểm chứng

- Chỉ người được chọn và còn quyền mới đọc/nhận sự kiện; người ngoài Circle,
  người đã bị thu hồi, account cũ và response cũ không thể khôi phục truy cập.
- Bắt đầu/kết thúc/hủy/hết hạn có state rõ ràng; retries không tạo hành trình
  hoặc thông báo trùng; trạng thái chưa rõ được giữ để reconcile.
- Kết thúc hoặc mất consent chấm dứt ưu tiên JOURNEY; không tự bật lại GPS sau
  process kill, reboot, account change hoặc permission revocation.
- Dữ liệu cũ có tuổi/freshness; không suy diễn điện thoại mất mạng hay người gặp
  nguy hiểm chỉ từ marker không cập nhật.
- UI được xem trên emulator ở theme sáng/tối và chữ lớn, với controls thực sự
  thao tác được. Record SHA, CI run và artifacts; field evidence được ghi riêng.
- Cảnh báo/arrival tự động chỉ được đánh dấu Verified khi có bằng chứng phù hợp
  với quy tắc đã chốt, ngoài các test dùng dữ liệu tổng hợp.

## Giai đoạn tiếp theo sau bản thủ công

1. Chốt native finish review và ghi rõ giới hạn của cổng formal; chỉ merge khi
   kiểm thử liên quan xanh và các lỗi material đã giải quyết.
2. Chạy protocol [tracking validation](tracking-validation.md) trên điện thoại
   thật: consent OFF/revoke, account switch, process kill/reboot, mất mạng và
   recovery, kết thúc/hết hạn khi upload đang chạy. Ghi model/OEM, phiên bản,
   thời lượng, độ chính xác và số đo pin; không đưa tọa độ vào báo cáo diagnostics.
3. Kiểm tra Firebase thật trên tài khoản thử có quyền và đã bị revoke; ghi riêng
   provider acceptance, notification được nhận và thao tác mở/đọc lại server.
4. Dựa trên field samples mới đề xuất accuracy/dwell/gap cho arrival và stale;
   cần quyết định sản phẩm và bằng chứng calibration trước contract/UI mới.
5. Chuẩn bị M8 bằng inventory security/observability/release và tài liệu Data
   Safety/permissions đối chiếu hành vi thực tế. M8 chưa bắt đầu và chưa có kết
   luận đủ điều kiện phát hành Google Play.

## Ma trận thực địa cho bản thủ công — chưa chạy

Dùng hai điện thoại/tài khoản thử có consent; thêm một tài khoản không được
chọn làm đối chứng. Ghi SHA/APK, Android/OEM, thời điểm, expected/observed và
artifact đã lọc theo protocol tracking validation, không ghi token/tọa độ.

| Kịch bản | Expected | Bằng chứng cần thu |
| --- | --- | --- |
| Chọn một người xem, mở từ push | Chỉ người còn grant đọc được; màn chi tiết nêu đúng chủ hành trình | Server authorization readback, thông báo thực nhận và ảnh đã lọc |
| Thu hồi rồi cấp lại grant / rời rồi vào Circle | Lựa chọn cũ không được phục hồi | GET sau revoke/regrant/rejoin và trạng thái trên máy người xem |
| Create mất phản hồi, cancel khi offline | Không create ID mới hay khẳng định thành công; có mạng reconcile cùng ID | UI pending, server state, outbox metadata không tọa độ |
| Đã đến nơi so với Kết thúc | Chỉ thao tác đầu tạo ARRIVED; ưu tiên dừng ở cả hai, chia sẻ thường theo cài đặt riêng | State server và diagnostics trước/sau |
| Hết hạn khi offline hoặc upload đang chạy | Lease không vượt hạn; không tự xác nhận đến nơi | Đồng hồ thời gian thực/monotonic quan sát, state sau reconnect |
| Consent OFF, đổi owner, kill/reboot | Không khôi phục ưu tiên từ push/read/response cũ | Provider/service và queue owner-bound trước/sau |
| Trong nhà, GPS yếu, mẫu mock | Thiếu/cũ/accuracy/mock được thể hiện, không suy ra tai nạn hoặc ETA | Mẫu quan sát đã lọc, ảnh UI và điều kiện môi trường |
| Màn hình khóa, Battery Saver, hạn chế OEM | Ghi gián đoạn và số đo thực; không suy từ test emulator | Phiên baseline/Journey cùng điều kiện, pin/thời lượng và OEM |

Kết quả hiện tại của mọi hàng: **Not run — cần điện thoại và cấu hình dịch vụ
thật**. Ma trận là kế hoạch cho lượt tiếp theo, không phải bằng chứng đã pass.
