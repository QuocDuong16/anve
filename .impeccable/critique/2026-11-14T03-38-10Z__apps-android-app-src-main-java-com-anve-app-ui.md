---
target: Toàn bộ UI/UX flow app AnVề
total_score: 34
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 2
target_identity: "file:/home/niand/Workspace/anve/apps/android/app/src/main/java/com/anve/app/ui"
timestamp: 2026-11-14T03-38-10Z
slug: apps-android-app-src-main-java-com-anve-app-ui
---
# Critique vòng 1 (lượt 2) — Toàn bộ UI/UX flow app AnVề

Method: design review only (detector not applicable, native platform — Android/Compose). Chỉ đọc mã nguồn + strings + tài liệu sản phẩm; không chạy emulator/gradle theo ràng buộc môi trường.

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 4 | Hệ thống trạng thái trung thực tối đa: pending/confirmed/unknown phân tách ở mọi mutation (consent, SOS, journey, avatar, invite) |
| 2 | Match System / Real World | 3 | Role server ("owner"/"member") lộ raw tiếng Anh trong bảng Quyền xem |
| 3 | User Control and Freedom | 4 | Cancel/back/discard/push-return đầy đủ; "Ẩn với tôi" không có đường un-hide |
| 4 | Consistency and Standards | 3 | 5 lệch nhỏ: role raw, error bare-text trong sheet, error màu onSurface trong dialog, dẫn đường consent phân vân, tiêu đề Vị trí lặp |
| 5 | Error Prevention | 3 | Thiếu dialog xác nhận "Thu hồi phiên" thiết bị; mọi hành động phá hủy khác đều có xác nhận |
| 6 | Recognition Rather Than Recall | 4 | Checklist kích hoạt, trạng thái tại chỗ, "Đang đăng nhập: X", chip thiết bị hiện tại |
| 7 | Flexibility and Efficiency | 3 | Pull-to-refresh + nút, chip giá trị nhanh, điền vị trí hiện tại; không có tùy biến |
| 8 | Aesthetic and Minimalist Design | 3 | Thương hiệu mạch lạc; màn Vị trí dày đặc khi nhiều trạng thái xếp chồng; tiêu đề lặp |
| 9 | Error Recovery | 4 | Lỗi tiếng Việt chỉ đích danh + hành động phục hồi, phân biệt chưa-xác-nhận vs thất-bại, giữ nguyên draft |
| 10 | Help and Documentation | 3 | Help ngữ cảnh rất tốt (disclosure, explainer, caveat) nhưng không có điểm vào help/FAQ riêng |
| **Total** | | **34/40** | **Good — nền tảng vững, còn vùng cần siết** |

Cognitive load checklist: 0–1 mục trượt (chip thời hạn hành trình có 5 lựa chọn, hơi vượt guideline ≤4) → tải nhận thức thấp.

## Design Specificity Verdict

**Specific — authored cho AnVề, không phải category-interchangeable.**
- Ngôn ngữ trạng thái trung thực là của riêng sản phẩm: "Máy chủ chưa xác nhận…", "Đến nơi tự động — ước tính", "Không suy diễn có tai nạn", phân biệt consent/máy chủ vs ghi nhận/thiết bị (strings.xml, TrackingScreen.kt). Đúng Brand Commitments trong PRODUCT.md.
- Hệ màu teal/amber/sand/ink có rationale sơn mài; emergency amber tách khỏi error, marker STALE xám trung tính (Theme.kt:64-91, LiveMapScreen.kt:450-459) — quyết định ngôn ngữ hình ảnh gắn với luật sản phẩm "dữ liệu cũ không phải bằng chứng nguy hiểm".
- Checklist kích hoạt 3 bước tự thu gọn theo tiến trình thật (CircleScreen.kt:489-542) là compose riêng cho luồng family-safety.
- Scan deterministic: không áp dụng (native platform).
- Overlay trực quan: không áp dụng (native platform).

## Overall Impression

Đợt overhaul trước đã nâng chất lượng thật: trạng thái "chưa xác nhận" là hạng nhất, error prevention ở luồng quyền tốt, copy tiếng Việt trung thực và nhất quán. Vùng còn lại là nét tinh: một lỗ hổng xác nhận phá hủy (thu hồi phiên thiết bị), một leak jargon (role raw), và các lệch pattern nhỏ trong sheet/dialog — không điều nào chặn luồng chính.

## What's Working

