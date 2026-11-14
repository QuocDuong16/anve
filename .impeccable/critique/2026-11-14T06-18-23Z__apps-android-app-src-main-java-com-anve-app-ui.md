---
target: Toàn bộ UI/UX flow app AnVề
total_score: 32
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 3
target_identity: "file:/home/niand/Workspace/anve/apps/android/app/src/main/java/com/anve/app/ui"
timestamp: 2026-11-14T06-18-23Z
slug: apps-android-app-src-main-java-com-anve-app-ui
---
# Critique vòng 3 — Toàn bộ UI/UX flow app AnVề

Method: design review only (detector not applicable, native platform). Chỉ đọc — không chạy gradle/make, không emulator, không detector (native Android).

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 3 | Consent toggle chỉ khóa control khi busy, không spinner (gặp ở issue consent-busy); còn lại có labeled progress + pending states |
| 2 | Match System / Real World | 4 | Tiếng Việt trần tình, không jargon leak, disclaimers trung thực ("không suy diễn tai nạn") |
| 3 | User Control and Freedom | 4 | Discard-confirm cả PlaceEditor lẫn Journey draft, cancel/escape đầy đủ, "Để sau" trên mọi dialog, push deep-link có đường về |
| 4 | Consistency and Standards | 3 | 5 lệch nhỏ: Journey failure dùng attention-treatment, result-notice còn 3 nơi vi phạm rule riêng, icon recenter = Refresh, join card copy lệch, unlabeled switches |
| 5 | Error Prevention | 3 | Retention chip commit ngay không confirm; thu hồi mã mời không confirm; PlaceEditor draft không saveable |
| 6 | Recognition Rather Than Recall | 3 | Hàng điểm lịch sử trông như text tĩnh, affordance chạm không lộ |
| 7 | Flexibility and Efficiency | 3 | Pull-to-refresh + nút refresh kép, auto load-more, preset chips, "Dùng vị trí hiện tại"; không có customization |
| 8 | Aesthetic and Minimalist Design | 3 | Vị trí và Place details xếp chồng 4-5 khối chú thích bodySmall |
| 9 | Error Recovery | 3 | AnveErrorNotice + copy mapped theo contract code rất tốt; nhưng notice lỗi SOS không có retry liền kề |
| 10 | Help and Documentation | 3 | Giải thích ngữ cảnh dày đặc (disclosure, permission explainers, diagnostics); không có entry điểm help/docs tìm được |
| **Total** | | **32/40** | **Good — nền vững, sửa các điểm lệch nhỏ** |

Cognitive load checklist: 0-1 failure → low. Không có decision point nào vượt 4 lựa chọn trần (SOS outbox row tối đa 3 text actions, Places details đã nhóm card).

## Design Specificity Verdict

**LLM assessment**: Authored cho AnVề, không category-interchangeable. Ba cấu trúc không thể đổi thương hiệu: activation checklist gắn vòng đời Circle (mời → consent → audience, tự thu gọn theo progress thật), taxonomy freshness với marker STALE xám trung tính (LiveMapScreen.kt:491-497 — tuổi dữ liệu không tô đỏ nguy hiểm), và amber emergency tách bạch error (Theme.kt:64-75). Copy trung thực về giới hạn dữ liệu là bản sắc sản phẩm, không phải badge.

**Deterministic scan**: not applicable (native platform).

**Visual overlays**: không chạy (native).

## Overall Impression

Vòng 3 cho thấy app đã đạt trạng thái "một hệ thống, một giọng nói": error notice, snackbar result, destructive-confirm, FAB clearance, row-toggle đã đồng bộ ở hầu hết mặt phẳng. Khoảng cách tới 40 không còn nằm ở cấu trúc mà ở các góc lệch sót: accessibility của đúng control quan trọng nhất (switch consent), và 3 nơi result-notice vẫn chưa theo rule app tự viết trong DESIGN.md.

## What's Working

