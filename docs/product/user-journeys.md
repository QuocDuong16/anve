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
2. Nếu tài khoản chưa thuộc Circle nào và chưa hoàn tất/bỏ qua onboarding trên
   thiết bị (trạng thái lưu theo `userId`), ứng dụng mở luồng thiết lập 5 bước:
   - **B1 Chào mừng:** chọn *Thiết lập từng bước* hoặc *Tự khám phá ứng dụng*
     để vào thẳng Home. (Khi mở app từ link mời đang chờ, chuyển thẳng sang
     bước Tham gia ở B2.)
   - **B2 Gia đình:** chọn *Tạo gia đình mới* (đặt tên, tạo link/mã mời 7 ngày
     và mở Android sharesheet) hoặc *Tham gia gia đình đã có* (mở hộp thư lời
     mời hoặc nhập mã qua hộp thoại xác nhận C). Có thể bấm *Mời thêm sau*.
   - **B3 Quyền & đồng ý:** hiển thị giải thích nổi bật (prominent disclosure)
     trước khi xin quyền; tách riêng thông báo (Android 13+), công tắc
     *Đồng ý chia sẻ vị trí* (mặc định TẮT), quyền vị trí khi dùng app và
     vị trí nền (chỉ dẫn mở Cài đặt ở Android 11+, đọc lại khi quay lại). Mọi
     bước đều có nút **Để sau, tiếp tục**; onboarding không tự bật theo dõi GPS.
   - **B4 Ai xem & địa điểm quen thuộc:** nếu nhóm đã có thành viên khác thì
     cho cấp quyền xem từng người (mặc định tắt); nếu chưa có ai thì hiện thẻ
     nhắc chờ người thân tham gia. Cho phép tạo một địa điểm quen thuộc đầu tiên
     (`sharedWithCircle` hoặc riêng tư, kèm bật theo dõi nếu đã có người xem
     hợp lệ) hoặc bấm **Để sau**.
   - **B5 Hoàn tất:** tóm tắt đúng trạng thái thực tế của nhóm, quyền và địa
     điểm trước khi vào Home. Có thể mở lại từ trang Cá nhân.
3. Từ chối hoặc chọn *Để sau* ở bất kỳ quyền nào không khóa các tính năng không
   cần vị trí. Màn Vị trí và Chẩn đoán vẫn chỉ ra bước cần xử lý; không tự bật
   consent hay GPS.

Avatar: mở ảnh trong Cá nhân → chọn một ảnh bằng Photo Picker → xem trước →
bấm Lưu. Chọn ảnh và đóng bảng chưa gửi dữ liệu. Xóa ảnh cũng cần bấm Lưu;
lỗi mạng chưa rõ kết quả được kiểm tra bằng đọc metadata, không tự tải lên lại.

## 2. Tạo nhóm và mời người thân

1. Tạo nhóm bằng tên; chủ nhóm ban đầu là thành viên duy nhất.
2. Chủ động mở Tạo lời mời (trong tab Nhóm hoặc B2 onboarding); chọn email
   hoặc chỉ tạo mã/liên kết và thời hạn (onboarding dùng link/mã mặc định 7 ngày).
3. Liên kết chia sẻ (`https://<domain>/invite#<code>` khi cấu hình HTTPS, hoặc
   `anve://invite/<code>`) cùng mã dự phòng được gửi qua Android sharesheet khi
   người dùng chọn. Mã đặt sau dấu `#` nên không gửi lên server hoặc vào log.
   Email (trong tab Nhóm) là ràng buộc tài khoản nhận, không phải thư gửi qua email.
4. Máy chủ luôn trả cùng kiểu kết quả cho email đã/chưa đăng ký; không cho
   người gửi xem trạng thái nhận thông báo hay việc người nhận ẩn lời mời.
5. Chỉ người tự xác nhận tham gia mới xuất hiện trong danh sách thành viên.

MVP giới hạn một Circle mỗi tài khoản. Quyền chủ nhóm chỉ quản lý nhóm,
không cấp quyền xem vị trí hay điều khiển thiết bị của thành viên khác.

## 3. Nhận và xác nhận lời mời

1. Mở `https://<domain>/invite#<code>` (bấm *Mở trong AnVề* để chuyển sang
   `anve://invite/<code>` hoặc mở Google Play nếu chưa cài) hoặc mở trực tiếp
   `anve://invite/<code>` / nhập mã, đăng nhập nếu cần. Đây vẫn là link tùy
   chỉnh với trang cầu nối tĩnh, không phải HTTPS App Link đã được xác minh.
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
