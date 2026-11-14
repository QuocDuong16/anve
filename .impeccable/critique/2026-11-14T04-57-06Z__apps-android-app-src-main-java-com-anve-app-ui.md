---
target: Toàn bộ UI/UX flow app AnVề
total_score: 35
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 2
target_identity: "file:/home/niand/Workspace/anve/apps/android/app/src/main/java/com/anve/app/ui"
timestamp: 2026-11-14T04-57-06Z
slug: apps-android-app-src-main-java-com-anve-app-ui
---
# Critique vòng 2 — AnVề Android (apps/android/app/src/main/java/com/anve/app/ui)

Phạm vi: toàn bộ UI/UX flow — AnVeApp, splash, signin, home, circle (gồm invitation), map, tracking, history, places, journey, sos, diagnostics, avatar, theme, common. Phương pháp: design review (native platform, detector/browser không áp dụng), đọc ~10.100 dòng Kotlin + strings.xml + PRODUCT.md + DESIGN.md + docs/product/user-journeys.md; persona test 3 persona; chấm Nielsen 0-4 theo Heuristics Scoring Guide. Không chạy gradle/make/emulator (nhiệm vụ chỉ review, và môi trường local không có device).

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 4 | Mọi mutation đều có phản hồi nhìn thấy (labeled progress, pending chip, snackbar, dialog xác nhận); điểm trừ duy nhất là thông báo kết quả ở Tài khoản không bao giờ tự tắt |
| 2 | Match System / Real World | 4 | Tiếng Việt thuần, không jargon (FCM chỉ nằm trong note "Kỹ thuật:"), dd/MM/yyyy + 24h, freshness nói "Vị trí đã cũ" chứ không suy diễn nguy hiểm |
| 3 | User Control and Freedom | 3 | Back/cancel/Để sau ở mọi điểm; form Hành trình mất trắng draft khi back, không hỏi như PlaceEditor |
| 4 | Consistency and Standards | 3 | Top-bar rule + destructive-confirm + AnveErrorNotice nhất quán; còn lệch: 3 kiểu thông báo thành công, switch không gắn nhãn ở 2 surface, ArrowBack không AutoMirrored, FAB clearance chỉ áp 2/5 danh sách |
| 5 | Error Prevention | 4 | Confirm-dialog cho mọi mutation phá hủy; gate cho từng blocker (consent/quyền/mạng/người nhận) một lúc một; validate tọa độ inline; education dialog trước quyền nền |
| 6 | Recognition Rather Than Recall | 4 | Checklist kích hoạt giữ trạng thái tới khi xong; explainer bullet inline; mọi icon hành động có contentDescription; chips hiện giá trị hiện tại |
| 7 | Flexibility and Efficiency | 3 | Pull-to-refresh + nút dự phòng, "Cả ngày", chips mốc sẵn, auto load-more, "Dùng vị trí hiện tại"; chưa có bulk action |
| 8 | Aesthetic and Minimalist Design | 3 | Card grouping sạch, hero control rõ; "Cài đặt chia sẻ" card trùng lặp chỉ dẫn với checklist trên tab Nhóm; copy trung thực dài nhưng đã bẻ bullet |
| 9 | Error Recovery | 4 | Mỗi lỗi là câu tiếng Việt đủ nghĩa + hướng xử lý cạnh đó; trạng thái "chưa xác nhận" nói chính xác cái gì chưa biết, "app không tự gửi lại" |
| 10 | Help and Documentation | 3 | Contextual help rất tốt (disclosure, education dialog, privacy note) nhưng không có help/FAQ tổng |
| **Total** | | **35/40** | **Good — nền rất chắc, còn ~2 P2 và 10 P3** |

## Design Specificity Verdict

**Authored cho AnVề, không category-interchangeable.** Bộ copy "trung thực trạng thái" là dấu hiệu sản phẩm riêng: consent ≠ capture được nói ở 5 surface khác nhau; stale marker xám trung tính kèm bình luận chống suy diễn tai nạn (LiveMapScreen.kt:466-473); SOS phân biệt 4 mức push (queued → accepted → delivered-ish → acknowledged) bằng đúng giới hạn PRODUCT.md; emergency amber tách khỏi error. Activation checklist 3 bước (mời → đồng ý → chọn người xem) chỉ tồn tại ở model consent-family này. Palette teal/amber/sand + kiểu chữ native được dùng nhất quán theo DESIGN.md.

Detector/browser: not applicable (native platform).

## Overall Impression

Vòng 2 đã xử lý sạch các điểm nghẽn vòng trước: destructive-confirm phủ kín, AnveErrorNotice dùng thống nhất, pull-to-refresh Nhóm có vòng đời đúng, role member map đúng key, tiêu đề Tracking không lặp. Bề mặt còn lại là lấp lánh lớp mỏng: hai lỗi P2 (FAB đè nút cuối, lỗi không được TalkBack đọc) và một loạt lệch nhỏ về tính nhất quán.

## What's Working

