# Giao thức Chess Online — phiên bản 1

Đặc tả hiện được triển khai trên nhánh `feature/game-protocol`; người 1 và 2 cần review khi tích hợp.

## Framing và envelope

- TCP, UTF-8, một JSON object trên một dòng, kết thúc bằng LF (`\n`). Cổng dự kiến: 5000, do phần 1 cấu hình.
- `ProtocolCodec.encode(message)` **đã thêm LF**; không gọi thêm `println` lên chuỗi này.
- `decode(line)` nhận một dòng đã bỏ CR/LF. JSON không có newline thô bên trong; newline trong chuỗi được JSON escape.
- Tối đa **1.048.576 byte UTF-8 mỗi dòng**, không tính terminator; độ sâu JSON tối đa 32.
- Người 1 phải dùng bộ đọc dòng có giới hạn **trước khi** cấp phát toàn bộ dòng, timeout và UTF-8 decoder từ chối byte lỗi. `readLine()` không giới hạn chỉ được dùng trong test harness.
- Từ chối duplicate key, JSON nối đuôi, giá trị `type` lạ, field envelope lạ, kiểu dữ liệu sai.
- Không dùng Java serialization, Socket.IO, WebSocket hoặc polymorphic JSON typing.

```json
{"type":"MOVE","requestId":"req-17","roomId":"AB23CD","payload":{"move":"e2e4"}}
```

| Field | Quy định |
| --- | --- |
| `type` | Chuỗi bắt buộc, phân biệt hoa/thường, tên trong `MessageType` |
| `requestId` | Tùy chọn/null; 1–64 ký tự `[A-Za-z0-9._:-]` |
| `roomId` | Tùy chọn/null ở envelope; bắt buộc với lệnh liên quan phòng; 6 ký tự `[A-Z0-9]` |
| `payload` | Object; có thể bỏ qua để dùng `{}`; không chấp nhận `null`, array hoặc scalar trên wire |

`requestId` chỉ dùng đối chiếu, **không phải khóa chống gửi trùng**. Không tự gửi lại `MOVE` khi timeout; dùng `GET_STATE` để kiểm tra.
Khi broadcast, chỉ phản hồi gửi về người yêu cầu có `requestId`; thông báo tới đối thủ bỏ field này để tránh trùng ID giữa hai client.
Session do Server gán và truyền trực tiếp vào `handle(sessionId, message)`. Client không được gửi session, màu quân, FEN hoặc kết quả để Server tin theo.

## Lệnh Client → Server

Chỉ các field payload liệt kê dưới đây được chấp nhận. Field thừa bị từ chối.

| Lệnh | `roomId` | Payload | Kết quả |
| --- | --- | --- | --- |
| `CREATE_ROOM` | Phải bỏ qua/null | `{"playerName":"An"}` | `ROOM_CREATED`, người tạo là Trắng, trạng thái `WAITING` |
| `JOIN_ROOM` | Bắt buộc | `{"playerName":"Bình"}` | `GAME_START` cho cả hai, người vào là Đen |
| `MOVE` | Bắt buộc | `{"move":"e2e4"}` | `STATE` cho cả hai; lỗi trả `MOVE_REJECTED` cho người gửi |
| `RESIGN` | Bắt buộc | `{}` | `GAME_END` cho cả hai; đối thủ thắng |
| `GET_STATE` | Bắt buộc | `{}` hoặc `{"fromPly":101}` | `STATE` riêng cho người yêu cầu; đồng bộ bàn cờ và một trang lịch sử |
| `PING` | Không dùng | `{}` | `PONG`; không đổi trạng thái phòng |

Tên hiển thị: trim khoảng trắng, 1–40 Unicode code point, không có ký tự điều khiển. Tên không phải danh tính/xác thực.
UCI: `[a-h][1-8][a-h][1-8][qrbn]?`, viết thường; ví dụ `e2e4`, phong cấp `e7e8q`, nhập thành `e1g1`.
Một session chỉ tham gia một phòng đang chờ/đang chơi. Không tự tham gia hai bên của một ván.
Sau khi ván kết thúc, session có thể tạo/vào phòng mới; phòng cũ được xóa khi cả hai không còn gắn với phòng.

## Phản hồi Server → Client

`ROOM_CREATED`, `GAME_START`, `STATE`, `GAME_END` cùng dùng payload snapshot:

| Field | Ý nghĩa |
| --- | --- |
| `matchId` | UUID riêng của ván, không tái sử dụng dù mã phòng có thể được dùng lại |
| `version` | Số phiên bản trạng thái của ván, tăng khi bắt đầu, đi cờ hoặc kết thúc |
| `status` | `WAITING`, `IN_PROGRESS`, `FINISHED` |
| `yourColor` | `WHITE` hoặc `BLACK`, riêng theo người nhận |
| `whitePlayer`, `blackPlayer` | Tên hiển thị; chưa có `blackPlayer` khi chờ |
| `fen` | Bàn cờ do Chesslib xuất; Client render trực tiếp |
| `turn` | Màu tới lượt; khi `FINISHED` field này chỉ phản ánh FEN, không cho phép đi tiếp |
| `inCheck` | Bên tới lượt có đang bị chiếu |
| `moveNumber` | Số half-move/ply đã chấp nhận; bắt đầu từ 0 |
| `lastMove` | UCI mới nhất, bỏ qua nếu chưa có nước đi |
| `history` | Một trang tối đa 100 bản ghi hợp lệ, thứ tự tăng dần theo `ply` |
| `historyFromPly` | Ply đầu tiên được yêu cầu trong trang |
| `nextHistoryPly` | Nếu còn trang tiếp; dùng trong `GET_STATE` tiếp theo |
| `result`, `reason` | Chỉ có khi kết thúc; xem bảng dưới |

