# Hoàn thiện Journey và link mời

PR #20 đã merge vào main tại `a8a49f2`; Go và Android CI trên commit main đều
xanh. M8-D vẫn còn các cổng public website, provider và thiết bị thật; không
đồng nghĩa ứng dụng đã sẵn sàng phát hành.

Nhánh `codex/complete-journeys-and-invites` tiếp tục ba mục còn thiếu đã trao
đổi: tự đến nơi, cảnh báo chưa có cập nhật và link mời. Các mục Proposed như
multi-Circle và SSE/WebSocket không được tự coi là phạm vi đã chốt.

| Phần | Trạng thái trên nhánh |
| --- | --- |
| API, migration và xử lý tự đến nơi | Implemented; kiểm thử tổng hợp, chưa nghiệm thu GPS thực địa |
| API, hàng đợi cảnh báo theo từng đợt, owner và người xem đã chọn | Implemented; provider thật chưa kiểm chứng |
| Android DTO, kiểm tra lại quyền/episode và dedup push | Implemented; bảng tùy chọn B, xác nhận thủ công và nhãn đến nơi ước tính |
| API xem trước lời mời, account binding; parser native link | Implemented; Intent cold/warm, inbox qua đăng nhập/xoay máy, sharesheet và xác nhận C |
| Giao diện tùy chọn Journey và xem trước lời mời | Implemented; đã duyệt B/C và kế hoạch Compose qua chat, chờ CI/native finish review của UI mới |

Quyết định của owner: tự đến nơi tắt mặc định, bật theo từng chuyến; cảnh báo
gửi cho chủ hành trình và những người xem đã chọn còn quyền. Cả hai tùy chọn
mặc định tắt ở API để client cũ giữ nguyên hành vi. Các ngưỡng và quyền được
ghi tại [ADR 0016](../adr/0016-journey-automation-and-invite-links.md).

Kiểm tra nền tảng trước UI: `make quality-check` qua; sau khi thêm audit episode và kiểm
thử thu hồi quyền, `make server-check` và `make server-test-integration` qua
lại trên toàn bộ package được target khai báo. Integration chạy với PostgreSQL/
PostGIS thật của stack Docker local, có race detector; migration up/down cũng
qua. Ở lượt đó Android JVM kiểm tra parser và episode; chưa có UI B/C để
nghiệm thu. Hosted CI của từng lượt phải được kiểm tra riêng theo commit.

Hosted CI trên `ff029630e7c71d3cd7cda287363f60cb505925c0`: [Go](https://github.com/QuocDuong16/anve/actions/runs/43168512235)
và [Android](https://github.com/QuocDuong16/anve/actions/runs/43168512163)
đều thành công. Đã đọc log PostgreSQL và artifact instrumentation: 32 tests,
0 lỗi/skip, 30 captures của UI hiện có; signed minified startup cũng qua.
Đây là bằng chứng nền tảng, không phải render của bảng B/hộp thoại C mới.
Sau phần inbox/readback, `make quality-check` và toàn bộ integration local
qua lại; CI cho commit tiếp theo được theo dõi riêng trên PR #21.

Comp tham khảo dùng dữ liệu minh họa, lưu tại
`.impeccable/mocks/feature-completion/`: `journey-automation-a/b/c.png` và
`invite-preview-a/b/c.png`, kèm prompt. Owner đã duyệt Journey B và lời mời C
qua câu hỏi chọn mẫu; sidecar tương ứng ghi `approved: true`. Journey:
A bước 4 trên form, B bảng tùy chọn, C mục thu/mở. Lời mời: A màn riêng,
B bảng từ dưới lên, C hộp thoại. Điều hướng nền trong ảnh lời mời chỉ minh
họa; implementation giữ cấu trúc app hiện tại.

Plan có 22 semantic regions, không raster; portal review thoát 2 vì không có
trình duyệt cho người duyệt. Ngoại lệ duyệt kế hoạch qua chat cho scope mới
được owner duyệt: “Duyệt nha bạn code tiếp đi”. Formal receipt vẫn pending;
không giả lập phê duyệt và không dùng lại ngầm ngoại lệ M7-B trước đó.
Logic giữ link trong bộ nhớ qua xoay máy/đăng nhập, hủy khi đổi tài khoản,
hết hạn sau 15 phút và readback tham gia chưa rõ đã có JVM tests qua;
UI/Intent đã được nối theo ngoại lệ hiện tại. Hộp thoại kiểm tra tên nhóm,
tài khoản và hạn mời; nhóm đã tham gia, link không hợp lệ, loading, offline,
retry và kết quả chưa rõ có trạng thái riêng. Back/“Để sau” không tự join;
sau xác nhận đã gửi, đóng UI không phải hủy được mutation máy chủ.

Controls là Compose thật, không nhúng mockup. Mặc định hai lựa chọn tắt;
đóng sheet bỏ draft, Apply áp dụng cho lần tạo này. App nói rõ ước tính có thể
sai và có thể tự kết thúc hành trình, giữ xác nhận thủ công. Tests mới bao gồm
admission/late-account response, single-submit/read-only reconciliation,
controls và render sáng/tối/chữ lớn 1.3, URI cold/warm và rotation. APK test
đã compile; kết quả emulator và independent finish review phải lấy trên CI
đúng commit, được liên kết tại PR #21, không suy ra từ JVM/build local.

Sau khi nối UI, `make quality-check` qua lại, bao gồm lint, JVM và release
privacy/config guard với baseline đã đối chiếu. Unit tests có cả giữ nhóm đã
được server xác nhận khi tải thành viên lỗi và chặn membership không khớp
nhóm trong readback. Kết quả này chưa thay thế lượt emulator của UI mới.

Review inventory xác nhận chỉ thêm entry VIEW/BROWSABLE của MainActivity và
hash source manifest, không thêm quyền/SDK; xem
[boundary review](../security/journey-invitation-boundary-review.md).
Các cổng Firebase/Maps/Google thật, GPS/pin/OEM và HTTPS App Links công khai
vẫn mở. Không coi việc code ba tính năng là hoàn tất toàn bộ M8 hay phát hành.
