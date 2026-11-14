---
target: Toàn bộ UI/UX flow app AnVề
total_score: 33
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 2
target_identity: "file:/home/niand/Workspace/anve/apps/android/app/src/main/java/com/anve/app/ui/apps/android/app/src/main/java/com/anve/app/ui"
timestamp: 2026-11-14T09-04-45Z
slug: apps-android-app-src-main-java-com-anve-app-ui
---
# Critique — Toàn bộ UI/UX flow app AnVề (ui/, independent re-score v5)

Method: design review only (detector not applicable, native Android platform). Đọc lại toàn bộ 43 file Kotlin trong `apps/android/app/src/main/java/com/anve/app/ui/` (màn hình + ViewModel + theme/common/avatar), đối chiếu PRODUCT.md, DESIGN.md, docs/product/user-journeys.md và strings.xml. Không có `.impeccable/critique/ignore.md`.

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 3 | Kết quả consent/rename/phiên không được công bố cho TalkBack (thẻ kết quả thiếu liveRegion); tab Lịch sử/Địa điểm/SOS chớp trống vài frame khi session re-emit |
| 2 | Match System / Real World | 4 | Copy tiếng Việt trung thực vượt chuẩn: "Vị trí đã cũ", "chưa xác nhận", phân biệt consent ↔ ghi vị trí ↔ quyền xem; jargon chỉ xuất hiện trong note có nhãn "Kỹ thuật:" |
| 3 | User Control and Freedom | 4 | Mọi luồng có lối thoát: Để sau/Hủy/Ẩn với tôi, xác nhận before-destructive, khôi phục pre-push bằng "Quay lại", hủy SOS kèm recheck |
| 4 | Consistency and Standards | 3 | Nút "gác cò" destructive nhất quán màu error trên Home/Circle/Places nhưng trung tính trên SOS/Journey; heading() semantics không đồng đều; marker violet ngoài palette |
| 5 | Error Prevention | 3 | Khỏe: một blocker mỗi lần (journey), thông báo cạnh nút bị khóa, xác nhận giảm retention. Còn hở: khoảng giờ bắt đầu > kết thúc chỉ bị chặn sau khi chọn |
| 6 | Recognition Rather Than Recall | 4 | Checklist kích hoạt, chip thành viên, hint chạm điểm, explainer từng gạch một dòng — không có gì phải nhớ |
| 7 | Flexibility and Efficiency | 3 | Pull-to-refresh + nút refresh, auto load-more, preset 1/7/30 ngày và 1/2/4/8 giờ; chưa có shortcut mạnh hơn (favorites, bật lại hành trình cũ) |
| 8 | Aesthetic and Minimalist Design | 3 | Một hành động chính mỗi màn, nhóm card rõ; vài chỗ nhiễu: TextButton "Vị trí & chia sẻ" trôi dưới chip trạng thái trên Bản đồ, màn SOS dài |
| 9 | Error Recovery | 4 | Thông báo gọi đúng vấn đề + hướng dẫn + giữ nguyên công việc; trạng thái "chưa xác nhận" kèm Kiểm tra kết quả; trang lỗi không xóa dữ liệu đã tải |
| 10 | Help and Documentation | 2 | Không có help/docs/hệ support nào: lỗi đăng nhập chỉ bảo "liên hệ người cài đặt AnVề" không kèm kênh liên hệ; bù lại inline explainer rất tốt |
| **Total** | | **33/40** | Good — nền tảng vững, còn khoảng cách tới Excellent |

## Design Specificity Verdict

**Authored cho AnVề, không category-interchangeable.** Bộ teal/sơn mài + amber emergency tách khỏi error, copy "không suy diễn có tai nạn", checklist kích hoạt ba bước, nhãn Fresh/Delayed/Stale gray-neutral — những quyết định này chỉ có thể thuộc về một app chia sẻ vị trí tự nguyện trong gia đình. Deterministic scan: not applicable (native platform).

## Priority Issues

**[P2] SOS dặn gọi 113/114/115 nhưng không cho nút gọi — SosScreen.kt:183-185 + strings.xml:389.** Disclaimer là đúng产品, nhưng ở khoảnh khắc căng thẳng cao nhất, người lớn tuổi phải thoát app, tìm app điện thoại và gọi tay. Fix: thêm một TextButton/OutlinedButton "Gọi 115" (hoặc 3 phím) cạnh disclaimer dùng `ACTION_DIAL` — không cần quyền, không thay đổi cam kết "không tự gọi".

**[P2] Kết quả một-lần không được TalkBack công bố — HomeScreen.kt:724-744 (ResultNoticeCard), TrackingScreen.kt:398-429.** "Đã cập nhật tên hiển thị", kết quả bật consent, "Đã đăng xuất thiết bị" chỉ hiển thị thị giác; AnveErrorNotice đã có liveRegion nên hai thẻ này là lỗ hổng cùng một quy ước. Fix: thêm `Modifier.semantics { liveRegion = Polite }` vào Text trong ResultNoticeCard và hai thẻ thông điệp của TrackingContent.

**[P3] Journey stale bảo người xem "hãy liên hệ" mà không cho kênh liên hệ — JourneyScreen.kt:354-356 (journey_stale_notice).** Người xem phải tự nhớ/rời app để gọi. Fix: thêm hành động liên hệ (ACTION_DIAL khi có số, hoặc ít nhất deep-link ra mục chi tiết SOS của người đó) cạnh thông báo.

