# Phần 1: TCP Server và hạ tầng mạng

Phạm vi: bootstrap, transport, cấu hình log Server, đóng gói Server và Docker.
Không thay đổi common/protocol, server/game hoặc client.
Giao thức hiện tại là chuỗi UTF-8 phân cách bằng `|` trong [protocol.md](protocol.md).
Ví dụ JSON trong tài liệu phân công Word là phiên bản cũ.

## Chạy bằng Java

Cần JDK 17 trở lên, Maven hoặc Maven Wrapper và Internet để tải dependency lần đầu.
Chạy từ thư mục gốc dự án:

```powershell
.\mvnw.cmd -pl server -am verify
java -jar server/target/server-1.0-SNAPSHOT.jar
```

Nếu đã cài Maven, có thể thay `.\mvnw.cmd` bằng `mvn`.
Nếu JAVA_HOME đang trỏ sai, đặt lại cho phiên PowerShell hiện tại trước khi build:

```powershell
$env:JAVA_HOME = 'C:\duong-dan-den-jdk-17'
```

Biến môi trường (đặt trước khi chạy Java):

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| CHESS_HOST | 0.0.0.0 | Địa chỉ lắng nghe |
| CHESS_PORT | 5000 | Cổng TCP |
| CHESS_TIMEOUT_MS | 120000 | Thời hạn nhận một dòng hoàn chỉnh, kể cả chờ byte đầu |
| CHESS_MAX_CLIENTS | 64 | Tối đa Client, từ 1 đến 256 |
| CHESS_LOG_DIR | logs | Thư mục technical log |

Timeout tính lại sau mỗi dòng hoàn chỉnh. Client đang chờ lâu nên gửi PING định kỳ
(ví dụ mỗi 30 giây), đọc PONG trên luồng nhận hiện có. Server không tự gửi heartbeat.
Đổi timeout lớn hơn nếu Client hiện tại chưa gửi PING định kỳ.

## Chạy Docker Compose

```powershell
docker compose up --build -d
docker compose logs -f server
docker compose down
```

Docker build dùng Java 17, runtime chạy bằng user 10001. Cổng 5000 trong container
được ánh xạ ra `${CHESS_PORT:-5000}` trên máy chủ. CHESS_HOST trong container luôn
là 0.0.0.0. Volume `server-logs` giữ technical log và nhật ký ván khi xóa container;
`docker compose down` không xóa volume này.

## Kết nối LAN

1. Chạy Server trên máy chủ, dùng `ipconfig` tìm địa chỉ IPv4 của card mạng LAN.
2. Cho phép TCP inbound cổng 5000 trên firewall của máy chủ khi demo LAN.
3. Client trên máy khác nhập IPv4 của máy chủ và cổng 5000. Client cùng máy có thể dùng 127.0.0.1.
4. Chạy hai Client, tạo/vào phòng và đi e2e4; thử thêm hai Client ở phòng khác.

Không nhập 0.0.0.0 vào Client: đó là địa chỉ bind của Server.

## Đọc và giải thích code

1. `GameServer.main()` đọc cấu hình và tạo một GameService dùng chung.
2. `start()` mở ServerSocket; `acceptClients()` nhận kết nối và cấp session UUID.
3. `ConnectionManager.register()` kiểm tra giới hạn và lưu session.
4. `ClientHandler.run()` đọc byte đến LF, bỏ CR cuối dòng nếu có. Dòng tối đa
   16.384 byte UTF-8; kiểm tra giới hạn trước khi tạo String và từ chối UTF-8 lỗi.
5. `ConnectionManager.handle()` gọi GameService và xếp OutboundMessage vào hàng đợi
   của đúng người nhận. Khóa chung giữ thứ tự từ xử lý đến xếp phản hồi.
