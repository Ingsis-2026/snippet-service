# snippet-service

Parte de **Snippet Searcher** (Ingeniería de Sistemas 2026). Es el servicio al que va a hablar la UI y el que coordina a los demás.

## De qué es dueño

- **Metadata de cada snippet** (nombre, descripción, lenguaje, versión, owner) en su propio Postgres.
- **Código de cada snippet**, en Azure Blob Storage: un blob por snippet, que se llama como su id. En local y en las VMs es Azurite, el emulador de Azure Storage.
- **Tests de cada snippet**: inputs y outputs esperados (US8). Correrlos es trabajo de `language-service`.
- **Reglas de lint y formato elegidas por cada usuario** (US11, US14). Se le pasan a `language-service` en cada llamada.
- **Estado de validez de cada snippet** (`PENDING` / `COMPLIANT` / `NOT_COMPLIANT`), persistido, para poder filtrar y ordenar por él (US5).
- **Las colas de snippets a lintear (US15) y a formatear (US12)**, en streams de Redis. Ver [Linteo y formateo automáticos](#linteo-y-formateo-automáticos).

## De qué no es dueño

- Quién puede ver qué snippet: eso es `permission-service`, y se le consulta.
- Parsear, ejecutar, formatear o lintear código: eso es `language-service`.

## Linteo y formateo automáticos

Cuando un usuario cambia sus reglas de lint (US15) o de formato (US12), no se lo hace esperar a que se procesen todos sus snippets.

**Lint.**

1. En la misma transacción que guarda las reglas, todos sus snippets pasan a `PENDING`.
2. Una vez guardado, se encola cada snippet en el stream `lint-requests` de Redis (`XADD`) y se responde.
3. Un worker lee el stream como parte del consumer group `snippet-service`, lintea cada snippet con el código y las reglas que hay en ese momento y guarda `COMPLIANT` o `NOT_COMPLIANT`. Recién ahí confirma el mensaje (`XACK`).

Lo mismo pasa al crear o actualizar un snippet: se encola para lintearlo.

**Formato.**

1. Una vez guardadas las reglas, se encola cada snippet del usuario en el stream `format-requests` y se responde.
2. Otro worker, con el mismo consumer group, lo formatea en language-service con el código y las reglas que hay en ese momento. Si el código cambió, el snippet pasa a `PENDING`, se guarda el código formateado y se encola en `lint-requests`, porque el linteo que tenía era del código anterior. Recién ahí confirma el mensaje.
3. Si el owner editó el snippet mientras se formateaba, no se pisa su código: queda el que guardó él.

**Por qué dos streams y no uno con el tipo de trabajo.** Formatear es lo más pesado y cada formateo termina encolando un linteo: en un solo stream, una tanda grande de formateos dejaría esperando detrás suyo el linteo de un snippet recién creado. Con uno por tipo, cada worker avanza por su cuenta, el backlog y los pendientes de cada uno se ven por separado (`XLEN`, `XPENDING`), y el mensaje sigue siendo solo el id del snippet. El worker es el mismo código para los dos (`RedisSnippetWorker`); solo cambia qué hace con cada snippet.

**Tolerancia a fallos.** Un mensaje que falla, o que quedó a medias porque se cayó el servicio, sigue pendiente en Redis. Pasado `streams.retry-after` (1 minuto) lo retoma cualquier réplica (`XCLAIM`). Después de `streams.max-attempts` entregas (3) se saltea: se confirma sin procesar para no frenar al resto. Un snippet que no se pudo lintear queda `PENDING`; uno que no se pudo formatear queda con el código que tenía.

Los dos son idempotentes: se usa el estado actual, no el del momento en que se encoló, y formatear código ya formateado no lo cambia. Por eso da igual que un snippet se procese dos veces o en otro orden. Si se corta entre guardar el código formateado y encolarlo para lintear, el reintento lo encola igual, porque el snippet ya quedó `PENDING`.

## Correr en local

Necesita su Postgres, Azurite y Redis. Lo más simple es levantarlos desde el repo [`infra`](https://github.com/Ingsis-2026/infra) con `docker compose up -d snippet-db azurite redis`.

```sh
./gradlew bootRun   # levanta en :8080
```

| Variable | Default |
|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5432` / `snippet` / `snippet` / `snippet` |
| `PERMISSION_URL` / `LANGUAGE_URL` | `http://localhost:8081` / `http://localhost:8082` |
| `BLOB_CONNECTION_STRING` / `BLOB_CONTAINER` | el Azurite de `localhost:10000`, con su cuenta de desarrollo / `snippets` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |
| `HOSTNAME` | `snippet-service`. Es el nombre de la réplica en el consumer group; en Docker es el id del container. |

## Calidad

`./gradlew check` corre ktlint, los tests y exige 80% de cobertura. Lo único excluido de la cobertura es el `main()` de Spring.

Los tests no necesitan Docker: en vez de Azurite usan un store en memoria (`InMemoryContentStore`), y en vez de los streams de Redis unas colas en memoria (`RecordingLintQueue`, `RecordingFormatQueue`) que no procesan nada hasta que el test lo pide. El worker de Redis se prueba con Redis simulado (`RedisStreamsTest`).

## CI/CD

- **CI** (`ci.yml`): `./gradlew check` en cada push y PR a `main` o `dev`.
- **CD** (`cd.yml`): cuando CI pasa sobre un push, publica `ghcr.io/ingsis-2026/snippet-service:{dev|prod}` y `:sha-<commit>`. `dev` corresponde a dev y `main` a prod.
