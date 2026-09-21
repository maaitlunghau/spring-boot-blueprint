# API Reference — spring-boot-blueprint

Tài liệu tổng hợp toàn bộ HTTP API của backend, viết để client Flutter (`~/Documents/SelfStudy/flutter`, app `userhub`) biết cách gọi.

**Nguồn sự thật:** file này nằm trong repo backend. Nếu API đổi, sửa ở đây trước rồi copy sang `~/Documents/SelfStudy/flutter/docs/reference/backend-api.md`.

Đối chiếu với code tại commit `a873614` (branch `feature/user-management`).

---

## 1. Thông tin kết nối

| Môi trường | Base URL |
|---|---|
| Máy host (macOS, curl/Postman) | `http://localhost:8081` |
| **Android emulator** | `http://10.0.2.2:8081` ← emulator không thấy `localhost` của máy host |
| iOS simulator | `http://localhost:8081` |
| Thiết bị thật (cùng WiFi) | `http://<IP-LAN-của-máy>:8081` |
| Flutter web | `http://localhost:8081` — **nhưng xem mục 8, CORS chưa bật** |

Swagger UI: `http://localhost:8081/swagger-ui/index.html` · OpenAPI JSON: `http://localhost:8081/v3/api-docs`

### Backend chưa có authentication

Hiện `SecurityConfig` để `.anyRequest().permitAll()` — **mọi endpoint gọi được mà không cần token**. Đây là trạng thái tạm thời để tiện test, sẽ bị revert về `.authenticated()` trước khi merge vào `main`.

Hệ quả cho Flutter:

- Bây giờ cứ gọi thẳng, không cần header `Authorization`.
- **Không có endpoint `/api/auth/login` hay `/api/auth/register`.** Module `auth` mới chỉ là package rỗng. `login_screen.dart` / `auth_state.dart` trong `userhub` vẫn phải dùng fake cho tới khi module Auth được build.
- Khi Auth xong thì toàn bộ request sẽ cần `Authorization: Bearer <token>` — thiết kế tầng data (mục 7) nên chừa sẵn chỗ gắn header, đừng hardcode kiểu "không có auth".

---

## 2. Response envelope

**Mọi** response (kể cả lỗi) đều bọc trong cùng một envelope:

```json
{
  "status": 200,
  "message": "Success",
  "data": { },
  "timestamp": "2026-09-22T07:15:30.123456Z"
}
```

| Field | Kiểu | Ghi chú |
|---|---|---|
| `status` | int | Trùng với HTTP status code |
| `message` | String | Mô tả kết quả, hoặc thông điệp lỗi |
| `data` | T \| null | `null` với endpoint void và với hầu hết lỗi |
| `timestamp` | String | ISO-8601 UTC, luôn có hậu tố `Z` → `DateTime.parse()` ra đúng UTC |

Không có endpoint nào trả `204 No Content` hay trả DTO trần — xoá cũng trả `200` + envelope. Nghĩa là Dart chỉ cần **một** hàm parse envelope duy nhất.

### Envelope phân trang

Endpoint danh sách có `data` là `PageResponse`:

```json
{
  "status": 200,
  "message": "Success",
  "data": {
    "content": [ { "id": "...", "fullName": "..." } ],
    "page": 0,
    "size": 20,
    "totalElements": 42,
    "totalPages": 3,
    "last": false
  },
  "timestamp": "2026-09-22T07:15:30.123456Z"
}
```

`page` đếm từ **0**.

---

## 3. Model `UserResponse`

Đây là `data` của mọi endpoint trả về user.

```json
{
  "id": "0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b",
  "fullName": "Nguyen Van A",
  "email": "a@example.com",
  "role": "USER",
  "enabled": true,
  "imageUrl": "https://res.cloudinary.com/.../avatar.png",
  "bannedReason": null,
  "bannedUntil": null,
  "deletedAt": null,
  "emailVerified": false,
  "emailVerifiedAt": null,
  "createdAt": "2026-09-20T03:11:00.000000Z"
}
```

