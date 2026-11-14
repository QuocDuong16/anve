---
target: Toàn bộ UI/UX flow app AnVề
total_score: 34
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 1
target_identity: "file:/home/niand/Workspace/anve/apps/android/app/src/main/java/com/anve/app/ui"
timestamp: 2026-11-14T07-35-17Z
slug: apps-android-app-src-main-java-com-anve-app-ui
---
# Critique vòng 4 — Toàn bộ UI/UX flow app AnVề

Method: design review only (detector không áp dụng — native Android; không chạy `impeccable detect` theo ràng buộc vòng này). Chấm lại từ đầu trên code hiện tại (git main + thay đổi vòng 3/4 chưa gộp), so chiếu PRODUCT.md, DESIGN.md, docs/product/user-journeys.md, strings.xml và toàn bộ `apps/android/app/src/main/java/com/anve/app/ui/`. Không có `.impeccable/critique/ignore.md`.

## Bảng heuristic Nielsen (0–4)

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 4 | Mọi thao tác đều có xác nhận (snackbar theo Result Notice Rule, card kết quả ở Tài khoản), loading có nhãn ở History/Places/Diagnostics, trạng thái pending/unconfirmed hiển thị đầy đủ và trung thực |
| 2 | Match System / Real World | 4 | Tiếng Việt tự nhiên, phân biệt consent-vs-ghi-vị-trí, freshness-vs-an-toàn bằng ngôn ngữ đời thường; ngày dd/MM/yyyy, giờ 24h |
| 3 | User Control and Freedom | 4 | Cancel/back ở mọi bước, xác nhận trước mọi thao tác phá hủy, nháp PlaceEditor/JourneyForm giữ qua process death, "Để sau" thật sự hủy |
| 4 | Consistency and Standards | 3 | Hộp thoại xác nhận phá hủy của Journey (Thu hồi người xem/Kết thúc) và SOS (Hủy/Kết thúc/Thu hồi) không tô màu error như 8+ hộp thoại khác; JourneyOptionsSheet là row-switch duy nhất không toggleable-row |
| 5 | Error Prevention | 4 | Ràng buộc chủ động dày đặc: date picker chặn tương lai, tọa độ validate inline, mã mời tự uppercase, phiên hiện tại không tự thu hồi, giảm retention phải xác nhận, consent mặc định tắt kèm giải thích |
| 6 | Recognition Rather Than Recall | 3 | Hầu hết có chữ, có gợi ý (history tap hint, places map hint); tab Bản đồ ẩn card "Cài đặt chia sẻ" nên không có pointer nào tới Vị trí/Tài khoản |
| 7 | Flexibility and Efficiency | 3 | Pull-to-refresh song song nút Làm mới, chip thời lượng nhanh, "Cả ngày", auto load-more; không có accelerator ngoài các chip/gesture này |
| 8 | Aesthetic and Minimalist Design | 3 | Hệ thống card/tone nhất quán; SOS và bảng mời dày đặc câu caveat tĩnh; Tài khoản có thể xếp chồng tới 4 card thông báo |
| 9 | Error Recovery | 4 | Không có error code; read-back/"Kiểm tra lại" cho kết quả chưa rõ là điểm hiếm thấy; lỗi kèm retry cạnh thông báo, không xóa nháp người dùng |
| 10 | Help and Documentation | 2 | Giúp đỡ ngữ cảnh tốt (disclosure dialog, education dialog) nhưng không có điểm vào trợ giúp nào; 2 chuỗi đăng nhập hứa "hướng dẫn" mà app không có |
| **Total** | | **34/40** | **Good (28–35) — đỉnh dải Good** |

## Verdict về tính đặc thù của thiết kế (Design Specificity)

**Authored cho sản phẩm này, không phải category-interchangeable.** Bằng chứng: activation checklist 3 bước chỉ có ý nghĩa với luồng "mời → consent → cấp quyền xem" của AnVề; nhãn confidence Fresh/Delayed/Stale/Unknown + marker STALE xám trung tính thể hiện đúng nguyên tắc "dữ liệu cũ không phải bằng chứng nguy hiểm"; các hàng trạng thái SOS phân biệt server-ghi-nhận / provider-chấp-nhận / người-nhận-xác-nhận đúng ràng buộc PRODUCT.md; disclaimer "Khoảng cách không phải thời gian đến" và "Không tự suy diễn đến nơi từ GPS" là ngôn ngữ riêng của domain. Hệ màu teal/sand/amber với emergency tách khỏi error được áp dụng nhất quán (SOS FAB, SOS bottom bar). Điểm chưa "sở hữu" trọn vẹn: các hộp thoại xác nhận phá hủy chưa đồng bộ 100% một pattern (xem H4) — đó là chỗ sản phẩm chưa kết luận được ngôn ngữ riêng của mình.

