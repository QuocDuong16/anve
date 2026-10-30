# Privacy & Safety — AnVề

> Tài liệu này là **source of truth** cho chính sách riêng tư, mô hình đồng ý,
> authorization, retention, xóa dữ liệu, chống lạm dụng và giới hạn của các
> tính năng an toàn. Toàn bộ nội dung là **yêu cầu hệ thống bắt buộc** cho các
> milestone tương ứng — privacy là functional requirement, không phải trang
> Settings phụ.
>
> Trạng thái: các kiểm soát **đã Implemented** trong M1/M2 là: consent switch
> (mặc định TẮT, audit trong cùng transaction), audit ownership một sự kiện
> một dòng trong transaction (ADR 0004), xóa tài khoản không giải tán nhóm có
> thành viên và được serialize với cả tạo nhóm lẫn redeem (ADR 0005 §4), cách
> ly identity dev/Google, development login chỉ loopback/allowlist và không
> mount ngoài development, hàng đợi pending-revocation mã hóa có giới hạn,
> loại trừ `sharedpref`, `database` và `file/datastore` khỏi backup/transfer,
> và router chưa dùng `middleware.RealIP` (lý do spoofing).
>
> **M3 đã Implemented — Automated Verified:** ingestion batch chỉ nhận dữ
> liệu khi session còn sống, device thuộc session, consent bật và Circle
> membership còn hiệu lực, tất cả kiểm tra trong cùng transaction; tắt
> consent dừng acquisition, xóa điểm chưa gửi và lưu operation OFF bền vững
> theo owner; consent dùng version/CAS để response cũ không ghi đè ý định mới.
> Batch validation bị từ chối được cô lập có giới hạn và chỉ event sai đã
> nhận diện riêng mới bị loại. Retention 30 ngày mặc định
> (`LOCATION_RETENTION_DAYS`) chạy tự động; account/device deletion cascade
> location rows; Room queue 7 ngày trên thiết bị. M4 read authorization được
> Implemented — Automated Verified theo [ADR 0007](../adr/0007-location-read-audience-authorization.md):
> membership periods + sharing epochs + explicit per-viewer grants; existing
> M3 points không được backfill vào epoch. Latest API áp authorization trong
> PostgreSQL. Android không lưu tọa độ thành viên trên đĩa; marker cache chỉ ở
> memory và bị xóa khi auth hết hạn/đổi account. Polling 10 giây kiểm tra lại
> quyền ở request kế tiếp khi mạng hoạt động; dữ liệu đã được gửi/nhìn thấy
> không thể thu hồi khỏi thiết bị người xem. Transport và freshness: [ADR
> 0008](../adr/0008-m4-polling-confidence-and-map.md). Map runtime/device
> validation vẫn pending. Threat model ingestion ở ADR 0006.

## 1. Nguyên tắc nền tảng

1. **Consent-based sharing.** Vị trí chỉ được thu thập và phân phát khi chủ thể
   dữ liệu đang bật chia sẻ. Tham gia một Circle không tự động bật chia sẻ.
2. **Backend-enforced authorization.** Mọi quyền đều được Go backend kiểm tra
   trên từng request. **Không dựa vào trạng thái UI để bảo vệ dữ liệu** — UI
   chỉ phản ánh, không thực thi.
3. **Data minimization.** Thu thập những gì tính năng cần; không thu thập "dự
   phòng"; retention mặc định ngắn nhất mà tính năng vẫn dùng được.
4. **Transparency.** Người dùng luôn biết khi nào vị trí đang được chia sẻ
   (persistent notification của foreground service + trạng thái trong app).
   Không có chế độ ẩn.
5. **Không tracking bí mật.** AnVề không phải spyware. Ứng dụng không được thiết
   kế, quảng bá hay mở rộng để giám sát người khác mà không có sự đồng ý của họ.
   Đây là nguyên tắc sản phẩm, không phải tính năng tùy chọn.

## 2. Mô hình đồng ý (consent)

| Hành vi | Ai quyết | Ghi nhận |
| --- | --- | --- |
| Tham gia Circle | Người được mời (chấp nhận/từ chối) | Sự kiện tham gia, kèm thời điểm |
| Bật/tắt chia sẻ vị trí | Chủ thể dữ liệu | Sự kiện bật/tắt, kèm thời điểm |
| Tạm dừng (pause) | Chủ thể dữ liệu | Sự kiện pause/resume |
| Cấp quyền xem cho thành viên | Chủ thể dữ liệu | Sự kiện cấp quyền |
| Thu hồi quyền | Chủ thể dữ liệu | Sự kiện thu hồi |
| Rời Circle | Thành viên | Sự kiện rời nhóm |
| Được vào một journey/được nhận SOS alert | Chủ thể dữ liệu (chọn từng lần) | Theo từng hành trình/sự kiện |

