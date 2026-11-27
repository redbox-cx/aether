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

| Package    | Purpose                                            |
|------------|----------------------------------------------------|
| `ui`       | JavaFX user interface (FXML + controllers)         |
| `core`     | Application logic, transfer flow                   |
| `protocol` | Aether Transfer Protocol (ATP v1)                  |
| `crypto`   | X25519, AES-256-GCM, security code                 |
| `network`  | UDP device discovery (47800), TCP transfer (47801) |
| `model`    | Device, Transfer, FileInfo, Chunk, SessionKey      |

## Workflow

- `main` is protected, changes go through pull requests.
- One branch per task from the project plan, e.g. `feature/f01-discovery`, `feature/f06-protocol`, `feature/f02-f03-send-ui`.
- Keep pull requests small and merge often.
- Shared files (`pom.xml`, `AetherApp`, `main-view.fxml`) only after agreeing in the team.
- The UI only talks to `core.AetherBackend`. The app uses `core.NetworkBackend`; to work on the UI without a network, start it with `-Daether.demo=true` (uses `core.DemoBackend`).
