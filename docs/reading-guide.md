# Đọc phần 3 từ đâu?

Bắt đầu bằng ví dụ này:

```text
MOVE|ABC234|e2e4
```

Nó chỉ có ba phần: lệnh MOVE, mã phòng ABC234, nước đi e2e4.

## 1. ProtocolCodec.decode()

Lệnh này:

```java
String[] parts = line.split("\\|", -1);
```

tách chuỗi thành:

```text
parts[0] = "MOVE"
parts[1] = "ABC234"
parts[2] = "e2e4"
```

Message giữ loại lệnh và các ô phía sau. Vì bỏ tên lệnh ra riêng:
getType() là MOVE, getField(0) là ABC234, getField(1) là e2e4.
Không có payload, ObjectNode, annotation hay Jackson.

## 2. GameService.handle()

Hàm nhận thẳng chuỗi đọc từ socket:

```java
game.handle(sessionId, "MOVE|ABC234|e2e4");
```

Nó tách chuỗi rồi xem lệnh:

```java
if (type == MessageType.MOVE) {
    return handleMove(sessionId, request);
}
```

## 3. GameService.handleMove()

Đọc theo 7 chú thích trong hàm:

1. Lấy nước đi và tìm phòng.
2. Kiểm tra người gửi thuộc phòng, ván đã bắt đầu.
3. Kiểm tra đúng lượt.
4. Nhờ Chesslib kiểm tra luật và cập nhật bàn cờ.
5. Lưu nước hợp lệ.
6. Kiểm tra ván đã kết thúc chưa.
7. Tạo chuỗi phản hồi cho hai người.

synchronized(room) bảo đảm mỗi lúc chỉ một luồng sửa phòng đó.

## 4. Xem phản hồi

GameResponseBuilder nối các giá trị bằng dấu |.
OutboundMessage chỉ giữ hai thứ: gửi cho session nào và gửi chuỗi gì.
Người 1 lấy getMessage(), gửi bằng println() rồi flush().

Đọc tiếp RoomManager/GameRoom khi muốn hiểu phòng;
ChessRulesAdapter khi muốn hiểu gọi luật cờ;
GameEventLogger/MatchEventWriter khi muốn hiểu ghi log.

[Bảng lệnh và thứ tự các ô phản hồi](protocol.md).