1. **Hệ trạng thái trung thực (Nielsen 1 & 9 đạt 4)**: SOS phân biệt queued/sending/unknown/confirmed/server-expired (SosScreen.kt:232-253); Journey giữ pending-notice với "Kiểm tra lại / Hủy yêu cầu đang chờ" (JourneyScreen.kt:168-182); map phân biệt Fresh/Delayed/Stale/Offline/Unknown bằng màu có chủ đích. Hiếm app consumer nào làm cẩn thận đến thế.
2. **Error prevention ở luồng quyền vị trí**: disclosure trước khi bật, dialog giáo dục trước quyền nền, phát hiện permanently-denied với đường ra Cài đặt, ràng buộc ngày tương lai trong date picker (TrackingScreen.kt:182-294, HistoryScreen.kt:479-484).
3. **Nhất quán destructive-confirm**: xóa thành viên/nhóm/lịch sử/địa điểm đều dialog xác nhận với nút confirm màu error + Hủy trung tính (CircleScreen.kt:203-225, HistoryScreen.kt:450-473, PlacesScreen.kt:434-450).

## Priority Issues

- **[P2] "Thu hồi phiên" thiết bị chạy không có xác nhận** — HomeScreen.kt:825-830 nối thẳng `viewModel::revokeDevice` (HomeViewModel.kt:207), không dialog, không chặn row "Thiết bị này"; trong khi đăng xuất, xóa tài khoản, xóa thành viên… đều có xác nhận. Bấm nhantes từ chối phiên (kể cả phiên đang dùng). Fix: thêm AlertDialog xác nhận kiểu destructive-confirm hiện có, và vô hiệu hóa nút cho `device.session?.current == true`.
- **[P2] Role server hiển thị raw trong bảng Quyền xem** — LiveMapScreen.kt:409 `Text(member.role)` in nguyên giá trị "owner"/"member" (AudienceMember.role từ MemberDto.role, VisibleLocationRepository.kt:254-259) trong khi CircleScreen.kt:601-607 đã map sang "Chủ nhóm"/"Thành viên". Leak jargon tiếng Anh trên màn quyết định quyền riêng tư. Fix: tái dùng cùng mapping circle_role_owner/circle_role_member.
- **[P3] Màn Nhóm không có đường làm mới** — CircleViewModel chỉ refresh lúc init và sau mutation (CircleViewModel.kt:51,122,136); không pull-to-refresh, không nút refresh; thành viên mới tham gia từ thiết bị khác không hiện cho tới khi rời màn hoặc mutation. Các tab khác đều có affordance tương ứng. Fix: thêm PullToRefreshBox giống History/Places hoặc refresh khi tab được chọn lại.
- **[P3] Tiêu đề màn Vị trí lặp chồng** — TopAppBar secondary page (HomeScreen.kt:323-332) và headline trong content (TrackingScreen.kt:382) cùng dùng `tracking_title`. Fix: bỏ headline trong content, giữ subtitle giải thích.
- **[P3] Lỗi trong sheet dùng text trần thay vì AnveErrorNotice** — EmailInvitationSheet.kt:139, AvatarSheet.kt:71, ReceivedInvitations.kt:31 hiển thị lỗi màu error trần, lệch với chuẩn errorContainer card toàn app (ErrorNotice.kt:20-26). Fix: dùng AnveErrorNotice trong sheet.
- **[P3] Lỗi trong InvitationDialog màu onSurface** — InvitationDialog.kt:70 render `errorRes` bằng màu ink thường, lỗi không phân biệt được khỏi thông tin. Fix: dùng colorScheme.error (hoặc errorContainer nhỏ).
- **[P3] TalkBack đọc trùng nhãn bottom nav và SOS FAB** — HomeScreen.kt:345-356 đặt icon contentDescription trùng label hiển thị; SOS FAB (HomeScreen.kt:366-377) đặt contentDescription "Mở SOS" cùng text con "SOS". Fix: icon decorative (contentDescription = null) khi đã có label; để text FAB tự phát âm.
- **[P3] Dẫn đường bật chia sẻ phân vân** — Checklist Nhóm bảo mở "màn Vị trí" (strings.xml:179) còn bảng Quyền xem bảo "Bật trong Tài khoản" (map_consent_off, strings.xml:471); switch consent lại nằm trên cả hai màn. Fix: chọn một màn chính cho việc bật consent và đồng bộ toàn bộ pointer.
- **[P3] Hai switch điều khiển cùng một consent máy chủ** — ConsentCard Tài khoản (HomeScreen.kt:755-759) và switch hàng consent ở Vị trí (TrackingScreen.kt:550-554). Copy đã giải thích nhưng người lớn tuổi có thể bạt cả hai và đợi hai kết quả khác nhau. Fix: trên Tài khoản giữ switch như cổng nhanh nhưng thêm liên kết "Mở màn Vị trí" ngay cạnh kết quả bật.
- **[P3] "Ẩn với tôi" không có đường hoàn tác** — ReceivedInvitations.kt:42-44 chỉ có Ẩn + Tải lại; lời mời gia đình bị ẩn nhầm là mất khỏi hộp thư vĩnh viễn. Fix: thêm dialog xác nhận trước khi ẩn hoặc một mục "Lời mời đã ẩn" với nút hiện lại.
- **[P3] Thông báo thành công màn Nhóm không được TalkBack công bố** — CircleScreen.kt:276-285 in `messageRes` dạng text trần, không liveRegion; Places/History/SOS dùng snackbar. Fix: chuyển sang snackbar hoặc thêm semantics liveRegion Polite.
- **[P3] `signin_server_error` nội suy tin nhắn máy chủ verbatim** — strings.xml:23 "Máy chủ báo lỗi: %1$s" đưa thẳng chuỗi server vào copy người dùng; server trả message kỹ thuật/ngôn ngữ khác sẽ lộ raw. Fix: map mã lỗi server sang resource tiếng Việt, giữ %1$s chỉ cho dev build.

