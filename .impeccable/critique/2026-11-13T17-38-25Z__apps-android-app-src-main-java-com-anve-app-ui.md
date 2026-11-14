---
target: toàn bộ UI/UX flow Android (apps/android .../ui)
total_score: 29
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 4
target_identity: "file:/home/niand/Workspace/anve/apps/android/app/src/main/java/com/anve/app/ui"
timestamp: 2026-11-13T17-38-25Z
slug: apps-android-app-src-main-java-com-anve-app-ui
---
# Critique UI/UX toàn diện — AnVề Android

**Method: design review only (detector not applicable, native platform)**

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 3 | Đổi retention/tạo nhóm thành công thiếu xác nhận tức thời |
| 2 | Match System / Real World | 3 | "Push", "vĩ độ/kinh độ", "hàng đợi" leak vào SOS/Places |
| 3 | User Control and Freedom | 4 | Hủy/Thoát/Để sau/Ẩn với tôi/Kiểm tra lại ở mọi luồng |
| 4 | Consistency and Standards | 2 | 4 mẫu header; copy một nửa ngoài strings.xml; date picker platform |
| 5 | Error Prevention | 4 | Xác nhận mọi hành vi phá hủy, ràng buộc input, consent mặc định tắt |
| 6 | Recognition Rather Than Recall | 3 | Copy dẫn đến "tab Vị trí" không tồn tại; AssistChip giả |
| 7 | Flexibility and Efficiency | 2 | Không accelerator; phân trang thủ công |
| 8 | Aesthetic and Minimalist Design | 2 | Tracking/History quá tải; card-stack đơn điệu |
| 9 | Error Recovery | 4 | Unknown-state ladder + retry + giữ trạng thái lần thử |
| 10 | Help and Documentation | 2 | Chỉ có giải thích inline; không help surface |
| **Total** | | **29/40** | **Good** |

Cognitive load: 3/8 checklist failure (single focus — Tracking; chunking — History; minimal choices — PermissionsCard/SOS) → moderate-to-high.

## Design Specificity Verdict

Copy: authored cho AnVề (ngôn ngữ minh bạch consent ≠ capture ≠ permission là tài sản khác biệt). Visual: category-typical — mọi màn là card-stack M3 teal; khoảnh khắc brand "Đã đến nơi" chưa được thiết kế. Deterministic scan: not applicable (native). Visual overlays: not applicable (native).

## Overall Impression

Trục đạo đức rõ (minh bạch dữ liệu) nhưng ba màn quan trọng nhất — Vị trí, SOS, Bản đồ — bị quá tải hoặc nhồi nhét. Cơ hội lớn nhất: giữ hệ copy trung thực, tái cấu trúc mức độ hiển thị theo tần suất/cường độ cảm xúc của từng tác vụ.

## What's Working

1. Hệ thống copy trung thực về trạng thái (consent ≠ tracking ≠ permission; không suy diễn an toàn/nguy hiểm từ dữ liệu cũ).
2. Error recovery cho kết quả không chắc chắn: SOS outbox 4 tầng, "Kiểm tra lại" mọi nơi, sign-in giữ trạng thái lần thử.
3. Luồng nhận lời mời (InvitationDialog + ReceivedInvitations): preview, một lần dùng, thời hạn, notice consent-first.

## Priority Issues

1. [P1] TrackingScreen quá tải: 5 card, 4 khái niệm quyền, dual-switch consent/tracking dễ bị gộp; "Đã yêu cầu, chưa hoạt động" quá kỹ thuật. Fix: một điều khiển chính Start/Dừng, consent thành dòng phụ, quyền chỉ trồi lên khi thiếu. → $impeccable distill + $impeccable clarify.
2. [P1] SOS không thiết kế cho khẩn cấp: trang gộp 3 mối quan tâm; CTA "Gửi yêu cầu SOS" sau danh sách người nhận → dưới fold; không có xác nhận tức thời sau gửi. Fix: sheet gửi riêng với CTA ghim đáy + snackbar xác nhận. → $impeccable shape + $impeccable layout.
3. [P1] State điều hướng remember (không Saveable) chết khi xoay/process death (HomeScreen.kt:111-117, manifest không configChanges); TopAppBar secondary thiếu title cho Vị trí/SOS; 4 mẫu header. Fix: rememberSaveable hoặc NavController; chuẩn hóa header. → $impeccable polish + $impeccable layout.
4. [P1] Hổng activation: sau tạo nhóm không có bước tiếp theo; copy "tab Vị trí" trỏ đến tab không tồn tại (strings.xml:35). Fix: checklist 3 bước post-circle-creation với deep-link; sửa copy. → $impeccable onboard + $impeccable clarify.
5. [P2] FAB SOS màu error + icon Warning trên mọi tab; Spacer(80.dp) né FAB là composition chống nhau. Fix: FAB brand amber/primaryContainer, cân nhắc hold-to-send. → $impeccable colorize.

## Persona Red Flags

- Jordan (lần đầu/lớn tuổi): Places editor bắt nhập vĩ độ/kinh độ; thuật ngữ push trong SOS; "tab Vị trí" không tìm được.
- Casey (một tay, bị ngắt): xoay máy mất tab + người nhận SOS đã chọn; push ép chuyển màn cắt ngang tác vụ; CTA SOS dưới fold.
- Sam (accessibility): hàng người nhận SOS không toggleable-by-row (chỉ Checkbox tap được); History selected chỉ báo bằng màu; marker hue color-only (được cứu bởi label).

## Minor Observations

- "Đăng xuất" trùng label cho thu hồi thiết bị và đăng xuất chính mình (strings.xml:40-41).
- Pattern luminance()-ink ad-hoc ở 6+ file — lệch Paired Roles Rule của DESIGN.md.
- Color(0xff3563d9) hardcoded cho circle ở Places.
- GoogleMap trong LazyColumn nuốt pan dọc; date ISO 2026-11-14; DatePicker/TimePicker platform cũ.
- HomeContent error/message là Text trần; AssistChip onClick={} làm badge; AudienceSheet đoạn 60+ từ; splash hiện version; "Trang tiếp theo" phân trang tay.

## Questions to Consider

- Consent và tracking gộp một điều khiển với hai dòng trạng thái?
- SOS hold-to-send trên FAB?
- "Đã đến nơi" xứng đáng là khoảnh khắc brand thế nào?
- Một mẫu header duy nhất — mẫu nào?
