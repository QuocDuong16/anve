# User journeys — AnVề

Các luồng dưới đây đối chiếu với source hiện tại. M1–M7 có triển khai và
bằng chứng tự động theo [progress](../implementation/progress.md); kiểm thử
emulator/fixture không chứng minh OAuth, Maps, FCM, GPS hay pin trên thiết bị
thật. Avatar và lời mời email của nhánh hiện tại được theo dõi riêng ở
[profile and email invitations](../implementation/profile-and-email-invitations.md).
Mở app, link hay thông báo không tự bật theo dõi hoặc tham gia nhóm.

## 1. Onboarding và cấp quyền

1. Mở app, đăng nhập Google bằng Credential Manager; API xác minh ID token.
   Dev login chỉ dành cho môi trường phát triển được cho phép.
2. Tạo/tham gia nhóm mà chưa cần cấp vị trí. Có thể sửa tên trong Cá nhân.
3. Khi chủ động dùng thu thập vị trí, đọc giải thích rồi cấp quyền Android
   theo từng bước. Vị trí nền không được xin ngay lúc khởi động app.
4. Từ chối/thu hồi quyền không khóa các tính năng không cần vị trí. Màn Vị trí
   và Chẩn đoán chỉ ra bước cần xử lý; không tự bật consent hay GPS.

Avatar: mở ảnh trong Cá nhân → chọn một ảnh bằng Photo Picker → xem trước →
bấm Lưu. Chọn ảnh và đóng bảng chưa gửi dữ liệu. Xóa ảnh cũng cần bấm Lưu;
lỗi mạng chưa rõ kết quả được kiểm tra bằng đọc metadata, không tự tải lên lại.

## 2. Tạo nhóm và mời người thân

1. Tạo nhóm bằng tên; chủ nhóm ban đầu là thành viên duy nhất.
2. Chủ động mở Tạo lời mời; chọn email hoặc chỉ tạo mã/liên kết và thời hạn.
3. Mã/liên kết được chia sẻ qua Android sharesheet khi người dùng chọn.
   Email là ràng buộc tài khoản nhận, không phải thư gửi qua email.
4. Máy chủ luôn trả cùng kiểu kết quả cho email đã/chưa đăng ký; không cho
   người gửi xem trạng thái nhận thông báo hay việc người nhận ẩn lời mời.
5. Chỉ người tự xác nhận tham gia mới xuất hiện trong danh sách thành viên.

MVP giới hạn một Circle mỗi tài khoản. Quyền chủ nhóm chỉ quản lý nhóm,
không cấp quyền xem vị trí hay điều khiển thiết bị của thành viên khác.

## 3. Nhận và xác nhận lời mời

1. Mở `anve://invite/<code>` hoặc nhập mã, đăng nhập nếu cần. Link tùy chỉnh
   không phải HTTPS App Link đã được xác minh.
2. Với lời mời email, xem nhóm, tên người mời và thời hạn trong mục Nhóm.
   FCM chỉ gợi ý mở luồng này; chi tiết được đọc lại với tài khoản hiện tại.
3. Bấm Xem lời mời → hộp thoại C đã duyệt cho thấy nhóm, tài khoản đang dùng,
   thời hạn và việc tham gia không tự bật chia sẻ vị trí.
4. Bấm Tham gia nhóm để redeem một lần; Để sau chỉ đóng hộp thoại.
   Ẩn với tôi là lựa chọn riêng trong hộp thư; không thu hồi mã toàn cục.

Email không khớp, hết hạn, đã dùng, bị thu hồi hoặc nhóm đã giải tán không
cho phép tham gia. Đã ở nhóm khác thì bị chặn, không tự rời nhóm cũ.
Thao tác chưa rõ kết quả chỉ đọc lại để xác nhận. Đổi tài khoản xóa trạng thái
riêng và không dùng response cũ để tham gia với tài khoản mới.

## 4. Bật chia sẻ, chọn người xem và Live Map

1. Chủ thể bật chia sẻ và bắt đầu thu thập ở màn Vị trí sau các bước consent,
   quyền hệ thống và kiểm tra phiên. Foreground service có thông báo liên tục.
2. Chọn từng người được xem; membership tự nó không cấp quyền vị trí.
3. Người xem mở bản đồ; chỉ các quyền còn hiệu lực mới có snapshot. Tên,
   thời điểm, accuracy và Fresh/Delayed/Stale/Unknown phân biệt dữ liệu cũ.