**Deterministic scan:** not applicable (native platform — detector chỉ đọc web markup).

## Priority Issues

**[P2] Chuỗi đăng nhập hứa "hướng dẫn" mà app không có đường dẫn nào tới hướng dẫn đó**
- Where: `strings.xml:13` (signin_google_unavailable — "xem hướng dẫn cấu hình"), `strings.xml:20` (signin_auth_not_configured — "Hãy xem hướng dẫn thiết lập"); render tại `SignInScreen.kt:200-208` chỉ có nút "Thử lại".
- Why it matters: đây là màn đầu tiên; khi Google Sign-In chưa sẵn sàng, người dùng được chỉ tới tài liệu không tồn tại trong app — theo quy tắc severity, lỗi này đủ để người dùng phải tìm hỗ trợ.
- Fix: viết lại 2 chuỗi thành hướng dẫn tự chứa ("Kiểm tra kết nối tới máy chủ và thử lại; nếu vẫn lỗi, liên hệ người cài đặt AnVề") hoặc thêm một action mở URL/hướng dẫn trong app.

**[P3] Hộp thoại xác nhận phá hủy của Hành trình không tô màu error**
- Where: `JourneyScreen.kt:267-269` — confirmButton dùng `JourneyTextAction` màu ink mặc định cho cả REVOKE_RECIPIENT ("Thu hồi người xem") và END.
- Why it matters: mọi hộp thoại phá hủy khác (Xóa thành viên, Giải tán, Thu hồi phiên, Xóa lịch sử, Giảm thời hạn lưu, Bỏ thay đổi) đều dùng confirm màu `error` + Hủy trung tính; người dùng học "màu đỏ = hành động khó phục hồi" từ 8+ hộp thoại rồi gặp một ngoại lệ.
- Fix: tô `MaterialTheme.colorScheme.error` cho nhãn confirm ở nhánh REVOKE_RECIPIENT và END, giữ màu trung tính cho ARRIVE.

**[P3] Hộp thoại SOS dùng Button filled màu primary cho thao tác hủy/thu hồi**
- Where: `SosScreen.kt:472-475` (Hủy yêu cầu / Kết thúc / Xác nhận đã biết) và `SosScreen.kt:476-482` (Thu hồi quyền xem) — confirm là `Button` filled primary.
- Why it matters: cùng lớp hành động phá hủy nhưng trình bày khác với pattern destructive-confirm toàn app; nút filled nhấn mạnh hành động khó phục hồi như một CTA bình thường.
- Fix: đổi confirmButton của 2 hộp thoại này sang TextButton màu `error`, giữ "Hủy" trung tính.

**[P3] Snackbar kết quả ở tab Nhóm có thể bị bỏ qua vĩnh viễn khi một lỗi đang mở**
- Where: `CircleScreen.kt:300-306` — LaunchedEffect chỉ keyed trên `messageText` và skip khi `state.errorRes != null`; `CircleViewModel.kt:188-199` (removeMember) đặt `messageRes` mà không xóa `errorRes`.
- Why it matters: kịch bản thật — lỗi "Không tải được danh sách thành viên" đang mở, người dùng vẫn xóa được thành viên, thông báo "Đã xóa thành viên khỏi nhóm" bị skip; khi lỗi được dismiss, effect không chạy lại (key không đổi) nên kết quả một-lần này không bao giờ hiển thị và không được consume.
- Fix: cho removeMember đi qua `setBusy` (clear cả error khi thành công) hoặc key effect trên cả `messageRes` và `errorRes` để chạy lại khi lỗi mở rồi đóng.

**[P3] Thời gian sự kiện ở SOS và Địa điểm rơi về chuỗi ISO thô khi parse lỗi**
- Where: `SosScreen.kt:485` (`AnveTime.full(value, value)` — dùng ở outbox 231, events 324, detail 336-341), `PlacesScreen.kt:455` (`AnveTime.full(event.recordedAt, event.recordedAt)`).
- Why it matters: HistoryScreen.kt:373 ghi rõ nguyên tắc "Never leak the raw ISO string as marker copy"; SOS là màn có độ stakes cao nhất — hiển thị "2026-11-14T03:38:10Z" cho một người lớn tuổi là rò rỉ máy móc đúng chỗ không được phép.
- Fix: dùng fallback đọc được như `journey_time_unknown` ("chưa rõ thời điểm") hoặc `invite_expiry_unknown` giống JourneyScreen/ReceivedInvitations.

