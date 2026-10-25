# Tổng quan sản phẩm AnVề

> Tài liệu này là **source of truth** cho định vị sản phẩm, tầm nhìn, đối tượng
> sử dụng, điểm khác biệt và các nguyên tắc thiết kế của AnVề. Khi tài liệu
> khác (README, `features.md`, `roadmap.md`…) và tài liệu này khác nhau, tài
> liệu này được ưu tiên cho các chủ đề thuộc phạm vi của nó.

## 1. AnVề là gì?

**AnVề** là ứng dụng **Family Safety & Location Sharing** trên Android: giúp
các thành viên trong gia đình chủ động chia sẻ vị trí, biết nhau đang ở đâu,
vị trí được cập nhật lần cuối khi nào, dữ liệu đó đáng tin ở mức nào, và có
cần hỗ trợ hay không.

- **Tên sản phẩm:** AnVề
- **Tagline:** “Đường về nhà, gần hơn mỗi ngày.” (hiển thị trên splash screen)
- **Loại sản phẩm:** Android Family Safety & Location Sharing Application
- **Nền tảng:** Android native (Kotlin + Jetpack Compose)
- **Backend:** Go + PostgreSQL/PostGIS
- **Repository:** `QuocDuong16/anve`

### Tầm nhìn

Gia đình lo cho nhau nhất khi biết rõ nhau đang ở đâu và có ổn hay không —
nhưng không ai muốn bị theo dõi ngược đời. AnVề đặt việc chia sẻ vị trí vào
tay từng thành viên: mỗi người tự quyết định khi nào chia sẻ, chia sẻ với ai,
và có thể dừng lại bất cứ lúc nào. Đồng thời, AnVề không dừng lại ở việc vẽ
một marker trên bản đồ: ứng dụng cho người xem biết dữ liệu vị trí đó **mới
đến đâu và đáng tin ở mức nào**, thay vì buộc họ phỏng đoán.

### Định vị

*Family Safety & Location Intelligence for Android.*

Sản phẩm hướng đến kiến trúc đủ tốt để mở rộng hoặc thương mại hóa trong
tương lai, nhưng quy mô sử dụng ban đầu là gia đình nhỏ.

## 2. Đối tượng sử dụng

- **Nhóm gia đình nhỏ** muốn nắm được hành trình của nhau: con đi học, vợ/chồng
  đi làm về muộn, bố mẹ ở xa.
- **Thành viên được chia sẻ vị trí** — người xem bản đồ để an tâm hoặc kịp
  hỗ trợ khi có sự kiện bất thường.
- **Chủ thể dữ liệu** — mỗi thành viên là người chủ dữ liệu vị trí của chính
  mình, tự kiểm soát việc chia sẻ.

AnVề **không** nhắm đến việc theo dõi người khác mà không có sự đồng ý. Ứng
dụng không phải spyware, không phải phần mềm giám sát bí mật.

## 3. Giá trị cốt lõi

1. **An tâm có căn cứ** — người xem không chỉ thấy marker mà còn thấy dữ liệu
   mới đến đâu (freshness) và đáng tin ở mức nào (confidence), nên không nhầm
   “điện thoại mất mạng” với “chuyện gì đó đã xảy ra”.
2. **Chia sẻ có chủ quyền** — vị trí chỉ được chia sẻ khi chủ thể dữ liệu đồng
   ý, và việc đồng ý đó có thể thu hồi bất cứ lúc nào, được backend thực thi.
3. **Thân thiện với pin và thực tế Android** — Location Engine điều chỉnh chiến
   lược thu thập theo trạng thái di chuyển thay vì hứng GPS liên tục.
4. **Hỗ trợ đúng lúc** — Place Alerts, SOS và Safe Journey tập trung vào các
   khoảnh khắc gia đình thực sự cần nhau: đến/trường học, về nhà muộn, cần
   giúp đỡ.

## 4. Nguyên tắc thiết kế

Bốn nguyên tắc bắt buộc cho mọi quyết định sản phẩm và kỹ thuật:

| Nguyên tắc | Ý nghĩa thực thi |
| --- | --- |
| **Privacy-first** | Consent-based sharing; backend thực thi quyền, không dựa vào UI; minimal collection và retention; không tracking bí mật. |
| **Reliability-first** | Không mất dữ liệu đã ghi nhận bền vững chỉ vì upload thất bại; last known location không bị đồng nhất với live location; hành vi bị giới hạn bởi OS phải được trình bày đúng. |
| **Battery-aware** | Location Engine thích ứng theo trạng thái di chuyển; không đánh đổi pin vô lý để lấy tần suất GPS; chiến lược thu thập phải đo lường được. |
| **Transparency-first** | Người dùng luôn biết khi nào vị trí đang được chia sẻ; trạng thái dữ liệu được thể hiện rõ; diagnostics có kiểm soát. |

## 5. Điểm khác biệt (differentiation strategy)

Các tính năng Family Circle, Live Map, History, Geofencing và SOS là **nền
tảng chung của cả nhóm ứng dụng family safety**, không phải lợi thế độc quyền
của AnVề. AnVề lựa chọn tập trung khác biệt ở năm điểm sau — đây là **chiến
lược định vị sản phẩm**, không phải những kết quả kỹ thuật đã được chứng minh:

