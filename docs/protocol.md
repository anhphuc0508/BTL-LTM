# Giao thức chuỗi — phiên bản 2

Mỗi thông điệp là **một dòng chuỗi UTF-8**, các giá trị cách nhau bằng dấu `|`.
Ví dụ: `MOVE|ABC234|e2e4`. Bên gửi thêm xuống dòng rồi flush; bên nhận đọc một dòng.
Không có JSON, payload, requestId hay bước mã hóa/giải mã bảo mật.

Phiên bản này thay thế giao thức JSON cũ theo yêu cầu đổi sang chuỗi.
Code tích hợp cũ cần đổi cách gửi/đọc theo bảng dưới.

## Client gửi

| Chuỗi | Ý nghĩa |
| --- | --- |
| `CREATE_ROOM|Phúc` | Tạo phòng, người tạo cầm Trắng |
| `JOIN_ROOM|ABC234|Nam` | Vào phòng, người vào cầm Đen |
| `MOVE|ABC234|e2e4` | Đi từ e2 đến e4 |
| `MOVE|ABC234|a7a8q` | Phong cấp thành hậu |
| `RESIGN|ABC234` | Đầu hàng |
| `GET_STATE|ABC234` | Lấy trạng thái và tối đa 100 nước lịch sử từ nước 1 |
| `GET_STATE|ABC234|101` | Lấy trạng thái và lịch sử từ nước 101 |
| `PING` | Kiểm tra kết nối; Server trả `PONG` |

- Tên người chơi sau khi bỏ khoảng trắng hai đầu dài 1–40 ký tự Unicode; không chứa `|` hay ký tự điều khiển.
- Mã phòng gồm đúng 6 ký tự chữ hoa/số, do Server tạo.
- Nước đi: ô đi + ô đến, thêm `q/r/b/n` nếu phong cấp; ví dụ `e2e4`, `a7a8n`.
- Không gửi màu, FEN, session hay kết quả từ Client. Server tự xác định.
- Không thêm/bớt ô so với bảng. Không có escape cho dấu `|`.
- Mỗi dòng tối đa **16.384 byte UTF-8**, không tính ký tự xuống dòng.
- Giữ nguyên ô rỗng bằng `line.split("\\|", -1)`. Không gọi `trim()` trên cả dòng.

## Server trả trạng thái

Các lệnh `ROOM_CREATED`, `GAME_START`, `STATE`, `GAME_END` có cùng thứ tự ô:

```text
TYPE|roomId|yourColor|status|turn|ply|fen|inCheck|result|reason|matchId|version|lastMove|whiteName|blackName
```

Sau khi `String[] parts = line.split("\\|", -1);`:

| Vị trí trong parts | Giá trị | Ví dụ / ý nghĩa |
| ---: | --- | --- |
| 0 | TYPE | ROOM_CREATED / GAME_START / STATE / GAME_END |
| 1 | roomId | ABC234 |
| 2 | yourColor | WHITE / BLACK, riêng cho người nhận |
| 3 | status | WAITING / IN_PROGRESS / FINISHED |
| 4 | turn | WHITE / BLACK; chỉ cho đi khi IN_PROGRESS |
| 5 | ply | Số nước đã chấp nhận, ban đầu 0; mỗi bên đi tính một nước |
| 6 | fen | Toàn bộ bàn cờ ở dạng FEN; chứa dấu cách nhưng không chứa dấu `|` |
| 7 | inCheck | true / false |
| 8 | result | Rỗng khi chưa kết thúc; WHITE_WIN / BLACK_WIN / DRAW |
| 9 | reason | Rỗng khi chưa kết thúc; lý do kết thúc |
| 10 | matchId | UUID riêng cho ván |
| 11 | version | Số phiên bản trạng thái tăng khi trạng thái đổi |
| 12 | lastMove | Nước gần nhất, rỗng khi chưa đi nước nào |
| 13 | whiteName | Tên người cầm Trắng |
| 14 | blackName | Tên người cầm Đen, rỗng khi đang chờ |

Ví dụ phòng mới, chưa có người Đen:

```text
ROOM_CREATED|ABC234|WHITE|WAITING|WHITE|0|rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1|false|||12345678-1234-1234-1234-123456789abc|0||Phúc|
```

