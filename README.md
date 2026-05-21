# Design and Performance Evaluation of a Lightweight Post Quantum Security Framework for Resource Constrained IoT Devices

Java/Maven project that explores a Post‑Quantum Cryptography (PQC) security layer for an IoT-style data pipeline, with optional MySQL persistence and a JavaFX dashboard UI.

## Requirements

- JDK 17+
- Maven 3.9+
- (Optional) MySQL 8.x (if you want DB persistence)

## Configure

This project reads configuration from `src/main/resources/application.properties`.

For secrets (like the database password), **use environment variables or JVM system properties** instead of committing them:

- Environment variable (recommended):
  - `DB_PASSWORD` for `db.password`
  - `DB_USERNAME` for `db.username`
  - `DB_URL` for `db.url`

- JVM system property:
  - `-Ddb.password=...`

Example (PowerShell):

```powershell
$env:DB_PASSWORD = "your_password"
mvn -q test
```

## Build & Run

Run tests:

```bash
mvn test
```

Run the JavaFX dashboard:

```bash
mvn javafx:run
```

Notes:
- JavaFX dependencies in `pom.xml` are currently configured with the `win` classifier (Windows).
- If MySQL is enabled, ensure the DB exists and the credentials in `application.properties` (or env overrides) are correct.