Quy tắc:

- Đồng ý là **hành động rõ ràng**, không mặc định có hiệu lực ngược về quá khứ.
- Thu hồi có hiệu lực về phía phân phát dữ liệu **ngay cả khi client của người
  bị thu hồi không online** — server chặn từ nguồn.
- Việc xin quyền hệ thống (location, background location, notifications) phải
  có màn hình giải thích *trước* khi gọi permission dialog của Android.
- **Consent không đồng nghĩa tracking.** Consent là bản ghi đồng ý trên máy
  chủ; điện thoại chỉ thực sự thu thập khi người dùng bật theo dõi và cấp
  quyền OS. Foreground service phải hiển thị thông báo cố định; UI tách rõ
  "đã đồng ý" khỏi "đang cập nhật vị trí".
- **Tắt consent là hành động local-first:** acquisition dừng ngay, mọi điểm
  chưa gửi bị xóa khỏi thiết bị, ý định thu hồi được lưu bền vững trước khi
  gọi mạng và chặn upload mới cho đúng owner. App không tuyên bố server đã
  thu hồi khi request chưa được xác nhận. Nếu sign-out xảy ra khi OFF đang
  chờ, operation owner-scoped còn nguyên; credential chỉ được giữ mã hóa,
  giới hạn 7 ngày và chỉ dùng cho consent OFF rồi sign-out. Hết hạn/401 để
  operation ở trạng thái cần chủ tài khoản xác thực lại, không báo thành công.
- OFF đóng cổng thu thập và upload trong process trước khi ghi DataStore/Room.
  Nếu ghi outbox thất bại, GPS vẫn dừng và worker không mở request mới trong
  process hiện tại; máy chủ chưa được xác nhận và bảo đảm này không bền qua
  process death nếu mọi lần lưu đều thất bại. Purge Room hoặc dừng foreground
  service thất bại được báo riêng; các điểm cũ không được backfill.
- Consent API dùng `expectedVersion` và CAS ở server. Khi nhận conflict, client
  chỉ reconcile bằng snapshot có xác thực: nếu server đã ở đúng trạng thái thì
  chỉ cập nhật snapshot, không ghi lại/audit lần hai; nếu khác thì park operation
  cùng version/state server và yêu cầu chủ tài khoản đưa ra lựa chọn mới. Không
  tự rebase ON/OFF cũ lên version mới. Operation khác tài khoản không được thực
  thi dưới session đang đăng nhập.
- ON/OFF conflict giữ location acquisition fail-closed trên thiết bị này. OFF
  vẫn dừng máy hiện tại khi server mới hơn đang ON, nhưng không âm thầm đổi
  account-wide state. UI cho biết quyết định server mới hơn và cho phép một thao
  tác mới dựa trên snapshot đó.
- Request GPS đã gửi trước khi OFF có thể commit nếu lấy khóa giao dịch server
  trước consent update. Sau khi consent OFF commit, ingestion mới bị từ chối.
  Hủy WorkManager không được coi là bằng chứng request đã gửi bị rollback.

## 3. Mô hình ủy quyền (authorization)

- **Vai trò trong Circle** (ví dụ: người tạo nhóm/quản trị, thành viên) chỉ điều
  khiển việc **quản trị nhóm** — mời, xóa invite, giải tán nhóm.
- **Quyền xem dữ liệu** là một trục riêng: quản trị nhóm **không** tự động được
  xem location history của mọi thành viên; quyền đọc vị trí/history bắt nguồn
  từ sự đồng ý của từng chủ thể dữ liệu.
- Thành viên hiện tại không tự động được xem điểm cũ trước khi tham gia.
  M4 kiểm tra membership interval, sharing epoch và grant rõ ràng cho từng
  người xem theo [ADR 0007](../adr/0007-location-read-audience-authorization.md).
  Latest read API không trả lịch sử M5.
