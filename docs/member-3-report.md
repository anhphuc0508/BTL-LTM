# Báo cáo phần 3

Ngày: 06/11/2026. Nhánh: feature/game-protocol.

## Thay đổi mới: dùng chuỗi thuần túy

Theo yêu cầu, đã thay JSON bằng các dòng như:

```text
CREATE_ROOM|Phúc
JOIN_ROOM|ABC234|Nam
MOVE|ABC234|e2e4
RESIGN|ABC234
PING
```

- ProtocolCodec dùng split để đọc và nối chuỗi để ghi.
- Message chỉ giữ loại lệnh và mảng chuỗi; bỏ payload, requestId, Jackson và annotation.
- GameService.handle(sessionId, line) nhận String trực tiếp.
- OutboundMessage.getMessage() trả String, chưa có xuống dòng.
- Phản hồi trạng thái và lịch sử cũng là chuỗi; GET_STATE trả STATE, các dòng HISTORY và HISTORY_END.
- Nhật ký từng ván chuyển sang file .log dạng chuỗi.
- Xóa dependency Jackson khỏi Maven; bỏ equals/hashCode và accessor cũ trong các lớp dữ liệu đã đổi.
- Cập nhật tài liệu và test theo định dạng mới.

Đây là thay đổi giao thức/API; code đã tích hợp bản JSON cần chuyển theo [protocol.md](protocol.md).

## Chức năng phần 3 hiện có

| Chức năng | File chính |
| --- | --- |
| Tách lệnh, kiểm tra dòng UTF-8 | common/.../ProtocolCodec.java, Message.java, MessageType.java |
| Tạo/vào phòng, gán màu, quản lý thành viên | RoomManager.java, GameRoom.java |
| Nước đi, lượt, quyền, đầu hàng, trạng thái, disconnect | GameService.java |
| Kiểm tra ô đầu vào và ghép chuỗi phản hồi | GameRequestReader.java, GameResponseBuilder.java |
| Kiểm tra luật bằng Chesslib 1.3.7 | ChessRulesAdapter.java |
| Lưu nước hợp lệ, phân trang 100 nước | MoveRecord.java, GameResponseBuilder.java |
| Log kỹ thuật và file riêng cho mỗi ván | GameEventLogger.java, MatchEventWriter.java |
| Build Java 17 multi-module | pom.xml, Maven Wrapper |

Luồng nghiệp vụ vẫn kiểm tra thành viên, đúng lượt và nước hợp lệ trước khi đổi bàn cờ.
Nước sai không được thêm lịch sử. Hai phòng độc lập; khóa phòng tránh áp dụng trùng nước.
Có chiếu hết, nhập thành, bắt tốt qua đường, phong cấp và các trường hợp hòa.
Hòa lặp ba lần/50 nước hiện tự kết thúc, chưa có thao tác claim draw.

## Kiểm chứng

**74/74 test qua, không failure/error/skipped**, trên Temurin 17.0.20.1, Maven 3.9.16.
Maven Wrapper chạy clean verify thành công; đóng gói cả common, server và client.

- 18 test codec chuỗi.
- 21 test luật cờ.
- 28 test nghiệp vụ/phòng/đồng thời/lịch sử.
- 5 test logging.
- 2 test TCP với hai client giả lập.

Chi tiết: [test-plan.md](test-plan.md).

## Cách đọc và phần còn lại

Bắt đầu với [hướng dẫn đọc code](reading-guide.md), rồi GameService.handleMove().
[architecture.md](architecture.md) có cách đưa dòng từ socket vào service và gửi chuỗi trả về.

Chỉ triển khai phần 3; Server TCP production, Swing và Docker vẫn chờ phần 1/2.
TCP test dùng harness riêng. Chưa có demo LAN/Docker hoặc reconnect/resume.
Không có tài khoản, database hay khôi phục trạng thái sau restart.
Nhật ký ván là best-effort; lỗi ghi file không đảo ngược nước hợp lệ trong bộ nhớ.
