# Đọc phần 3 từ đâu?

**Bắt đầu với `server/src/main/java/com/chess/server/game/GameService.java`.**

## 1. Đọc hàm `handle()`

Đây là nơi nhận một yêu cầu do người 1 chuyển từ socket vào.
Nó xem loại lệnh rồi gọi hàm tương ứng:

```java
if (type == MessageType.MOVE) {
    return handleMove(sessionId, request);
}
```

Các lệnh khác cũng tương tự: tạo phòng, vào phòng, đầu hàng, xem trạng thái và ping.

## 2. Đọc hàm `handleMove()`

Ví dụ người chơi muốn đi tốt từ e2 đến e4:

1. Đọc chuỗi `e2e4` và tìm phòng.
2. Kiểm tra người gửi thuộc phòng, trận đã bắt đầu.
3. Kiểm tra đúng lượt.
4. Nhờ thư viện luật cờ kiểm tra nước đi.
5. Lưu nước hợp lệ vào lịch sử.
6. Kiểm tra chiếu hết/hòa.
7. Tạo phản hồi cho hai người chơi; người 1 gửi chúng qua socket.

Trong code có chú thích đánh số đúng theo các bước này.
`synchronized (room)` giúp mỗi lần chỉ một luồng sửa trạng thái của phòng đó.

## 3. Các file hỗ trợ

| File | Khi nào đọc? |
| --- | --- |
| `Message.java` trong `common` | Muốn biết thông điệp chứa gì; getter `getType()`, `getRoomId()`, `getPayload()` |
| `RoomManager.java` | Muốn hiểu tạo phòng, tìm phòng và ai đang thuộc phòng nào |
| `GameRoom.java` | Muốn xem một phòng lưu người chơi, bàn cờ và lịch sử ra sao |
| `ChessRulesAdapter.java` | Muốn biết cách gọi thư viện kiểm tra luật cờ |
| `GameRequestReader.java` | Muốn hiểu cách lấy chuỗi/numbers từ JSON đầu vào |
| `GameResponseBuilder.java` | Muốn xem JSON gửi về Client được tạo như thế nào |
| `GameEventLogger.java`, `MatchEventWriter.java` | Muốn xem ghi log và lưu nhật ký ván đấu |

`Message` là class thường. Ví dụ `request.getPayload()` lấy phần `{"move":"e2e4"}` trong JSON.
Các hàm `payload()`, `type()`, `roomId()` và `requestId()` được giữ để code đã dùng cách gọi cũ vẫn chạy.
JSON gửi qua mạng và API `GameService.handle()` / `onDisconnect()` vẫn theo [protocol](protocol.md).