1. **Kỷ luật copy trung thực** (strings.xml:36-36, 87-92, 811-818; SignInViewModel.kt:141-155): mỗi câu nói đúng điều app biết — "máy chủ chưa xác nhận", "app không tự gửi lại", dev-only raw server text bị nhốt sau BuildConfig.DEBUG. Người dùng phổ thông đọc được trạng thái mà không bị lừa về độ chắc chắn.
2. **Error Recovery toàn diện**: AnveErrorNotice (ErrorNotice.kt:28-49) + retry cạnh lỗi, các trạng thái unknown có nhánh "Kiểm tra kết quả" riêng (AvatarSheet.kt:79-80, EmailInvitationSheet.kt:146-151, InvitationDialog.kt:77); form giữ draft khi lỗi (PlacesScreen.kt), history giữ trang đã tải khi lỗi append (HistoryViewModel.kt:139-158).
3. **Cognitive load thấp**: 0-1 mục fail trong checklist 8 điểm — grouping card, hero control đơn, blocker hiển thị từng cái một (JourneyScreen.kt:435-441), progressive disclosure (segmented Diagnostics, expandable tracking diagnostics, options sheet).

## Priority Issues

**[P2] FAB SOS đè lên nút hành động cuối ở Nhóm / Tài khoản / Vị trí** — HOME_FAB_CLEARANCE (72dp, HomeScreen.kt:176) chỉ được History (HistoryScreen.kt:282) và Places (PlacesScreen.kt:262) dùng; CircleScreen.kt:280, HomeContent HomeScreen.kt:580 và TrackingContent TrackingScreen.kt:375 vẫn padding 16dp. Khi cuộn hết: "Tạo mã mời mới" (chủ nhóm), "Đăng xuất"/"Xóa tài khoản và dữ liệu" (Tài khoản) và "Hành trình"/"Chẩn đoán" (Vị trí) bị phần phải che dưới FAB. Vì sao đáng sửa: nguy cơ chạm nhầm SOS trên hai màn rủi ro nhất của app. Fix: đặt `bottom = HOME_FAB_CLEARANCE` vào contentPadding của ba LazyColumn đó.

**[P2] AnveErrorNotice không có liveRegion — TalkBack không đọc lỗi mới xuất hiện** — ErrorNotice.kt:28-49 không set semantics; chỉ SignInScreen.kt:170-173 và CircleScreen.kt:295-303 tự thêm ở call site, còn lại 8+ surface dùng notice im lặng với screen reader. Fix: thêm `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` vào Text bên trong AnveErrorNotice, bỏ phần lặp ở SignIn.

**[P3] Form Hành trình mất draft khi back, không hỏi** — JourneyScreen.kt:148 `BackHandler { if (creating) creating = false ... }`, các trường dùng remember (JourneyScreen.kt:373-378). PlaceEditor đã làm đúng pattern discard-confirm (PlacesScreen.kt:519-525, 701-713). Fix: thêm dialog "Bỏ thay đổi?" khi draft khác rỗng, hoặc rememberSaveable các trường.

**[P3] Switch/checkbox không gắn tên thành viên** — AudienceSheet LiveMapScreen.kt:428-432 (Switch quyền xem), PlacesScreen.kt:355-360 (Switch chia sẻ) và 376-382 (Checkbox người nhận) là control rời trong Row/ListItem; SOS (SosScreen.kt:194-208, `toggleable`) và Journey (JourneyScreen.kt:385-413, `selectable(role=…)`) đã làm đúng. Fix: bọc `Modifier.toggleable(value, role = Role.Switch, onValueChange)` trên cả row.

**[P3] Thông báo kết quả ở Tài khoản không bao giờ tắt** — HomeContent render messageRes/errorRes thành card (HomeScreen.kt:592-643) nhưng HomeViewModel.consumeMessage (HomeViewModel.kt:300-302) không được gọi ở đâu; "Đã cập nhật tên hiển thị" tồn tại vô hạn, làm kết quả mới khó tin là mới. Fix: thêm TextButton "Đã hiểu" gọi consumeMessage, hoặc clear sau timeout ngắn.

**[P3] Ba kiểu trình bày thông báo thành công** — card secondaryContainer (HomeScreen.kt:596-607, TrackingScreen.kt:392-404), text màu primary trần (CircleScreen.kt:292-305), snackbar (HistoryScreen.kt:203-210, PlacesScreen.kt:158-178, SosScreen.kt:387-395). Cùng loại sự kiện ("đã lưu") hiển thị 3 cách. Fix: chuẩn hóa một pattern (khuyến nghị snackbar cho kết quả một lần, card cho trạng thái cần trụ lại) và ghi vào DESIGN.md cạnh Failure Notice Rule.

**[P3] Điểm lịch sử được chọn phân biệt chỉ bằng màu chữ** — HistoryScreen.kt:410-424 đổi `onSurface` → `primary`, không có selected semantics cũng như nền khác biệt. Fix: thêm nền surfaceVariant khi chọn + `Modifier.semantics { selected = … }`.

