# AnVề

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Gia đình nhỏ muốn chủ động chia sẻ vị trí trong các tình huống như con đi học,
người thân đi làm về muộn hoặc bố mẹ ở xa. Mỗi thành viên là chủ dữ liệu vị trí
của mình; người thân được cấp quyền xem dùng thông tin đó để an tâm và liên hệ
khi cần hỗ trợ. Ngôn ngữ hiện tại của ứng dụng là tiếng Việt.

## Product Purpose

AnVề là ứng dụng Family Safety & Location Sharing trên Android. Sản phẩm giúp
gia đình biết vị trí được chia sẻ, thời điểm cập nhật và độ tin cậy của dữ liệu,
đồng thời giữ quyền quyết định chia sẻ trong tay từng thành viên.

Thành công là người dùng hiểu dữ liệu đang cho biết điều gì, kiểm soát được ai
được xem và có thể chủ động dừng chia sẻ. Khả năng chạy nền và mức tiêu thụ pin
phải được đo trên thiết bị thực tế trước khi đưa ra cam kết.

## Positioning

Định vị: **Family Safety & Location Intelligence for Android**. Định hướng khác
biệt là minh bạch về freshness của vị trí, tracking thích ứng với trạng thái
di chuyển, consent có thể thu hồi và khả năng phục hồi trong điều kiện Android
thực tế. Đây là hướng phát triển sản phẩm; hiệu quả pin và độ ổn định trên
thiết bị thật chưa được chứng minh bằng các kiểm thử tự động.

## Operating Context

- Android native với Kotlin và Jetpack Compose; backend Go và PostgreSQL/PostGIS
  trong cùng monorepo.
- Luồng chính: đăng nhập → tạo hoặc tham gia Family Circle → chủ động bật chia
  sẻ và cấp quyền xem → xem bản đồ, lịch sử hoặc sự kiện được phép nhận.
- MVP giới hạn mỗi người dùng trong một Circle. Tham gia Circle không tự bật
  chia sẻ và không tự cấp quyền đọc vị trí.
- Quyền hệ thống, consent chia sẻ, trạng thái thu thập thực tế và quyền của người
  xem là các điều kiện riêng. Giao diện phải giúp người dùng phân biệt chúng.
- Mất mạng, Battery Saver, quyền bị thu hồi và hạn chế của OEM là điều kiện sử
  dụng cần hỗ trợ và giải thích; không hứa GPS hoạt động liên tục mọi lúc.

## Capabilities and Constraints

- Phạm vi đã triển khai M0–M6 gồm nền tảng ứng dụng/API, auth và privacy, Circle,
  Location Engine và offline queue, Live Map và freshness, History và Places,
  SOS và notifications. Trạng thái và bằng chứng của từng phần nằm trong
  [features](docs/product/features.md), [roadmap](docs/product/roadmap.md) và
  [implementation progress](docs/implementation/progress.md); không suy ra
  mọi mục mô tả trong đặc tả đã được triển khai.
- Safe Journey, Diagnostics hoàn chỉnh và field validation thuộc M7; release
  hardening và Google Play readiness thuộc M8. Các phần này chưa được coi là
  chức năng đã hoàn tất.
- Consent mặc định tắt, do chủ thể dữ liệu quyết định; backend thực thi quyền
  trên từng request. Chủ nhóm không thể tự kích hoạt tracking cho người khác.
- Live Map dùng conditional polling khi foreground theo chu kỳ 10 giây. Vị trí
  cũ phải kèm thời điểm cập nhật; freshness là độ mới của dữ liệu, không phải
  xác suất một người đang an toàn hay bằng chứng thiết bị đang online.
- Place Alerts được đánh giá từ mẫu vị trí đã đồng bộ; sự kiện có thể trễ hoặc
  bị bỏ lỡ. Không coi đó là OS geofence wakeup hay bằng chứng an toàn.
- SOS hiện không đính kèm tọa độ và không tự bật GPS. Phải phân biệt server đã
  ghi nhận, push provider đã chấp nhận và người nhận đã xác nhận. FCM chỉ mang
  định danh sự kiện và loại, không truyền tọa độ GPS.
- Diagnostics và log mặc định không chứa tọa độ chính xác hoặc credentials;
  việc xuất dữ liệu phải do người dùng chủ động và công bố nội dung được xuất.
- Confidence score định lượng, nhiều Circle và server-push realtime là Proposed;
  không biến chúng thành yêu cầu hoặc cam kết đã được xác minh.
- Chưa chốt môi trường production và SLO triển khai; không tự thêm cam kết uptime,
  latency, số đo pin hoặc kết quả duyệt Google Play.

## Brand Commitments

- Giữ tên **AnVề** và tagline **“Đường về nhà, gần hơn mỗi ngày.”**
- Nội dung dùng tiếng Việt, diễn đạt rõ trạng thái và giới hạn của dữ liệu.
  Mất GPS hay ngừng cập nhật không được diễn đạt thành kết luận có tai nạn.
- AnVề là công cụ chia sẻ tự nguyện trong gia đình; không có tracking bí mật và
  không thay thế dịch vụ cấp cứu chuyên nghiệp.

## Evidence on Hand

- [Product overview](docs/product/overview.md) là nguồn chính cho định vị, đối
  tượng, mục tiêu và taxonomy Implemented / Verified / Planned / Proposed /
  Not in scope. File này tóm tắt ngữ cảnh dùng cho Impeccable; khi có khác biệt,
  đối chiếu tài liệu nguồn theo phạm vi của từng tài liệu.
- [Privacy & safety](docs/product/privacy-and-safety.md) quy định các bất biến
  consent, quyền truy cập, retention, deletion và giới hạn tính năng an toàn.
- [User journeys](docs/product/user-journeys.md) mô tả luồng sản phẩm;
  [non-functional requirements](docs/product/non-functional-requirements.md)
  phân biệt mục tiêu đề xuất với số đo thực tế.
- Mã nguồn Android/API, kiểm thử và các ADR nằm trong repository; progress ghi
  nhận kiểm thử backend, JVM và CI emulator. Các kiểm thử push hiện dùng fake
  provider; chưa xác minh gửi Firebase thật, Google Sign-In với OAuth client
  thật, Maps rendering với key thực, GPS/pin/OEM trên thiết bị vật lý.
- Có [ảnh splash](docs/screenshots/anve-splash.png) trong repository; ảnh này
  không chứng minh các màn hình khác hoặc hành vi runtime hiện tại.

## Product Principles

1. **Privacy-first:** consent và quyền xem do chủ thể dữ liệu kiểm soát, được
   backend thực thi; thu thập tối thiểu và tuân thủ retention/deletion.
2. **Reliability-first:** bảo vệ dữ liệu đã ghi bền vững trước lỗi upload trong
   giới hạn chính sách privacy; trạng thái chưa xác nhận phải được giữ rõ ràng.
3. **Battery-aware:** thu thập thích ứng theo di chuyển; đánh giá bằng số đo
   thực địa thay vì suy ra từ build hay bộ đếm.
4. **Transparency-first:** người dùng biết khi nào chia sẻ đang hoạt động, dữ
   liệu mới đến đâu và vì sao tracking bị gián đoạn; diagnostics cần đọc hiểu
   được bởi người không phải developer.
