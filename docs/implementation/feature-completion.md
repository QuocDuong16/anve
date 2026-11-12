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
| Android DTO, kiểm tra lại quyền/episode và dedup push | Implemented; chưa có giao diện bật tùy chọn |
| API xem trước lời mời, account binding; parser native link | Implemented; chưa nối Intent, chia sẻ và xác nhận trên UI |
| Giao diện tùy chọn Journey và xem trước lời mời | Planned; đang chờ chọn comp trước khi code |

Quyết định của owner: tự đến nơi tắt mặc định, bật theo từng chuyến; cảnh báo
gửi cho chủ hành trình và những người xem đã chọn còn quyền. Cả hai tùy chọn
mặc định tắt ở API để client cũ giữ nguyên hành vi. Các ngưỡng và quyền được
ghi tại [ADR 0016](../adr/0016-journey-automation-and-invite-links.md).

Kiểm tra local: `make quality-check` qua; sau khi thêm audit episode và kiểm
thử thu hồi quyền, `make server-check` và `make server-test-integration` qua
lại trên toàn bộ package được target khai báo. Integration chạy với PostgreSQL/
PostGIS thật của stack Docker local, có race detector; migration up/down cũng
qua. Android JVM mới kiểm tra parser và episode, chưa có Intent/UI mới để
nghiệm thu. Hosted CI của nhánh phải được kiểm tra riêng theo commit.

Comp tham khảo dùng dữ liệu minh họa, lưu tại
`.impeccable/mocks/feature-completion/`: `journey-automation-a/b/c.png` và
`invite-preview-a/b/c.png`, kèm prompt và trạng thái chưa duyệt. Journey:
A bước 4 trên form, B bảng tùy chọn, C mục thu/mở. Lời mời: A màn riêng,
B bảng từ dưới lên, C hộp thoại. Điều hướng nền trong ảnh lời mời chỉ minh
họa; implementation giữ cấu trúc app hiện tại. Chưa chọn thay người dùng.

Sau khi duyệt: triển khai controls Compose thật, trạng thái loading/empty/
offline/error/permission, bảo vệ đổi tài khoản và kết quả join chưa rõ;
kiểm tra native sáng/tối/chữ lớn trên CI emulator. Không nhúng mockup vào app.
Theo dõi bằng chứng exact-commit tại PR của nhánh; chưa coi toàn bộ tính năng
đã hoàn tất khi các luồng Android này còn thiếu.