| Field | Kiểu Dart | Ghi chú |
|---|---|---|
| `id` | `String` | UUID v7 dạng chuỗi — **đừng parse thành int** |
| `fullName` | `String` | |
| `email` | `String` | luôn lowercase, đã trim ở backend |
| `role` | `String` | `"ADMIN"` hoặc `"USER"` |
| `enabled` | `bool` | **`false` = đang bị ban.** Không có field `banned` riêng |
| `imageUrl` | `String` | luôn có giá trị — user mới nhận avatar mặc định |
| `bannedReason` | `String?` | chỉ có khi đang bị ban |
| `bannedUntil` | `DateTime?` | `null` khi ban vĩnh viễn **hoặc** khi không bị ban → phải xét chung với `enabled` |
| `deletedAt` | `DateTime?` | khác `null` = đã soft-delete |
| `emailVerified` | `bool` | độc lập hoàn toàn với `enabled` |
| `emailVerifiedAt` | `DateTime?` | |
| `createdAt` | `DateTime` | |

Các field thời gian đều là ISO-8601 UTC có `Z`. `passwordHash` và `imagePublicId` **không bao giờ** lộ ra response.

Cách đọc trạng thái user cho đúng:

| Trạng thái | Điều kiện |
|---|---|
| Bình thường | `enabled == true && deletedAt == null` |
| Bị ban vĩnh viễn | `enabled == false && bannedUntil == null` |
| Bị ban tạm thời | `enabled == false && bannedUntil != null` |
| Đã xoá mềm | `deletedAt != null` (chỉ thấy qua `GET /api/users/deleted`) |

---

## 4. Danh sách endpoint

Tất cả đều có prefix `/api/users`. Cột cuối là rate limit (xem mục 6).

| # | Method | Path | Công dụng | Limit |
|---|---|---|---|---|
| 1 | `GET` | `/api/users` | Danh sách + lọc + phân trang | 100/phút |
| 2 | `GET` | `/api/users/deleted` | Danh sách user đã xoá mềm | 100/phút |
| 3 | `GET` | `/api/users/{id}` | Chi tiết 1 user | 100/phút |
| 4 | `POST` | `/api/users` | Tạo user (kèm gửi OTP) | 10/phút |
| 5 | `PATCH` | `/api/users/{id}/profile` | Đổi `fullName` | 100/phút |
| 6 | `PATCH` | `/api/users/{id}/role` | Đổi role | 100/phút |
| 7 | `PATCH` | `/api/users/{id}/ban` | Ban user | 10/phút |
| 8 | `PATCH` | `/api/users/{id}/unban` | Gỡ ban | 10/phút |
| 9 | `PATCH` | `/api/users/{id}/restore` | Khôi phục user đã xoá mềm | 10/phút |
| 10 | `POST` | `/api/users/{id}/verify-email` | Xác thực OTP | 10/phút |
| 11 | `POST` | `/api/users/{id}/resend-verification-otp` | Gửi lại OTP | 5/phút |
| 12 | `POST` | `/api/users/{id}/avatar` | Upload avatar (multipart) | 3/phút |
| 13 | `DELETE` | `/api/users/{id}` | Xoá mềm | 10/phút |
| 14 | `DELETE` | `/api/users/{id}/purge` | Xoá vĩnh viễn | 10/phút |

---

### 1 & 2. Danh sách user

```
GET /api/users
GET /api/users/deleted
```

Hai endpoint dùng **chung bộ query param**. `/api/users` chỉ trả user chưa xoá; `/deleted` chỉ trả user đã xoá mềm.

**Phân trang & sắp xếp** (Spring `Pageable` bind tự động):

| Param | Mặc định | Ví dụ |
|---|---|---|
| `page` | `0` | `page=2` |
| `size` | `20` | `size=10` |
| `sort` | không sắp xếp | `sort=createdAt,desc` · `sort=fullName,asc` |

`sort` nhận **tên field của entity** (`createdAt`, `fullName`, `email`, `role`, `bannedAt`...), không phải tên cột DB.

**Bộ lọc:**

| Param | Kiểu | Ghi chú |
|---|---|---|
| `keyword` | String | khớp `fullName` **hoặc** `email`, không phân biệt hoa thường |
| `role` | `ADMIN` \| `USER` | sai giá trị → `400` |
| `banned` | bool | `true` = đang bị ban (`enabled=false`) |
| `emailVerified` | bool | |
| `createdFrom` / `createdTo` | ISO-8601 | khoảng thời gian tạo |
| `bannedAtFrom` / `bannedAtTo` | ISO-8601 | |
| `bannedUntilFrom` / `bannedUntilTo` | ISO-8601 | |