4. Conditional HTTPS polling chỉ hoạt động khi màn bản đồ ở foreground.
   Không suy ra thiết bị nguồn offline hay một người gặp nạn từ tuổi dữ liệu.

Điểm thu thập hợp lệ có thể chờ mạng trong hàng đợi giới hạn. Xem được vị trí
trong fixture không chứng minh Google Maps hoặc GPS thật hoạt động.

## 5. Tạm dừng, dừng và thu hồi

Tạm dừng thu thập cục bộ và tắt chia sẻ trên máy chủ là các điều khiển riêng,
có trạng thái rõ ở màn Vị trí. OFF dừng thu thập và xóa các điểm chưa gửi;
không gửi bù queue cũ sau khi tắt. Khi offline, app ghi rõ máy chủ chưa xác
nhận. Thu hồi từng người xem/rời nhóm chấm dứt quyền; cấp lại hoặc tham gia
lại không phục hồi các lựa chọn temporal cũ. Không thể thu hồi dữ liệu người
khác đã xem hoặc chụp lại. Xem các ADR về consent/capture generation.

## 6. Places và cảnh báo địa điểm

Chủ thể tạo địa điểm/bán kính, bật theo dõi cho chính mình và chọn người nhận
đang có quyền. Quyền chủ nhóm không cho bật theo dõi thay người khác.
Máy chủ đánh giá các mẫu hợp lệ bằng hysteresis/dwell và bằng chứng giới hạn;
không có Android Geofencing API trong bản hiện tại. Mạng chậm có thể làm
phát hiện/thông báo chậm. FCM không chứa tọa độ; mở sự kiện phải kiểm tra quyền
lại. Ngưỡng GPS/jitter và độ tin cậy ngoài thực địa vẫn cần kiểm chứng.

## 7. SOS

Giữ nút và xác nhận → chọn người nhận hợp lệ → tạo sự kiện. SOS hiện không
đính kèm tọa độ và không tự bật GPS. Queue có owner/ID fence khi offline;
UI phân biệt chờ gửi, máy chủ ghi nhận, provider chấp nhận và người nhận
xác nhận. Nhận FCM chỉ có ID/loại sự kiện; app đọc lại thông tin được phép.
Provider acceptance không chứng minh thiết bị đã nhận hoặc người thân đã đọc.
SOS không thay thế dịch vụ cấp cứu và không hứa tự gọi lực lượng hỗ trợ.

## 8. Hành trình

1. Chọn địa điểm đã lưu, thời hạn và từng người còn quyền xem vị trí. Cần mạng
   khi bắt đầu và thu thập đã được bật riêng. Mỗi tài khoản chỉ một chuyến active.
2. Bảng tùy chọn B cho bật tự đến nơi và nhắc thiếu cập nhật từng chuyến;
   cả hai mặc định tắt. Chỉ dùng vị trí đã được phép thu thập.
3. Trong chuyến, tiến trình là tuổi/accuracy/khoảng cách đường chim bay;
   không phải ETA, tuyến đường hay tỷ lệ hoàn thành.
4. Bấm Đã đến nơi và xác nhận; hoặc kết thúc/hủy riêng. Nếu chủ động bật tự
   nhận diện, server cần đủ mẫu đạt điều kiện và ghi “Đến nơi tự động — ước tính”.
5. Nhắc thiếu cập nhật gửi cho chủ chuyến và người xem đã chọn còn quyền,
   với nội dung hãy liên hệ; không suy diễn tai nạn. Mỗi episode được phân biệt.

Ưu tiên JOURNEY bị giới hạn và cần đọc lại quyền; không tự phục hồi GPS sau
process death/reboot. Kết thúc dừng ưu tiên JOURNEY; chia sẻ thông thường vẫn
theo lựa chọn riêng. Quên kết thúc thì hết hạn; mất quyền chặn đọc/dispatch.
Tạo/hủy không rõ kết quả giữ cùng ID để đọc lại/fence, không tự tạo bản mới.
Tự đến nơi và cảnh báo thiếu cập nhật có kiểm thử tự động tại PR #21, nhưng
ngưỡng thực địa, GPS, pin/OEM và Firebase thật vẫn chưa được xác minh.
