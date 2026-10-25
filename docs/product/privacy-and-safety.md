# Privacy & Safety — AnVề

> Tài liệu này là **source of truth** cho chính sách riêng tư, mô hình đồng ý,
> authorization, retention, xóa dữ liệu, chống lạm dụng và giới hạn của các
> tính năng an toàn. Toàn bộ nội dung là **yêu cầu hệ thống bắt buộc** cho các
> milestone tương ứng — privacy là functional requirement, không phải trang
> Settings phụ.
>
> Trạng thái: toàn bộ mô tả bên dưới là **Planned** trừ hai điều đã Implemented:
> Android backup/transfer rules loại trừ domain `sharedpref` và `database`, và
> router chưa dùng `middleware.RealIP` (lý do spoofing — bất biến kỹ thuật đã
> ghi trong [`../../AGENTS.md`](../../AGENTS.md)).

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

## 3. Mô hình ủy quyền (authorization)

- **Vai trò trong Circle** (ví dụ: người tạo nhóm/quản trị, thành viên) chỉ điều
  khiển việc **quản trị nhóm** — mời, xóa invite, giải tán nhóm.
- **Quyền xem dữ liệu** là một trục riêng: quản trị nhóm **không** tự động được
  xem location history của mọi thành viên; quyền đọc vị trí/history bắt nguồn
  từ sự đồng ý của từng chủ thể dữ liệu.
- **Quyền nhận sự kiện** (Place Alerts, SOS, Safe Journey) được chọn cho từng
  loại sự kiện/địa điểm/hành trình, không kế thừa âm thầm từ quyền xem bản đồ.
- Server thực thi mô hình trên cho **mọi** endpoint đọc/ghi, kể cả các endpoint
  tiện ích (diagnostics, danh sách thành viên).

## 4. Retention (thời hạn giữ dữ liệu)

- Location history có **thời hạn lưu tối đa** do sản phẩm đặt (mốc định lượng
  cụ thể là quyết định mở — xem [`../architecture/overview.md`](../architecture/overview.md#7-các-quyết-định-kỹ-thuật-chưa-chốt));
  người dùng có thể chọn ngắn hơn, không dài hơn mặc định.
- Dữ liệu chờ đồng bộ (offline queue) quá hạn mà chưa gửi được thì bị loại,
  không gửi bù dữ liệu đã vượt cửa sổ retention.
- Dữ liệu sự kiện (SOS, Place Alert) giữ theo chính sách riêng, ngắn hơn history
  nếu không có giá trị sản phẩm.
- Retention được thực thi **tự động phía backend** (dọn dẹp định kỳ), không phụ
  thuộc hành vi người dùng.

## 5. Xóa dữ liệu và tài khoản

- **Xóa location history**: chủ thể dữ liệu xóa được toàn bộ hoặc theo khoảng
  thời gian; việc xóa phản ánh ở **mọi người xem** (server chặn nguồn).
- **Xóa tài khoản**: xóa tài khoản và dữ liệu liên quan theo chính sách được
  định nghĩa khi hiện thực M1; danh sách chính xác những gì bị xóa/nguyên bản
  giữ lại (ví dụ nghĩa vụ sổ sách nếu sau này có thanh toán) phải được công bố
  trong app.
- **Rời Circle** chấm dứt mọi quyền đang cấp; dữ liệu vị trí của người đó đối
  với Circle đó được xử lý theo chính sách retention, không giữ "tàng hình".
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

## 7. Audit các sự kiện quan trọng

Các sự kiện sau bắt buộc phải có audit record (Planned — hiện thực cùng milestone
của từng tính năng):

- Tham gia / rời Circle; chấp nhận / từ chối invite.
- Bật / tắt / tạm dừng chia sẻ vị trí.
- Cấp / thu hồi quyền xem; thay đổi người nhận sự kiện.
- Đọc location history (ai đọc phần nào) — mức chi tiết là quyết định mở.
- Kích hoạt / acknowledge SOS.
- Xóa history; xóa tài khoản.

Audit log không chứa tọa độ; chỉ chứa ai, làm gì, với ai, khi nào.

## 8. Giới hạn của các tính năng an toàn (phải phản ánh trong UI)

| Tính năng | Giới hạn bắt buộc phải công bố |
| --- | --- |
| Location Confidence | Là trạng thái **độ mới/độ tin cậy của dữ liệu**, không phải đánh giá độ an toàn của con người. Không hiển thị dạng xác suất nếu chưa qua calibration. |
| Live Map | Không cam kết realtime tuyệt đối; luôn kèm thời điểm dữ liệu; last known location không được trình bày như vị trí hiện tại. |
| Place Alerts | Geofence có thể được OS delivered chậm; không cam kết phát hiện tức thời. |
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
  bị thu hồi, không log tọa độ, tôn trọng backup/data-extraction rules.

Nếu một điều khiển chỉ tồn tại ở một tầng, nó **chưa hoàn thành**.