- **Quyền nhận sự kiện** (Place Alerts, SOS, Safe Journey) được chọn cho từng
  loại sự kiện/địa điểm/hành trình, không kế thừa âm thầm từ quyền xem bản đồ.
  Với M6 đang triển khai, mỗi SOS chọn rõ người nhận, không đính kèm vị trí
  hoặc tự bật GPS; có thể thu hồi từng người nhận. Push chỉ chứa ID và loại
  sự kiện, và quyền được kiểm tra lại khi gửi cũng như khi đọc. Provider chấp
  nhận không có nghĩa là thiết bị đã nhận hoặc người dùng đã xem.
  Với M5-B, chủ thể tự bật theo dõi và chọn người nhận; người nhận còn phải có
  grant vị trí hiện tại và quyền xem Place tương ứng. Mất rồi cấp lại một trong
  các quyền không tự phục hồi cấu hình nhận sự kiện trước đó.
- Server thực thi mô hình trên cho **mọi** endpoint đọc/ghi, kể cả các endpoint
  tiện ích (diagnostics, danh sách thành viên).

## 4. Retention (thời hạn giữ dữ liệu)

- Location history có **thời hạn lưu tối đa** do sản phẩm đặt. M3 đặt mặc
  định **30 ngày kể từ thời điểm thiết bị ghi nhận**, cấu hình bằng
  `LOCATION_RETENTION_DAYS` (1–365); đây là chính sách tạm thời trong khi
  History (M5) đang triển khai UI chọn thời hạn từ 1–30 ngày, giới hạn bởi
  cấu hình server. Cùng cửa sổ này là giới hạn upload
  bù: điểm cũ hơn bị từ chối thay vì lưu rồi xóa ngay.
- Dữ liệu chờ đồng bộ (offline queue) trên thiết bị bị loại sau **7 ngày**
  hoặc khi vượt 5.000 điểm; điểm chưa gửi không được gửi bù sau khi người
  dùng tắt chia sẻ.
- SOS hết hiệu lực sau 7 ngày; đọc bị chặn sau 30 ngày và worker xóa nội dung
  theo lô, có thể trễ nếu tồn đọng. Server
  vẫn giữ fence ID tối thiểu đến khi xóa tài khoản để ngăn tạo lại sự kiện cũ.
  Place Alert phụ thuộc retention của các điểm bằng chứng; chính sách này đang
  được kiểm thử trong M6.
- Retention được thực thi **tự động phía backend** (chạy lúc khởi động và mỗi
  giờ), không phụ thuộc hành vi người dùng. Đã có integration test cho purge
  và runtime smoke test xác nhận dòng quá hạn bị xóa khi API khởi động.

## 5. Xóa dữ liệu và tài khoản

- **Xóa location history**: chủ thể dữ liệu xóa được toàn bộ hoặc theo khoảng
  thời gian; việc xóa phản ánh ở **mọi người xem** (server chặn nguồn).
  Thiết kế M5-A dùng hàng rào xóa và checkpoint theo tài khoản để từ chối
  điểm offline chưa xác thực; thiết bị chưa đồng bộ có thể mất cả điểm mới.
  Dữ liệu đã tải xuống hoặc một response đang truyền không thể thu hồi.
- **Xóa tài khoản**: xóa tài khoản và dữ liệu liên quan theo chính sách được
  định nghĩa khi hiện thực M1; danh sách chính xác những gì bị xóa/nguyên bản
  giữ lại (ví dụ nghĩa vụ sổ sách nếu sau này có thanh toán) phải được công bố
  trong app. **Đã Implemented:** chủ Circle còn thành viên khác **không thể**
  xóa tài khoản — server trả `CIRCLE_HAS_MEMBERS` và app hướng dẫn giải tán
  nhóm trước; Circle rỗng của chủ tài khoản bị xóa trong cùng transaction và
  có audit `circle.dissolved`. Không có đường xóa tài khoản nào tự động giải
  tán nhóm của người khác. Transaction xóa khóa **account row** trước khi
  quyết định, nên một Circle đang được tạo đồng thời hoặc được nhìn thấy và
  audit đầy đủ, hoặc việc tạo thất bại — không có cửa sổ cascade âm thầm
  (ADR 0005 §4, test tất định).
