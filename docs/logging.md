# Logging và lịch sử

| Nguồn | Nơi lưu | Mục đích |
| --- | --- | --- |
| Technical log | Console, logs/server.log | Theo dõi hoạt động/lỗi |
| Nhật ký từng ván | `logs/matches/<matchId>.log` | Event dạng chuỗi, mỗi dòng một event |
| Lịch sử trong bộ nhớ | GameRoom.history | Gửi lịch sử cho Client bằng GET_STATE |

Nhật ký mới là chuỗi thuần túy; các file .jsonl cũ nếu có không bị sửa/xóa.
Lịch sử và trạng thái không tự nạp lại sau khi Server restart.

## Các sự kiện

- ROOM_CREATED, GAME_START, MOVE_ACCEPTED, GAME_END: INFO và ghi nhật ký từng ván.
- MOVE_REJECTED: WARN trong technical log, không thêm vào lịch sử/nhật ký nước hợp lệ.
- MATCH_LOG_WRITE_FAILED: ERROR kèm exception; nước hợp lệ vẫn được giữ và trả STATE.

Mỗi dòng nhật ký có cùng thứ tự:

```text
event|roomId|matchId|at|version|ply|color|uci|fen|result|reason
```

color/uci chỉ có ở MOVE_ACCEPTED. result/reason chỉ có khi đã kết thúc.
Ô không có giá trị để rỗng, tách bằng split("\\|", -1).
at là UTC; ply là số nước đã chấp nhận; FEN là bàn cờ sau event.

Technical log ghi mã phòng, matchId, ply, nước hợp lệ, kết quả và lý do.
Từ chối nước đi ghi sessionId và mã lý do; mã phòng chỉ được ghi nếu đúng định dạng.
Không ghi tên người chơi hoặc toàn bộ input chưa kiểm tra vào technical log.

## Cấu hình

SLF4J + Logback, mức INFO, có timestamp/level/thread/logger/message/stack trace.
Console và file logs/server.log, rolling theo ngày và 10 MB,
giữ tối đa 7 ngày, tổng technical archive tối đa 100 MB.
Đổi thư mục technical log bằng CHESS_LOG_DIR.

GameService mặc định dùng logs/matches cho nhật ký ván.
Có thể truyền new MatchEventWriter(path) riêng hoặc MatchEventWriter.disabled().
CHESS_LOG_DIR không tự đổi đường dẫn này.
Tên file dùng UUID do Server tạo; không nhận đường dẫn Client.

Mỗi event mở/đóng file ngay dưới khóa phòng.
Nhật ký là best-effort: lỗi ghi được log, không đảo ngược nước đi đã chấp nhận.
Chưa có chính sách tự xóa nhật ký ván hoặc phục hồi trạng thái từ file.
logs/, target/ và .tools/ đã được Git bỏ qua.

Người 1 bổ sung log kết nối/timeout/shutdown và Docker;
người 2 bổ sung log Client/Swing.

GameLoggingTest kiểm tra Logback thật, nội dung file chuỗi, từ chối không thêm history,
lỗi ổ đĩa không làm sai kết quả nước đi, tách file theo UUID và từ chối đường dẫn sai.