Tạo phòng: trả ROOM_CREATED cho người tạo.
Vào phòng: gửi GAME_START cho cả hai, mỗi người nhận đúng màu của mình.
Nước hợp lệ: gửi STATE mới cho cả hai. Nếu kết thúc ván, gửi thêm GAME_END cho cả hai.
Đầu hàng: gửi GAME_END cho cả hai.

## Lịch sử khi GET_STATE

Server trả lần lượt:

1. Một dòng STATE.
2. Từ 0 đến 100 dòng HISTORY.
3. Một dòng HISTORY_END, kể cả trang rỗng.

```text
HISTORY|roomId|matchId|ply|color|uci|acceptedAt|fen
HISTORY_END|roomId|matchId|nextPly
```

`acceptedAt` là thời điểm UTC như `2026-11-06T00:00:00Z`.
`nextPly=0` nghĩa là đã hết lịch sử ở thời điểm lấy trang.
Nếu nextPly=101, gửi tiếp `GET_STATE|ABC234|101`.
fromPly phải là số nguyên từ 1 đến tổng số nước + 1.

Các STATE tự phát sau MOVE không kèm các dòng HISTORY; Client có thể thêm lastMove theo ply.
Khi cần đồng bộ đầy đủ, dùng GET_STATE và ghép lịch sử theo (matchId, ply), không thêm trùng.
Client gửi từng yêu cầu GET_STATE sau khi đã nhận HISTORY_END của yêu cầu trước.

## Thông báo và lỗi

```text
PONG
ERROR|reason
MOVE_REJECTED|roomId|reason
OPPONENT_DISCONNECTED|roomId|matchId|version|DISCONNECT
```

Nếu mã phòng trong MOVE thiếu/sai, ô roomId trong MOVE_REJECTED để rỗng.
Khi đối thủ rớt kết nối, người còn lại nhận OPPONENT_DISCONNECTED rồi GAME_END với lý do DISCONNECT.

| Mã lỗi | Ý nghĩa |
| --- | --- |
| BAD_MESSAGE | Dòng trống, lệnh không nhận diện được hoặc chứa xuống dòng |
| MESSAGE_TOO_LARGE | Vượt giới hạn byte |
| INVALID_ARGUMENTS | Sai số ô hoặc fromPly không hợp lệ |
| INVALID_PLAYER_NAME | Tên không hợp lệ |
| ROOM_ID_REQUIRED / INVALID_ROOM_ID | Thiếu/sai định dạng mã phòng |
| INVALID_MOVE_FORMAT | Chuỗi nước đi sai định dạng |
| ROOM_NOT_FOUND / ROOM_FULL | Không tìm thấy phòng / phòng đủ hai người |
| ALREADY_IN_ROOM / NOT_IN_ROOM | Đã ở phòng khác đang hoạt động / không thuộc phòng |
| GAME_NOT_STARTED / GAME_FINISHED | Ván chưa bắt đầu / đã kết thúc |
| NOT_YOUR_TURN / ILLEGAL_MOVE | Sai lượt / nước đi sai luật |
| SERVER_BUSY | Đã đạt giới hạn số phòng |
| UNSUPPORTED_COMMAND | Client gửi loại thông điệp chỉ dành cho Server |

Lý do kết thúc: CHECKMATE, STALEMATE, INSUFFICIENT_MATERIAL, FIFTY_MOVE_RULE,
THREEFOLD_REPETITION, RESIGNATION hoặc DISCONNECT.
Bản hiện tại tự kết thúc hòa khi lặp ba lần/50 nước.

## Framing và thứ tự

`ProtocolCodec.decode()` nhận dòng đã bỏ ký tự kết thúc dòng.
`encode()` và `OutboundMessage.getMessage()` trả chuỗi **chưa có xuống dòng**.
Bên gửi dùng `println(line)` và flush, hoặc `write(line + "\n")` rồi flush.

TCP có thể chia/gộp dữ liệu; một lần read không tương ứng một lệnh.
Tầng transport của người 1 cần đọc theo dòng và áp giới hạn trước khi cấp phát dòng quá lớn.
Một GameService dùng chung cho Server; mỗi session xử lý lệnh theo thứ tự.
Hàng đợi gửi cần giữ thứ tự phản hồi trong phòng và không trộn byte giữa các luồng.
Client bỏ snapshot có version thấp hơn version đã hiển thị trong cùng matchId.
