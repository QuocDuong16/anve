---
target: Toàn bộ UI/UX flow app AnVề
total_score: 34
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 3
target_identity: "file:/home/niand/Workspace/anve/apps/android/app/src/main/java/com/anve/app/ui"
timestamp: 2026-11-14T09-29-18Z
slug: apps-android-app-src-main-java-com-anve-app-ui
---
# Critique độc lập v6 — Toàn bộ UI/UX flow app AnVề (apps/android .../ui)

Lượt critique độc lập, chấm lại từ đầu trên code hiện tại của main. Phạm vi: AnVeApp.kt, splash, signin, home, circle (gồm invitation), map, tracking, history, places, journey, sos, diagnostics, theme, common, avatar, brand — toàn bộ ~11.6k dòng UI + strings.xml + PRODUCT.md/DESIGN.md/user-journeys.md. Không có .impeccable/critique/ignore.md nên không finding nào được bỏ qua.

## Bảng heuristic Nielsen (0–4)

| # | Heuristic | Điểm | Lý do chính |
|---|---|---|---|
| 1 | Visibility of system status | 4 | Mọi mutation đều có pending + kết quả xác nhận (snackbar/live region), freshness hiển thị mọi nơi, outbox SOS phân biệt từng trạng thái, pull-to-refresh song song nút refresh. Spinner trần (không nhãn) ở SOS là vết nhỏ nhưng tiến trình vẫn hiển thị. |
| 2 | Match system & real world | 3 | Tiếng Việt tự nhiên, copy trung thực; nhưng "máy chủ" xuất hiện trong nhãn điều khiển quan trọng nhất ("Đồng ý chia sẻ (máy chủ)"), "Circle" tiếng Anh lọt ở sos_recipients_empty, "hàng đợi" trong chi tiết. |
| 3 | User control & freedom | 4 | Hủy/đóng/Thoát ở mọi dialog-sheet-draft; discard-confirm cho Places/Journey editor; hệ thống back được giữ; retry/check-again cho trạng thái chưa rõ kết quả; không bẫy người dùng. |
| 4 | Consistency & standards | 3 | Hệ pattern chia sẻ rất chặt (AnveErrorNotice, destructive-confirm, toggleable-row, top-bar rule); lệch nhỏ: SOS spinner không theo pattern labeled-progress, "Circle" vs "Nhóm", journey_no_viewers trỏ sai màn so với các pointer cùng loại. |
| 5 | Error prevention | 4 | Validation inline (email, tọa độ, giờ bắt đầu < kết thúc), blocker-at-a-time ở Journey, confirm cho mọi hành động phá hủy, retention chỉ confirm khi giảm, chặn thu hồi phiên đang dùng, uppercase mã mời. |
| 6 | Recognition over recall | 4 | Checklist "Bước tiếp theo", hint chạm (history/map), mọi icon có nhãn hoặc decorative đúng quy tắc, bottom nav có nhãn, không hành động ẩn ngoài overflow chuẩn. |
| 7 | Flexibility & efficiency | 3 | Pull-to-refresh + nút, auto load-more, chip "Cả ngày", Dùng vị trí hiện tại, gọi nhanh 113/114/115; chưa có accelerator mạnh hơn cho người dùng quen. |
| 8 | Aesthetic & minimalist | 3 | Sạch và có thứ bậc; SharingSettingsCard thêm một tầng điều hướng phía trên nội dung tab, Vị trí là scroll dài nhiều card, note kỹ thuật SOS luôn hiển thị. |
| 9 | Recognize/diagnose/recover errors | 3 | AnveErrorNotice + retry cạnh thông báo, message theo lớp lỗi, giữ trạng thái (history giữ trang cũ); trừ điểm: poll lỗi ở Places đuổi người dùng khỏi details và gắn nhãn sai "Địa điểm không còn khả dụng". |
| 10 | Help & documentation | 3 | Không có help/docs, nhưng contextual help xuất sắc: disclosure trước khi bật theo dõi, explainer quyền nền, bullet quyền xem, caveat trung thực ở từng quyết định. |
| | **Tổng** | **34/40** | Khu vực "Good — nền tảng vững, xử lý vùng yếu còn lại". |

## Design-specificity verdict

Rất cao. UI được dựng quanh chính các sự thật sản phẩm của AnVề, không phải template chung: freshness ≠ an toàn (marker stale xám trung tính), consent ≠ ghi vị trí (hai điều khiển + copy phân biệt), emergency ≠ error (amber SOS riêng), push accepted ≠ đã đọc, "không suy diễn tai nạn" xuất hiện đúng nơi người dùng lo nhất (journey stale notice). Copy vẽ ranh giới dữ liệu chính xác tới mức từng câu có rationale trong code. Không có dấu hiệu nói dối hoặc hứa hẹn vượt phạm vi đã triển khai.

## Priority issues

Không còn P1. 3 P2, 7 P3.

