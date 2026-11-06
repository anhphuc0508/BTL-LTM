# Báo cáo phần 3 — Game Logic & Protocol

Ngày thực hiện: **06/11/2026**. Nhánh làm việc: **`feature/game-protocol`**.
Phạm vi theo tài liệu: protocol dùng chung, phòng/trận phía Server, thư viện luật cờ, game log, lịch sử nước đi và kiểm thử nghiệp vụ.

## Các phần đã triển khai

| Hạng mục | File chính | Kết quả |
| --- | --- | --- |
| Protocol | `common/.../Message.java`, `MessageType.java`, `ProtocolCodec.java`, `ProtocolException.java` | Envelope type/requestId/roomId/payload, UTF-8 JSON Lines, giới hạn input, kiểm tra JSON và lỗi rõ mã |
| Quản lý phòng | `server/.../game/RoomManager.java`, `GameRoom.java` | Tạo mã phòng, tối đa hai người, Trắng/Đen, session membership, trạng thái chờ/đang chơi/kết thúc, xóa phòng khi không còn thành viên |
| Xử lý nghiệp vụ | `GameService.java`, `OutboundMessage.java` | Create/join/move/resign/ping/get-state/disconnect, kiểm tra quyền và lượt, trả phản hồi đúng session, đồng bộ hai bên |
| Luật cờ | `ChessRulesAdapter.java`, `PlayerColor.java` | Tích hợp Chesslib 1.3.7 (Apache 2.0), kiểm tra legal moves, xuất FEN, chiếu/chiếu hết/hòa; không tự viết toàn bộ luật cờ |
| Lịch sử | `MoveRecord.java` | Chỉ ghi nước hợp lệ: roomId/matchId/ply/color/UCI/thời điểm/FEN; phân trang 100 record để đồng bộ |
| Game event/archive | `MatchEventWriter.java` | Event create/start/accepted/end theo UUID từng ván; rejection là WARN trong technical log; không thêm nước sai vào history |
| Đồng thời | `RoomManager`, `GameRoom`, `GameService` | Khóa theo phòng cho kiểm tra/apply/history/snapshot, kiểm tra 32 yêu cầu cùng lúc không apply trùng |
| Build | Các `pom.xml`, Maven Wrapper | Maven multi-module Java 17, dependency cố định; client/server dùng chung common, Chesslib chỉ có ở server |
| Logging nền | `server/src/main/resources/logback.xml` | Console + file rolling để game log chạy được; người 1 có thể mở rộng cho log mạng/Docker |
| Tài liệu | `docs/protocol.md`, `architecture.md`, `logging.md`, `test-plan.md`, `third-party.md`, README | Giao thức, mã lỗi, JSON mẫu, điểm nối cho hai thành viên còn lại, kiểm thử, nguồn và giấy phép dependency |

`GET_STATE`, `matchId`, `version` và phân trang history là phần bổ sung vào đặc tả dự kiến để hỗ trợ đồng bộ, chống thêm trùng và giới hạn kích thước thông điệp. Cả nhóm cần review contract trước khi merge.
Các `pom.xml` ở gốc/common/server/client và cấu hình logging nền được bổ sung để phần 3 có thể build/test; chưa triển khai raw TCP Server, Swing hoặc Docker.

## API bàn giao cho người 1

```java
GameService game = new GameService(); // Một instance dùng chung cho Server.
List<OutboundMessage> replies = game.handle(sessionId, message);
List<OutboundMessage> notifications = game.onDisconnect(sessionId);
```

`sessionId` do tầng TCP gán. Mỗi phản hồi chỉ ra `recipientSessionId` và `Message`; người 1 chịu trách nhiệm tìm socket và gửi qua `ProtocolCodec`.
Không phụ thuộc chữ ký `SessionContext` chưa có của người 1. [Chi tiết ghép code](architecture.md).

## Kết quả kiểm thử

**70/70 test thành công, 0 failure, 0 error, 0 skipped**, chạy ngày 06/11/2026 trên Temurin **17.0.20.1**, Maven **3.9.16**, compiler release **17**.
`mvnw.cmd verify` báo **BUILD SUCCESS** và đóng gói thành công cả ba module.

Xem [test-plan.md](test-plan.md) và báo cáo Surefire trong `common/target/`, `server/target/`.
Bộ test gồm protocol, luật đặc biệt, nghiệp vụ, nhiều phòng, yêu cầu đồng thời, game logging và hai TCP client giả lập.
Lệnh bàn giao: `.\mvnw.cmd verify`.

## Giới hạn và phần cần tích hợp

- Server TCP production, session/socket lifecycle, bounded reader, timeout, thứ tự hàng đợi gửi và Docker thuộc người 1.
- Swing/TCP Client, render FEN, popup lỗi, hiển thị history, xử lý EDT và Client logging thuộc người 2.
- Chưa demo hai máy LAN, bốn Swing Client hoặc Docker; TCP integration test dùng harness riêng trong test.
- Lịch sử hiển thị bằng UCI; chưa sinh SAN. Không có reconnect/resume, tài khoản hoặc database.
- Quy tắc MVP tự kết thúc hòa khi lặp ba lần/50 nước; chưa triển khai thao tác claim draw theo quy trình thi đấu.
- Archive ván đấu là best-effort, không tự phục hồi state khi restart; lỗi ổ đĩa được log và không làm mất nước đi đã chấp nhận trong bộ nhớ.

File `client/src/main/java/com/chess/client/ChessClient.java` đã có thay đổi cục bộ từ trước; giữ nguyên và không đưa thay đổi này vào commit phần 3.
