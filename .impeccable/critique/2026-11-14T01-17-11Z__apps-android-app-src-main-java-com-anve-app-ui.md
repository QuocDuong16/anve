---
target: toàn bộ UI/UX flow Android (apps/android .../ui) — lần 3 sau chu trình fix
total_score: 34
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 0
target_identity: "file:/home/niand/Workspace/anve/apps/android/app/src/main/java/com/anve/app/ui"
timestamp: 2026-11-14T01-17-11Z
slug: apps-android-app-src-main-java-com-anve-app-ui
---
# Critique UI/UX lần 3 — AnVề Android (sau chu trình fix)

**Method: design review only (detector not applicable, native platform)**

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 3 | Đổi retention History im lặng ngoài trạng thái chip |
| 2 | Match System / Real World | 3 | Places editor còn nhập vĩ độ/kinh độ tay (giới hạn scope) |
| 3 | User Control and Freedom | 4 | Hủy/Để sau/Ẩn/Kiểm tra lại/Thu hồi + snackbar action |
| 4 | Consistency and Standards | 3 | 2 treatment top bar chưa có rule ghi rõ; còn lại đã thống nhất |
| 5 | Error Prevention | 4 | Confirm đầy đủ; SOS bottom bar chặn gửi rỗng |
| 6 | Recognition Rather Than Recall | 4 | "tab Vị trí" hết; checklist dẫn đường; không chip giả |
| 7 | Flexibility and Efficiency | 3 | Pull-to-refresh + scroll-to; chưa có accelerator khác |
| 8 | Aesthetic and Minimalist Design | 3 | Hero hierarchy rõ; History/Diagnostics còn dày |
| 9 | Error Recovery | 4 | Unknown ladder + AnveErrorNotice(retry) + SOS snackbar→outbox |
| 10 | Help and Documentation | 3 | Không help surface; inline guidance + checklist là help chính |
| **Total** | | **34/40** | **Good (85%)** |

Cognitive load: 1/8 failure (chunking History control card).

## Design Specificity Verdict

Copy authored; visual bắt đầu authored (hệ emergency amber tách error là quyết định brand). Khoảnh khắc "Đã đến nơi" chưa được thiết kế. Deterministic scan/overlays: not applicable (native).

## Priority Issues (còn lại — P3)

1. [P3] History retention change thiếu xác nhận (chip state không đủ) → snackbar như Places save. → $impeccable polish
2. [P3] Push deep-link ép điều hướng không có đường quay lại → lưu previous destination hoặc banner suggestion. → $impeccable polish
3. [P3] Places editor nhập tọa độ tay → cần quyết định sản phẩm (current-location button hoặc chấp nhận). → $impeccable shape
4. [P3] Thiếu rule tài liệu hóa cho 2 treatment top bar. → $impeccable document

## Persona Red Flags

- Jordan: chỉ còn tọa độ tay (P3 #3).
- Casey: xoay máy đã sống sót; còn push interrupt.
- Sam: không còn red flag phát hiện từ source (toggleable rows, dual encoding màu+text, headings, 48dp).

## Minor Observations

- ConsentResult passthrough — ngoại lệ có chủ đích, đã ghi memory.
- DiagnosticsScreen dày nhất app — ứng viên $impeccable quieter.
- Icons.Default.Sos — kiểm tra nguồn dependency và pin.

## Điểm đã sửa trong đợt này (không còn là findings)

SOS pinned CTA + snackbar + scroll-to + Surface chips + toggleable rows; TrackingControlCard một điều khiển; Journey single-blocker; AnveErrorNotice dùng chung 5+ màn; rememberSaveable + Savers + reset-guard; M3 pickers; vi-VN dates; SurfaceInk helper; emergency amber tách error; STALE marker trung tính; checklist onboarding Nhóm; copy single-source @StringRes.
