# snippet-service

Parte de **Snippet Searcher** (Ingeniería de Sistemas 2026). Es el servicio al que va a hablar la UI y el que coordina a los demás.

## De qué es dueño

- **Metadata de cada snippet** (nombre, descripción, lenguaje, versión, owner) en su propio Postgres.
- **Código de cada snippet**, en el [asset-service](https://github.com/austral-ingsis/asset-service) de la cátedra: un asset por snippet en el container `snippets`, que se llama como su id. asset-service lo guarda en Azure Blob Storage; en local y en las VMs es Azurite, el emulador de Azure Storage. snippet-service no habla con Azurite.
- **Tests de cada snippet**: inputs y outputs esperados (US8). Correrlos es trabajo de `language-service`.
- **Reglas de lint y formato elegidas por cada usuario** (US11, US14). Se le pasan a `language-service` en cada llamada.
- **Estado de validez de cada snippet** (`PENDING` / `COMPLIANT` / `NOT_COMPLIANT` / `INVALID` / `ERROR`), persistido, para poder filtrar y ordenar por él (US5).
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

**Tolerancia a fallos.** No es lo mismo que falle el snippet que algo de lo que depende.

- **Falla el snippet** (por ejemplo, language-service responde 4xx). El mensaje sigue pendiente en Redis, igual que uno que quedó a medias porque se cayó el servicio. Pasado `streams.retry-after` (1 minuto) lo retoma cualquier réplica (`XCLAIM`). Al fallar `streams.max-attempts` veces (3) se saltea: se confirma sin procesar para no frenar al resto. Un snippet que no se pudo lintear queda `ERROR` («no se pudo evaluar»); uno que no se pudo formatear queda con el código que tenía.
- **Falla una dependencia**: language-service o asset-service no responden, responden 5xx o 429, o no hay conexión con la base o Redis. No es culpa del snippet, así que no se le gasta un intento: se lo vuelve a agregar al final del stream y se confirma la copia vieja, lo mismo que con los que quedaban de la tanda. El worker deja de leer por `streams.backoff` (10 s), y cada falla seguida duplica la pausa hasta `streams.max-backoff` (5 minutos). Los mensajes postergados llevan, además de `snippetId`, los campos `attempts` (intentos fallidos de las copias anteriores) y `deferrals` (cuántas veces se lo postergó). Para que un snippet que hace responder 500 a language-service no se postergue para siempre, después de `streams.max-deferrals` (20) postergaciones también se saltea.

Encolar pasa después de guardar el cambio, fuera de la transacción: si Redis no responde en ese momento, o el servicio se cae a mitad de camino, el snippet queda `PENDING` sin un mensaje que lo vaya a lintear. Cada `linting.sweep.interval` (5 minutos) se vuelven a encolar los que llevan más de `linting.sweep.stuck-after` (10 minutos) `PENDING`; para eso cada snippet guarda desde cuándo está en su estado (`status_changed_at`). El formateo no tiene un estado pendiente ni este barrido: si se pierde un formateo, el snippet sigue con su código, que es válido, y se lo puede descargar formateado con las reglas nuevas (US13).

Los dos son idempotentes: se usa el estado actual, no el del momento en que se encoló, y formatear código ya formateado no lo cambia. Por eso da igual que un snippet se procese dos veces o en otro orden. Si se corta entre guardar el código formateado y encolarlo para lintear, el reintento lo encola igual, porque el snippet ya quedó `PENDING`.

## Versión nueva del parser

"Con cada versión nueva del parser del lenguaje, se deberán re-evaluar y validar todos los snippets."

1. Al arrancar (después de `parser.initial-delay`, 30 s) y cada `parser.check-interval` (5 minutos), snippet-service le pregunta a language-service con qué versión de la librería de PrintScript corre (`GET /version`).
2. Si no es la última con la que se validó, que queda guardada en la tabla `parser_version`, todos los snippets pasan a `PENDING` y se encolan en `lint-requests`.
3. El worker de linteo valida cada snippet antes de lintearlo: si el parser actual no lo acepta queda `INVALID`; si lo acepta, `COMPLIANT` o `NOT_COMPLIANT` como siempre.
4. Recién cuando quedaron todos encolados se guarda la versión nueva. Si algo falla antes, la próxima vuelta lo hace de nuevo: encolar dos veces no cambia nada, porque lintear es idempotente.

La primera vez que corre no hay versión guardada, así que se validan todos los snippets que ya estaban.

## Tests al actualizar un snippet (US16)

En la misma transacción que el update, todos los tests del snippet vuelven a `PENDING`: el resultado que tenían era del código anterior. Una vez guardado, se corren en otro hilo (`@Async`, con el executor de Spring Boot) y el PUT responde sin esperarlos. Cada test termina `PASSED` o `FAILED`; uno que no se pudo correr queda `PENDING` y no frena a los demás. La UI consulta los tests hasta que ninguno esté `PENDING`.

## Tareas programadas

Los dos workers de los streams, el barrido de pendientes, el chequeo de la versión del parser y los reportes a New Relic corren en un pool de `SCHEDULING_POOL_SIZE` hilos (4), así una tanda lenta de un worker no frena a los demás. Spring nunca corre dos vueltas de la misma tarea a la vez, y cada tarea tiene su propio estado.

## Correr en local

Necesita su Postgres, asset-service (con su Azurite) y Redis. Lo más simple es levantarlos desde el repo [`infra`](https://github.com/Ingsis-2026/infra) con `docker compose up -d snippet-db asset-service redis`.

```sh
./gradlew bootRun   # levanta en :8080
```

| Variable | Default |
|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5432` / `snippet` / `snippet` / `snippet` |
| `PERMISSION_URL` / `LANGUAGE_URL` | `http://localhost:8081` / `http://localhost:8082` |
| `ASSET_URL` / `ASSET_CONTAINER` | `http://localhost:8083` / `snippets` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |
| `HOSTNAME` | `snippet-service`. Es el nombre de la réplica en el consumer group; en Docker es el id del container. |
| `AUTH0_DOMAIN` / `AUTH0_AUDIENCE` | `dev-2fvmkxju8g70kqvs.us.auth0.com` / `https://snippet-searcher/api`. El tenant de Auth0 que emite los access tokens y el identifier de esta API en ese tenant. No son secretos. |
| `AUTH0_M2M_CLIENT_ID` / `AUTH0_M2M_CLIENT_SECRET` | Vacíos. La aplicación machine-to-machine de Auth0 del servicio: con `read:users` en la Management API busca con quién compartir (US7), y autorizada en la API interna pide el token con el que llama a permission-service y language-service. Sin ellos, `GET /users` responde 503 y a esos dos servicios los llama sin token (tienen que correr con `AUTH_ENABLED=false`). **El secret nunca se commitea.** |
| `AUTH0_INTERNAL_AUDIENCE` | `https://snippet-searcher/internal`. El identifier de la API interna en Auth0, la audience que validan permission-service y language-service. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173`. Origins de otros dominios que pueden llamar a la API desde el navegador, separados por comas. La UI desplegada no lo necesita: llama a `/api` en su mismo dominio. |
| `SCHEDULING_POOL_SIZE` | `4`. Cuántos hilos tienen las tareas programadas (ver [Tareas programadas](#tareas-programadas)). |
| `LINT_SWEEP_INTERVAL` / `LINT_SWEEP_STUCK_AFTER` | `5m` / `10m`. Cada cuánto se buscan snippets `PENDING` sin un mensaje que los vaya a lintear, y cuánto tienen que llevar así para volver a encolarlos. |

## Calidad

`./gradlew check` corre ktlint, los tests y exige 80% de cobertura. Lo único excluido de la cobertura es el `main()` de Spring.

Los tests no necesitan Docker: en vez de asset-service usan un store en memoria (`InMemoryContentStore`), y en vez de los streams de Redis unas colas en memoria (`RecordingLintQueue`, `RecordingFormatQueue`) que no procesan nada hasta que el test lo pide. El worker de Redis se prueba con Redis simulado (`RedisStreamsTest`).

## CI/CD

- **CI** (`ci.yml`): `./gradlew check` en cada push y PR a `main` o `dev`.
- **CD** (`cd.yml`): cuando CI pasa sobre un push, publica `ghcr.io/ingsis-2026/snippet-service:{dev|prod}` y `:sha-<commit>`. `dev` corresponde a dev y `main` a prod.