- **Đăng xuất**: xóa phiên trên thiết bị luôn thành công, kể cả khi offline.
  Nếu server không xác nhận được việc thu hồi, app thêm một bản ghi vào **hàng
  đợi pending-revocation đã mã hóa** (tối đa 8 bản ghi, mỗi bản ghi độc lập
  với id riêng, hết hạn sau 7 ngày kể từ lúc đăng xuất, không dùng được như
  session) và thử thu hồi lại ở lần mở sau với backoff. Màn hình đăng nhập nói
  rõ server chưa xác nhận thay vì tuyên bố "đã đăng xuất mọi nơi"; thông báo
  sống ở app scope nên không mất khi navigation. Chi tiết: ADR 0005 §3 và §5.
- **Rời Circle** chấm dứt mọi quyền đang cấp. M3 chặn ingestion mới ngay khi
  membership đã bị xóa (kiểm tra trong transaction); điểm đã lưu xử lý theo
  retention. Client purge queue của mình khi server trả
  `LOCATION_CIRCLE_REQUIRED`.
- **Tắt chia sẻ khi offline:** intent OFF được lưu bền vững theo owner trước
  network access; acquisition dừng, queue chưa gửi bị xóa, và worker chặn
  upload location mới tới khi consent operation được reconcile. Upload đã gửi
  có thể commit theo lock ordering của transaction phía server. UI nói rõ
  "đã dừng trên thiết bị; máy chủ chưa xác nhận" thay vì tuyên bố đã thu hồi.
  Nếu sign-out xảy ra đồng thời và session bị revoke trước khi OFF được xác
  nhận, operation vẫn tồn tại nhưng cần đăng nhập lại đúng owner; trạng thái
  không bị báo thành công.
- **Đăng xuất / xóa tài khoản:** đăng xuất dừng tracking và purge queue local
  (không để dữ liệu của tài khoản cũ chờ upload); xóa tài khoản cascade
  devices/sessions/location_points ở server. Queue local được purge khi
  session biến mất, kể cả khi phiên bị thu hồi từ xa.
- Các hành động xóa đều xuất hiện trong audit log.

## 6. Chống lạm dụng & an toàn sản phẩm

- **Không phải spyware:** không có chế độ hoạt động ẩn, không che giấu foreground
  service notification, không lấy vị trí ngoài trạng thái đang chia sẻ.
- **Invite là cơ chế duy nhất** để một người xuất hiện trong nhóm; không có
  đường "thêm người" không cần sự chấp nhận của người được thêm.
- **Rate limiting** cho sự kiện dễ spam (SOS, mời lại) — thiết kế cụ thể khi
  hiện thực M6.
- **Kiểm soát thiết bị/phiên đăng nhập:** thành viên xem được thiết bị và phiên
  của **chính mình** và có thể xóa phiên lạ — không ai xem được thiết bị của
  người khác.
- **Development login là fixture nội bộ, không phải auth provider:** chỉ mount
  khi `ENVIRONMENT=development`; listener mặc định loopback; nếu buộc phải
  nghe non-loopback thì cần `AUTH_DEV_LOGIN_ALLOW_NETWORK=true` + CIDR
  allowlist tường minh (catch-all bị từ chối) và endpoint tự kiểm tra địa chỉ
  nguồn — không tin `X-Forwarded-For`/`X-Real-IP`. Compose chỉ publish cổng
  trên `127.0.0.1`; `compose-lint` chặn cấu hình publish ra ngoài. Không nhúng
  shared secret vào APK/repo (ADR 0005 §1–2).

## 7. Audit các sự kiện quan trọng

Quy tắc đã Implemented (ADR 0004 §3): **mỗi thay đổi nghiệp vụ ghi đúng một
audit row, bên trong cùng transaction với thay đổi đó.** Không có best-effort
audit: một sự kiện bắt buộc mà có thể mất im lặng sau khi nghiệp vụ đã commit
thì tệ hơn một request thất bại.

Các sự kiện đã có audit: đăng nhập (Google/dev), đăng xuất, thu hồi thiết bị,
bật/tắt chia sẻ vị trí, xóa tài khoản, tạo/đổi tên/giải tán Circle, tham gia
Circle (redeem), rời Circle, xóa thành viên, tạo/thu hồi invite. Session bị
supersede khi đăng nhập lại được ghi trong event đăng nhập (detail
`device_id`), không phải event riêng.

Các sự kiện sau bắt buộc phải có audit record khi milestone tương ứng được
hiện thực:

- Cấp / thu hồi quyền xem; thay đổi người nhận sự kiện.
- Đọc location history (ai đọc phần nào) — mức chi tiết là quyết định mở.
- Kích hoạt / acknowledge SOS.
- Xóa history.

