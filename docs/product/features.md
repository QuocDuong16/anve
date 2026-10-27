# Đặc tả chức năng AnVề

> Tài liệu này là **source of truth** cho phạm vi chức năng và trạng thái triển
> khai của từng nhóm tính năng. Mô tả bên dưới là **target product capabilities**
> — không có nghĩa là đã triển khai. Trạng thái của từng nhóm được ghi rõ ở cuối
> mỗi mục; quy ước trạng thái định nghĩa ở [`overview.md`](overview.md#7-phân-loại-trạng-thái-status-taxonomy).
>
> Mô tả kỹ thuật của kiến trúc nằm ở [`../architecture/overview.md`](../architecture/overview.md);
> điều kiện chấp nhận từng milestone nằm ở [`roadmap.md`](roadmap.md).

## 1. Nền tảng M0 — đã có trong source code

Các thành phần sau **Implemented** và được CI xác nhận (chi tiết ở
[`roadmap.md`](roadmap.md#m0--foundation)):

- Splash screen hiển thị wordmark “AnVề”, tagline và phiên bản build (JVM unit test).
- Go API server (chi): `GET /healthz`, `GET /readyz`, `GET /api/v1/version` — đúng theo [`packages/api-contracts/openapi.yaml`](../../packages/api-contracts/openapi.yaml).
- Custom migration runner (`cmd/migrate`): transaction từng file, advisory lock, rollback; integration test chạy trên PostgreSQL/PostGIS thật trong CI.
- Migration `0001_extensions`: `postgis`, `uuid-ossp`, `pg_trgm`.
- Local stack Docker Compose (postgres/postgis + migrate + api), toolchain pin qua mise, hai workflow CI độc lập (Go, Android).
- Android backup/transfer rules loại trừ `sharedpref`, `database` và `file/datastore` (nơi lưu session blob đã mã hóa và installation id).

### 1b. Authentication, thiết bị & privacy foundation — M1

**Implemented** (backend + Android client logic; chi tiết và bằng chứng ở
[`../implementation/progress.md`](../implementation/progress.md)):

- Đăng nhập Google qua Credential Manager: app lấy Google ID token, Go
  backend xác minh chữ ký/audience (ADR 0002) và cấp **session riêng của
  AnVề** (opaque access + refresh token, chỉ lưu hash trên server).
- Phiên đăng nhập bền (persistent), refresh có rotation, thu hồi tức thì
  (mỗi request được kiểm tra lại session), đăng xuất, xóa tài khoản (cascade
  + audit).
- **Xóa tài khoản an toàn:** chủ Circle còn thành viên khác nhận
  `CIRCLE_HAS_MEMBERS` và được hướng dẫn giải tán nhóm trước — xóa tài khoản
  không bao giờ âm thầm giải tán nhóm của người khác. Xóa thất bại (4xx/5xx/
  timeout) **không** xóa phiên local; kết quả không rõ được đối soát bằng
  request có xác thực.
- **Đăng xuất offline:** phiên local luôn bị xóa; nếu server không xác nhận,
  một bản ghi pending-revocation đã mã hóa được thêm vào **hàng đợi có giới hạn**
  (tối đa 8 bản ghi, mỗi bản ghi hết hạn sau 7 ngày, không ghi đè lẫn nhau)
  và được thử lại độc lập ở lần mở sau với backoff. Thông báo trên màn hình
  đăng nhập nói rõ server **chưa** xác nhận thu hồi; UI không tuyên bố
  "đã đăng xuất mọi nơi".
- Đăng ký thiết bị theo **app installation id** do app sinh (ổn định qua
  restart/update/sign-out, mới sau reinstall; không đọc hardware id); liệt
  kê/thu hồi thiết bị của chính mình; cross-user access bị từ chối (có test).
- **Consent switch** chia sẻ vị trí (mặc định TẮT) với audit record trong
  cùng transaction ghi nhận thay đổi; UI nói rõ switch là ghi nhận đồng ý,
  chưa phải GPS đang chạy. Từ M3, việc thu thập do tab Vị trí điều khiển
  (bật theo dõi + quyền OS), tách biệt hoàn toàn khỏi consent switch.
  Thay đổi consent dùng phiên bản compare-and-set; khi tắt offline, ý định
  thu hồi được lưu trong outbox mã hóa theo owner, dừng thu thập và xóa queue
  chưa gửi ngay. Sign-out không xóa ý định này; account khác không thể dùng
  credential hoặc operation của owner trước.
- Error envelope thống nhất (`code/message/requestId`), rate limiting cho
  endpoint đăng nhập; `readyz` kiểm tra database.
- Dev-only login chỉ mount khi `ENVIRONMENT=development` (staging/production/
  unknown bị từ chối ngay khi khởi động), chỉ phục vụ từ allowlist địa chỉ
  nguồn (mặc định loopback; non-loopback cần opt-in + CIDR tường minh; không
  tin `X-Forwarded-For`), và **cách ly identity**: dev fixture không thể đăng
  nhập vào tài khoản Google, Google không thể nhận tài khoản dev
  (`IDENTITY_CONFLICT`).
- Local data: session blob và hàng đợi pending-revocation mã hóa Keystore
  trong DataStore, loại trừ khỏi cloud backup và device transfer
  (`sharedpref`, `database`, `file/datastore`).

Chưa xác minh: chạy thực tế trên thiết bị (field validation) và luồng Google
với OAuth client thật (đang chờ cấu hình bên ngoài — xem
[`../implementation/setup-android.md`](../implementation/setup-android.md)).

Từ mục 2 trở đi, **toàn bộ là Planned hoặc Proposed** cho đến khi có code tương ứng.

## 2. Family Circle

> Trạng thái: **Implemented — Verified (M2)**: backend (PostgreSQL + integration
> tests: authorization matrix, invite lifecycle, concurrent redeem, dissolve
> cascade) và Android client (tạo/tham gia/rời/giải tán, mã mời, danh sách
> thành viên; unit tests qua MockWebServer). Đã smoke test end-to-end qua HTTP
> trên stack local. Chưa có field validation trên thiết bị thật. Chi tiết:
> [`../implementation/progress.md`](../implementation/progress.md).

**Mục đích.** Hệ thống nhóm gia đình — đơn vị chia sẻ và ủy quyền trung tâm của
AnVề: ai thuộc cùng nhóm, ai được xem ai, và sự đồng ý của từng người được ghi
nhận thế nào.

**User story.** *Là một thành viên gia đình, tôi muốn tạo một nhóm gồm những
người thân và mỗi người tự quyết định tham gia, để cả nhóm nhìn thấy nhau một
cách có sự đồng ý, không ai bị đưa vào một cách âm thầm.*

**Yêu cầu chức năng.**

- Tạo Family Circle; thành viên tạo nhóm giữ vai trò quản trị nhóm.
- Mời thành viên qua invite code hoặc invite link; người được mời **chủ động
  chấp nhận hoặc từ chối**.
- Xem danh sách thành viên kèm trạng thái hiển thị của từng người.
- Quản lý thông tin hiển thị của chính mình (tên hiển thị, avatar).
- Rời khỏi nhóm bất cứ lúc nào.
- Quản lý quyền truy cập dữ liệu theo vai trò và quyền chia sẻ của từng thành viên.
- Thu hồi quyền chia sẻ vị trí đã cấp.
- Xem các thiết bị và phiên đăng nhập thuộc tài khoản của mình.

**Luồng tương tác.** Tạo nhóm → nhận invite code/link → chia sẻ cho người thân
→ người thân đăng nhập, mở link, xem quyền sẽ cấp → chấp nhận → xuất hiện trong
danh sách thành viên. Rời nhóm: menu cài đặt cá nhân → “Rời nhóm” → xác nhận →
quyền chia sẻ của người đó với nhóm chấm dứt.

**Dữ liệu (conceptual — chưa có schema).** Circle, Membership (member, vai trò,
trạng thái tham gia), Invite (code, hạn dùng, số lần dùng), liên kết User ↔
Device ↔ phiên đăng nhập. Chi tiết thuật ngữ ở [`glossary.md`](glossary.md).

**Business rules.**

- MVP giả định **một người dùng thuộc một Circle**; khả năng thuộc nhiều Circle
  là mở rộng tương lai (xem mục Proposed trong [`roadmap.md`](roadmap.md)).
- Người quản trị nhóm **không** được tự ý kích hoạt theo dõi trên thiết bị của
  người khác; việc chia sẻ luôn do chủ thể dữ liệu bật.
- Quyền quản trị Circle **không đồng nghĩa** quyền truy cập vô hạn location
  history của mọi thành viên — history vẫn tuân theo quyền chia sẻ từng người.
- Invite code có hạn dùng và thời hạn hiệu lực; rời Circle làm chấm dứt mọi
  quyền đang cấp cho người đó.

**Privacy/security.** Tham gia phải là hành động rõ ràng của người được mời;
authorization được backend thực thi cho từng request đọc/ghi; rời nhóm phải
kích hoạt dọn dẹp quyền và (theo chính sách retention —
[`privacy-and-safety.md`](privacy-and-safety.md)) dữ liệu liên quan.

**Edge cases.** Invite trùng lặp hoặc hết hạn; người dùng đã ở trong nhóm khác;
thành viên rời nhóm khi còn dữ liệu chờ đồng bộ (xem Offline Sync); chủ nhóm
rời nhóm (cần quy định chuyển giao hoặc giải tán nhóm); thành viên bị xóa
thiết bị khỏi tài khoản của chính họ.

**Phụ thuộc.** Authentication và device identity (M1); authorization model;
notifications cho lời mời (M6 cho kênh FCM — MVP có thể dùng invite link trực tiếp).

**Acceptance criteria (mức sản phẩm).** Không ai xuất hiện trong một Circle khi
chưa tự chấp nhận; mọi quyền đọc dữ liệu đều bắt nguồn từ một lần đồng ý còn
hiệu lực; rời nhóm không để lại quyền truy cập nào; danh sách thiết bị/phiên
đăng nhập phản ánh đúng thực tế.

## 3. Live Map

> Trạng thái: **Planned — M4**.

**Mục đích.** Hiển thị vị trí thành viên trên bản đồ gần thời gian thực, kèm
bối cảnh để người xem hiểu dữ liệu đang “tươi” đến đâu.

**User story.** *Là thành viên trong nhóm, tôi muốn mở bản đồ và thấy từng người
thân đang ở đâu, vị trí cập nhật lúc mấy giờ và đáng tin đến mức nào, để tôi
biết có cần liên hệ hay hỗ trợ không.*

**Yêu cầu chức năng.**

- Bản đồ Google Maps; marker cho từng thành viên đang chia sẻ.
- Marker hiển thị avatar/tên, kèm thời điểm vị trí được ghi nhận.
- Trạng thái online/offline của thiết bị, độ chính xác GPS nếu provider báo cáo,
  trạng thái chia sẻ vị trí của từng thành viên.
- Camera focus vào một thành viên khi chọn marker.
- Theo dõi cập nhật realtime bằng kết nối phù hợp (định hướng WebSocket — xem
  quyết định mở trong [`../architecture/overview.md`](../architecture/overview.md#7-các-quyết-định-kỹ-thuật-chưa-chốt)).
- Hiển thị last known location kèm nhãn tuổi dữ liệu khi thiết bị mất kết nối.

**Luồng tương tác.** Mở tab bản đồ → thấy toàn bộ thành viên đang chia sẻ →
chọn một người để camera bám theo → nhận cập nhật vị trí mới qua kết nối
realtime → với người offline, thấy last known location được gắn nhãn thời gian.

**Dữ liệu.** Location Point mới nhất theo thành viên (tọa độ, timestamp ghi nhận
trên thiết bị, timestamp server nhận, accuracy, trạng thái thiết bị). Hiện thực
bằng PostGIS ở tầng database.

**Business rules.**

- **Last known location không được đồng nhất với live location** — UI phải phân
  biệt rõ hai trạng thái này.
- Không cam kết realtime tuyệt đối; hiển thị phải luôn kèm thời điểm dữ liệu.
- Chỉ hiển thị thành viên mà người xem hiện được cấp quyền xem.

**Privacy/security.** Bản đồ chỉ render thành viên có quyền; việc tắt chia sẻ
phải làm marker biến mất hoặc chuyển trạng thái rõ ràng ở phía người xem, được
backend đảm bảo (không gửi dữ liệu cho người không còn quyền).

**Edge cases.** Mất kết nối giữa chừng (fallback last known); GPS jitter làm
marker nhảy; nhiều thiết bị cùng tài khoản; dữ liệu quá cũ (chuyển sang trạng
thái Stale/Offline theo Confidence Engine).

**Phụ thuộc.** Family Circle (M2), Smart Tracking + ingestion (M3), Location
Confidence (M4), realtime transport.

**Acceptance criteria.** Người xem luôn nhìn thấy tuổi của dữ liệu vị trí;
người dừng chia sẻ không còn hiển thị ở phía người xem trong thời gian ngắn;
mất mạng của người xem không làm UI hiển thị dữ liệu sai như thể đang live.

## 4. Smart Tracking (Android Location Engine)

> Trạng thái: **Implemented — Automated Verified (M3); Pending Field
> Validation.** Manifest đã khai báo quyền vị trí foreground/background,
> foreground service type `location` và notification; engine dùng Fused
> Location Provider sau interface `LocationProvider`, policy thích ứng có
> unit test với fake clock, và trạng thái được hiển thị trong tab Vị trí.
> Chưa có đo pin thực địa, chưa xác minh screen-off/reboot/OEM restrictions
> trên thiết bị thật — xem [`../implementation/progress.md`](../implementation/progress.md)
> và ADR 0006.

**Mục đích.** Engine thu thập vị trí native Kotlin, thích ứng theo trạng thái
di chuyển, tôn trọng pin và các giới hạn của Android.

**User story.** *Là người dùng, tôi muốn ứng dụng ghi vị trí của tôi một cách
đáng tin khi tôi di chuyển mà không hao pin vô lý và không cần tôi mở ứng dụng
liên tục.*

**Yêu cầu chức năng.**

- Theo dõi vị trí khi ứng dụng chạy nền, trong phạm vi quyền và trạng thái hệ
  điều hành cho phép (foreground service với kiểu `location`, permission
  `ACCESS_BACKGROUND_LOCATION` được cấp đúng quy trình Android).
- Flow quyền phân biệt foreground precise/approximate, background, dịch vụ
  vị trí tắt và quyền bị từ chối. API 28 không hỏi quyền background riêng;
  API 29 chỉ hỏi background sau foreground; API 30+ giải thích rồi mở trang
  quyền ứng dụng trong Settings. Khi quay lại, app đọc lại trạng thái OS;
  từ chối quyền không chặn các chức năng khác.
- Điều chỉnh chiến lược cập nhật theo trạng thái di chuyển: ưu tiên cập nhật
  khi đang di chuyển, giảm truy cập vị trí khi đứng yên.
- Xử lý thay đổi độ chính xác do provider báo cáo.
- Xử lý Battery Saver, Doze và các giới hạn background execution; không giả
  định OS luôn cho phép GPS cập nhật với chu kỳ cố định.
- Khôi phục hoạt động hợp lệ sau app restart hoặc reboot.
- Không đánh đổi mức tiêu thụ pin vô lý để lấy tần suất GPS.

**Thiết kế trạng thái logic (mô hình tham khảo).**

`IDLE`, `STATIONARY`, `MOVING`, `JOURNEY`, `DEGRADED`, `OFFLINE`, `PAUSED` —
mô hình này là tham khảo ban đầu; thiết kế chi tiết có thể thay đổi nếu có cơ
sở kỹ thuật, miễn là giữ được: thích ứng theo chuyển động, tôn trọng giới hạn
OS, và trạng thái có thể quan sát được (Diagnostics).

**Luồng tương tác.** Người dùng bật chia sẻ → Android hiển thị foreground
service notification (bắt buộc theo OS) → engine chạy theo trạng thái → người
dùng tạm dừng hoặc tắt chia sẻ bất cứ lúc nào từ UI.

**Dữ liệu.** Location Point ghi nhận trên thiết bị (tọa độ, accuracy, timestamp
thiết bị) đẩy lên ingestion API; dữ liệu chờ mạng nằm trong Room queue (Offline Sync).

**Business rules.** Tần suất thu thập do chính sách của engine quyết định theo
trạng thái, không theo nhu cầu realtime của người xem; việc ở trạng thái nào
phải suy ra được từ Diagnostics.

**Privacy/security.** Thu thập chỉ diễn ra khi người dùng đang bật chia sẻ;
tọa độ không đi vào log mặc định; quyền bị thu hồi ở OS phải làm engine dừng
một cách sạch sẽ.

**Edge cases.** Quyền background bị rút giữa chừng; thiết bị vào Battery Saver;
reboot giữa hành trình; GPS unavailable trong nhà; nhà sản xuất kill foreground
service (các dòng OEM khác nhau).

**Phụ thuộc.** Privacy foundation (M1) — permission flow; Offline Sync (cùng
M3) để dữ liệu không mất khi mất mạng; Diagnostics (M7) để quan sát trong thực địa.

**Acceptance criteria.** Trên thiết bị thật: chia sẻ hoạt động khi màn hình tắt;
dừng hoàn toàn khi người dùng tắt; đứng yên không sinh vị trí thừa; sau reboot
engine quay lại đúng trạng thái được phép; pin tiêu thụ ở mức được đo và công bố
(tham chiếu NFR — [`non-functional-requirements.md`](non-functional-requirements.md)).

## 5. Location Confidence Engine

> Trạng thái: **Planned — M4** (các trạng thái độ mới dữ liệu). Confidence score
> định lượng là **Proposed** — xem cuối mục. Đây là product differentiator cốt lõi.

**Mục đích.** Giúp người xem đánh giá độ tin cậy của dữ liệu vị trí thay vì chỉ
nhìn một marker không ngữ cảnh.

**User story.** *Là người xem bản đồ, tôi muốn biết vị trí của mẹ tôi là mới
đây hay đã cũ, thiết bị có đang mất mạng không, để tôi không nhầm “dữ liệu cũ”
với “chuyện gì đó đã xảy ra”.*

**Yêu cầu chức năng.** Engine tổng hợp các yếu tố tham khảo sau thành một trạng
thái hiển thị:

- Tuổi của tọa độ (location age) — tính từ lúc thiết bị ghi nhận.
- Độ chính xác do location provider báo cáo.
- Thời điểm thiết bị ghi nhận và thời điểm server nhận (hai mốc phải phân biệt).
- Trạng thái kết nối của thiết bị nguồn.
- Trạng thái location permission trên thiết bị nguồn.
- Tình trạng đồng bộ (queue backlog, sync thành công lần cuối).
- Nguồn vị trí nếu có (GPS/network/fused).
- Dấu hiệu dữ liệu bất thường (nhảy tọa độ bất hợp lý, timestamp lệch).

**Trạng thái hiển thị (tham khảo).**

| Trạng thái | Ý nghĩa |
| --- | --- |
| Fresh | Vị trí mới ghi nhận, độ chính xác ổn. |
| Delayed | Còn nhận dữ liệu nhưng chậm hơn thường lệ. |
| Stale | Dữ liệu đã cũ; không còn tin là vị trí hiện tại. |
| Offline | Thiết bị nguồn mất kết nối; hiển thị last known. |
| Unknown | Không đủ dữ liệu để đánh giá. |

**Business rules — ranh giới ngôn ngữ.**

- Đây là trạng thái **độ mới/độ tin cậy của dữ liệu**, **không phải xác suất
  định lượng rằng người thân đang an toàn**. UI và copy không được diễn giải
  khác đi.
- Không tự gán confidence score tùy tiện rồi trình bày như số liệu được khoa
  học xác nhận.

**Privacy/security.** Trạng thái confidence lộ ít thông tin hơn tọa độ, nhưng
cần cẩn thận: một thành viên không có quyền xem vị trí cũng không được suy ra
trạng thái chi tiết của người đó.

**Edge cases.** Đồng hồ thiết bị lệch so với server; dữ liệu đồng bộ bù arrives
out-of-order; accuracy rất xấu trong nhà; timestamp tương lai.

**Phụ thuộc.** Smart Tracking + ingestion (M3) — cần metadata đầy đủ trên từng
Location Point; Live Map (M4) là bề mặt hiển thị chính.

**Acceptance criteria.** Mỗi marker trên bản đồ có trạng thái confidence nhất
quán và giải thích được khi người dùng chạm vào; trạng thái biến đổi đúng khi
thiết bị nguồn offline/stale; không có văn bản nào trong app gợi ý đây là đánh
giá độ an toàn của con người.

**Proposed (chưa chốt): confidence score định lượng.** Nếu muốn nâng từ trạng
thái sang điểm số, phải định nghĩa rõ: giả thuyết, inputs, trọng số, giới hạn,
và phương pháp calibration/validation trên dữ liệu thực địa trước khi hiển thị
bất kỳ con số nào cho người dùng. Cho đến khi đó, chỉ dùng trạng thái rời rạc ở trên.

## 6. Location History

> Trạng thái: **Planned — M5**.

**Mục đích.** Lưu và xem lại lịch sử vị trí **được chia sẻ hợp lệ**, phân biệt
rõ dữ liệu gốc và dữ liệu đồng bộ bù.

**User story.** *Là thành viên nhóm, tôi muốn xem hành trình của con tôi hôm
nay trên bản đồ kèm thời điểm ghi nhận, để tôi biết con đã về đến nhà lúc mấy giờ.*

**Yêu cầu chức năng.**

- Lưu lịch sử tọa độ đã được cấp quyền chia sẻ.
- Xem hành trình theo ngày; hiển thị đường đi trên bản đồ.
- Hiển thị thời điểm ghi nhận của từng điểm.
- Phân biệt dữ liệu ghi nhận trực tiếp và dữ liệu đồng bộ bù (sync sau offline).
- Tùy chọn thời hạn lưu dữ liệu (retention).
- Xóa lịch sử theo quyền của chủ thể dữ liệu.

**Luồng tương tác.** Chọn thành viên → chọn ngày → xem polyline hành trình kèm
timeline → chủ thể dữ liệu có thể xóa toàn bộ hoặc một khoảng thời gian.

**Dữ liệu.** Chuỗi Location Point theo thành viên + ngày, có cờ nguồn (live
capture vs backfill), phục vụ truy vấn theo khoảng thời gian qua PostGIS.
Thiết kế API/storage phải mở rộng được mà **không bắt buộc lưu GPS với tần suất
cực cao** (lưu theo chính sách lấy mẫu của Location Engine).

**Business rules.** Thành viên khác chỉ truy cập lịch sử khi được cấp quyền
tương ứng; quyền xem hiện tại không tự động truy ngược toàn bộ quá khứ nếu
chính sách chia sẻ không cho phép; retention mặc định ngắn, người dùng có thể
giảm (không tăng vô hạn).

**Privacy/security.** Đây là dữ liệu nhạy cảm nhất của sản phẩm: authorization
per-request từ backend, retention tối thiểu, xóa là hành động thực sự (xem
[`privacy-and-safety.md`](privacy-and-safety.md)); không log tọa độ.

**Edge cases.** Điểm trùng timestamp; hành trình đứt quãng do offline; dữ liệu
backfill làm xáo trộn thứ tự thời gian (bắt buộc preserve original timestamps);
yêu cầu xóa giữa lúc đang sync.

**Phụ thuộc.** Ingestion + offline sync (M3), authorization model (M1–M2).

**Acceptance criteria.** Hành trình hiển thị đúng thứ tự thời gian; người không
có quyền không lấy được lịch sử bằng bất kỳ đường API nào; việc xóa được phản
ánh ở mọi người xem; retention được thực thi tự động phía backend.

## 7. Offline Sync

> Trạng thái: **Implemented — Automated Verified (M3); Pending Field
> Validation.** Room queue (owner-scoped, 5.000 điểm/7 ngày), WorkManager
> batch 100 điểm/lần (tối đa 5 batch và 64 request/pass) với network
> constraint, server idempotency theo `(user, eventId)`, giữ nguyên
> `recordedAt`. Batch validation bị từ chối được tách có giới hạn; chỉ event
> invalid đã cô lập mới bị loại. Consent OFF chặn batch mới và purge queue;
> outbox theo owner được reconcile riêng, server vẫn kiểm tra quyền trong
> transaction. Xem ADR 0006.

**Mục đích.** Đảm bảo dữ liệu vị trí đã ghi nhận bền vững trên thiết bị không
bị mất chỉ vì upload thất bại.

**User story.** *Là người dùng đi qua vùng mất sóng, tôi muốn vị trí của tôi
trong lúc đó vẫn được ghi lại và gửi đi khi có mạng lại, để người thân thấy đủ
hành trình của tôi.*

**Định hướng kỹ thuật (planned).**

- Local persistence bằng **Room**.
- **Durable upload queue**: ghi đĩa trước, gửi sau.
- Batch upload để giảm số request.
- Retry với backoff; không retry vô hạn không giới hạn.
- **Idempotency** và **deduplication**: gửi lại không sinh bản ghi trùng.
- **Preserve original timestamps** — không làm sai thứ tự thời gian khi gửi bù.

**Luồng tương tác.** Hoàn toàn ngầm: người dùng không cần làm gì; UI có thể
hiện trạng thái “đang chờ đồng bộ N điểm” trong Diagnostics.

**Dữ liệu.** Bảng queue trong Room: location item, trạng thái gửi, số lần retry,
idempotency key. Batch API phía server phải chấp nhận băng giá trùng lặp.

**Business rules — điều kiện dừng bắt buộc.** Phải định nghĩa rõ xử lý khi:

- Người dùng **tắt chia sẻ vị trí**: intent OFF được lưu bền vững trước
  network access, acquisition dừng ngay, các điểm **chưa gửi bị xóa khỏi
  thiết bị** (privacy-first, ADR 0006 §8), không bắt đầu upload mới khi có
  operation consent đang chờ. Server-confirmed chỉ sau acknowledgment đúng
  owner/generation; request đã bay theo lock ordering transaction phía server.
- Người dùng **rời Circle** hoặc bị thu hồi quyền: dữ liệu chưa gửi của người
  đó với Circle đó phải bị xử lý theo chính sách — mặc định **loại bỏ**, không
  gửi bù dữ liệu cho một nhóm người dùng đã không còn thuộc về.
- Dữ liệu quá hạn (vượt retention khi còn nằm trong queue): loại bỏ.

**Privacy/security.** Queue nằm trong domain `database` mà Android backup rules
hiện đã loại trừ — chính sách backup/transfer cho Room database phải được quyết
định từng file trước khi bất cứ thứ gì rời thiết bị (bất biến đã ghi trong
[`AGENTS.md`](../../AGENTS.md)); không đồng bộ vị trí qua FCM.

**Edge cases.** Đổi tài khoản giữa chừng; queue rất lớn sau nhiều ngày offline;
thiết bị đầy bộ nhớ; server trả lỗi vĩnh viễn cho một batch (không retry vô hạn).

**Phụ thuộc.** Location Engine (M3) cùng mốc; ingestion API (M3); chính sách
retention/authorization (M1–M2).

**Acceptance criteria.** Tắt máy bay rồi bật lại: dữ liệu không mất, không trùng,
thứ tự thời gian đúng; tắt chia sẻ/rời nhóm được xử lý đúng quy tắc ở trên; tính
toàn vẹn được kiểm chứng bằng test tự động (không chỉ quan sát thủ công).
**Đã automated-verified ở M3:** queue sống qua process restart, retry không
trùng nhờ `(user, eventId)`, out-of-order vẫn giữ event time, 99 event hợp lệ
được giữ khi một event trong batch 100 bị từ chối, split có ngân sách request,
consent OFF xóa queue và chặn upload, account switch/sign-out purge queue.
Chưa có field validation trên thiết bị thật.

## 8. Privacy Controls

> Trạng thái: **Consent + audit + backend authorization Implemented (M1);
> consent enforcement tại location ingestion và privacy-first revocation
> Implemented (M3); realtime distribution/History theo quyền thuộc M4–M5.**
> Đặc tả chính sách đầy đủ nằm ở
> [`privacy-and-safety.md`](privacy-and-safety.md) — mục này chỉ tóm tắt
> mặt chức năng. Bằng chứng M1: audit record cho thay đổi consent (đã
> test), session revocation tức thì (đã test), switch consent mặc định
> TẮT ở tầng database. M3: consent/session/device/Circle được kiểm tra
> trong cùng transaction với batch ingestion; consent mutation có CAS version,
> tắt consent dừng acquisition, xóa điểm chưa gửi và giữ outbox operation
> owner-scoped tới xác nhận server. Batch validation isolation không xóa
> event hợp lệ cùng batch.

Privacy là **functional requirement bắt buộc, không phải trang Settings phụ**.

Tối thiểu phải có:

- Consent-based location sharing; chủ động bật/tắt chia sẻ.
- Thể hiện rõ khi vị trí đang được chia sẻ (persistent notification + trạng thái UI).
- Cho phép tạm dừng (pause) và tiếp tục.
- Kiểm soát thành viên có quyền xem; thu hồi quyền bất cứ lúc nào.
- Rời Circle; xóa location history; xóa tài khoản và dữ liệu liên quan theo chính sách.
- Hạn chế collection và retention theo mặc định tối thiểu.
- Không triển khai tracking bí mật (nguyên tắc sản phẩm, xem [`overview.md`](overview.md#6-mục-tiêu-và-phi-mục-tiêu)).
- Audit các sự kiện quyền truy cập quan trọng.

**Ràng buộc thực thi.** Privacy control phải được thực thi **cả tại Android
client và Go backend**; không được dựa vào trạng thái UI để bảo vệ dữ liệu.

**Acceptance criteria.** Mọi đường đọc dữ liệu vị trí đều đi qua authorization
backend; tắt chia sẻ làm mọi người xem mất quyền truy cập trong thời gian ngắn
kể cả khi dùng lại token cũ; các sự kiện consent/revocation xuất hiện trong audit.

## 9. Place Alerts

> Trạng thái: **Planned — M5**.

**Mục đích.** Cho phép định nghĩa địa điểm quan trọng (nhà, trường học, công ty,
địa điểm tùy chỉnh) và nhận thông báo khi thành viên đến/rời địa điểm đó.

**User story.** *Là phụ huynh, tôi muốn nhận thông báo khi con đến trường an
toàn, để tôi không phải liên tục dõi bản đồ.*

**Yêu cầu chức năng.**

- Tạo địa điểm: chọn tọa độ trên bản đồ, đặt tên, thiết lập vùng geofence (bán kính).
- Phát hiện đến/rời vùng cho thành viên đang chia sẻ vị trí.
- Gửi thông báo cho **những người được phép nhận**.
- Chống thông báo lặp; chống GPS jitter và dao động gần ranh giới (hysteresis/dwell time).
- Phân biệt **event time** (lúc sự kiện xảy ra) và **notification delivery time**.

**Luồng tương tác.** Người tạo địa điểm định nghĩa + chọn người nhận → khi
thành viên đến/rời vùng → hệ thống xác nhận sự kiện (kết hợp Android Geofencing
API và server-side verification khi cần) → thông báo đi kèm thời điểm sự kiện.

**Dữ liệu.** Place (tên, tọa độ, bán kính, chủ sở hữu, phạm vi áp dụng),
Geofence Event (thành viên, place, loại enter/exit, event time, độ tin cậy).

**Business rules.** Chỉ tạo geofence cho hành vi của người **đang chia sẻ vị
trí** và nhận thông báo cho người **được cấp quyền**; không cam kết geofence
luôn được phát hiện ngay tức khắc — OS batch geofence delivery.

**Privacy/security.** Việc một người “đã đến nhà” là thông tin nhạy cảm: người
nhận phải được chủ thể dữ liệu đồng ý; sự kiện vẫn tuân authorization backend.

**Edge cases.** Vùng chồng nhau; người dùng rời nhóm giữa chừng; GPS jitter tại
ranh giới; thiết bị offline lúc qua vùng (event time vẫn giữ nguyên khi sync bù).

**Phụ thuộc.** Family Circle (M2), Location Engine (M3), Notifications/FCM (M6 —
kênh push; MVP có thể nhận in-app trước).

**Acceptance criteria.** Không spam lặp khi người ở sát ranh giới; event time
đúng dù thông báo đến muộn; người không có quyền không nhận được thông báo;
geofence hoạt động sau reboot.

## 10. SOS & Emergency Events

> Trạng thái: **Planned — M6**.

**Mục đích.** Cho phép thành viên chủ động kích hoạt tín hiệu cấp cứu đến những
người thân được chọn.

**User story.** *Là người dùng gặp tình huống nguy hiểm, tôi muốn bấm SOS một
cách có chủ ý để người thân biết ngay tôi cần giúp và ở đâu, dù tôi không thể
giải thích thêm.*

**Target behavior.**

- Kích hoạt có **cơ chế chống chạm nhầm** (ví dụ giữ nút + xác nhận — thiết kế cụ thể để quyết sau).
- Ghi nhận sự kiện vào backend kèm vị trí và thời gian hiện có.
- Gửi thông báo đến những người được phép nhận qua **Firebase Cloud Messaging**.
- Hiển thị chi tiết SOS (người gửi, vị trí, thời điểm).
- Người nhận xác nhận đã nhận biết sự kiện (acknowledge).
- Event lifecycle có trạng thái rõ ràng; có thể tiếp tục cập nhật hành trình khi người dùng cho phép.
- Xử lý khi không có mạng (sự kiện được queue và gửi khi có kết nối).
- Phân biệt ba mốc: **đã gửi** (thiết bị) → **đã được dịch vụ push chấp nhận** →
  **đã được người nhận xác nhận**.

**Dữ liệu.** SOS Event (người gửi, lifecycle state, vị trí đính kèm, event time,
danh sách người nhận + acknowledge), liên kết với Journey nếu đang chạy.

**Business rules.** Chỉ người được cấp quyền nhận mới nhận được SOS; lifecycle
không được vẽ lại quá khứ (mỗi mốc thời gian ghi tại chỗ nó xảy ra).

**Privacy/security.** Vị trí đính kèm theo SOS tuân cùng quyền chia sẻ; chống
lạm dụng (spam SOS) cần giới hạn tỷ lệ — thiết kế ở [`privacy-and-safety.md`](privacy-and-safety.md).

**Ranh giới cam kết.** SOS **không** thay thế dịch vụ cấp cứu chuyên nghiệp
(113/115/114). UI phải nói rõ điều này. Không cam kết thông báo đến ngay lập tức
trong mọi điều kiện mạng.

**Edge cases.** Không có mạng lúc bấm; người nhận offline; nhiều SOS liên tiếp;
SOS khi đang Safe Journey; người dùng hủy nhầm.

**Phụ thuộc.** Notifications/FCM (M6 cùng mốc), Family Circle + quyền chia sẻ,
Offline Sync (queue sự kiện khi mất mạng).

**Acceptance criteria.** Ba mốc trạng thái hiển thị đúng và không bị gộp;
người nhận đúng danh sách nhận được; mất mạng không làm sự kiện biến mất; UI
không bao giờ hứa “báo cảnh sát thay bạn”.

## 11. Safe Journey

> Trạng thái: **Planned — M7**. Có thể lùi sau MVP nếu cần giảm scope — quyết
> định ở [`roadmap.md`](roadmap.md).

**Mục đích.** Tính năng khác biệt: người dùng chủ động bắt đầu một hành trình
(đi học, đi làm, di chuyển xa, về nhà muộn) và người thân được chọn theo dõi
tiến trình đến khi về đích.

**User story.** *Là người dùng thường về nhà muộn, tôi muốn chủ động bắt đầu
hành trình để bố mẹ thấy tôi đang đến đâu và biết khi nào tôi về đến nhà.*

**Định hướng.**

- Chọn hoặc thiết lập điểm đến; thông báo cho những người được chọn.
- Theo dõi tiến trình (khoảng cách/tiến độ theo dữ liệu vị trí có sẵn).
- Location Engine ưu tiên chiến lược cập nhật phù hợp trong lúc JOURNEY.
- Đánh dấu đến nơi; thông báo hành trình hoàn tất.
- Cảnh báo hành trình **ngừng cập nhật bất thường theo quy tắc được kiểm chứng**.

**Business rules — ranh giới suy diễn.** **Không mặc định suy diễn** mất GPS hoặc
ngừng di chuyển là tai nạn. Cảnh báo bất thường phải dựa trên quy tắc có ngưỡng
được kiểm chứng, và cách hiển thị phải là “hành trình ngừng cập nhật — hãy liên
hệ”, không phải “đã xảy ra sự cố”.

**Privacy/security.** Người theo dõi hành trình là tập con được chủ thể dữ liệu
chọn cho từng hành trình, không kế thừa từ quyền Circle một cách âm thầm; kết
thúc hành trình chấm dứt việc đẩy vị trí ưu tiên cao.

**Edge cases.** Người dùng quên kết thúc hành trình (auto-expire theo chính
sách); đổi điểm đến giữa chừng; hành trình chạy qua vùng mất mạng; hành trình
kết thúc mà không đến đích.

**Phụ thuộc.** Location Engine + JOURNEY state (M3), Notifications (M6),
Diagnostics để thu thập dữ liệu thực địa cho ngưỡng cảnh báo (M7 cùng mốc).

**Acceptance criteria.** Người theo dõi nhận được: bắt đầu hành trình, tiến
trình, đến nơi; hành trình dừng cập nhật sinh cảnh báo đúng quy tắc và đúng
ngôn ngữ thận trọng; kết thúc hành trình chấm dứt hoàn toàn luồng đẩy vị trí.

## 12. Tracking Health & Diagnostics

> Trạng thái: **Planned — M7** (mốc hoàn thiện; các dấu hiệu cần quan sát phải
> được thiết kế trong Location Engine ngay từ M3).

**Mục đích.** Module chẩn đoán giúp phát hiện vì sao tracking không hoạt động
trên một thiết bị cụ thể — đặc biệt quan trọng vì dự án không có nhiều điện
thoại thật để debug thường xuyên.

**User story.** *Là người dùng báo “không thấy vị trí cập nhật”, tôi muốn một
trang diagnostics cho biết nguyên nhân ở đâu (quyền bị tắt, battery optimization,
không có mạng, queue kẹt…) để tôi hoặc người hỗ trợ tự sửa được.*

**Scope.**

- Location permission status; background permission status.
- Location service status; foreground service status.
- Battery optimization status.
- Network state.
- Last recorded location timestamp; last successful sync.
- Queue backlog (số điểm chờ đồng bộ).
- App/build version.
- **Diagnostic export có kiểm soát** — người dùng chủ động xuất, nội dung được
  lọc trước khi rời thiết bị.

**Ràng buộc.** Không đưa tọa độ chính xác hoặc thông tin nhạy cảm vào log mặc
định; export phải tường minh những gì nó chứa.

**Acceptance criteria.** Từ trang diagnostics, một người không phải developer
cũng đọc ra được lý do tracking không chạy; export không chứa tọa độ chính xác
trừ khi người dùng chọn rõ ràng.

## 13. Notifications

> Trạng thái: **Planned — M6** (kênh FCM và cơ chế gửi nhận).

**Mục đích.** Firebase Cloud Messaging là kênh push notification chính, dùng
cho các sự kiện hợp lệ:

- Family Circle invitation.
- Place Alerts (đến/rời địa điểm).
- SOS.
- Safe Journey events (bắt đầu, hoàn tất, cảnh báo).
- Những sự kiện an toàn hợp lệ khác.

**Ràng buộc kiến trúc.** **Không sử dụng FCM để truyền GPS liên tục.** Location
ingestion và realtime distribution do Go backend xử lý qua HTTPS/WebSocket hoặc
phương án phù hợp (xem [`../architecture/overview.md`](../architecture/overview.md)).
FCM data message có thể dùng như *tín hiệu* (“có dữ liệu mới, hãy fetch”) — đây
là mẫu phổ biến và không vi phạm ràng buộc trên, nhưng quyết định cụ thể để mở
ở quyết định kỹ thuật chưa chốt.

**Acceptance criteria.** Mỗi loại sự kiện có template thông báo rõ ràng; không
có đường nào gửi tọa độ qua FCM payload; thông báo tôn trọng trạng thái quyền
tại thời điểm gửi.

---

*Các mục 2–4 và 7–8 đã có implementation tương ứng M2/M3 (xem trạng thái ở
đầu mỗi mục và bằng chứng tại [`roadmap.md`](roadmap.md),
[`../implementation/progress.md`](../implementation/progress.md)). Các mục
5–6, 9–13 vẫn là Planned/Proposed cho M4–M7 — không có dòng code nghiệp vụ
nào tồn tại cho chúng tại thời điểm viết tài liệu.*
