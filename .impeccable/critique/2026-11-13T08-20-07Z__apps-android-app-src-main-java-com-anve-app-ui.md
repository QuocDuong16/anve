---
target: Android UI M1-M5
total_score: 25
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 4
target_identity: "file:/home/niand/Workspace/anve/apps/android/app/src/main/java/com/anve/app/ui"
timestamp: 2026-11-13T08-20-07Z
slug: apps-android-app-src-main-java-com-anve-app-ui
closed: true
---
# Impeccable critique — giao diện M1–M5

**Phạm vi:** splash/đăng nhập, điều hướng và tài khoản, Nhóm, chia sẻ vị trí, Bản đồ trực tiếp, Lịch sử, Địa điểm. M6 trở đi không được chấm nội dung màn hình. Thanh điều hướng hiện dùng chung có tab SOS đời sau nên được tính khi đánh giá điều hướng.

**Phương pháp:** design review only (detector not applicable, native platform). Mã nguồn Compose và ảnh splash hiện có đã được xem; không có emulator/thiết bị kết nối để kiểm tra mọi màn hình, dark mode, chữ phóng to hay thao tác chạm thực tế. Đây là đánh giá thiết kế/mã nguồn, không phải xác nhận render đầy đủ.

## Nhận định nhanh

Các luồng đã có nhiều nội dung đặc thù AnVề: người dùng tự bật chia sẻ, quyền xem tách khỏi việc theo dõi, và bản đồ/lịch sử có trạng thái kết nối, đồng bộ trễ. Tuy nhiên, phần vỏ giao diện đời đầu chưa đạt cùng độ hoàn thiện. Ưu tiên lớn nhất là tương phản splash và dark theme, lỗi đăng nhập Google trình bày nguyên văn lỗi tiếng Anh, cùng thanh điều hướng có tới bảy mục.

## Điểm Nielsen

| # | Heuristic | Điểm | Nhận xét |
|---|---|---:|---|
| 1 | Hiển thị trạng thái hệ thống | 3/4 | Có tải, lỗi, thử lại, đồng bộ trễ; nhưng trạng thái lỗi đăng nhập chưa hướng dẫn phục hồi tốt. |
| 2 | Khớp với ngôn ngữ đời thực | 3/4 | Cách diễn đạt về chia sẻ khá gần gũi; thông báo credential lại lộ thông điệp kỹ thuật tiếng Anh. |
| 3 | Người dùng kiểm soát và tự do | 3/4 | Có quyền bật/tắt và quản lý người xem; nhiều thao tác quản lý dồn vào các trang dài. |
| 4 | Nhất quán và tiêu chuẩn | 3/4 | Dùng Compose/Material 3 nhất quán; tương phản của một số vai màu không ổn ở dark theme. |
| 5 | Ngăn ngừa lỗi | 2/4 | Có xác nhận khi xóa và phân biệt quyền; các trang Lịch sử/Địa điểm chứa nhiều thao tác dễ lẫn. |
| 6 | Nhận biết thay vì ghi nhớ | 2/4 | Thanh 7 tab buộc người dùng tự tìm đúng khu vực; nhiều điều kiện quyền nằm rải trên trang. |
| 7 | Linh hoạt và hiệu quả | 2/4 | Bộ lọc và thao tác có sẵn, nhưng điều hướng đông mục và trang dài làm tăng số bước quét. |
| 8 | Thẩm mỹ tối giản | 2/4 | Splash có hiệu ứng nhưng dấu hiệu vòng tròn gợi radar hơn “đường về”; một số trang gom nhiều lớp chức năng. |
| 9 | Nhận biết và khôi phục lỗi | 2/4 | Có retry ở một số màn, nhưng lỗi OAuth hiện tại chưa chuyển thành chỉ dẫn dễ làm theo. |
| 10 | Trợ giúp và tài liệu | 3/4 | Có giải thích quyền riêng tư và độ trễ; chưa phải lúc nào cũng nói rõ bước kế tiếp khi thất bại. |
| **Tổng** |  | **25/40 — Chấp nhận được** | Cần sửa một số điểm lớn trước khi trải nghiệm đồng đều. |

## Điểm làm tốt