Bỏ trống param nào thì filter đó không áp dụng. Mọi filter kết hợp bằng **AND**.

```bash
curl "http://localhost:8081/api/users?keyword=nguyen&role=USER&banned=false&page=0&size=10&sort=createdAt,desc"
```

Ngày giờ phải encode đúng — `+` trong ISO-8601 sẽ bị hiểu là dấu cách. Dùng dạng `Z`:

```
?createdFrom=2026-09-01T00:00:00Z&createdTo=2026-09-30T23:59:59Z
```

→ `200` + `PageResponse<UserResponse>`

---

### 3. Chi tiết user

```
GET /api/users/{id}
```

→ `200` + `UserResponse` · `404` nếu không tồn tại **hoặc đã bị xoá mềm**.

---

### 4. Tạo user

```
POST /api/users
Content-Type: application/json
```

```json
{
  "fullName": "Nguyen Van A",
  "email": "a@example.com",
  "password": "matkhau123",
  "role": "USER"
}
```

| Field | Ràng buộc |
|---|---|
| `fullName` | bắt buộc, không rỗng |
| `email` | bắt buộc, đúng định dạng email |
| `password` | bắt buộc, **tối thiểu 8 ký tự** |
| `role` | bắt buộc, `"ADMIN"` hoặc `"USER"` |

→ `201` + `UserResponse`, message `"User created successfully"`

Kèm theo: backend sinh OTP 6 số và **gửi email thật** tới địa chỉ đó (xem mục 5).

Lỗi:

| Code | Khi nào |
|---|---|
| `400` | validation fail (xem mục 6 — `data` chứa map lỗi theo field) |
| `400` | `role` không phải `ADMIN`/`USER` → message `"Invalid request"` |
| `409` | email đã tồn tại → `"Duplicate resource: User with identifier: ..."` |
| `409` | email thuộc user đang bị xoá mềm → `"This email cannot be used for registration at this time..."` |

⚠️ Endpoint này cho client tự chọn `role` nên **về mặt nghiệp vụ là admin-only** (chưa có gì enforce). Đừng dùng nguyên DTO này cho màn hình tự đăng ký công khai — sẽ thành lỗ hổng leo thang đặc quyền.

---

### 5. Đổi profile

```
PATCH /api/users/{id}/profile
```

```json
{ "fullName": "Tên mới" }
```

Chỉ đổi được `fullName`. **Không đổi được email qua endpoint này** (cần luồng verify riêng, chưa build).

→ `200` + `UserResponse` · `400` validation · `404` không thấy

---

### 6. Đổi role

```
PATCH /api/users/{id}/role
```

```json
{ "role": "ADMIN" }
```

→ `200` + `UserResponse` · `400` role sai · `404` không thấy

---

### 7. Ban user

```
PATCH /api/users/{id}/ban
```

```json
{
  "reason": "Vi phạm điều khoản",
  "bannedUntil": "2026-12-31T23:59:59Z"
}
```

| Field | Ràng buộc |
|---|---|
| `reason` | bắt buộc |
| `bannedUntil` | tuỳ chọn, **phải ở tương lai**. Bỏ trống = ban vĩnh viễn |

→ `200` + `UserResponse` (`enabled` thành `false`)

| Code | Khi nào |
|---|---|
| `400` | `bannedUntil` ở quá khứ |
| `400` | target có role `ADMIN` → `"Cannot ban a user with ADMIN role"` |
| `404` | không thấy user |
| `409` | user đã bị ban sẵn |

Ban tạm thời sẽ được scheduler tự gỡ sau khi hết hạn (chạy mỗi 5 phút) — client không cần làm gì.

---

### 8. Gỡ ban

```
PATCH /api/users/{id}/unban
```

Không có body.

→ `200` + `UserResponse` · `404` không thấy · `409` user không hề bị ban

---

### 9. Khôi phục user đã xoá mềm

```
PATCH /api/users/{id}/restore
```

