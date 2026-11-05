# Chess Online — Java Swing & TCP Socket

Bộ khung dự án theo mục 4 của tài liệu `Chess_Online_Phan_Cong_Moi_3_Nguoi_Logging.docx`.
Thư mục repository hiện tại là thư mục gốc dự án (`chess-online/` trong tài liệu).

Hiện tại chỉ có cấu trúc thư mục và file trống, chưa có code hoặc cấu hình chạy.
Các file Java, `pom.xml`, Logback, Docker và tài liệu trong `docs/` đều để trống.
Chưa thể build hoặc chạy ứng dụng. File `.gitkeep` giữ thư mục test trong Git.

## Nhánh và phân công

| Nhánh | Người phụ trách | Phạm vi |
| --- | --- | --- |
| `main` | Cả nhóm | Nhánh chính để tích hợp |
| `feature/network-server` | Người 1 | `server/.../bootstrap/`, `server/.../transport/`, cấu hình Logback Server, `docker/`, `docker-compose.yml` |
| `feature/swing-client` | Người 2 | `client/` |
| `feature/game-protocol` | Người 3 | `common/`, `server/.../game/`, chủ trì `docs/protocol.md` |

Cả ba nhánh thành viên bắt đầu từ cùng bộ khung trên `main`.

## Cấu trúc

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
