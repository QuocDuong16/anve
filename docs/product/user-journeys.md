# User journeys — AnVề

> Tài liệu này mô tả **hành trình người dùng mục tiêu** của AnVề. Toàn bộ các
> journey dưới đây là **Planned** (xem [`overview.md`](overview.md#7-phân-loại-trạng-thái-status-taxonomy)):
> chỉ splash screen là đã tồn tại trong source. Chi tiết yêu cầu chức năng của
> từng bước nằm ở [`features.md`](features.md); tài liệu này tập trung vào trải
> nghiệm end-to-end và các nhánh thất bại mà thiết kế phải xử lý.

## Mục lục

1. [Onboarding & cấp quyền](#1-onboarding--cấp-quyền)
2. [Tạo Family Circle](#2-tạo-family-circle)
3. [Tham gia Family Circle](#3-tham-gia-family-circle)
4. [Bật chia sẻ vị trí và xem Live Map](#4-bật-chia-sẻ-vị-trí-và-xem-live-map)
5. [Tạm dừng và dừng chia sẻ](#5-tạm-dừng-và-dừng-chia-sẻ)
6. [Place Alerts](#6-place-alerts)
7. [SOS](#7-sos)
8. [Safe Journey](#8-safe-journey)

---

## 1. Onboarding & cấp quyền

**Nhân vật:** người dùng mới cài AnVề.
**Điều kiện trước:** chưa có tài khoản.
**Milestone:** M1 (authentication) + M3 (location permissions).

1. Cài ứng dụng, mở lần đầu → thấy splash screen với wordmark và tagline
   (bước duy nhất đã Implemented).
2. Đăng ký/đăng nhập (phương thức xác thực cụ thể là quyết định mở —
   [`../architecture/overview.md`](../architecture/overview.md#7-các-quyết-định-kỹ-thuật-chưa-chốt)).
3. Ứng dụng giải thích *vì sao* cần vị trí (trước khi xin quyền hệ thống) —
   transparency-first, không xin quyền ngay lúc mở app lần đầu.
4. Cấp `ACCESS_FINE_LOCATION` (khi bật chia sẻ lần đầu mới xin
   `ACCESS_BACKGROUND_LOCATION`, theo quy trình nhiều bước của Android).
5. Hoàn tất hồ sơ hiển thị (tên hiển thị cho người thân thấy).

**Nhánh thất bại:** từ chối quyền → ứng dụng vẫn dùng được phần không cần vị
trí, kèm hướng dẫn bật lại; quyền bị thu hồi ở System Settings → ứng dụng phát
hiện qua Diagnostics và thể hiện trạng thái, không tự xin lại liên tục.

## 2. Tạo Family Circle

**Nhân vật:** người thành lập nhóm gia đình.
**Milestone:** M2.

1. Chọn “Tạo nhóm gia đình” → đặt tên nhóm.
2. Hệ thống tạo Circle và invite code/link.
3. Chia sẻ link qua SMS/Zalo/… cho người thân.
4. Xem danh sách thành viên: hiện tại chỉ có mình; người mới xuất hiện khi họ
   tự chấp nhận.
5. Quản lý nhóm: xem thành viên, mời thêm, (tùy vai trò) mời lại invite hết hạn.

**Ràng buộc xuyên suốt:** việc tạo nhóm **không** bật chia sẻ vị trí cho ai;
mỗi thành viên tự bật của riêng mình. Người tạo nhóm không có quyền ẩn nào
trên thiết bị của người khác.

**Nhánh thất bại:** invite link hết hạn → gửi lại; người được mời từ chối →
không có gì được ghi nhận ngoài hành động từ chối.

## 3. Tham gia Family Circle

**Nhân vật:** người thân được mời.
**Milestone:** M2.

1. Nhận invite link/code.
2. Mở link (deep link) hoặc nhập code trong app.
3. Xem **rõ ràng** nhóm là gì, ai tạo, tham gia nghĩa là gì — trước khi quyết định.
4. Chọn “Tham gia” → xuất hiện trong danh sách thành viên.
5. Từ lúc này mới có thể bật chia sẻ vị trí của chính mình cho nhóm.

**Nhánh thất bại:** chưa có tài khoản → onboarding chạy trước; nhóm đã giải tán
→ thông báo rõ; người dùng đã ở một Circle khác (giới hạn MVP một-Circle) →
giải thích và hướng dẫn.

## 4. Bật chia sẻ vị trí và xem Live Map

**Nhân vật:** người chia sẻ (con/bố mẹ) và người xem.
**Milestone:** M3–M4.

**Phía người chia sẻ:**

1. Bật “Chia sẻ vị trí” → xác nhận → Android hiển thị persistent notification
   của foreground service.
2. Location Engine bắt đầu theo trạng thái di chuyển (đứng yên thì ít cập nhật,
   di chuyển thì dày hơn).
3. Bất cứ lúc nào cũng có thể tạm dừng hoặc tắt hẳn.

**Phía người xem:**

1. Mở tab bản đồ → thấy marker của những người **đang** chia sẻ.
2. Mỗi marker kèm: tên, thời điểm ghi nhận, trạng thái dữ liệu (Fresh/Delayed/
   Stale/Offline/Unknown — xem [Confidence Engine](features.md#5-location-confidence-engine)
   trong `features.md`).
3. Chạm vào một người → camera bám theo; kết nối realtime cập nhật vị trí mới.
4. Người mất mạng hiển thị last known location **có nhãn tuổi dữ liệu** —
   không được nhìn như live location.

**Nhánh thất bại:** người xem mất mạng → bản đồ hiện dữ liệu cuối cùng kèm
thời điểm, rõ ràng là dữ liệu cũ; người chia sẻ mất mạng → các điểm được queue
(sync bù khi có mạng, hành trình không đứt).

## 5. Tạm dừng và dừng chia sẻ

**Nhân vật:** người đang chia sẻ.
**Milestone:** M2–M3 (control), M4 (hiển thị ở người xem).

1. Mở control chia sẻ (nút lớn, luôn thấy được trạng thái hiện tại).
2. Chọn “Tạm dừng” (nghỉ tạm, định kỳ) hoặc “Dừng chia sẻ” (chấm dứt).
3. Ứng dụng dừng thu thập; backend chấm dứt phân phát.
4. Người xem thấy trạng thái thay đổi (marker biến mất hoặc hiển thị
   “đã dừng chia sẻ”) — được backend đảm bảo, không phụ thuộc UI người chia sẻ.

**Ràng buộc:** đây phải là thao tác dễ tìm, không bị chôn trong settings —
quyền thu hồi phải nhanh ít nhất bằng quyền cấp.

**Nhánh cần xử lý:** dừng chia sẻ khi còn dữ liệu chờ đồng bộ — dữ liệu của
khoảng thời gian **đã** chia sẻ vẫn được gửi bù; dữ liệu sau khi dừng không sinh
ra (quy tắc ở [Offline Sync](features.md#7-offline-sync)).

## 6. Place Alerts

**Nhân vật:** phụ huynh (tạo địa điểm + nhận thông báo) và thành viên di chuyển.
**Milestone:** M5.

1. Phụ huynh tạo địa điểm “Trường học”: chọn tọa độ, bán kính vùng, chọn người
   áp dụng (ai đang chia sẻ) và người nhận thông báo.
2. Con đi học qua vùng → hệ thống phát hiện sự kiện đến (Android Geofencing API
   + server-side verification khi cần).
3. Phụ huynh nhận thông báo: “Con đã đến trường lúc 7h12” — event time, dù
   thông báo có thể đến chậm hơn vài phút.

**Chống nhiễu bắt buộc:** không lặp thông báo khi đậu xe sát ranh giới;
hysteresis/dwell time xử lý GPS jitter.

**Nhánh thất bại:** thiết bị của người di chuyển offline lúc qua vùng → sự kiện
được phát hiện muộn hơn khi sync bù, event time vẫn giữ nguyên; người nhận
offline → thông báo theo cơ chế FCM (có thể đến khi thiết bị online lại).

## 7. SOS

**Nhân vật:** người gặp nguy hiểm (gửi) và người thân (nhận).
**Milestone:** M6.

**Người gửi:**

1. Mở SOS → cơ chế chống chạm nhầm (giữ nút + xác nhận).
2. Xác nhận → sự kiện được ghi vào backend kèm vị trí và thời gian hiện có.
3. Thấy trạng thái rõ ba mốc: đã gửi → dịch vụ push đã chấp nhận → người nhận
   đã xác nhận.
4. Mất mạng → sự kiện nằm trong queue, gửi khi có kết nối; trạng thái phản ánh
   điều đó, không giả vờ đã gửi.

**Người nhận:**

1. Nhận thông báo FCM ưu tiên cao với chi tiết SOS.
2. Mở app → thấy vị trí và thời điểm của người gửi.
3. Bấm “Đã nhận biết” → mốc acknowledge được ghi nhận cho người gửi và các
   người nhận khác thấy.

**Ranh giới cam kết:** SOS là kênh báo cho người thân, **không** thay thế dịch
vụ cấp cứu 113/115/114 — app nói rõ điều này ngay trong luồng SOS.

## 8. Safe Journey

**Nhân vật:** người đi về muộn (chủ hành trình) và người thân được chọn.
**Milestone:** M7 — có thể lùi sau MVP.

1. Người dùng bắt đầu hành trình: chọn điểm đến (hoặc dùng địa điểm đã có),
   chọn người thân được theo dõi hành trình này.
2. Những người được chọn nhận thông báo “An đã bắt đầu hành trình về nhà”.
3. Trong lúc hành trình: cập nhật tiến trình được ưu tiên hơn thông thường
   (Location Engine ở trạng thái JOURNEY).
4. Đến nơi → hành trình hoàn tất → thông báo “đã về đến nhà” cho người theo dõi.
5. Nếu hành trình ngừng cập nhật bất thường → người theo dõi nhận cảnh báo theo
   quy tắc đã kiểm chứng, với ngôn ngữ thận trọng: “hành trình ngừng cập nhật —
   hãy liên hệ”, **không** suy diễn tai nạn.

**Nhánh thất bại:** quên kết thúc hành trình → auto-expire theo chính sách;
mất mạng giữa đường → sync bù, người theo dõi thấy dữ liệu cũ kèm nhãn tuổi;
người dùng hủy hành trình → thông báo hủy cho người theo dõi.

---

*Các journey trên là khung trải nghiệm mục tiêu dùng để lập kế hoạch triển khai
và review UX; khi một milestone được hiện thực, journey tương ứng phải được đối
chiếu và cập nhật theo những gì code thực sự làm.*