Không có body. → `200` + `UserResponse`

`409` nếu user **không** ở trạng thái xoá mềm — gồm cả trường hợp id không tồn tại. Đây là chủ ý (không phân biệt để tránh lộ thông tin), nên **đừng** hiển thị "user không tồn tại" cho lỗi này.

---

### 10. Xác thực email bằng OTP

```
POST /api/users/{id}/verify-email
```

```json
{ "otp": "123456" }
```

`otp` bắt buộc, **đúng 6 chữ số** (regex `\d{6}`).

→ `200` + `UserResponse` (`emailVerified` thành `true`)

| Code | Khi nào |
|---|---|
| `400` | OTP sai định dạng (validation) |
| `400` | `"Invalid or expired verification code"` |
| `404` | không thấy user |
| `409` | email đã verify rồi |

**Chỉ có đúng một thông điệp `400` cho mọi kiểu sai** — sai mã, hết hạn, đã dùng, hay hết lượt thử đều trả y hệt nhau. Đây là chủ ý chống dò mã, nên UI đừng cố đoán nguyên nhân cụ thể.

Luật cần biết khi làm UI:

- OTP sống **10 phút**.
- Sai **5 lần** là mã đó chết hẳn, dù sau đó nhập đúng cũng vô dụng → phải bấm gửi lại.
- Gửi lại sẽ vô hiệu hoá mã cũ ngay lập tức.

---

### 11. Gửi lại OTP

```
POST /api/users/{id}/resend-verification-otp
```

Không có body. → `200`, `data` là `null`, message `"Verification code resent"`

| Code | Khi nào |
|---|---|
| `404` | không thấy user |
| `409` | email đã verify rồi |
| `429` | còn trong cooldown → `"Please wait N seconds before requesting another code"` |

**Cooldown 60 giây mỗi user**, tách biệt với rate limit theo IP. Hai cái đều trả `429` nên không phân biệt được bằng status code — dựa vào `message` để hiện đúng thông báo. UI nên tự đếm ngược 60s sau khi gửi thành công thay vì để user bấm rồi ăn lỗi.

---

### 12. Upload avatar

```
POST /api/users/{id}/avatar
Content-Type: multipart/form-data
```

Một field duy nhất tên **`file`**.

| Ràng buộc | Giá trị |
|---|---|
| Định dạng | `image/jpeg`, `image/png`, `image/webp` |
| Dung lượng | ≤ 5MB |

→ `200` + `UserResponse` với `imageUrl` mới

| Code | Khi nào |
|---|---|
| `400` | thiếu file / sai định dạng / quá 5MB |
| `404` | không thấy user |
| `502` | Cloudinary lỗi |

Ảnh được backend chuẩn hoá về vuông 512×512, crop theo khuôn mặt. Client cứ gửi ảnh gốc, không cần tự resize.

⚠️ Hai điểm cần lưu ý:

- Rate limit chỉ **3 lần/phút** — chặt nhất trong tất cả endpoint. Dễ dính khi test.
- File vượt quá giới hạn multipart của servlet sẽ ra **`500`** chứ không phải `400` (chưa có handler riêng cho `MaxUploadSizeExceededException`). Nên **kiểm tra dung lượng ở phía Flutter trước khi gửi** để tránh lỗi khó hiểu.

---

### 13. Xoá mềm

```
DELETE /api/users/{id}
```

→ `200`, `data` là `null`, message `"User deleted successfully"`

| Code | Khi nào |
|---|---|
| `400` | target có role `ADMIN` |
| `404` | không thấy user |

Không xoá thật — chỉ set `deletedAt`. Sau đó user biến mất khỏi mọi endpoint thường, chỉ còn thấy qua `GET /api/users/deleted`. Email vẫn bị **khoá**, không đăng ký lại được cho tới khi purge.

Scheduler sẽ tự purge sau **30 ngày**.

---

### 14. Xoá vĩnh viễn

```
DELETE /api/users/{id}/purge
```

→ `200`, `data` là `null`, message `"User permanently deleted"`

`409` nếu user chưa ở trạng thái xoá mềm (gồm cả id không tồn tại). Phải xoá mềm trước rồi mới purge được.

