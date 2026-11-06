# Thư viện và giấy phép

Dependency được khai báo trong Maven; không chép source/jar thư viện vào Git.

| Thành phần | Phiên bản cố định | Mục đích | Nguồn / giấy phép |
| --- | --- | --- | --- |
| Chesslib | 1.3.7 | Sinh nước đi hợp lệ, FEN, chiếu hết/hòa | [Release chính thức](https://github.com/bhlangonijr/chesslib/releases/tag/1.3.7), [Apache 2.0](https://github.com/bhlangonijr/chesslib/blob/1.3.7/LICENSE) |
| Jackson 2 | 2.22.4 (BOM) | JSON tree/codec dùng chung | [FasterXML](https://github.com/FasterXML/jackson-databind), Apache 2.0 |
| SLF4J | 2.0.20 | API logging | [SLF4J license](https://www.slf4j.org/license.html), MIT |
| Logback | 1.5.38 | Console, rolling technical log | [Logback license](https://logback.qos.ch/license.html), EPL 1.0 hoặc LGPL 2.1 |
| JUnit Jupiter | 5.14.4 | Unit/integration tests | [JUnit 5](https://github.com/junit-team/junit5), EPL 2.0 |
| Apache Maven Wrapper | 3.3.4 | Script bootstrap Maven | [Apache Maven Wrapper](https://github.com/apache/maven-wrapper), Apache 2.0 |
| Apache Maven | 3.9.16 | Build multi-module | [Apache Maven](https://maven.apache.org/download.cgi), Apache 2.0 |

Chesslib lấy từ JitPack theo hướng dẫn của upstream; các dependency còn lại lấy từ Maven Central.
Chesslib 1.3.7 phụ thuộc Commons Lang 3.18.0 (Apache 2.0) theo POM upstream.
Giữ nguyên header giấy phép trong các script Maven Wrapper sinh từ bản phân phối chính thức.

`ChessRulesAdapter` kiểm tra UCI có trong `board.legalMoves()` **trước** `board.doMove(...)`.
Upstream lưu ý `doMove` riêng lẻ không kiểm tra đầy đủ cách di chuyển của quân; xem [Board.java tại tag 1.3.7](https://github.com/bhlangonijr/chesslib/blob/1.3.7/src/main/java/com/github/bhlangonijr/chesslib/Board.java).
Đây là lý do có test xe nhảy qua quân và các nước sai hình học, ngoài test chiếu/nhập thành/phong cấp.