- **A. Trustworthy Location** — người dùng thấy được dữ liệu vị trí mới đến đâu
  và đáng tin ở mức nào, thay vì một marker câm lặng.
- **B. Battery-Aware Tracking** — Location Engine thu thập thích ứng, có chiến
  lược rõ ràng và đo lường được theo trạng thái di chuyển.
- **C. Privacy & Consent** — quyền chia sẻ nằm trong tay chủ thể dữ liệu và
  được backend kiểm soát, không chỉ ở tầng UI.
- **D. Safe Journey** — ưu tiên các trường hợp sử dụng thực tế của gia đình:
  đi học, đi làm, di chuyển xa, về nhà muộn.
- **E. Android Reliability** — coi trọng hành vi foreground/background service,
  offline recovery, device restrictions (Battery Saver, OEM kills) và những
  đặc thù thiết bị Android mà các sản phẩm cùng loại thường bỏ qua.

## 6. Mục tiêu và phi mục tiêu

### Mục tiêu (goals)

- Cung cấp chia sẻ vị trí trong gia đình với độ tin cậy dữ liệu được thể hiện rõ.
- Cho phép từng thành viên kiểm soát hoàn toàn việc chia sẻ của chính mình.
- Vận hành bền vững trên thiết bị Android thực tế: nền, mất mạng, pin yếu, OEM restrictions.
- Giữ kiến trúc đơn giản (modular monolith, một repo, một toolchain) cho đến khi quy mô buộc phải thay đổi.
- Có đủ tài liệu để một developer hoặc AI agent mới onboard không cần đọc lại lịch sử chat.

### Phi mục tiêu (non-goals)

- **Không** tracking bí mật; **không** giám sát người khác không có sự đồng ý.
- **Không** cam kết GPS hoạt động 24/7 trong mọi điều kiện, và **không** cam kết
  realtime tuyệt đối hay độ chính xác GPS tuyệt đối.
- **Không** dùng FCM để truyền GPS liên tục; location ingestion và realtime
  distribution là việc của backend.
- **Không** xây microservices, không thêm Redis/Kafka chỉ vì xu hướng; không
  dùng Firebase Realtime Database làm kho dữ liệu vị trí chính.
- **Không** thay thế dịch vụ cấp cứu chuyên nghiệp (SOS chỉ là kênh báo cho
  người thân).
- **Không** gán “độ an toàn” định lượng cho người thân — confidence đo độ mới
  và độ tin cậy **của dữ liệu**, không phải xác suất rằng ai đó đang an toàn.

## 7. Phân loại trạng thái (status taxonomy)

Mọi tính năng và mốc phát triển trong tài liệu này được phân loại theo 5 mức.
Taxonomy này là quy ước chung của toàn bộ documentation.

| Trạng thái | Định nghĩa |
| --- | --- |
| **Implemented** | Tồn tại trong source code và có bằng chứng cụ thể. |
| **Verified** | Implemented, đồng thời đã có kiểm thử hoặc bằng chứng xác nhận thêm (CI, integration test, smoke test). |
| **Planned** | Nằm trong product scope đã chốt (theo brief này) nhưng chưa triển khai. |
| **Proposed** | Ý tưởng mở rộng, chưa phải yêu cầu đã chốt; cần quyết định trước khi lên kế hoạch. |
| **Not in scope** | Cố ý loại khỏi phạm vi hiện tại (xem Phi mục tiêu). |

Lưu ý quan trọng khi đọc trạng thái:

- CI PASS chỉ chứng minh code build, test và lint sạch — **không** chứng minh
  tính năng Android đã hoạt động ổn định trên điện thoại thật. Bằng chứng
  on-device phải ghi rõ ràng.
- Tài liệu mô tả cả trạng thái hiện tại của source code lẫn tầm nhìn sản phẩm,
  nhưng luôn phân biệt hai điều đó; không bao giờ trình bày Planned như đã
  Implemented.
- Trạng thái chi tiết của từng nhóm chức năng nằm ở `features.md`; trạng thái
  từng milestone nằm ở `roadmap.md`.

## 8. Bản đồ tài liệu

| Tài liệu | Nội dung |
| --- | --- |
| [`features.md`](features.md) | Đặc tả chức năng đầy đủ cho 12 nhóm tính năng + trạng thái từng nhóm. |
| [`user-journeys.md`](user-journeys.md) | Hành trình người dùng end-to-end: onboarding, Circle, chia sẻ, Live Map, Place Alerts, SOS, Safe Journey. |
| [`privacy-and-safety.md`](privacy-and-safety.md) | Consent, authorization, retention, deletion, chống lạm dụng, giới hạn của tính năng an toàn. |
| [`roadmap.md`](roadmap.md) | Milestone M0–M8 và trạng thái thực tế đã xác minh của từng mốc. |
| [`glossary.md`](glossary.md) | Định nghĩa thuật ngữ (Circle, Location Point, Freshness, Confidence…). |
| [`non-functional-requirements.md`](non-functional-requirements.md) | Quality attributes, cách đo, và proposed quality targets. |
| [`../architecture/overview.md`](../architecture/overview.md) | Kiến trúc logic, ranh giới component, data flows, security/trust boundaries. |
| [`../adr/`](../adr/) | Architecture decision records đã được chấp nhận. |