**Không thể hoàn tác** — xoá hẳn khỏi DB kèm ảnh trên Cloudinary. Nên có bước xác nhận ở UI.

---

## 5. Email & OTP khi dev

`POST /api/users` gửi email thật qua tài khoản SMTP cấu hình trong `.env` — không có mail-catcher local. Khi test bằng email giả sẽ không nhận được mã.

Đường đi của email là **bất đồng bộ**: outbox → RabbitMQ → listener → SMTP. Scheduler quét outbox mỗi 5 giây, nên email tới **sau** response `201` vài giây. Đừng thiết kế UI kiểu "tạo xong là có mã ngay".

Cách lấy OTP nhanh khi dev mà không cần mở mail: đọc thẳng DB.

```sql
SELECT otp_hash, expires_at, attempt_count FROM email_verification_tokens
ORDER BY created_at DESC LIMIT 1;
```

Nhưng `otp_hash` đã BCrypt nên **không đọc ngược ra mã được** — vẫn phải mở email thật. Đây là điểm ma sát đã biết khi test luồng verify.

---

## 6. Xử lý lỗi

### Lỗi thường

`data` là `null`, `message` chứa nội dung lỗi:

```json
{
  "status": 404,
  "message": "Resource not found: User with identifier: 0199a1b2-...",
  "data": null,
  "timestamp": "2026-09-22T07:15:30.123456Z"
}
```

### Lỗi validation — shape khác

Đây là **trường hợp duy nhất** `data` có nội dung khi lỗi. `data` là map `field → message`:

```json
{
  "status": 400,
  "message": "Validation failed",
  "data": {
    "email": "Email format should be valid",
    "password": "Password must be at least 8 characters long"
  },
  "timestamp": "2026-09-22T07:15:30.123456Z"
}
```

Rất hợp để map thẳng vào `errorText` của từng `TextFormField`. Phân biệt bằng `message == "Validation failed"`, hoặc kiểm tra `data` có phải `Map` không.

### Bảng status code

| Code | Ý nghĩa | Gặp ở đâu |
|---|---|---|
| `200` | OK | |
| `201` | Đã tạo | chỉ `POST /api/users` |
| `400` | Sai dữ liệu / vi phạm nghiệp vụ | validation, OTP sai, ban ADMIN, file sai |
| `404` | Không tìm thấy | mọi endpoint có `{id}` |
| `409` | Xung đột trạng thái | trùng email, đã ban, chưa ban, đã verify, chưa xoá mềm |
| `429` | Quá nhiều request | rate limit hoặc cooldown OTP |
| `500` | Lỗi không lường trước | message luôn là `"Internal server error"` |
| `502` | Lỗi dịch vụ ngoài | upload Cloudinary thất bại |

### Rate limiting

Giới hạn tính theo **IP**, chưa theo user. Trên emulator/simulator mọi request chung một IP nên **dùng chung một bucket**.

Response `429` có thêm header **`Retry-After`** (số giây):

```json
{ "status": 429, "message": "Too many requests...", "data": null, "timestamp": "..." }
```

Thuật toán là token bucket — token hồi liên tục chứ không reset theo mốc phút, nên chờ vài giây là có lại một phần lượt.

Bảng limit ở mục 4. Chặt nhất: avatar **3/phút**, resend OTP **5/phút**.

---

## 7. Gợi ý tầng data cho Flutter

`userhub` hiện chưa có HTTP client nào trong `pubspec.yaml`. Thêm `http` (đủ dùng và gần với chuẩn) hoặc `dio` (nhiều tiện ích hơn: interceptor, upload progress):

```yaml
dependencies:
  http: ^1.2.0
```

### Base URL theo platform

```dart
import 'dart:io' show Platform;
import 'package:flutter/foundation.dart' show kIsWeb;

String get apiBaseUrl {
  if (kIsWeb) return 'http://localhost:8081';
  if (Platform.isAndroid) return 'http://10.0.2.2:8081'; // emulator ≠ localhost
  return 'http://localhost:8081';
}
```

Chạy trên **máy thật** thì phải thay bằng IP LAN của máy chủ (`ipconfig getifaddr en0`), và máy điện thoại phải cùng WiFi.

### Parse envelope một lần dùng chung