1. **Honest-state copy toàn hệ thống** — mọi trạng thái chưa xác nhận đều nói thật ("máy chủ chưa xác nhận", "app không tự gửi lại", "Kiểm tra kết quả"), không bao giờ diễn đạt dữ liệu cũ thành kết luận an toàn/nguy hiểm. Điều này hiếm và đúng chất family-safety.
2. **AnveErrorNotice + mapped contract errors** — một presentation lỗi duy nhất (errorContainer, liveRegion polite, retry/dismiss tùy chọn) và copy map theo từng mã lỗi (rate limited, invite unavailable, consent conflict...).
3. **Journey blocking-notice tuần tự** — JourneyCreateForm chỉ hiện MỘT điều kiện chặn theo thứ tự ưu tiên (capture → mạng → người xem → điểm đến, JourneyScreen.kt:482-488) thay vì xếp chồng mọi quy tắc.

## Priority Issues

1. **[P2] Switch consent vô danh với TalkBack** — TrackingScreen.kt:552-556 và HomeScreen.kt:804-808 là Switch trần không có label/role-row: người dùng screen reader chỉ nghe "bật/tắt" mà không biết đây là control chia sẻ vị trí quan trọng nhất. Fix: bọc row bằng toggleable(role = Role.Switch) như AudienceSheet (LiveMapScreen.kt:413-429) hoặc semantics contentDescription như JourneyOptionsSheet.kt:103 đã làm.
2. **[P2] One-shot results còn 3 nơi vi phạm Result Notice Rule tự viết** — invite_created_notice render thành Text trần persists vô hạn trong ReceivedInvitations.kt:60 (không liveRegion → TalkBack không nghe gì sau khi tạo lời mời); avatarMessageRes card không dismiss (HomeScreen.kt:664-679); consentMessage card không dismiss trong khi card message kế bên có (HomeScreen.kt:646-662). Fix: phát vào shell snackbar hoặc thêm dismiss + liveRegion.
3. **[P2] Retention chip commit ngay khi chạm** — HistoryScreen.kt:442-451 gọi vm.changeRetention(days) trực tiếp: chạm nhầm "1 ngày" là giảm thời hạn lưu vĩnh viễn (dữ liệu cũ có thể bị dọn) mà không có confirm hay dòng cảnh báo. Fix: confirm dialog hoặc dòng "giảm thời hạn sẽ xóa dữ liệu cũ hơn" trước khi commit.

## Persona Red Flags

**Bà Hường (62 tuổi, lo con cháu — project persona)**: hàng điểm lịch sử không có dấu hiệu chạm được (HistoryScreen.kt:407-434); khối disclosure/discussion dài nhiều đoạn trên Vị trí; chip 1/7/30 ngày chạm là xong không hỏi lại. Điểm cộng: checklist đánh số, SOS amber luôn trong tầm tay, nút hero to rõ.

**Minh (35 tuổi, một tay, bận rộn)**: PlaceEditor draft không rememberSaveable (PlacesScreen.kt:522-525) — xoay máy/process death mất tọa độ đã gõ trong khi Journey draft sống (JourneyScreen.kt:415-420); người nhận Places không saveable (PlacesScreen.kt:147-149) mất toggle trước khi "Lưu người nhận". Điểm cộng: FAB/bottom bar trong thumb zone, SOS send pinned bottom.

**Sam (phụ thuộc accessibility)**: 2 switch vô danh (P2 #1); invite-created result không được công bố (P2 #2); còn lại mẫu mực — toggleable rows, liveRegion trong AnveErrorNotice, NavigationBarItem label-only không double-announce, heading() semantics ở Journey.

## Minor Observations

- Journey failure notices (journey_reload_failed, journey_start_unconfirmed) render qua JourneyNotice màu secondaryContainer (JourneyScreen.kt:188, 506-516) — trông giống thông tin chú ý, không phân biệt được failure khỏi info; lệch Failure Notice Rule.
- InviteRow "Thu hồi" chạy ngay không confirm (CircleScreen.kt:689-694) trong khi remove/leave/dissolve/hide đều confirm.
- SOS error notice (SosScreen.kt:182) không có retry liền kề; "Kiểm tra lại" nằm cuối danh sách dài (SosScreen.kt:377).
- Card join Nhóm tựa "Tham gia bằng mã mời" nhưng nút là "Xem lời mời" (CircleScreen.kt:463); key circle_join_button ("Tham gia nhóm", strings.xml:134) thành dead key.
- Icon recenter của MemberLocationCard dùng Refresh (LiveMapScreen.kt:355-357) — đọc như "tải lại" thay vì "đưa bản đồ tới".
- Vị trí và Place details dày chú thích: Places details xếp chồng share_notice + monitor_notice + offline_notice + consent_notice trước action.