**[P3] JourneyOptionsSheet là nhóm row-switch duy nhất không phải toggleable-row**
- Where: `JourneyOptionsSheet.kt:98-107` — Switch tự mang `onCheckedChange` + contentDescription, cả hàng không toggleable.
- Why it matters: mọi switch/checkbox khác (consent, audience, places share/recipients, SOS recipients) là row-toggleable với switch con chỉ hiển thị; TalkBack/đập ngón tay ở đây chỉ nhận đúng ô switch nhỏ.
- Fix: bọc Row bằng `Modifier.toggleable(role = Role.Switch)` và đặt `onCheckedChange = null` trên Switch con.

**[P3] Chỉ Hành trình đánh dấu heading; TalkBack không có mỏ neo điều hướng tiêu đề ở SOS/Chẩn đoán/Lịch sử/Địa điểm**
- Where: có `heading()` chỉ tại `JourneyScreen.kt:222,229,298,401`; thiếu ở `SosScreen.kt:150` (SosCard title), `DiagnosticsScreen.kt:421` (DiagnosticsSection title), `HistoryScreen.kt:461` (retention title), `PlacesScreen.kt:368,392` (section titles).
- Why it matters: người dùng màn đọc qua tiêu đề để nhảy nhanh trong danh sách dài — SOS và Chẩn đoán là hai màn dài nhất app.
- Fix: thêm `Modifier.semantics { heading() }` cho các Text tiêu đề section/card ở 4 màn trên.

**[P3] Tab Bản đồ không còn pointer nào tới Vị trí/Tài khoản**
- Where: `HomeScreen.kt:420` — SharingSettingsCard bị ẩn khi `tab == HomeTab.MAP`.
- Why it matters: người dùng phổ thông đứng ở Bản đồ muốn dừng chia sẻ phải đoán rằng "Vị trí" nằm sau tab khác; với người lớn tuổi đây là bước nhớ-vượt-màn-hình duy nhất còn lại trong app.
- Fix: giữ một pointer gọn trên Bản đồ (một hàng TextButton "Vị trí & chia sẻ" dưới chip trạng thái) hoặc thêm affordance dừng chia sẻ vào card thông tin thành viên.

**[P3] Khối "Giải thích" ở Địa điểm đặt phần chia sẻ xuống dưới các toggle mà nó giải thích**
- Where: `PlacesScreen.kt:426-431, 532-551` — PlacesNotesCard nằm trước hàng nút hành động; `places_share_notice` giải thích quyền chia sẻ giờ chỉ thấy sau khi bung "Giải thích".
- Why it matters: người dùng bật/tắt "Quyền xem địa điểm" mà không thấy câu "Thành viên vẫn phải tự bật theo dõi cho bản thân" — đúng hiểu lầm Consent≠tracking mà câu này tồn tại để chặn.
- Fix: tách một dòng chia sẻ ngắn phía trên các toggle chia sẻ, giữ hai notice còn lại (monitor-mine, offline) trong PlacesNotesCard thu gọn.

**[P3] Bảng mời hiển thị ba khối giải thích tĩnh xếp chồng cùng form**
- Where: `EmailInvitationSheet.kt:92-118` — invite_email_note trong card email + invite_email_info (Icon Info) + invite_privacy_warning (Icon Warning).
- Why it matters: với người gửi lần đầu, ba đoạn caveat tĩnh luôn hiển thị cạnh form tạo tài năng nhận/ghi chú — tải nhận thức thừa trước cả khi có lỗi.
- Fix: gộp invite_email_note vào invite_email_info thành một khối, giữ riêng privacy warning (nó mang tín hiệu cảnh báo khác về mặt thị giác).

**[P3] Nhóm chip thời hạn hành trình đưa ra 5 lựa chọn ngang hàng tại một điểm quyết định**
- Where: `JourneyScreen.kt:441-447` — 30 phút/1 giờ/2 giờ/4 giờ/8 giờ.
- Why it matters: vượt ngưỡng ≤4 lựa chọn nhìn thấy cùng lúc của working memory; phần còn lại của app giữ mọi nhóm chọn trong 3-4 chip (retention 3, expiry mời 3).
- Fix: bỏ chip "30 phút" (hoặc gộp 4/8 giờ sau một chip "Nhiều hơn") để nhóm còn ≤4 như các nhóm khác.