```dart
class ApiResponse<T> {
  final int status;
  final String message;
  final T? data;
  final DateTime timestamp;

  ApiResponse({
    required this.status,
    required this.message,
    required this.data,
    required this.timestamp,
  });

  /// [fromData] chuyển phần `data` thô thành T. Truyền null cho endpoint void.
  factory ApiResponse.fromJson(
    Map<String, dynamic> json,
    T Function(Object json)? fromData,
  ) {
    final raw = json['data'];
    return ApiResponse(
      status: json['status'] as int,
      message: json['message'] as String,
      data: (raw == null || fromData == null) ? null : fromData(raw),
      timestamp: DateTime.parse(json['timestamp'] as String),
    );
  }
}
```

### Model User

```dart
class User {
  final String id;
  final String fullName;
  final String email;
  final String role;
  final bool enabled;
  final String imageUrl;
  final String? bannedReason;
  final DateTime? bannedUntil;
  final DateTime? deletedAt;
  final bool emailVerified;
  final DateTime? emailVerifiedAt;
  final DateTime createdAt;

  User({
    required this.id,
    required this.fullName,
    required this.email,
    required this.role,
    required this.enabled,
    required this.imageUrl,
    required this.bannedReason,
    required this.bannedUntil,
    required this.deletedAt,
    required this.emailVerified,
    required this.emailVerifiedAt,
    required this.createdAt,
  });

  factory User.fromJson(Map<String, dynamic> json) => User(
        id: json['id'] as String,
        fullName: json['fullName'] as String,
        email: json['email'] as String,
        role: json['role'] as String,
        enabled: json['enabled'] as bool,
        imageUrl: json['imageUrl'] as String,
        bannedReason: json['bannedReason'] as String?,
        bannedUntil: _parseDate(json['bannedUntil']),
        deletedAt: _parseDate(json['deletedAt']),
        emailVerified: json['emailVerified'] as bool,
        emailVerifiedAt: _parseDate(json['emailVerifiedAt']),
        createdAt: DateTime.parse(json['createdAt'] as String),
      );

  static DateTime? _parseDate(Object? v) =>
      v == null ? null : DateTime.parse(v as String);

  bool get isBanned => !enabled;
  bool get isDeleted => deletedAt != null;
  bool get isPermanentBan => isBanned && bannedUntil == null;
}
```

`DateTime.parse` trên chuỗi có `Z` trả về `DateTime` ở UTC. Muốn hiển thị theo giờ máy thì gọi `.toLocal()` ở tầng UI.

### PageResponse

```dart
class PageResponse<T> {
  final List<T> content;
  final int page;
  final int size;
  final int totalElements;
  final int totalPages;
  final bool last;

  PageResponse({
    required this.content,
    required this.page,
    required this.size,
    required this.totalElements,
    required this.totalPages,
    required this.last,
  });

  factory PageResponse.fromJson(
    Map<String, dynamic> json,
    T Function(Map<String, dynamic>) fromItem,
  ) =>
      PageResponse(
        content: (json['content'] as List)
            .map((e) => fromItem(e as Map<String, dynamic>))
            .toList(),
        page: json['page'] as int,
        size: json['size'] as int,
        totalElements: json['totalElements'] as int,
        totalPages: json['totalPages'] as int,
        last: json['last'] as bool,
      );
}
```

`last` dùng để biết khi nào dừng infinite scroll.

### Exception theo status code

```dart
class ApiException implements Exception {
  final int status;
  final String message;
  /// Chỉ khác null với lỗi validation: field → thông điệp.
  final Map<String, String>? fieldErrors;

  ApiException(this.status, this.message, [this.fieldErrors]);

  bool get isValidation => fieldErrors != null;
  bool get isNotFound => status == 404;
  bool get isConflict => status == 409;
  bool get isRateLimited => status == 429;

  @override
  String toString() => message;
}
```

Ném nó ra từ một chỗ duy nhất khi `status >= 400`:

```dart
Never _throwApiError(Map<String, dynamic> body) {
  final status = body['status'] as int;
  final message = body['message'] as String;
  final data = body['data'];

  if (message == 'Validation failed' && data is Map) {
    throw ApiException(
      status,
      message,
      data.map((k, v) => MapEntry(k as String, v as String)),
    );
  }
  throw ApiException(status, message);
}
```

