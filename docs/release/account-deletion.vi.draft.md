# Xóa tài khoản và dữ liệu AnVề — bản nháp nội dung

**Chưa công bố.** URL dự kiến:
`https://anve.niand.id.vn/delete-account`. Nội dung chuẩn bị ngày 12/11/2026;
khả năng nhận email, xác minh yêu cầu và thời gian xử lý chưa được thử nghiệm.

AnVề do **Nguyễn Võ Quốc Dương (NianD)** vận hành. Để yêu cầu xóa tài khoản
khi không truy cập được ứng dụng, gửi email đến **nvqduong.dev@gmail.com**
với tiêu đề **“Yêu cầu xóa tài khoản AnVề”** và địa chỉ email đã dùng đăng
nhập. Không gửi mật khẩu, mã OTP, token, giấy tờ tùy thân hay lịch sử vị trí.

Nếu còn truy cập được AnVề, bạn có thể dùng chức năng xóa tài khoản trong
phần cài đặt tài khoản. Nếu là chủ nhóm còn thành viên, app sẽ yêu cầu giải
quyết nhóm trước; yêu cầu hỗ trợ không được dùng để bỏ qua quyền của các
thành viên khác. Một thao tác bị từ chối chưa phải tài khoản đã được xóa.

Sau khi yêu cầu được xác minh và xử lý, tài khoản và dữ liệu vận hành phụ
thuộc trong cơ sở dữ liệu AnVề được xóa, bao gồm phiên/thiết bị và các bản
ghi vị trí gắn với tài khoản. Việc xóa tài khoản AnVề không xóa tài khoản
Google. Dữ liệu người khác đã nhận hoặc lưu lại không thể thu hồi từ họ.

Bản ghi kiểm toán có thể còn định danh kỹ thuật. Thời hạn/lý do giữ lại
audit, nhật ký, hàng rào ID, bản sao lưu và dữ liệu ở nhà cung cấp phải được
hoàn thiện trong chính sách riêng tư trước khi công bố. Không hứa xóa ngay
mọi bản sao hoặc thời hạn xử lý chưa được thiết lập.

## Quy trình vận hành cần hoàn thiện trước công bố

1. Thử nhận yêu cầu từ bên ngoài; xác minh người yêu cầu kiểm soát tài khoản
   bằng quy trình phù hợp, không chỉ tin địa chỉ From của email.
2. Hướng dẫn chủ nhóm xử lý vướng mắc mà không xóa dữ liệu/ngắt quyền của
   thành viên khác trái ý họ. Không xóa trực tiếp qua SQL để né kiểm soát app.
3. Xử lý bằng luồng có xác thực/ủy quyền được hỗ trợ; xác minh kết quả ở
   nguồn có thẩm quyền. Chưa có cổng quản trị xóa hộ tài khoản trong slice này.
4. Ghi nhận dữ liệu đã xóa, phần giữ lại cùng lý do/thời hạn, giới hạn provider/
   backup và thông báo kết quả. Thiết lập thời hạn xử lý có thể thực hiện được.
5. Kiểm thử mất quyền truy cập, yêu cầu giả mạo/trùng lặp, chủ nhóm còn thành
   viên, xóa thành công và xử lý sau restore; lưu bằng chứng đã loại định danh.

Đây là yêu cầu chuẩn bị vận hành, không phải tuyên bố quy trình hỗ trợ đã chạy.