**[P3] Tài khoản có thể xếp chồng tới bốn card thông báo dismiss được trước nội dung**
- Where: `HomeScreen.kt:615-700` — errorRes card + messageRes card + consentMessage card + avatarMessageRes card, mỗi card một nút "Đã hiểu".
- Why it matters: cùng một lô kết quả được trình bày thành 4 khối cần 4 lần dismiss dù `consumeMessage` vốn coi chúng là một batch; người dùng mới mở app sau nhiều thao tác gặp tường thông báo.
- Fix: gộp các message cùng batch vào một card (hiển thị thông báo mới nhất), chỉ giữ AnveErrorNotice riêng vì nó có retry riêng.

## Persona Red Flags

**Bà Hằng (63 tuổi, lo cho con cháu — Operate mode):**
- Điểm tốt: activation checklist đánh số 1-2-3 với nút hành động trực tiếp; nút hero "Bật chia sẻ và theo dõi" relabel theo trạng thái; SOS bottom bar pin nút gửi kèm dòng trạng thái đếm người nhận; mọi hộp thoại phá hủy có Hủy.
- Red flags: đứng ở tab Bản đồ, không có đường dẫn nhìn thấy nào tới màn dừng chia sẻ (HomeScreen.kt:420); bảng mời 3 khối caveat tĩnh trước khi tạo lời mời đầu tiên; nếu timestamp parse lỗi, SOS hiển thị chuỗi ISO thô.

**Casey (một tay, bị ngắt quãng, mạng chậm):**
- Điểm tốt: SOS gửi pin ở bottom bar; mọi nháp (PlaceEditor, JourneyForm, recipients, retention dialog) là `rememberSaveable`; offline notice + số điểm chờ đồng bộ + retry sync ở Vị trí; push deep-link có snackbar "Quay lại" khôi phục nguyên trạng trước push.
- Red flags: sửa tọa độ tay vẫn là đường chính khi GPS chậm (nút "Dùng vị trí hiện tại" có timeout 10s — chấp nhận được); chip thời hạn 5 lựa chọn cần nhìn kỹ khi vừa bắt đầu chuyến.

**Sam (phụ thuộc TalkBack):**
- Điểm tốt: mọi switch/checkbox (trừ JourneyOptionsSheet) là row-toggleable ≥48dp; AnveErrorNotice tự mang polite live region; không icon nào thiếu nhãn; trạng thái chọn mang trong semantics (selectable/toggleable), không dùng màu đơn thuần.
- Red flags: không có heading nào để điều hướng nhanh ở SOS/Chẩn đoán/Lịch sử/Địa điểm; JourneyOptionsSheet switch là target nhỏ duy nhất trong app; hộp thoại confirm màu đỏ là tín hiệu thị giác không có chất tương đương bằng chữ (cùng nhãn nhắc lại hành động, nên chấp nhận được).

## Minor Observations

- "Chưa kết nối" ở chip Bản đồ dùng chấm màu `error` (`LiveMapScreen.kt:243-247`) — đọc được là lỗi kết nối (đúng vai trò error), không phải ngụ ý nguy hiểm; chấp nhận, chỉ theo dõi.
- Chip trạng thái map là Card nền trong khi nút "Quyền xem" là OutlinedButton trên nền trong suốt — hai mức nền khác nhau trên cùng một hàng (`LiveMapScreen.kt:237-258`); chỉ là nhận xét thị giác nhẹ.
- `diag_row_checkpoint` ("Xác nhận ghi nhận cục bộ") là nhãn khó dịch nhất cho người không kỹ thuật, dù nằm trong tab Chi tiết của Chẩn đoán — nơi chấp nhận chuyên môn hóa.
- TrackingScreen disclosure dialog là một khối ~90 từ; cần một lần đọc nhưng chỉ hiện một lần — chấp nhận vì độ stakes của consent.
- `EmailInvitationSheet` tự khóa menu thời hạn bằng `expanded = showExpiry && editable` — hành vi đúng, dễ bị bỏ sót khi review; ghi lại để không bị "sửa nhầm" sau này.

## Cognitive Load Assessment

Checklist 8 mục: fail 1 — "Minimal choices" (nhóm chip thời hạn hành trình 5 lựa chọn, `JourneyScreen.kt:441-447`); phần còn lại đạt: một focus mỗi màn (JourneyCreateForm xếp blocker theo `when` một-lỗi-một-lúc, `JourneyScreen.kt:482-488`), chunking ≤4 (4 tab, checklist 3 bước, 4 card SOS), grouping bằng card/section, hierarchy hero-button rõ, progressive disclosure (DiagnosticsCard, PlacesNotesCard, options sheet, segmented Chẩn đoán), không cần nhớ qua màn (mọi tham chiếu "Mở màn X" đều kèm nút hành động trực tiếp). Kết luận: tải nhận thức thấp — vừa mức "good" theo thang guide (0-1 fail); mục fail đã nằm trong danh sách P3 ở trên.