**[P3] Màu nút "gác cò" destructive không nhất quán — SosScreen.kt:375 và JourneyScreen.kt:377 (trung tính) vs HomeScreen.kt:910-914, CircleScreen.kt:694-700 (error).** Cùng một lớp hành động (mở dialog xác nhận thu hồi) mang hai màu khác nhau. Fix: chọn một quy ước (error cho mọi nút gác cò) và áp cho cả hai.

**[P3] Toggle mở rộng không công bố trạng thái — TrackingScreen.kt:775-781 ("Chi tiết kỹ thuật"), PlacesScreen.kt:561-567 ("Giải thích").** TalkBack đọc nhãn nhưng không biết đang mở hay đóng. Fix: thêm `Role.Expandable`/`stateDescription` hoặc đổi thành `toggleable` với contentDescription "Mở/Đóng".

**[P3] Heading semantics thiếu ở một số tiêu đề mục — CircleScreen.kt:367-371, 383-386 ("Thành viên", "Mã mời"), PlacesScreen.kt:474 ("Sự kiện").** Các màn khác (SOS, Diagnostics, Journey, Places chi tiết) đều đánh heading() nên điều hướng theo tiêu đề bị đứt quãng. Fix: thêm `Modifier.semantics { heading() }` cho các tiêu đề còn lại.

**[P3] Khoảng giờ Lịch sử chỉ bị chặn sau lựa chọn — HistoryScreen.kt:574-613 + HistoryViewModel.kt:100-108.** Chọn giờ bắt đầu sau giờ kết thúc mới thấy "Khoảng giờ không hợp lệ". Fix: kẹp đầu kia của khoảng (max(start, end-1)) khi xác nhận một đầu, hoặc vô hiệu hóa confirm khi start ≥ end.

**[P3] Email lời mời chỉ kiểm tra khi bấm Tạo — EmailInvitationSheet.kt:97-101 + PrivateInvitationsController.kt:65-67.** Nhập sai chỉ thấy lỗi sau submit, không có isError/quay lại ô lỗi. Fix: chạy regex khi email không trống và đặt `isError` + supportingText.

**[P3] Tab Lịch sử/Địa điểm/SOS chớp trống khi session re-emit — HistoryScreen.kt:182, PlacesScreen.kt:146, SosScreen.kt:113.** `return` khi owner == null vẽ khoảng trống thay vì "Đang kiểm tra…" như màn Tài khoản. Fix: render một dòng trạng thái khi `owner == null`.

**[P3] Retry của lỗi sharesheet làm việc khác — CircleScreen.kt:142 (state.errorRes = shareError ?: state.errorRes) + :350 (AnveErrorNotice(..., onRetry = onRetry)).** Lỗi "Không mở được trình chia sẻ" được bám kèm nút Thử lại nhưng Thử lại chỉ refresh nhóm; thông báo đã tự chỉ lối thoát (mã đang hiển thị) nên nút này gây hiểu lầm. Fix: hiển thị shareError qua AnveErrorNotice không kèm retry.

**[P3] Splash hardcode 44.sp đè displaySmall — SplashScreen.kt:54-57.** Đi ngược quy tắc token typography trong DESIGN.md; vì là brand moment, nên bỏ override hoặc ghi chú token riêng. Fix: dùng displaySmall nguyên vai trò hoặc loại fontSize override.

**[P3] Marker confidence "không rõ" dùng violet ngoài palette — LiveMapScreen.kt:511.** Cả bảng màu品牌 (teal/amber/sand/ink + green/yellow/gray/azure cho map) không có violet; nhánh `else` gần như không xảy ra nhưng vẫn là màu mới không có chủ. Fix: dùng gray stale icon cho nhánh else.

## Persona Red Flags

**Bà Hường (68, mẹ ở quê lo con gái về muộn):** Gửi SOS suôn sẻ (FAB amber, thanh gửi ghim đáy, xác nhận rõ). Điểm gãy duy nhất: disclaimer bảo gọi cấp cứu nhưng không có nút gọi — bà phải thoát app giữa lúc hoảng (P2 số 1). Màn SOS dài (4 mục) nhưng được nhóm card + tiêu đề heading rõ.

**Casey (bận rộn, một tay):** Thumb zone chuẩn (nav đáy, FAB, thanh gửi SOS ghim đáy), state giữ qua gián đoạn (rememberSaveable cho tab, draft, pre-push + "Quay lại"), pull-to-refresh và auto load-more. Không red flag lớn; History cần 2 tầng dialog để lọc giờ.

**Sam (TalkBack/keyboard):** Gần như mẫu hình: hàng toggleable gộp tên + trạng thái, target ≥48dp, AnveErrorNotice liveRegion, icon có nhãn. Ba lỗ hổng thật: kết quả một-lần không được công bố (P2), toggle mở rộng không nói trạng thái, heading navigation đứt ở Circle/Places events.

## Minor Observations

- Cognitive load checklist: 0-1 mục trượt — chip ≤4 lựa chọn mỗi nhóm (retention 3, expiry 4, stale 4, invite 3), journey chặn từng blocker một; load thấp.
- JourneyOptionsSheet/EmailInvitationSheet/AvatarSheet đều giới hạn 86% chiều cao màn và có preview — nhất quán.
- Phần "Sự kiện" ở Places nối chuỗi `"$at$delayed$confidence"` trực tiếp; nên gom qua string resource cho bản dịch sau này.
- Nút "Thử đồng bộ ngay" vô hiệu khi hàng đợi trống nhưng không giải thích vì sao — hàng "Điểm đang chờ" ngay trên đã trả lời; chấp nhận được.
- Live map: pointer "Vị trí & chia sẻ" dưới chip trạng thái là lựa chọn tối giản có chủ ý; có thể nâng thành IconButton nhỏ gọn hơn nếu sau này thêm hành động.