**[P3] Top bar Tài khoản/Vị trí dùng ArrowBack không AutoMirrored** — HomeScreen.kt:22,328 trong khi Journey/Diagnostics/Places đều dùng `Icons.AutoMirrored.Filled.ArrowBack`. Fix: đổi import sang AutoMirrored để khớp chuẩn M3 và sẵn sàng RTL.

**[P3] Tagline splash có hai nguồn sự thật** — SplashCopy.kt:15 (const Kotlin) và strings.xml:5 (`splash_tagline`) chứa cùng câu, SignInScreen đọc từ resources, SplashScreen đọc const — drift tiềm ẩn. Fix: một nguồn (strings.xml), SplashCopy giữ helper format phiên bản.

**[P3] Card "Cài đặt chia sẻ" trùng lặp chỉ dẫn với checklist kích hoạt trên tab Nhóm** — HomeScreen.kt:408-413 render card trên mọi tab trừ Bản đồ, trong khi checklist Nhóm (CircleScreen.kt:312-326) đã có chính các nút "Mở màn Vị trí"/"Tạo lời mời". Fix: ẩn card khi tab == CIRCLE và checklist đang hiển thị (hoặc chỉ giữ card cho Lịch sử/Địa điểm).

**[P3] "Tiếp tục ưu tiên hành trình" không có phản hồi đang chạy** — JourneyScreen.kt:336-338 gọi onResume mà JourneyViewModel.resumePriority (JourneyViewModel.kt:192-208) không set busy, LinearProgressIndicator đầu danh sách không chạy, lỗi mới hiện sau nhiều giây. Fix: set busy trong resumePriority (hoặc disable nút while-in-flight).

**[P3] Nhãn hero "Bật chia sẻ vị trí" làm mờ ranh giới consent ≠ capture mà chính màn hình dạy** — khi consent off, nút chính TrackingScreen.kt:524-527 hiển thị `tracking_enable_consent` ("Bật chia sẻ vị trí") nhưng hành vi là consent + start theo dõi ngay (PendingAction.ENABLE_THEN_START, TrackingScreen.kt:149-167), trong khi hàng consent ngay dưới nhấn mạnh "chưa phải điện thoại đang ghi vị trí". Fix: đổi copy thành "Bật chia sẻ và theo dõi" (hoặc tương đương) cho đúng hai fact cùng lúc.

## Persona Red Flags

**Bà Hùng (68 tuổi, người lớn tuổi lo con cháu)**: nút "Tạo mã mời mới" và "Xóa tài khoản" bị FAB SOS che một phần khi cuộn hết (issue P2 đầu) — chạm nhầm SOS khi đang làm việc khác là kịch bản thật; nhãn hero "Bật chia sẻ vị trí" không nói đủ việc vừa bật theo dõi (P3 cuối). Điểm tốt dẫn cô ấy qua được: checklist 1-2-3 giữ trạng thái tới khi xong, quyền nền có dialog giải thích trước khi vào Cài đặt, picker Material3 đúng tiếng Việt.

**Minh (người bận rộn, dùng máy một tay)**: nút "Bắt đầu hành trình" nằm đáy form cuộn, không ghim đáy màn như SOS — đang đi đường thì scroll+tap hai tay; back khỏi form mất trắng lựa chọn không hỏi. Điểm tốt: pull-to-refresh 3 tab, chips mốc thời lượng, auto load-more, "Dùng vị trí hiện tại" thay vì bắt gõ tọa độ.

**Sam (phụ thuộc accessibility/TalkBack)**: lỗi mới xuất hiện không được đọc to (AnveErrorNotice thiếu liveRegion); Switch "Quyền xem" và Checkbox người nhận địa điểm không mang tên thành viên trong chính node điều khiển; điểm lịch sử chọn màu-only, không selected semantics. Điểm tốt: NavigationBar không đọc trùng nhãn (đã sửa vòng này), FAB SOS có tên từ text, Journey gắn `heading()` (JourneyScreen.kt:198,261,364), DiagnosticsNotice tự chuyển bố cục khi fontScale > 1.15 (DiagnosticsScreen.kt:396).

## Minor Observations

- Key `circle_join_button` (strings.xml:134) không còn chỗ dùng — mã chết, không ảnh hưởng người dùng.
- CircleScreen giữ dialog state bằng `remember` (CircleScreen.kt:97-100) trong khi HomeContent/ReceivedInvitations dùng `rememberSaveable` — process death khi dialog mở sẽ đóng im lặng, không mất dữ liệu server-side.
- SOS không có pull-to-refresh (đã có polling 10s + "Kiểm tra lại yêu cầu" + snackbar jump) — nhất quán trải nghiệm có thể cải thiện nhưng không bắt buộc.
- "Thử đồng bộ ngay" (TrackingScreen.kt:737) disabled khi queue rỗng mà không có chú giải tại chỗ (queue count ngay trên đã nói).