### Ví dụ gọi danh sách

```dart
Future<PageResponse<User>> fetchUsers({
  String? keyword,
  String? role,
  bool? banned,
  int page = 0,
  int size = 20,
  String sort = 'createdAt,desc',
}) async {
  final uri = Uri.parse('$apiBaseUrl/api/users').replace(
    queryParameters: {
      if (keyword != null && keyword.isNotEmpty) 'keyword': keyword,
      if (role != null) 'role': role,
      if (banned != null) 'banned': '$banned',
      'page': '$page',
      'size': '$size',
      'sort': sort,
    },
  );

  final res = await http.get(uri);
  final body = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;

  if (res.statusCode >= 400) _throwApiError(body);

  return PageResponse.fromJson(
    body['data'] as Map<String, dynamic>,
    User.fromJson,
  );
}
```

Dùng `utf8.decode(res.bodyBytes)` chứ đừng dùng `res.body` — `res.body` đoán charset từ header và dễ làm hỏng tiếng Việt.

### Upload avatar (multipart)

```dart
Future<User> uploadAvatar(String userId, File image) async {
  final req = http.MultipartRequest(
    'POST',
    Uri.parse('$apiBaseUrl/api/users/$userId/avatar'),
  )..files.add(await http.MultipartFile.fromPath('file', image.path));

  final streamed = await req.send();
  final res = await http.Response.fromStream(streamed);
  final body = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;

  if (res.statusCode >= 400) _throwApiError(body);
  return User.fromJson(body['data'] as Map<String, dynamic>);
}
```

Tên field bắt buộc là `'file'`. Nhớ tự chặn >5MB trước khi gửi (xem cảnh báo ở endpoint 12).

---

## 8. Những chỗ dễ vấp

| Vấn đề | Chi tiết |
|---|---|
| **Android emulator không gọi được** | `localhost` trong emulator là chính nó. Phải dùng `10.0.2.2` |
| **Android chặn HTTP thường** | Từ Android 9 cleartext bị chặn mặc định. Cần `android:usesCleartextTraffic="true"` trong `AndroidManifest.xml` (chỉ để dev) |
| **CORS chưa cấu hình** | Backend không có `CorsConfigurationSource`. Flutter **web** sẽ bị chặn. Mobile không ảnh hưởng. Cần web thì phải thêm CORS ở backend |
| **Tiếng Việt bị lỗi font** | Dùng `utf8.decode(res.bodyBytes)`, không dùng `res.body` |
| **`enabled` chứ không phải `banned`** | Không có field `banned` trong response. `enabled == false` mới là bị ban |
| **`bannedUntil` null mơ hồ** | `null` vừa có thể là "không bị ban" vừa là "ban vĩnh viễn" — luôn xét cùng `enabled` |
| **`id` là UUID chuỗi** | Không parse sang số |
| **Dính 429 khi test avatar** | Chỉ 3 lần/phút |
| **Email OTP tới chậm** | Đi qua outbox + RabbitMQ, scheduler quét mỗi 5 giây |
| **Chưa có endpoint auth** | Không có login/register/refresh. Màn login của `userhub` vẫn phải fake |
| **Backend sẽ bật auth** | `permitAll()` là tạm thời. Thiết kế client nên chừa sẵn chỗ gắn header `Authorization` |

---

## 9. Khởi động backend

```bash
cd ~/Documents/Projects/spring-boot-blueprint
docker compose up -d                 # mysql + redis + rabbitmq
set -a; source .env; set +a          # .env không tự load trong shell không tương tác
./mvnw spring-boot:run
```

Kiểm tra đã lên chưa:

```bash
curl -s "http://localhost:8081/api/users?size=1" | head -c 200
```

| Cổng | Dịch vụ |
|---|---|
| `8081` | Backend |
| `8080` | phpMyAdmin (cần `docker compose --profile dev up -d`) |
| `3306` | MySQL |
| `6379` | Redis |
| `15672` | RabbitMQ management UI |

Ba container `mysql`/`redis`/`rabbitmq` **phải chạy trước** — app không khởi động được nếu thiếu.