1. **P2 — JourneyScreen.kt:479 + strings.xml:610 (`journey_no_viewers`)**: khi chưa có người theo dõi, form Hành trình bảo "Mở màn Vị trí để chọn người xem trước" — nhưng quyền xem vị trí chỉ được cấp ở màn Bản đồ → "Quyền xem" (chính circle_activation_audience_body xác nhận điều này; TrackingScreen không có điều khiển cấp quyền). Người dùng làm đúng chỉ dẫn vẫn bị kẹt ở bước chặn duy nhất của form, mà notice này lại không có action. Fix: sửa chuỗi trỏ tới "màn Bản đồ → Quyền xem" và thêm action mở Bản đồ vào notice (như journey_requires_capture đã có "Mở màn Vị trí").
2. **P2 — PlacesViewModel.kt:87-91 + PlacesScreen.kt:139-146,354-361**: vòng poll 10s gọi refresh(); nhánh lỗi xóa selectedId → một lỗi mạng thoáng qua đang khi user đang cấu hình địa điểm sẽ đuổi họ khỏi details, reset draft người nhận (rememberSaveable keyed theo selected?.id), và hiển thị "Địa điểm không còn khả dụng" cho địa điểm vẫn còn nguyên. Fix: khi refresh thất bại, giữ place đang chọn ở trạng thái cũ (đánh dấu stale, như History giữ trang đã tải) và chỉ báo "không còn khả dụng" khi danh sách mới tải thành công mà thiếu nó.
3. **P2 — HomeScreen.kt:306-315 + JourneyScreen.kt:175-186**: overlay Hành trình có Scaffold riêng không FAB, nên FAB SOS luôn-hiển thị biến mất đúng trong kịch bản rủi ro nhất của app (đang đi về). Muốn SOS phải back ra shell trước. Fix: thêm cùng ExtendedFloatingActionButton emergency (hoặc affordance SOS gọn) vào Scaffold của JourneyScreen, đối chiếu với shell.

## Minor observations (P3)

4. **SosScreen.kt:209** — spinner trần không nhãn cho loading/pending, lệch pattern labeled-progress mà HistoryScreen/PlacesScreen đã chuẩn hóa; TalkBack không đọc được gì. Fix: ghép spinner với "Đang tải…"/"Đang xử lý…".
5. **LiveMapScreen.kt:244-252** — chấm trạng thái "Chưa kết nối" dùng colorScheme.error (đỏ báo động) cho trạng thái kết nối/freshness; DESIGN.md quy về nguyên tắc "dữ liệu cũ không được đọc như nguy hiểm" (marker stale xám trung tính). Fix: dùng tông trung tính/attention cho chấm, giữ chữ làm tín hiệu chính.
6. **LiveMapScreen.kt:410-419** — AudienceSheet ở trạng thái chưa có nhóm/chưa bật consent chỉ nêu tên màn ("màn Vị trí") mà không có action; pointer "Vị trí & chia sẻ" của tab Bản đồ nằm dưới sheet. Fix: truyền onOpenLocation (và hop Nhóm cho no-circle) vào notice trong sheet, như ConsentNotice của PlacesScreen.
7. **PlacesScreen.kt:631,789-799,812** — PlaceEditor khóa Save im lặng khi Tên trống/quá dài, trong khi tọa độ sai đã có inline error; cùng dạng điều kiện chặn nhưng thiếu cùng kiểu giải thích. Fix: thêm dòng bodySmall lỗi cho trường tên (đối chiếu places_editor_invalid_coords).
8. **strings.xml:400 (sos_recipients_empty, SosScreen.kt:225)** — "tải thành viên Circle" để tiếng Anh "Circle" trong copy tiếng Việt, trong khi toàn app dùng "nhóm". Fix: đổi thành "thành viên nhóm".
9. **SignInScreen.kt:96-102** — người dùng chủ động đóng hộp thoại chọn tài khoản Google vẫn nhận card lỗi "Chưa đăng nhập. Google chưa hoàn tất xác nhận tài khoản…"; GetCredentialCancellationException phổ biến nhất là cancel có chủ đích. Fix: coi cancel là no-op im lặng (hoặc hint trung tính), giữ card lỗi cho thất bại thật.
10. **HomeScreen.kt:422-427** — card Cài đặt chia sẻ bị ẩn trên tab Bản đồ nên Tài khoản không có đường đi từ tab đó; trên Nhóm/Lịch sử/Địa điểm card này lại thêm một tầng điều hướng phía trên nội dung tab. Fix: cấp cho tab Bản đồ một pointer gọn tới Tài khoản (hoặc để LiveMapScreen tự mang), giữ card ở các tab khác.

## Persona red flags

- **Bà Lan, 62 tuổi, con gái ở xa**: flow xem con về nhà (Bản đồ → chip tên → "Vị trí mới" + giờ cập nhật) và SOS (nút gọi 113/114/115 cạnh disclaimer, bottom bar ghim, confirm đếm người) đều tốt. Vết đỏ: chuỗi "Đồng ý chia sẻ (máy chủ)" và nhất là journey_no_viewers trỏ sai màn — chính nhóm người này là người làm theo chỉ dẫn từng chữ.
- **Minh, bận rộn, dùng một tay**: Lịch sử (chevron ngày, chip giờ, Cả ngày, auto load-more) và Places editor (chạm bản đồ, dùng vị trí hiện tại, hint từng câu) tốt. Vết đỏ: bị đuổi khỏi details địa điểm bởi poll lỗi mạng giữa lúc cấu hình; FAB SOS mất tích trong overlay Hành trình.
- **Người dùng TalkBack**: toggleable-row gộp (consent, quyền xem, SOS, places), heading navigation, liveRegion polite, stateDescription cho disclosure — mẫu mực. Vết đỏ: spinner SOS trần không nhãn là điểm im lặng duy nhất trên một màn hình khẩn cấp.

## Kết luận

34/40. Không còn lỗi chặn; ba P2 còn lại đều là "chỉ dẫn/khôi phục sai ngữ cảnh" chứ không phải thiếu tính năng: trỏ sai màn ở bước chặn của Hành trình, mất ngữ cảnh khi poll lỗi ở Địa điểm, và SOS mất đi trong overlay Hành trình. Bảy P3 là polish nhất quán (jargon, hue, spinner, dead-end không giải thích). Đây vẫn là nền UI hiếm thấy về trung thực dữ liệu và kỷ luật pattern; các lỗi còn lại nhỏ, cụ thể và có fix 1-3 câu.
