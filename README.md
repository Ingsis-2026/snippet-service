# snippet-service

Parte de **Snippet Searcher** (Ingeniería de Sistemas 2026). Es el único servicio expuesto hacia afuera: la UI habla solo con él, a través de nginx en `/api/`.

## De qué es dueño

- **Metadata de cada snippet** (nombre, descripción, lenguaje, versión, owner) en su Postgres. El código fuente no vive acá: se guarda en `asset-service`.
- **Tests de cada snippet**: inputs y outputs esperados (US8). Correrlos es trabajo de `language-service`.
- **Reglas de lint y formato elegidas por cada usuario** (US11, US14). Se le pasan a `language-service` en cada llamada.
- **Estado de validez de cada snippet** (`PENDING` / `COMPLIANT` / `NOT_COMPLIANT`), persistido, para poder filtrar y ordenar por él (US5) y para re-evaluarlo cuando cambian las reglas (US15).
- **Productor de Redis Streams** para el formateo, el linteo y los tests automáticos (US12, US15, US16). Lo publica él porque es el único que sabe cuáles son *todos* los snippets de un usuario.
- **Validación del JWT de Auth0.** Los demás servicios quedan en la red interna.

## De qué no es dueño

- Quién puede ver qué snippet: eso es `permission-service`, y se le consulta.
- Parsear, ejecutar, formatear o lintear código: eso es `language-service`.

## Correr en local

Necesita Postgres y Redis. Lo más simple es levantarlos desde el repo [`infra`](https://github.com/Ingsis-2026/infra).

```sh
./gradlew bootRun
```

| Variable | Default |
|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5432` / `snippet` / `snippet` / `snippet` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |
| `AUTH0_ISSUER_URI` | — (formato `https://<tenant>.auth0.com/`) |
| `AUTH0_AUDIENCE` | — |
| `PERMISSION_URL` / `LANGUAGE_URL` / `ASSET_URL` | `http://localhost:8081` / `:8082` / `:8083` |

## Calidad

`./gradlew check` corre ktlint, los tests y exige 80% de cobertura. Lo único excluido de la cobertura es el `main()` de Spring.

## CI/CD

- **CI** (`ci.yml`): `./gradlew check` en cada push y PR a `main` o `dev`.
- **CD** (`cd.yml`): cuando CI pasa sobre un push, publica `ghcr.io/ingsis-2026/snippet-service:{dev|prod}` y `:sha-<commit>`. `dev` va a dev y `main` a prod. El redeploy por SSH queda apagado hasta que exista la variable `DEPLOY_ENABLED=true`.