Audit log không chứa tọa độ; chỉ chứa ai, làm gì, với ai, khi nào và các id
kỹ thuật nhỏ (device_id, circle_id, invite_id, session_id).

**Location ingestion không tạo một audit row cho mỗi batch hay mỗi điểm.**
Đó là telemetry vận hành tần suất cao, không phải thay đổi quyền; ghi nó
vào audit sẽ tạo một bản sao dữ liệu vị trí thứ hai (kể cả khi chỉ ghi id)
và làm audit log phình không kiểm soát. Các sự kiện *quyền* vẫn được audit:
bật/tắt consent, đăng xuất, thu hồi thiết bị, xóa tài khoản, rời/giải tán
nhóm. Log vận hành của ingestion chỉ chứa user/device id, số lượng
accepted/duplicate/rejected và thời gian xử lý — không tọa độ.

## 8. Giới hạn của các tính năng an toàn (phải phản ánh trong UI)

| Tính năng | Giới hạn bắt buộc phải công bố |
| --- | --- |
| Consent switch (M1) | Là **ghi nhận đồng ý**, không phải GPS đang chạy. Bật switch không có nghĩa GPS đã thu thập được vị trí hay ai đó đã được cấp quyền xem; OS permission, Location Engine và explicit audience grant là các điều kiện riêng. |
| Location Confidence | Là trạng thái **độ mới/độ tin cậy của dữ liệu**, không phải đánh giá độ an toàn của con người. Không hiển thị dạng xác suất nếu chưa qua calibration. |
| Live Map | Snapshot polling 10 giây khi foreground, không phải server push; không cam kết latency tuyệt đối. Luôn kèm thời điểm/freshness; last known location không được trình bày như vị trí hiện tại; dữ liệu đã giao không thể thu hồi khỏi thiết bị người xem. |
| Place Alerts | Máy chủ đánh giá các mẫu M3 sau khi đồng bộ; không có OS geofence wakeup và sự kiện có thể trễ hoặc bị bỏ lỡ. |
| SOS | Không thay thế dịch vụ cấp cứu 113/115/114; ba mốc "đã gửi / push đã chấp nhận / người nhận đã xác nhận" không được gộp thành "đã cứu". |
| Safe Journey | Không suy diễn mất GPS/ngừng di chuyển là tai nạn; cảnh báo dùng ngôn ngữ "hãy liên hệ". |
| GPS nói chung | Không cam kết GPS hoạt động 24/7 trong mọi điều kiện (trong nhà, Battery Saver, OEM restrictions). |

Ngoài ra: tài liệu và UI **không** đưa ra tuyên bố rằng Google Play chắc chắn
duyệt ứng dụng; khả năng tuân thủ Play policies (background location, foreground
service types) là mục tiêu kỹ thuật của M8, được kiểm chứng khi nộp, không phải
hứa trước.

## 9. Thực thi hai tầng

Mỗi điều khiển privacy ở trên phải tồn tại ở **cả hai** tầng:

- **Go backend**: authorization per-request, chấm dứt phân phát khi thu hồi,
  thực thi retention, ghi audit.
- **Android client**: thu thập chỉ khi đang bật chia sẻ, dừng sạch khi quyền OS
  bị thu hồi, không log tọa độ, tôn trọng backup/data-extraction rules. Đã
  Implemented: session blob và installation id nằm trong DataStore đã mã hóa
  bằng Keystore và bị loại trừ khỏi cloud backup lẫn device transfer
  (`file/datastore`, API < 31 và API 31+); đăng xuất offline giữ một **hàng
  đợi** pending-revocation đã mã hóa thay vì âm thầm bỏ qua việc thu hồi.
  **M3:** Fused Location Provider sau interface, foreground service type
  `location` với notification cố định, permission flow theo bước (foreground
  → background → notification), policy thích ứng và queue Room owner-scoped.
  Room database **không được mã hóa thêm ở tầng ứng dụng trong M3**; file
  nằm trong private storage, bị loại khỏi backup/transfer, chỉ giữ tối đa
  7 ngày và bị xóa khi consent OFF/sign-out. Threat model, residual risk và
  tiêu chí khi nào phải thêm Keystore/SQLCipher: ADR 0006 §6. Không có
  tuyên bố "mọi dữ liệu đã mã hóa".

Nếu một điều khiển chỉ tồn tại ở một tầng, nó **chưa hoàn thành**.
