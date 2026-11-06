# Kiểm thử phần 3

## Chạy kiểm thử

Windows, JDK 17 trở lên:

```powershell
.\mvnw.cmd verify
```

Linux/macOS: `./mvnw verify`.
Chỉ phần 3: `.\mvnw.cmd -pl 'common,server' -am test`.
Kết quả XML/text được Surefire sinh vào `common/target/surefire-reports/` và `server/target/surefire-reports/`.
Không commit dependency cache, file log hoặc build output.

## Kết quả đã chạy ngày 06/11/2026

Môi trường: Windows x64, Temurin **17.0.20.1**, Maven Wrapper 3.3.4 / Maven **3.9.16**, compiler release **17**.
Lệnh `mvnw.cmd verify` hoàn thành **BUILD SUCCESS**; cả `common`, `server`, `client` đều được đóng gói JAR.

| Test class | Số test | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `ProtocolCodecTest` | 21 | 0 | 0 | 0 |
| `ChessRulesAdapterTest` | 21 | 0 | 0 | 0 |
| `GameServiceTest` | 21 | 0 | 0 | 0 |
| `GameLoggingTest` | 5 | 0 | 0 | 0 |
| `GameServiceTcpIntegrationTest` | 2 | 0 | 0 | 0 |
| **Tổng** | **70** | **0** | **0** | **0** |

Số lượng có tính từng input của parameterized test. `client` hiện chưa có test vì giao diện/kết nối chưa triển khai.

## Bộ test tự động

| Test class | Phạm vi |
| --- | --- |
| `ProtocolCodecTest` | JSON Lines, Unicode, round-trip, LF, payload bất biến, duplicate key, field/type sai, trailing JSON, giới hạn byte/depth |
| `ChessRulesAdapterTest` | Nước hợp lệ/sai hình học, đổi lượt, nhập thành hai bên, cấm nhập thành qua chiếu, phong cấp cả hai màu/4 loại quân, en passant và hết hiệu lực, quân bị ghim, chiếu hết, các kiểu hòa |
| `GameServiceTest` | Create/join, màu quân, self-join/full room, session chỉ có một phòng, lượt/quyền/nước sai, chống giả color/FEN, history/timestamp, đầu hàng, chiếu hết/hòa, disconnect/cleanup, hai phòng độc lập, capacity, phân trang history, 32 yêu cầu đồng thời chỉ apply một lần |
| `GameLoggingTest` | Cấu hình Logback thật, event archive, rejection WARN, history không chứa nước sai, lỗi ổ đĩa, UUID file/path traversal |
| `GameServiceTcpIntegrationTest` | Hai client TCP loopback giả lập, gửi/nhận state, sai lượt, EOF, nhiều JSON Lines trong cùng lần ghi, JSON lỗi không làm mất lệnh tiếp theo |

Fixture phân trang tạo 105 `MoveRecord` trực tiếp trong test để kiểm tra các trang 100 + 5; không coi fixture này là một ván cờ hợp lệ.
Test TCP có harness riêng trong `src/test/`; production Server/Client vẫn do người 1 và 2 triển khai.

## Các kiểm thử cần làm khi ghép nhóm

| ID tài liệu | Trạng thái / bằng chứng hiện có | Phần cần ghép |
| --- | --- | --- |
| T01 | Hai TCP client giả lập kết nối harness | Hai Swing Client với Server thật |
| T02–T05 | Nghiệp vụ tạo/vào phòng, `e2e4`, sai lượt/sai luật đã có test | Render và thao tác UI |
| T06 | EOF thông báo đối thủ, kết thúc ván và giải phóng phòng đã có test | Timeout, ngắt mạng LAN thực tế |
| T07 | Hai phòng độc lập được kiểm tra ở tầng service | Bốn TCP/Swing Client với production Server |
| T08 | Codec kiểm tra malformed/oversize/depth; TCP harness kiểm tra JSON lỗi | Bounded reader, giới hạn socket và chống quá tải của người 1 |
| T09 | Adapter test nhập thành, phong cấp, en passant, king safety | Thao tác và render trong UI |
| T10 | Service test chiếu hết, hòa lặp ba lần, đầu hàng; adapter test các kiểu hòa khác | Hiển thị kết quả UI |
| T11 | Chưa kiểm thử Docker | Docker/Compose của người 1 |
| T12 | Game event/system log của phần 3 có test | Log mạng/Client của người 1 và 2 |
| T13 | History/ply/phân trang/từ chối không thêm record có test | `MoveHistoryPanel` thật |
| T14 | Cấu hình rolling technical log được nạp và ghi thử | Stress rotation vượt 10 MB/ngày và Docker logs |

Không suy ra rằng demo toàn hệ thống đã hoàn thành từ kết quả unit test hoặc harness.