6. `SessionContext.writeMessages()` là nơi duy nhất ghi socket của session đó.
   Một phản hồi luôn thêm LF và flush. Hàng đợi tối đa 256 dòng; đầy thì ngắt Client
   chậm để không giữ vô hạn dữ liệu. Không ghi socket khi giữ khóa xử lý lệnh.
7. EOF, timeout hoặc lỗi socket gọi `disconnect()` đúng một lần. Hàm này đóng socket,
   xóa session và gọi GameService.onDisconnect() để báo cho đối thủ.
8. `close()` ngừng accept, đóng session, ngắt worker và chờ worker dừng tối đa 5 giây.
   Ctrl+C và Docker stop đi qua shutdown hook này.

Một Client có một worker đọc và một worker ghi; accept có thread riêng.
Để code dễ đọc và bảo đảm thứ tự phản hồi, các lệnh game hiện được xử lý lần lượt
qua khóa ConnectionManager. Cách này phù hợp demo nhóm nhỏ; xử lý game/log chậm
sẽ làm Client khác chờ đến lượt xử lý. Socket chậm không giữ khóa đó.
Không hỗ trợ reconnect/resume hay phục hồi ván sau restart.

## Dữ liệu lỗi và logging

Lệnh sai thông thường nhận ERROR từ GameService, kết nối vẫn dùng tiếp.
Dòng quá dài, UTF-8 lỗi hoặc timeout khiến Server đóng riêng kết nối đó.
EOF giữa dòng bỏ lệnh chưa hoàn chỉnh. Hết chỗ hoặc đang shutdown thì đóng kết nối mới.
Server không cam kết gửi ERROR trước khi đóng các kết nối này.

Log mạng: SERVER_STARTED, CONNECT, DISCONNECT, TIMEOUT, BAD_MESSAGE, IO_ERROR,
CONNECTION_REJECTED, SERVER_STOPPED. Không ghi input thô hay tên người chơi.
Logback ghi console và server.log, rolling theo ngày/10 MB, giữ 7 ngày,
tổng archive tối đa 100 MB. Nhật ký ván mặc định vẫn ở logs/matches theo GameService.

## Kiểm thử

`GameServerIntegrationTest` dùng Server production và socket thật:
bốn Client/hai phòng, sai lượt, disconnect, dữ liệu bị chia/gộp, thứ tự phản hồi,
CRLF, giới hạn byte UTF-8, UTF-8 lỗi, EOF giữa dòng, timeout, giới hạn kết nối,
shutdown và giải phóng cổng. Các test phần game/protocol hiện có được chạy cùng.

Kết quả kiểm chứng ngày 10/11/2026:

- Maven `verify`: 81 test, không failure/error/skipped (74 test cũ và 7 test TCP mới).
  Build local dùng JDK 24 với compiler release 17 và Maven 3.9.12.
- JAR độc lập: PING trả PONG.
- Docker build thành công với JDK 17, runtime Java 17. Bốn TCP Client ngoài container
  tạo hai phòng riêng, gửi tên tiếng Việt, đi e2e4 và đầu hàng thành công.
- Docker logs có sự kiện mạng/game và SERVER_STOPPED khi docker stop.
- Docker Compose: cổng publish nhận CREATE_ROOM; volume có server.log và nhật ký ván,
  chủ sở hữu UID 10001. Container/network/volume thử nghiệm đã được dọn.
- Chưa demo bằng Swing trên hai máy LAN thật; cần thực hiện khi có môi trường đó.

Trong môi trường phát triển này, JAVA_HOME ban đầu trỏ tới JDK không còn dùng được
và Maven Wrapper PowerShell gặp lỗi `Cannot index into a null array`.
Kiểm chứng dùng Maven đã có trên máy và JAVA_HOME hợp lệ cho riêng lệnh build;
không sửa Wrapper hay cấu hình Java toàn máy. Sandbox cũng chặn dọn thư mục tạm
của test logging có sẵn; chạy kiểm thử ngoài sandbox đã qua đầy đủ.
