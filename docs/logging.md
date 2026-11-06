# Logging và lịch sử nước đi

## Hai nguồn dữ liệu

| Nguồn | Mục đích | Nơi lưu |
| --- | --- | --- |
| Technical/system log | Theo dõi nghiệp vụ, mạng, lỗi | Console và `logs/server.log` |
| Match archive | Bản ghi event của từng ván phục vụ demo/debug | `logs/matches/<matchId>.jsonl` |
| In-memory move history | Trạng thái nghiệp vụ để gửi cho Client | `GameRoom.history`, chỉ chứa nước hợp lệ |

Client nhận history từ snapshot Server; không đọc/parse `server.log` hoặc file archive để hiển thị.
History trong bộ nhớ và archive hiện không được nạp lại sau khi Server restart.

## Event phần 3 đã triển khai

| Event | Level | Technical log | Match archive |
| --- | --- | --- | --- |
| `ROOM_CREATED` | INFO | Có | Có |
| `GAME_START` | INFO | Có | Có |
| `MOVE_ACCEPTED` | INFO | Có, UCI/ply/context | Có, thêm color/UCI/time/FEN |
| `MOVE_REJECTED` | WARN | Có, requestId/sessionId/mã lý do | Không thêm vào lịch sử hợp lệ |
| `GAME_END` | INFO | Có, result/reason | Có |
| `MATCH_LOG_WRITE_FAILED` | ERROR | Có, kèm exception | Không ghi được event do lỗi ổ đĩa |

Context: `roomId`, `matchId`, `requestId` khi có, `ply`; event từ chối thêm `sessionId` đã được kiểm tra.
Không ghi tên hiển thị, raw JSON không kiểm soát, token hoặc payload Client vào technical log. Chuỗi UCI trong log chỉ là nước đã hợp lệ.
Exception khi ghi archive được logger ghi kèm stack trace, không đưa về Client.

Một lỗi ghi archive không thay đổi kết quả nước đã chấp nhận: vẫn giữ history trong bộ nhớ, trả `STATE` cho hai bên, ghi ERROR để vận hành biết.
Archive là bản ghi best-effort; không cam kết giao dịch hoặc durability khi mất điện.

## Cấu hình nền dùng chung

`server/src/main/resources/logback.xml` cung cấp cấu hình tối thiểu để game log hoạt động:

- SLF4J + Logback, mức gốc INFO.
- Timestamp ISO 8601 có offset, level, thread, logger, message, stack trace.
- Console và `logs/server.log`.
- Rolling theo ngày và kích thước 10 MB, giữ tối đa 7 ngày và tổng archive technical log 100 MB.
- Đổi thư mục technical log qua biến môi trường hoặc system property `CHESS_LOG_DIR`.

Đường dẫn match archive được truyền riêng vào `new MatchEventWriter(path)`; constructor mặc định của `GameService` dùng `logs/matches/`.
Đổi `CHESS_LOG_DIR` không tự đổi đường dẫn match archive. Muốn đồng bộ hai đường dẫn, người 1 truyền `MatchEventWriter` đúng thư mục khi khởi tạo service.
Có thể tắt archive bằng `MatchEventWriter.disabled()`; technical log và history vẫn hoạt động.
Mỗi event archive mở/đóng file ngay; truy cập cùng ván được khóa bởi `GameRoom`.
Tên file dùng UUID `matchId`, không dùng mã phòng có thể tái sử dụng; chỉ cho phép UUID hợp lệ để tránh đường dẫn từ input.
Match archive chưa có chính sách tự xóa; người vận hành quyết định thời gian lưu cho demo.

Người 1 bổ sung event kết nối/timeout/I/O, shutdown và Docker logs. Người 2 cấu hình Client logging theo PID và hiển thị lịch sử trên Swing.
Thư mục `logs/`, `target/` và bộ công cụ cục bộ `.tools/` đã được `.gitignore` bỏ qua.

## Kiểm chứng

`GameEventLogger` nhận event từ `GameService`, giữ tên logger `GameService` như API ban đầu;
`MatchEventWriter` chịu trách nhiệm ghi archive. Việc tách lớp giúp đọc luồng xử lý trận đấu dễ hơn.

`GameLoggingTest` kiểm tra cấu hình Logback thật, file archive, đúng một lần ghi nước hợp lệ, nước bị từ chối tạo WARN nhưng không thêm history, lỗi ổ đĩa không làm nước đi bị trả sai, và phân tách file theo matchId.