Mỗi bản ghi `history` gồm `roomId`, `matchId`, `ply`, `color`, `uci`, `acceptedAt` (UTC ISO 8601), `fen` sau nước đi.
Chưa sinh SAN; giao diện hiển thị UCI đúng theo dữ liệu Server.

Broadcast thông thường chứa tối đa 100 nước mới nhất. Để lấy **toàn bộ** lịch sử, gọi `GET_STATE` từ `fromPly=1` rồi đi theo `nextHistoryPly` đến khi field này không còn.
Phân trang giữ kích thước thông điệp có giới hạn kể cả ván dài. Snapshot bàn cờ luôn là trạng thái hiện tại, còn `history` là trang được yêu cầu.
Client ghép lịch sử theo `(matchId, ply)` để tránh thêm trùng; không coi mỗi `STATE` là một nước đi mới.
Không áp dụng snapshot có `version` thấp hơn trạng thái đã có. Phiên bản bằng nhau vẫn có thể chứa trang lịch sử khác hoặc `GAME_END` cho nước cuối.

| Kết quả | Lý do |
| --- | --- |
| `WHITE_WIN` / `BLACK_WIN` | `CHECKMATE`, `RESIGNATION`, `DISCONNECT` |
| `DRAW` | `STALEMATE`, `INSUFFICIENT_MATERIAL`, `FIFTY_MOVE_RULE`, `THREEFOLD_REPETITION` |

MVP tự kết thúc khi lặp ba lần hoặc đủ 50 nước không bắt quân/đi tốt; chưa có thao tác yêu cầu hòa theo quy trình thi đấu FIDE.
Nước dẫn tới kết thúc gửi `STATE` rồi `GAME_END` tới hai người, cùng snapshot cuối.
Đối thủ rớt kết nối khi đang chơi: người còn lại nhận `OPPONENT_DISCONNECTED` (matchId/version/reason) rồi `GAME_END` với lý do `DISCONNECT`. Không có reconnect/resume phiên đấu trong phiên bản này.

## Ví dụ luồng hai người

```jsonl
{"type":"CREATE_ROOM","requestId":"c1","payload":{"playerName":"An"}}
{"type":"JOIN_ROOM","requestId":"j1","roomId":"AB23CD","payload":{"playerName":"Bình"}}
{"type":"MOVE","requestId":"m1","roomId":"AB23CD","payload":{"move":"e2e4"}}
{"type":"MOVE_REJECTED","requestId":"m2","roomId":"AB23CD","payload":{"reason":"NOT_YOUR_TURN"}}
{"type":"GET_STATE","requestId":"s1","roomId":"AB23CD","payload":{"fromPly":1}}
{"type":"RESIGN","requestId":"r1","roomId":"AB23CD","payload":{}}
```

Mã phòng trong ví dụ là minh họa; dùng mã trả từ `ROOM_CREATED` khi chạy thực tế.
Ví dụ snapshot `STATE` sau `e2e4` (thời gian/UUID minh họa):

```json
{"type":"STATE","requestId":"m1","roomId":"AB23CD","payload":{"matchId":"30138bd9-98e7-4c32-a403-a985340dd25d","version":2,"status":"IN_PROGRESS","yourColor":"WHITE","whitePlayer":"An","blackPlayer":"Bình","fen":"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1","turn":"BLACK","inCheck":false,"moveNumber":1,"lastMove":"e2e4","history":[{"roomId":"AB23CD","matchId":"30138bd9-98e7-4c32-a403-a985340dd25d","ply":1,"color":"WHITE","uci":"e2e4","acceptedAt":"2026-11-06T00:00:00Z","fen":"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"}],"historyFromPly":1}}
```

FEN do thư viện sinh; không hardcode trường en passant theo ví dụ tài liệu thiết kế.

## Lỗi

`ERROR` và `MOVE_REJECTED` có payload `{"reason":"CODE"}`. Lỗi nghiệp vụ của lệnh `MOVE` dùng `MOVE_REJECTED`; lệnh khác dùng `ERROR`.
Lỗi framing/JSON do `ProtocolCodec` phát sinh `ProtocolException`; người 1 bắt exception này và gửi `ERROR`, không trả raw input hoặc stack trace về Client.

| Mã | Ý nghĩa |
| --- | --- |
| `BAD_MESSAGE` | Envelope/JSON không hợp lệ, kiểu hoặc tên thông điệp lạ |
| `MESSAGE_TOO_LARGE` | Vượt giới hạn byte |
| `UNSUPPORTED_COMMAND` | Client gửi một type chỉ được Server phát |
| `INVALID_PAYLOAD` | Thiếu field, sai kiểu, field thừa hoặc trang lịch sử sai |
| `INVALID_PLAYER_NAME` | Tên rỗng/quá dài/có ký tự điều khiển |
| `INVALID_MOVE_FORMAT` | Chuỗi UCI sai định dạng |
| `ROOM_ID_REQUIRED` | Lệnh cần phòng nhưng không gửi mã |
| `ROOM_NOT_FOUND` | Không có phòng với mã này |
| `ROOM_FULL` | Phòng đã có hai người |
| `ALREADY_IN_ROOM` | Session đã có phòng đang chờ/đang chơi |
| `NOT_IN_ROOM` | Người gửi không thuộc phòng |
| `GAME_NOT_STARTED` | Chưa đủ hai người |
| `GAME_FINISHED` | Ván đã kết thúc |
| `NOT_YOUR_TURN` | Không tới lượt người gửi |
| `ILLEGAL_MOVE` | Thư viện từ chối nước đi |
| `SERVER_BUSY` | Đạt giới hạn phòng được cấu hình (mặc định 10.000) |
