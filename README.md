# Chess Online — Java Swing & TCP Socket

Bộ khung dự án theo mục 4 của tài liệu `Chess_Online_Phan_Cong_Moi_3_Nguoi_Logging.docx`.
Thư mục repository hiện tại là thư mục gốc dự án (`chess-online/` trong tài liệu).

Nhánh `feature/game-protocol` đã triển khai phần 3: protocol JSON Lines và nghiệp vụ trận đấu.
TCP Server chạy thật, giao diện Swing và Docker còn chờ phần 1 và 2 tích hợp.
File `.gitkeep` giữ thư mục test trong Git.

## Build và kiểm thử

Cần **JDK 17 trở lên** và Internet ở lần chạy đầu để tải Maven/dependency từ Maven Central và JitPack.
Maven Wrapper đi kèm, không cần cài Maven riêng. Có thể đặt `JAVA_HOME` trỏ đến JDK.

Windows PowerShell:

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

Linux/macOS:

```sh
./mvnw test
./mvnw verify
```

Chỉ build/test phần 3 và module dùng chung:

```powershell
.\mvnw.cmd -pl 'common,server' -am test
```

`verify` tạo JAR thư viện cho từng module; hiện chưa có chương trình Server/Client có thể chạy độc lập.
Kết quả test ở `common/target/surefire-reports/` và `server/target/surefire-reports/`.

## Tài liệu bàn giao

- [Đặc tả protocol và ví dụ JSON](docs/protocol.md).
- [Kiến trúc và hướng dẫn ghép code](docs/architecture.md).
- [Logging](docs/logging.md).
- [Kế hoạch và bằng chứng kiểm thử](docs/test-plan.md).
- [Báo cáo phần 3](docs/member-3-report.md).
- [Thư viện và giấy phép](docs/third-party.md).

Thay đổi protocol/API cần được cả nhóm review trước khi merge vào `main`.

## Nhánh và phân công

| Nhánh | Người phụ trách | Phạm vi |
| --- | --- | --- |
| `main` | Cả nhóm | Nhánh chính để tích hợp |
| `feature/network-server` | Người 1 | `server/.../bootstrap/`, `server/.../transport/`, cấu hình Logback Server, `docker/`, `docker-compose.yml` |
| `feature/swing-client` | Người 2 | `client/` |
| `feature/game-protocol` | Người 3 | `common/`, `server/.../game/`, chủ trì `docs/protocol.md` |

Cả ba nhánh thành viên bắt đầu từ cùng bộ khung trên `main`.

## Cấu trúc ban đầu

```text
.
├── README.md
├── pom.xml
├── .gitignore
├── common/
│   ├── pom.xml
│   └── src/main/java/com/chess/common/protocol/
│       ├── Message.java
│       ├── MessageType.java
│       └── ProtocolCodec.java
├── server/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/chess/server/
│       │   ├── bootstrap/
│       │   │   └── GameServer.java
│       │   ├── transport/
│       │   │   ├── ClientHandler.java
│       │   │   ├── ConnectionManager.java
│       │   │   └── SessionContext.java
│       │   └── game/
│       │       ├── RoomManager.java
│       │       ├── GameRoom.java
│       │       ├── GameService.java
│       │       ├── ChessRulesAdapter.java
│       │       ├── MoveRecord.java
│       │       └── MatchEventWriter.java
│       ├── main/resources/logback.xml
│       └── test/java/.gitkeep
├── client/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/chess/client/
│       │   ├── ChessClient.java
│       │   └── ui/
│       │       ├── ConnectionFrame.java
│       │       ├── LobbyFrame.java
│       │       ├── GameFrame.java
│       │       ├── ChessBoardPanel.java
│       │       └── MoveHistoryPanel.java
│       └── main/resources/logback.xml
├── docker/
│   └── Dockerfile
├── docker-compose.yml
└── docs/
    ├── protocol.md
    ├── architecture.md
    ├── logging.md
    └── test-plan.md
```
