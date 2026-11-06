# Kiểm thử phần 3 — giao thức chuỗi

## Chạy

Cần JDK 17 trở lên:

```powershell
.\mvnw.cmd clean verify
```

Linux/macOS: `./mvnw clean verify`.
Chỉ phần 3: `.\mvnw.cmd -pl 'common,server' -am test`.

Surefire ghi báo cáo vào common/target/surefire-reports và server/target/surefire-reports.
Cache, log và build output không đưa vào Git.

## Kết quả 06/11/2026

Windows x64, Temurin 17.0.20.1, Maven Wrapper 3.3.4 / Maven 3.9.16, compiler release 17.
Lệnh clean verify báo BUILD SUCCESS, đóng gói JAR cả ba module.

| Test class | Số test | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| ProtocolCodecTest | 18 | 0 | 0 | 0 |
| ChessRulesAdapterTest | 21 | 0 | 0 | 0 |
| GameServiceTest | 28 | 0 | 0 | 0 |
| GameLoggingTest | 5 | 0 | 0 | 0 |
| GameServiceTcpIntegrationTest | 2 | 0 | 0 | 0 |
| **Tổng** | **74** | **0** | **0** | **0** |

Số test tính cả từng input trong parameterized test.
Client chưa có test vì giao diện/kết nối vẫn là bộ khung.

## Phạm vi đã kiểm tra

| Nhóm | Trường hợp |
| --- | --- |
| Chuỗi | MOVE trực tiếp, Unicode, FEN có dấu cách, giữ ô rỗng, encode không thêm LF, lệnh lạ/trống/null, chèn delimiter/CR/LF, giới hạn đúng byte UTF-8 |
| Luật cờ | Nước sai/hợp lệ, lượt, nhập thành/cấm qua chiếu, en passant/hết hiệu lực, phong cấp hai màu/4 loại quân, ghim quân, chiếu hết và các kiểu hòa |
| Phòng | Gán màu, tự vào phòng mình, phòng đầy, một session một phòng hoạt động, phòng chờ, giới hạn số phòng, cleanup |
| Nghiệp vụ | Sai lượt, người ngoài phòng, sai luật, thừa ô giả màu/FEN, định dạng nước/mã phòng, đầu hàng, chiếu hết, hòa, disconnect lặp |
| Đồng thời | 32 MOVE đồng thời chỉ chấp nhận một; phòng A bị khóa không chặn phòng B; nhiều JOIN chỉ một người cầm Đen |
| Lịch sử | Chính xác một record cho nước hợp lệ, timestamp/FEN đúng; 105 record chia trang 100 + 5; fromPly sai bị từ chối |
| Logging | Logback thật, nhật ký .log dạng chuỗi, rejection WARN không thêm history, lỗi ổ đĩa không trả sai kết quả, tên file UUID |
| TCP | Hai socket loopback gửi/nhận chuỗi tiếng Việt, STATE giống bàn cờ, sai lượt, EOF, nhiều dòng trong một lần ghi; lệnh lỗi không làm mất PING tiếp theo |

Fixture lịch sử 105 record được dựng trực tiếp để kiểm tra phân trang,
không dùng fixture đó làm bằng chứng về một ván cờ hợp lệ.

## Cần kiểm tra sau khi tích hợp

- Server production: giới hạn đọc dòng trước cấp phát, timeout, thứ tự gửi đa luồng, shutdown.
- Swing: render FEN, thao tác quân, phong cấp, history, popup kết quả, EDT.
- Hai máy LAN, bốn client với hai phòng, ngắt mạng thật.
- Docker/Compose, log mạng/Client, stress rolling log.

TCP harness chỉ ở src/test, không thay thế Server của người 1.
