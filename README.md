# snippet-service

Parte de **Snippet Searcher** (Ingeniería de Sistemas 2026). Es el servicio al que va a hablar la UI y el que coordina a los demás.

## De qué es dueño

- **Metadata de cada snippet** (nombre, descripción, lenguaje, versión, owner) en su propio Postgres.
- **Tests de cada snippet**: inputs y outputs esperados (US8). Correrlos es trabajo de `language-service`.
- **Reglas de lint y formato elegidas por cada usuario** (US11, US14). Se le pasan a `language-service` en cada llamada.
- **Estado de validez de cada snippet** (`PENDING` / `COMPLIANT` / `NOT_COMPLIANT`), persistido, para poder filtrar y ordenar por él (US5).

## De qué no es dueño

- Quién puede ver qué snippet: eso es `permission-service`, y se le consulta.
- Parsear, ejecutar, formatear o lintear código: eso es `language-service`.

## Correr en local

Necesita su Postgres. Lo más simple es levantarlo desde el repo [`infra`](https://github.com/Ingsis-2026/infra) con `docker compose up -d snippet-db`.

```sh
./gradlew bootRun   # levanta en :8080
```

| Variable | Default |
|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5432` / `snippet` / `snippet` / `snippet` |
| `PERMISSION_URL` / `LANGUAGE_URL` | `http://localhost:8081` / `http://localhost:8082` |

## Calidad

`./gradlew check` corre ktlint, los tests y exige 80% de cobertura. Lo único excluido de la cobertura es el `main()` de Spring.

## CI/CD

- **CI** (`ci.yml`): `./gradlew check` en cada push y PR a `main` o `dev`.
- **CD** (`cd.yml`): cuando CI pasa sobre un push, publica `ghcr.io/ingsis-2026/snippet-service:{dev|prod}` y `:sha-<commit>`. `dev` corresponde a dev y `main` a prod.
