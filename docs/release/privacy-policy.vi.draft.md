# Chính sách quyền riêng tư AnVề — bản nháp nội dung

**Chưa công bố.** Chuẩn bị ngày 12/11/2026 theo mã nguồn và hồ sơ M8-C.
Các quyết định vận hành ở cuối tài liệu phải được chốt trước khi dùng làm
chính sách công khai. URL dự kiến: `https://anve.niand.id.vn/privacy`.

## Người vận hành và liên hệ

AnVề do **Nguyễn Võ Quốc Dương (NianD)** vận hành. Bạn có thể liên hệ về
quyền riêng tư, hỗ trợ hoặc yêu cầu xóa tài khoản qua
**nvqduong.dev@gmail.com**.

## AnVề sử dụng thông tin nào

AnVề giúp các thành viên gia đình tự nguyện chia sẻ vị trí và liên lạc.
Ứng dụng xử lý thông tin đăng nhập Google, tên hiển thị, email và định danh
tài khoản; thông tin thiết bị/phiên đăng nhập và mã nhận thông báo để quản lý
truy cập, bảo vệ tài khoản và gửi thông báo bạn lựa chọn.

Khi bạn chủ động bật theo dõi và cấp quyền cần thiết, ứng dụng thu thập vị
trí, thời điểm, độ chính xác và thông tin chuyển động có sẵn để cập nhật vị
trí, lịch sử và đánh giá sự kiện địa điểm. Quyền vị trí nền cho phép cập nhật
khi ứng dụng không ở trước màn hình theo lựa chọn của bạn; thiết bị, mạng và
hệ điều hành có thể làm chậm hoặc ngừng cập nhật.

AnVề cũng lưu thông tin nhóm, người được phép xem, địa điểm đã lưu, hành trình
do bạn tạo, sự kiện SOS và xác nhận để thực hiện các tính năng tương ứng.
SOS hiện không đính kèm tọa độ và không tự bật GPS. “Đã đến nơi” là xác nhận
thủ công của người dùng, không phải kết luận từ vị trí.

Máy chủ xử lý dữ liệu cần thiết để vận hành dịch vụ và ghi nhận các thay đổi
quyền, tài khoản và phiên. Báo cáo chẩn đoán trong app chỉ được gửi khi bạn
chọn chia sẻ qua ứng dụng khác; báo cáo dùng danh sách trường giới hạn, không
chứa tọa độ, token đăng nhập hoặc định danh tài khoản/thiết bị/sự kiện.

## Lựa chọn và người được xem

Tham gia nhóm không tự bật chia sẻ vị trí. Bạn quyết định bật/tắt chia sẻ và
cấp hoặc thu hồi quyền xem của từng thành viên. Vai trò quản trị nhóm không
tự cấp quyền xem vị trí. Máy chủ kiểm tra quyền khi gửi/đọc dữ liệu.

Tắt chia sẻ dừng thu thập trên thiết bị này và xóa các điểm chưa gửi. Khi mất
mạng, app hiển thị trạng thái chờ xác nhận từ máy chủ; không coi thao tác đó
là máy chủ đã nhận. Dữ liệu đã được người xem nhận, lưu hoặc chụp lại không
thể được thu hồi khỏi thiết bị của họ.

## Google và các dịch vụ liên quan

AnVề sử dụng Google Sign-In để xác thực, Google Maps để hiển thị bản đồ,
dịch vụ vị trí của Google và Firebase Cloud Messaging để gửi thông báo khi
bạn thiết lập tính năng đó. Các SDK này có thể xử lý định danh cài đặt, IP,
thông tin thiết bị/yêu cầu và dữ liệu vận hành tùy dịch vụ, cấu hình và cách
sử dụng. Việc xóa tài khoản trong AnVề không đồng nghĩa xóa tài khoản Google.

Danh mục chi tiết đang được đối chiếu trong hồ sơ Data Safety. Trước khi
công bố cần bổ sung các nhà cung cấp hạ tầng thực tế, nơi xử lý dữ liệu và
cách xử lý việc lưu/xóa dữ liệu ở nhà cung cấp.

## Lưu trữ, bảo vệ và xóa dữ liệu

Hàng đợi vị trí chưa gửi trên thiết bị có giới hạn 5.000 điểm và tối đa 7
ngày. Cấu hình máy chủ hiện mặc định giữ lịch sử vị trí 30 ngày; giới hạn
thực tế phụ thuộc thiết lập lưu trữ được áp dụng khi triển khai. Địa điểm
đã lưu và các dữ liệu tính năng khác có vòng đời riêng, không mặc nhiên dùng
cùng thời hạn với lịch sử vị trí.

Ứng dụng cung cấp xóa lịch sử và xóa tài khoản. Xóa tài khoản xóa tài khoản
và dữ liệu vận hành phụ thuộc trong cơ sở dữ liệu; bản ghi kiểm toán có thể
còn định danh kỹ thuật. Không có cam kết xóa ngay mọi bản sao ở nhà cung cấp,
nhật ký hay bản sao lưu. Các thời hạn và lý do giữ lại phải được xác định
trước khi công bố chính sách này.

Bản release yêu cầu kết nối HTTPS. AnVề không sử dụng mã hóa đầu cuối: máy
chủ có thể xử lý dữ liệu vị trí. Dữ liệu cục bộ nằm trong vùng lưu trữ riêng
của ứng dụng; không được mô tả là mọi cơ sở dữ liệu đã mã hóa thêm ở tầng app.

Bạn có thể xóa tài khoản trong app hoặc liên hệ email ở trên khi không truy
cập được app. Nếu bạn sở hữu nhóm còn thành viên, cần giải quyết việc quản
lý nhóm trước khi xóa; hỗ trợ không được tự ý giải tán nhóm của người khác.
Xem bản nháp hướng dẫn yêu cầu xóa tài khoản đi kèm.

## Giới hạn sử dụng

AnVề không phải công cụ theo dõi bí mật hoặc dịch vụ cấp cứu. Dữ liệu vị trí
cũ, mất kết nối hay một hành trình chưa được xác nhận không chứng minh một
người đang an toàn hoặc gặp nguy hiểm. Phạm vi độ tuổi sử dụng và các yêu
cầu liên quan phải được xác định trước khi phát hành.

## Các mục phải hoàn thiện trước công bố

- Vùng triển khai và danh sách đơn vị xử lý/hạ tầng thực tế.
- Thời hạn/lý do giữ audit, nhật ký, hàng rào ID, bản sao lưu; quy trình xóa
  sau phục hồi và xử lý dữ liệu ở Google/Firebase/nhà cung cấp khác.
- Giới hạn lịch sử áp dụng thực tế, thời gian xử lý yêu cầu, quy trình xác
  minh chủ tài khoản và quyền theo thị trường phát hành.
- Độ tuổi/đối tượng, quốc gia phát hành; đối chiếu Data Safety và kiểm chứng
  cấu hình SDK/provider, HTTPS và luồng xóa bằng bản app phát hành.
- Ngày hiệu lực, URL công khai hoạt động và liên kết chính sách trong app/Console.

Không bỏ phần này và công bố các đoạn chưa chốt như thể đã được kiểm chứng.