## Persona Red Flags

**Bà Hoa (68 tuổi, lo cho con cháu — first-timer):**
- Hai switch cùng điều khiển consent (Tài khoản + Vị trí) — có thể bật xong chờ "theo dõi" chạy mà không hiểu cần nút "Bắt đầu theo dõi"; copy cảnh báo có nhưng là đoạn chữ nhỏ trong card.
- Dẫn đường bật chia sẻ khác nhau giữa checklist Nhóm và bảng Quyền xem — người không quen app sẽ đi hai đường cho một việc.
- Màn Vị trí khi gặp vấn đề xếp tới 9 khối (tiêu đề+subtitle, message, consent message, control, problem, permissions, status, diagnostics, 2 nút điều hướng) — grouping tốt nhưng vẫn là màn dày nhất app.
- Điểm sáng: checklist "Bước tiếp theo" 3 bước với bước đầu là nút chính — đúng thứ một người lớn tuổi cần sau khi tạo nhóm.

**Anh Minh (bận rộn, một tay trên xe — Casey):**
- Card "Cài đặt chia sẻ" — cổng vào điều khiển cốt lõi Vị trí — nằm đỉnh màn hình trên mọi tab; vùng ngón cái khó với, đối lập với SOS FAB/gửi SOS đã đặt đáy màn đúng chỗ.
- Tab Nhóm không pull-to-refresh (mọi tab khác có) — thao tác quen bị hụt ở đúng tab mặc định mở đầu.
- Điểm sáng: SOS có bottom bar ghim với dòng trạng thái + disclaimer; pull-to-refresh + nút refresh kép ở Lịch sử/Địa điểm.

**Chị Lan (TalkBack — accessibility):**
- Bottom nav đọc trùng "Nhóm, Nhóm" (icon desc + label); SOS FAB đọc "Mở SOS" + "SOS".
- Thông báo thành công ở Nhóm không được công bố (không liveRegion/snackbar).
- Role "owner"/"member" tiếng Anh được đọc nguyên văn trong bảng Quyền xem.
- Điểm sáng: copy 100% resource-backed, liveRegion trên màn đăng nhập, selectableGroup/Role.Checkbox trên hàng tùy chọn, heading() ở Hành trình; cặp màu có ThemeContrastTest (tồn tại trong repo — tôi không chạy được test vì ràng buộc không gradle).

## Minor Observations

- Chip thời hạn hành trình có 5 lựa chọn (30/60/120/240/480 — JourneyScreen.kt:395) hơi vượt guideline ≤4 lựa chọn tại một điểm quyết định.
- SOS screen giữ headline riêng "Yêu cầu hỗ trợ" dưới app bar "SOS" — hai chữ khác nhau nên chấp nhận được, nhưng cùng một họ trang secondary với Vị trí thì nên đồng bộ một kiểu.
- "Chi tiết kỹ thuật" (TrackingScreen.kt:747-793) là điểm progressive disclosure tốt; giữ nguyên.
- Marker STALE xám tự vẽ (LiveMapScreen.kt:470-497) là chi tiết chăm sóc đúng luật sản phẩm — giữ nguyên.
