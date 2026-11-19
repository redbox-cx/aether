# Aether

*Privacy first. Always.*

Secure file transfer between two devices on the local network (Java/JavaFX).
Module 306 / 183 – Team redlab.

## Requirements

- JDK 25
- Maven 3.9+

## Run

```
mvn javafx:run
```

Tests: `mvn test`

## Structure

| Package     | Purpose                                          |
|-------------|--------------------------------------------------|
| `ui`        | JavaFX user interface (FXML + controllers)       |
| `core`      | Application logic, transfer flow                 |
| `protocol`  | Aether Transfer Protocol (ATP v1)                |
| `crypto`    | X25519, AES-256-GCM, security code               |
| `network`   | UDP device discovery (47800), TCP transfer (47801) |
| `model`     | Device, Transfer, FileInfo, Chunk, SessionKey    |