- Nhóm, tracking và địa điểm diễn đạt rõ việc chia sẻ vị trí là tự nguyện; cấp quyền xem không tự bật theo dõi.
- Bản đồ và lịch sử cho thấy trạng thái như chưa kết nối, mẫu vị trí trễ/độ chính xác, thay vì ngụ ý dữ liệu luôn mới.
- Các màn hình dùng Compose/Material thật, có các trạng thái tải, rỗng, lỗi và thao tác thử lại ở nhiều luồng.

## Vấn đề ưu tiên

1. **[P1] Splash giảm tương phản mạnh ở cuối gradient.** Chữ dùng `onPrimary` trên gradient từ `primary` sang `primaryContainer`; ảnh splash cho thấy chữ trắng trên vùng xanh sáng phía dưới. Tính theo màu trong theme, `Sand50` trên `Teal100` chỉ khoảng **1.28:1**; dark theme `Teal900` trên `Teal700` khoảng **1.44:1**. Đổi nền/foreground theo từng theme để toàn dải đạt tương phản đọc được, rồi kiểm tra lại bằng ảnh render. Gợi ý: `$impeccable colorize`, `$impeccable polish`.

2. **[P1] Lỗi đăng nhập Google hiện chi tiết kỹ thuật.** `SignInScreen` ghép `cause.message` vào câu “Không lấy được tài khoản Google…”, nên lỗi như “No credentials available” hiện thẳng cho người dùng. Thay bằng nội dung tiếng Việt theo nhóm nguyên nhân, chỉ dẫn thử lại/thêm tài khoản hoặc chuyển cách hỗ trợ; giữ chi tiết kỹ thuật trong log. Gợi ý: `$impeccable clarify`, `$impeccable harden`.

3. **[P1] Thanh điều hướng có bảy mục.** Circle, Map, History, Places, SOS, Location và Account cùng nằm trong `NavigationBar`. Mục tiêu cốt lõi bị chia nhỏ và nhãn/icon khó quét trên điện thoại. Gom các mục ít dùng vào một điểm vào thứ cấp hoặc rút gọn thanh chính theo thứ tự ưu tiên sản phẩm; giữ SOS dễ truy cập theo yêu cầu an toàn. Gợi ý: `$impeccable distill`, `$impeccable layout`.

4. **[P1] Cặp màu dark theme cần rà toàn bộ, không chỉ splash.** Ví dụ `secondaryContainer`/`onSecondaryContainer` được dùng cho notice tài khoản và thông báo tracking; màu theme tối hiện tại có tương phản khoảng **2.50:1**. Một số trạng thái chọn dùng `primary` trên `surface` cũng cần đo lại. Chốt lại cặp màu theo vai trò rồi kiểm thử tất cả màn hình M1–M5 ở sáng/tối, bao gồm chữ thường và trạng thái chọn. Gợi ý: `$impeccable colorize`, `$impeccable audit`.

5. **[P2] Lịch sử và Địa điểm dồn nhiều mục tiêu vào một màn.** Lịch sử đặt lọc thành viên, ngày, giờ, bản đồ, danh sách điểm và thao tác xóa trong cùng cột. Địa điểm đặt CRUD, quyền chia sẻ địa điểm, cấu hình theo dõi và sự kiện trong một danh sách dài. Tách lớp bằng tiêu đề/nhóm hoặc đưa thao tác phụ vào sheet; giữ cảnh báo quyền ngay cạnh đúng thao tác. Gợi ý: `$impeccable distill`, `$impeccable layout`.

## Cảnh báo theo người dùng

- **Người mới cài:** nếu đăng nhập Google thất bại, thông báo kỹ thuật hiện tại không nói nên làm gì tiếp.
- **Người lớn tuổi hoặc phóng to chữ:** thanh bảy mục và các hàng điều khiển ngang ở Lịch sử có nguy cơ chật; cần xác nhận trên emulator với font scale lớn.
- **Chủ nhóm đang vội:** Lịch sử và Địa điểm đòi hỏi quét nhiều nội dung trước khi tìm được hành động cần thiết.

## Ghi chú nhỏ

- Hàng chọn địa điểm dùng `Text` với padding dọc 10dp; nên xác nhận vùng chạm đạt tối thiểu 48dp sau khi render.
- Ảnh splash trong repo là fixture đơn lẻ; chưa có bằng chứng render đại diện cho các màn còn lại hoặc dark theme.
- Dấu vòng tròn ở splash khá chung và có thể bị hiểu như radar/vị trí. Hướng logo mái nhà nối đường về đã được tạo thành concept riêng, nhưng chưa được duyệt để thay vào app.
