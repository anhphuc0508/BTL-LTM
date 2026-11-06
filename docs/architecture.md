# Ghép phần 3 vào Server và Client

## Luồng xử lý

```text
Client gửi "MOVE|ABC234|e2e4"
    → người 1 đọc một dòng từ socket
    → GameService.handle(sessionId, line)
    → kiểm tra phòng, lượt, luật cờ; lưu nước hợp lệ
    → trả danh sách OutboundMessage chứa chuỗi
    → người 1 gửi từng chuỗi cho đúng kết nối
```

`common` chứa Message, MessageType và ProtocolCodec dùng chuỗi Java; không cần thư viện runtime.
`server` dùng common, Chesslib và SLF4J/Logback. `client` phụ thuộc common.

## Điểm nối cho người 1

Khởi tạo **một GameService dùng chung** cho toàn bộ kết nối:

```java
GameService game = new GameService();
```

Sau khi đọc được một dòng UTF-8 không có ký tự kết thúc dòng:

```java
List<OutboundMessage> replies = game.handle(sessionId, line);
for (OutboundMessage reply : replies) {
    String recipient = reply.getRecipientSessionId();
    String text = reply.getMessage();
    // Tìm writer của recipient, println(text) rồi flush().
}
```

Không cần tạo Message hoặc gọi codec ở tầng transport.
getMessage() trả String chưa có xuống dòng.
Khi EOF/timeout/lỗi I/O: dừng luồng đọc rồi gọi `game.onDisconnect(sessionId)`,
gửi các thông báo trả về cho kết nối còn sống. Gọi disconnect lặp không kết thúc ván thêm lần nữa.

sessionId do Server gán duy nhất cho mỗi kết nối, gồm 1–128 ký tự [A-Za-z0-9._:-].
Không lấy session từ nội dung Client gửi.
Người 1 phụ trách accept loop, bounded reader, timeout, quản lý writer và đóng socket.
Giới hạn dòng theo ProtocolCodec.MAX_LINE_BYTES trước khi tạo String quá lớn.

## Điểm nối cho người 2

Gửi chuỗi theo [protocol.md](protocol.md), chẳng hạn:

```java
writer.println("MOVE|" + roomId + "|e2e4");
writer.flush();
```

Nhận một dòng rồi tách:

```java
String[] parts = line.split("\\|", -1);
String command = parts[0];
```

Với STATE/GAME_START, parts[2] là màu của mình, parts[6] là FEN.
Bảng đầy đủ nằm trong protocol.md. Có thể dùng ProtocolCodec.decode(line)
để lấy Message; getField(0) là ô đầu tiên **sau** tên lệnh.

Client chỉ gửi yêu cầu; Server quyết định lượt, nước hợp lệ và kết quả.
STATE có ply/lastMove để cập nhật lịch sử, GET_STATE trả lịch sử phân trang khi cần đồng bộ.
TCP chạy ở luồng nền; cập nhật Swing bằng SwingUtilities.invokeLater.

## Các lớp phần 3

| Lớp | Công việc |
| --- | --- |
| ProtocolCodec / Message | Tách và giữ các ô chuỗi |
| GameService | Chọn lệnh, xử lý trận đấu |
| GameRequestReader | Kiểm tra số ô, tên, mã phòng, nước đi |
| GameResponseBuilder | Ghép chuỗi phản hồi |
| RoomManager / GameRoom | Quản lý phòng, thành viên, bàn cờ |
| ChessRulesAdapter | Gọi Chesslib kiểm tra luật |
| MoveRecord | Lưu một nước hợp lệ |
| GameEventLogger / MatchEventWriter | Ghi log kỹ thuật và nhật ký từng ván |
| OutboundMessage | Giữ session nhận và chuỗi cần gửi |

## Đồng thời và vòng đời phòng

Mỗi phòng có khóa riêng: kiểm tra lượt, sửa bàn cờ, thêm lịch sử, tạo phản hồi và ghi log
đều trong cùng khóa. Hai phòng chơi độc lập.

Tạo/vào/rời phòng lấy khóa RoomManager rồi khóa GameRoom.
MOVE/RESIGN/GET_STATE chỉ giữ khóa quản lý trong lúc tìm phòng, nhả khóa rồi khóa phòng.
requireMember đọc ConcurrentHashMap và không lấy ngược khóa quản lý khi đang giữ khóa phòng.

Không ghi socket bên trong khóa phòng. Danh sách phản hồi và các chuỗi đã tạo không thay đổi.
Tầng gửi của người 1 cần giữ thứ tự sự kiện; khóa nghiệp vụ không tự giữ thứ tự ghi TCP
giữa các luồng sau khi handle() trả về. version giúp Client nhận biết snapshot cũ.

Phòng đi qua WAITING → IN_PROGRESS → FINISHED. Tối đa hai người, không spectator.
Rớt kết nối lúc chờ thì xóa phòng. Rớt kết nối khi đang chơi thì đối thủ thắng.
Sau khi kết thúc có thể tạo/vào ván mới; phòng không còn thành viên sẽ được xóa.
matchId UUID phân biệt các ván ngay cả khi mã phòng được dùng lại.

## Trạng thái tích hợp

Đã có TCP harness trong test để kiểm tra hai socket gửi/nhận chuỗi, sai lượt và EOF.
Harness chỉ phục vụ test, không thay thế production Server.
Bootstrap/transport, Swing và Docker còn là khung của người 1 và 2.
Chưa có demo LAN, khôi phục sau restart, tài khoản, database hoặc reconnect/resume.

Phiên bản 2 thay giao thức JSON và API Message cũ:
handle() nhận String, OutboundMessage.getMessage() trả String.
Code tích hợp trước đây cần cập nhật theo ví dụ trên.
