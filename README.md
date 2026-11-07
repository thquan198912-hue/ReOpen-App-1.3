# ReOpen App

Ứng dụng Android giám sát nhiều app cùng lúc. Khi một app bị thoát hoặc văng, app tự mở lại đúng app đó, chờ tải xong, rồi chạy macro (chạm/vuốt) riêng của app đó.

## Cách hoạt động
- Dịch vụ trợ năng (AccessibilityService) đọc danh sách cửa sổ đang hiển thị để biết app nào còn trên màn hình (hỗ trợ chia đôi màn hình, cửa sổ nổi).
- App nào vắng mặt quá N giây → mở lại bằng launch intent, chờ tải, rồi chạy macro của chính app đó.
- Nhiều app cùng lỗi: các macro chạy lần lượt, không chồng nhau.
- Toạ độ lưu theo % màn hình nên không phụ thuộc độ phân giải.

## Cài đặt trên điện thoại
1. Cài APK (cho phép cài từ nguồn ngoài).
2. Mở app → **Bật dịch vụ trợ năng** → bật *ReOpen App*.
   - Android 13+ nếu nút bị mờ: **Mở thông tin ứng dụng** → ⋮ → *Cho phép cài đặt bị hạn chế*, rồi bật lại.
   - Sau khi cập nhật bản mới: tắt rồi bật lại dịch vụ.
3. **＋ Thêm app** cho từng app cần giám sát.
4. Ghi macro cho từng app bằng nút nổi (xem dưới), bấm **Thử** để kiểm tra.
5. Bật công tắc **Bật giám sát** và bấm **Tắt tối ưu pin**.

## Nút nổi
- `≡` kéo để di chuyển, chạm để thu gọn thành chấm nhỏ
- `🟢/⚪` bật / tắt giám sát
- `[tên app]` chạm để đổi app đang chọn
- `⏺` ghi macro cho app đang chọn, `⏹` kết thúc và lưu (ghi mới thay macro cũ của app đó)
- `▶` chạy macro của app đang chọn ngay trên màn hình hiện tại (đang chạy thì thành `🛑`, bấm để dừng)
- `✕` thoát giám sát: bấm 2 lần trong 3 giây → tắt giám sát, dừng macro, ẩn thanh nổi (hiện lại bằng nút "Hiện / ẩn nút nổi" trong app)

Khi ghi, thao tác của bạn vẫn được chuyển vào app như bình thường và được lưu cả thời gian chờ giữa các bước.

## Số lần chạy macro
Bấm **Macro** của từng app để đặt *Số lần chạy macro* (1–9999) và *Nghỉ giữa các lần chạy (giây)*. Mặc định chạy 1 lần.

## Nhận diện app nhân bản (clone)
App nhận ra một app đang hiển thị bằng 3 tín hiệu, chỉ cần một trong ba:
1. app có cửa sổ đọc được trong danh sách cửa sổ;
2. app là cửa sổ vừa hiện lên gần nhất (bỏ qua hộp thoại cấp quyền, thanh hệ thống, bàn phím);
3. hệ thống báo app đang hiển thị — cần cấp quyền **Truy cập sử dụng** cho ReOpen App (mục 5 trong app). **Nên bật khi dùng app nhân bản**, vì game thường có cửa sổ không đọc được.

**Mở lại game (Android 10+)**: hệ thống chặn app nền tự mở app khác. Hãy cấp **Hiển thị trên ứng dụng khác** cho ReOpen App (mục 6 trong app), nếu không lệnh mở lại có thể bị bỏ qua (màn hình vẫn ở launcher).

Mỗi lúc chỉ xử lý một app: mở lại → xác nhận đã thấy → chờ tải → chạy macro, rồi mới tới app khác. Nếu sau 10 giây chưa thấy app, app thử mở lại bằng cách dự phòng (tối đa 3 lần).

## Chẩn đoán
Mở game, vào ReOpen App, bấm **Chẩn đoán cửa sổ**: nhật ký sẽ liệt kê các cửa sổ đang có, tín hiệu hệ thống và app nào được nhận ra. Bấm **Sao chép nhật ký** để gửi lại.

## Chống vòng lặp
- Mở lại 10 lần liên tiếp mà không nhận ra app trên màn hình → tự tắt giám sát app đó (kèm lý do trong nhật ký).
- App văng thật liên tục (10 lần trong 30 phút) → tự tắt giám sát app đó.
- Nếu game không mở lên được (đang ở màn hình chính) thì bỏ qua macro, không chạm nhầm vào icon khác.
- Bộ đếm thời gian dùng đồng hồ đơn điệu nên không lệch khi giờ máy bị chỉnh.

## Định dạng macro (có thể sửa tay trong nút "Macro")
```
tap,x,y,chờ_ms,giữ_ms
swipe,x1,y1,x2,y2,chờ_ms,thời_gian_vuốt_ms
```
x, y là % màn hình. Dạng cũ `x,y,chờ_ms` vẫn dùng được.

## Build APK
Đẩy code lên GitHub nhánh `main`. Workflow `.github/workflows/build.yml` tự build và đưa `app-debug.apk` vào:
- **Releases → Bản build mới nhất** (tải thẳng trên điện thoại)
- hoặc tab **Actions → artifact `ReOpenApp-apk`**

## Giới hạn
- Không phân biệt được app *văng* hay người dùng *chủ động thoát*: khi bật giám sát, rời app quá N giây là bị mở lại. Tắt công tắc khi muốn dùng máy bình thường.
- Khi mở lại một app trong lúc app khác đang chia đôi màn hình, tuỳ hãng máy mà bố cục có thể bị đổi; macro ghi theo vị trí trên màn hình nên cần giữ cùng bố cục lúc ghi và lúc chạy.
- Một số app có cửa sổ không đọc được tên; khi đó app dùng sự kiện chuyển cửa sổ nên chỉ nhận biết chính xác với app đang ở trên cùng.
- App có anti-cheat có thể phát hiện dịch vụ trợ năng/auto-click. Hãy đọc điều khoản và thử trên tài khoản phụ.
