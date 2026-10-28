# S1 — Catálogo de regiões: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** transformar o template `neovia-foundation-service` no `clima-service` e entregar `GET /api/clima/regioes`, `POST /api/clima/regioes/atualizar` e a validação de região, com cache Caffeine → Redis → SQL e fallback quando a Nimbus falha.

**Architecture:** layout `domain/app/infra/ui` em `br.com.neoviaengenharia.clima`.
- O `CatalogoRegioesService` (app) resolve o catálogo: L1/L2 (`CatalogoCache`), depois SQL (`RegiaoRepository`/`BuscaCatalogoRepository`), e por fim a Nimbus (`NimbusClient`).
- A ida à Nimbus acontece dentro de uma transação que trava a linha única de `catalogo_regioes_controle` (lock pessimista). Isso garante uma busca por janela entre instâncias, além do intervalo de 15 min depois de uma falha.
- O relógio é um `Clock` injetado.

**Tech Stack:** Java 17, Spring Boot 3.3.2, Spring Web/`RestClient`, Spring Data JPA, Flyway (`flyway-core` + `flyway-sqlserver`), SQL Server (prod), H2 modo `MSSQLServer` (testes), Caffeine, Spring Data Redis (Lettuce), springdoc 2.2.0, Lombok, JUnit 5, Testcontainers (Redis), OkHttp `mockwebserver`.

**Spec:** `docs/superpowers/specs/2026-10-15-clima-nimbus-regioes-spec.md` (inclui S1-FR-15 e a tabela `catalogo_regioes_controle`, decididas na fase de plano)

## Global Constraints

- Pacote raiz `br.com.neoviaengenharia.clima`; artifactId `clima-service`; `spring.application.name=clima-service`.
- Rotas sob `/api/clima/`. JSON com camelCase, instantes em ISO-8601 UTC (`2026-10-15T12:00:00Z`).
- Nimbus: base `https://cronos-api.nimbusmeteorologia.com.br` (`nimbus.base-url`), headers `api_key` = `NIMBUS_API_KEY`, `authorization` = `NIMBUS_AUTHORIZATION` (sem `Bearer`), query `integration=true`.
- Nenhuma credencial em código, `application.properties`, logs ou mensagens de erro. O datasource vem só de `SPRING_DATASOURCE_*`.
- Flyway: schema `clima`, tabela `flyway_schema_history_clima`, `spring.jpa.hibernate.ddl-auto=validate`, `hibernate.jdbc.time_zone=UTC`.
- Chaves de Redis com prefixo `clima:`. Chave do catálogo: `clima:regioes:catalogo`.
- Mensagens fixas: "Fonte de dados climáticos indisponível" (503), "Região não encontrada" (404), "Região inativa" (422).
- Formato de erro igual ao `Response` do pgo-api-service: `{"status": "<HttpStatus>", "message": "<texto>"}`.
- Validade do catálogo: 24 h desde `obtidoEm`. Intervalo após falha: 15 min.
- O SQL deve rodar igual em SQL Server e em H2 modo `MSSQLServer`: `BIGINT`, `INT`, `BIT`, `NVARCHAR(n)`, `DATETIME2`, `IDENTITY(1,1)`; nada de `sp_getapplock` nem T-SQL específico.
- Comandos: `./mvnw -q test` (suíte toda) e `./mvnw -q test -Dtest=<Classe>` (focado). O Docker precisa estar ativo (Redis via Testcontainers).

## Review Focus

1. **Nimbus fora com muitas requisições simultâneas.** Só uma tentativa a cada 15 min; os demais recebem o gravado com `desatualizado=true`, sem fila de requisições presas no lock além de um timeout. Teste: `CatalogoRegioesServiceIT.falhaRespeitaIntervaloDe15Minutos` (Task 5).
2. **Readiness do Kubernetes com o Redis fora.** O health indicator do Redis não pode derrubar o pod (S1-FR-12). Teste: `HealthIT.healthUpComRedisFora` (Task 1).
3. **Corpo da Nimbus com campo extra ou faltando.** Campo desconhecido é ignorado; campo obrigatório ausente torna o corpo inválido e nada é gravado parcialmente. Testes: `NimbusClientTest.ignoraCampoDesconhecido` e `NimbusClientTest.itemSemNomeEhCorpoInvalido` (Task 3).
4. **Mensagem de exceção com credencial.** Exceções do `RestClient` podem carregar headers; a exceção de indisponibilidade só leva status e tipo. Teste: `NimbusClientTest.mensagemDeErroNaoContemCredencial` (Task 3).
5. **Relógio na virada exata de 24 h.** `obtidoEm + 24h == agora` conta como vencido, porque a validade é `agora < obtidoEm + 24h`. Teste: `CatalogoSnapshotTest.venceExatamenteEm24h` (Task 2).

---

### Task 1: Esqueleto do clima-service

Transforma o template no serviço, sem regra de negócio. O arquivo `application.properties` versionado hoje tem um usuário e uma senha de SQL Server: eles saem neste task.

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Create: `src/test/resources/application-test.properties`
- Delete: `src/main/java/br/com/neoviaengenharia/foundation/**`, `src/test/java/br/com/neoviaengenharia/foundation/**`
- Create: `src/main/java/br/com/neoviaengenharia/clima/ClimaServiceApplication.java`
- Create: `src/main/java/br/com/neoviaengenharia/clima/infra/config/RelogioConfig.java`
- Create: `src/test/java/br/com/neoviaengenharia/clima/support/IntegracaoBase.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/HealthIT.java`

**Interfaces:**
- Produces:
  - bean `java.time.Clock` (UTC), que os testes substituem por `@TestConfiguration` com `Clock` mutável;
  - `IntegracaoBase`: classe abstrata `@SpringBootTest(webEnvironment = RANDOM_PORT)` `@ActiveProfiles("test")`, com Redis `GenericContainer("redis:7-alpine")` estático + `@ServiceConnection(name = "redis")`, e um `MockWebServer nimbus` estático cuja URL é registrada como `nimbus.base-url` via `@DynamicPropertySource`.

- [ ] **Step 1: Ajustar o `pom.xml`**
  - Renomear artifactId e name para `clima-service`, com descrição "Integração Neovia x Nimbus (API Cronos)".
  - Remover `mysql-connector-j` e `h2`.
  - Adicionar `spring-boot-starter-actuator`, `spring-boot-starter-validation`, `spring-boot-starter-data-redis`, `com.github.ben-manes.caffeine:caffeine`, `org.flywaydb:flyway-core` e `org.flywaydb:flyway-sqlserver`.
  - Adicionar em escopo de teste: `com.h2database:h2`, `org.springframework.boot:spring-boot-testcontainers`, `org.testcontainers:junit-jupiter` e `com.squareup.okhttp3:mockwebserver`. Se o Boot não gerenciar a versão do mockwebserver, fixar `4.12.0`.
  - Configurar o `maven-compiler-plugin` com os annotation processors do Lombok e do MapStruct, como em `pgo-api-service/pom.xml`.
  - Habilitar o failsafe ou incluir `*IT` no surefire, para que `./mvnw test` rode as classes `*IT`.
- [ ] **Step 2: Escrever o teste que falha**

```java
class HealthIT extends IntegracaoBase {
    @Test void healthUp() { /* GET /actuator/health → 200, $.status == "UP" */ }
}
@TestPropertySource(properties = "spring.data.redis.port=1")
class HealthComRedisForaIT extends IntegracaoBase {
    @Test void healthUpComRedisFora() { /* GET /actuator/health → 200, $.status == "UP" */ }
}
```

- [ ] **Step 3:** rodar `./mvnw -q test -Dtest='HealthIT,HealthComRedisForaIT'`. Esperado: FAIL na compilação (`IntegracaoBase`/`ClimaServiceApplication` inexistentes).
- [ ] **Step 4: Implementar a configuração**
  - `application.properties`:
    - `spring.application.name=clima-service`;
    - datasource `${SPRING_DATASOURCE_URL}`/`USERNAME`/`PASSWORD`, sem valor padrão;
    - JPA `validate` e `hibernate.jdbc.time_zone=UTC`;
    - Flyway com schema `clima` (`create-schemas=true`, `table=flyway_schema_history_clima`);
    - `spring.data.redis.host=${SPRING_DATA_REDIS_HOST:localhost}`, `port=${SPRING_DATA_REDIS_PORT:6379}`, `database=${SPRING_DATA_REDIS_DATABASE:0}`, `timeout=2s`;
    - `management.health.redis.enabled=false`;
    - `management.endpoint.health.probes.enabled=true`;
    - `nimbus.base-url=${NIMBUS_BASE_URL:https://cronos-api.nimbusmeteorologia.com.br}`, `nimbus.api-key=${NIMBUS_API_KEY}`, `nimbus.authorization=${NIMBUS_AUTHORIZATION}`, `nimbus.connect-timeout=5s`, `nimbus.read-timeout=10s`;
    - manter o springdoc do template.
  - `application-test.properties`: H2 `jdbc:h2:mem:clima;MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1` e `nimbus.api-key=chave-publica-teste`, `nimbus.authorization=chave-privada-teste`.
  - `RelogioConfig` expõe `Clock.systemUTC()`.
- [ ] **Step 5:** rodar `./mvnw -q test`. Esperado: PASS (2 testes, e nenhum teste antigo de ToDo).
- [ ] **Step 6:** `git rm -r` do pacote `foundation` (main e test) já feito no Step 4; conferir `git grep -n "<senha do sqladmin>\|sqladmin"`, que deve vir vazio.
- [ ] **Step 7:** commit `chore: transforma o template no esqueleto do clima-service`.

### Task 2: Domínio e persistência do catálogo

**Files:**
- Create: `src/main/resources/db/migration/V1__criar_catalogo_regioes.sql`
- Create: `src/main/java/br/com/neoviaengenharia/clima/domain/regiao/Regiao.java`, `RegiaoNimbus.java`, `CatalogoSnapshot.java`, `RegiaoResumo.java`, `ReconciliacaoCatalogo.java`
- Create: `src/main/java/br/com/neoviaengenharia/clima/infra/repo/RegiaoRepository.java`, `BuscaCatalogo.java`, `BuscaCatalogoRepository.java`, `ControleCatalogo.java`, `ControleCatalogoRepository.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/domain/regiao/ReconciliacaoCatalogoTest.java`, `CatalogoSnapshotTest.java`; `src/test/java/br/com/neoviaengenharia/clima/infra/repo/CatalogoPersistenciaIT.java`

**Interfaces:**
- Produces:
  - `record RegiaoNimbus(long regionId, String nome, String alias, boolean inativa, long estadoId)`, o item já validado vindo da Nimbus;
  - `@Entity Regiao`, tabela `regiao`: `regionId` (PK atribuída), `nome`, `alias`, `inativa`, `estadoId`, `removida`, `atualizadoEm`; métodos `atualizarCom(RegiaoNimbus, Instant)` e `marcarRemovida(Instant)`;
  - `record RegiaoResumo(long regionId, String nome, String alias, boolean inativa, long estadoId)`;
  - `record CatalogoSnapshot(List<RegiaoResumo> regioes, Instant obtidoEm)` com `Instant validoAte()` = `obtidoEm + 24h` e `boolean vigente(Instant agora)` = `agora.isBefore(validoAte())`. Lista só com regiões não removidas, ordenadas por `regionId`;
  - `ReconciliacaoCatalogo.aplicar(List<Regiao> gravadas, List<RegiaoNimbus> recebidas, Instant agora) -> List<Regiao>` (todas as linhas a salvar);
  - `@Entity BuscaCatalogo` (`catalogo_regioes_busca`: `id` IDENTITY, `obtidoEm`, `totalRegioes`) e `BuscaCatalogoRepository.findTopByOrderByObtidoEmDesc(): Optional<BuscaCatalogo>`;
  - `@Entity ControleCatalogo` (`catalogo_regioes_controle`: `id`=1, `ultimaFalhaEm` nullable) e `ControleCatalogoRepository.travar(): ControleCatalogo`, com `@Lock(PESSIMISTIC_WRITE)` e hint `jakarta.persistence.lock.timeout=15000`, buscando `id = 1`;
  - `RegiaoRepository.findAllByRemovidaFalseOrderByRegionId()`.

- [ ] **Step 1: Escrever os testes de unidade que falham**
  - `ReconciliacaoCatalogoTest.insereRegiaoNova`
  - `ReconciliacaoCatalogoTest.atualizaNomeAliasInativaEstado` (só pelo `regionId`)
  - `ReconciliacaoCatalogoTest.marcaRemovidaQuemNaoVeio` (S1-AC-06: 1359 e 1706 gravadas, chega só 1359 → 1706 com `removida=true`)
  - `ReconciliacaoCatalogoTest.reativaRemovidaQueVoltou`
  - `CatalogoSnapshotTest.vigenteAntesDe24h` (`obtidoEm` 2026-10-15T12:00Z, agora 2026-10-16T11:59:59Z → `true`)
  - `CatalogoSnapshotTest.venceExatamenteEm24h` (agora 2026-10-16T12:00Z → `false`)
- [ ] **Step 2:** rodar `./mvnw -q test -Dtest='ReconciliacaoCatalogoTest,CatalogoSnapshotTest'`. Esperado: FAIL na compilação.
- [ ] **Step 3: Implementar a migração, as entidades e a reconciliação**
  - Migração `V1`: as três tabelas da spec §6, mais `INSERT INTO catalogo_regioes_controle (id, ultima_falha_em) VALUES (1, NULL)`. Todas as colunas `DATETIME2` guardam UTC.
- [ ] **Step 4:** rodar o mesmo comando. Esperado: PASS.
- [ ] **Step 5: Escrever o teste de persistência que falha (`@DataJpaTest` + profile `test`, com Flyway ativo)**
  - `CatalogoPersistenciaIT.migracaoValidaContraEntidades` (contexto sobe com `ddl-auto=validate`)
  - `CatalogoPersistenciaIT.ultimaBuscaEhAMaisRecente`
  - `CatalogoPersistenciaIT.controleTemLinhaUnica` (`travar()` devolve `id=1` dentro de uma transação)
- [ ] **Step 6:** rodar `-Dtest=CatalogoPersistenciaIT`. Esperado: FAIL, depois PASS após ajustar os repositórios e entidades.
- [ ] **Step 7:** commit `feat: modelo e persistência do catálogo de regiões`.

### Task 3: Cliente da Nimbus (regiões)

**Files:**
- Create: `src/main/java/br/com/neoviaengenharia/clima/infra/nimbus/NimbusProperties.java`, `NimbusConfig.java`, `NimbusClient.java`, `NimbusIndisponivelException.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/infra/nimbus/NimbusClientTest.java`

**Interfaces:**
- Consumes: `RegiaoNimbus` (Task 2).
- Produces:
  - `@ConfigurationProperties("nimbus") record NimbusProperties(URI baseUrl, String apiKey, String authorization, Duration connectTimeout, Duration readTimeout)`, com `@Validated @NotBlank` nas chaves;
  - `NimbusClient.listarRegioes(): List<RegiaoNimbus>`;
  - `NimbusIndisponivelException extends RuntimeException` com `Motivo motivo()`. O enum `Motivo` tem `TIMEOUT`, `REDE`, `ERRO_HTTP`, `CREDENCIAL_RECUSADA` e `CORPO_INVALIDO`, e a exceção guarda também `Integer status()`. A mensagem é só "Nimbus indisponível: <motivo> [status]", sem a causa original.

- [ ] **Step 1: Escrever os testes que falham (MockWebServer, `NimbusClient` construído direto com `NimbusProperties`)**
  - `enviaHeadersEIntegration` (S1-AC-12): a requisição recebida tem path `/forecast-regions?integration=true`, `api_key: chave-publica-teste` e `authorization: chave-privada-teste` exatos, e o header `authorization` não começa com "Bearer".
  - `mapeiaRegioes`: o JSON da doc (1359 e 1706) vira 2 `RegiaoNimbus` com `nome`, `alias`, `inativa=false` e `estadoId=25`.
  - `ignoraCampoDesconhecido`: um item com `"extra": 1` ainda é mapeado.
  - `itemSemNomeEhCorpoInvalido`: `Motivo.CORPO_INVALIDO`.
  - `itemSemRegionIdEhCorpoInvalido`: `CORPO_INVALIDO`.
  - `listaVaziaEhCorpoInvalido` (S1-FR-14): `[]` vira `CORPO_INVALIDO`.
  - `status401` e `status403`: `CREDENCIAL_RECUSADA`, com `status()` 401/403.
  - `status503`: `ERRO_HTTP`.
  - `timeout`: com `readTimeout=200ms` e resposta atrasada em 1 s, `TIMEOUT`.
  - `mensagemDeErroNaoContemCredencial`: para 401 e timeout, `getMessage()` e `toString()` não contêm `chave-publica-teste` nem `chave-privada-teste`, e `getCause()` é `null`.
  - `logNaoContemCredencial` (`OutputCaptureExtension`): depois de uma chamada 200 e outra 401, a saída não contém nenhuma das duas chaves, e contém `forecast-regions` e o status.
- [ ] **Step 2:** rodar `-Dtest=NimbusClientTest`. Esperado: FAIL na compilação.
- [ ] **Step 3: Implementar `NimbusConfig` e `NimbusClient`**
  - `NimbusConfig` cria um `RestClient` com `SimpleClientHttpRequestFactory` (timeouts de `NimbusProperties`), `baseUrl` e os dois headers padrão. Siga o padrão de `pgo-api-service/.../infra/config/ClimaConfig.java:16`.
  - `NimbusClient` lê a resposta como DTO interno com `@JsonProperty` em snake_case (`region_id`, `name`, `alias`, `inactive`, `state_id`), com campos boxed para detectar ausência e `@JsonIgnoreProperties(ignoreUnknown = true)`.
  - Validação: lista vazia ou qualquer campo nulo → `CORPO_INVALIDO`.
  - Erros de rede e de timeout: `ResourceAccessException` com `SocketTimeoutException` na cadeia vira `TIMEOUT`; as demais viram `REDE`.
  - Log INFO: `nimbus GET /forecast-regions status={} duracaoMs={}`. Log ERROR em 401/403: `Nimbus recusou as credenciais (status={})`.
- [ ] **Step 4:** rodar `-Dtest=NimbusClientTest`. Esperado: PASS.
- [ ] **Step 5:** commit `feat: cliente da Nimbus para o catálogo de regiões`.

### Task 4: Cache multinível do catálogo

**Files:**
- Create: `src/main/java/br/com/neoviaengenharia/clima/infra/cache/CacheConfig.java`, `CatalogoCache.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/infra/cache/CatalogoCacheIT.java`

**Interfaces:**
- Consumes: `CatalogoSnapshot` (Task 2).
- Produces:
  - `CatalogoCache.obter(): Optional<CatalogoSnapshot>`: L1 primeiro; no L2, promove para o L1. Só devolve snapshot `vigente(clock.instant())`.
  - `CatalogoCache.guardar(CatalogoSnapshot)`: grava no L1 e no L2 (L2 com TTL = `validoAte - agora`, chave `clima:regioes:catalogo`, valor JSON via `ObjectMapper` com `JavaTimeModule`).
  - Qualquer exceção do Redis em `obter`/`guardar` vira log WARN `Falha no cache L2 (Redis): {}` e segue sem L2.

- [ ] **Step 1: Escrever os testes que falham (extends `IntegracaoBase`, `Clock` mutável)**
  - `guardaEDevolveDoL1`
  - `promoveDoL2`: guarda, limpa o L1 (método package-private `limparL1()` só para teste) e `obter()` ainda devolve o snapshot.
  - `naoDevolveVencido`: guarda com `obtidoEm` = agora; avança o relógio 24 h; `obter()` vem vazio.
  - `redisForaNaoLancaEloga` (`@TestPropertySource(properties = "spring.data.redis.port=1")` em classe aninhada ou separada, `OutputCaptureExtension`): `guardar` e `obter` não lançam exceção, e a saída contém "Falha no cache L2".
- [ ] **Step 2:** rodar `-Dtest=CatalogoCacheIT`. Esperado: FAIL.
- [ ] **Step 3: Implementar**
  - Caffeine de 1 entrada, com `Expiry` por entrada até `validoAte`.
  - `StringRedisTemplate` com o `ObjectMapper` do Spring.
- [ ] **Step 4:** rodar o mesmo. Esperado: PASS.
- [ ] **Step 5:** commit `feat: cache Caffeine → Redis do catálogo de regiões`.

### Task 5: Serviço do catálogo e validação de região

**Files:**
- Create: `src/main/java/br/com/neoviaengenharia/clima/app/regiao/CatalogoRegioesService.java`, `CatalogoResultado.java`, `ValidacaoRegiao.java`, `MotivoRecusa.java`, `FonteIndisponivelException.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/app/regiao/CatalogoRegioesServiceIT.java`, `ValidacaoRegiaoIT.java`

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces:
  - `record CatalogoResultado(CatalogoSnapshot snapshot, boolean desatualizado)`;
  - `CatalogoRegioesService.obter(): CatalogoResultado` e `CatalogoRegioesService.atualizar(): CatalogoResultado`;
  - `FonteIndisponivelException extends RuntimeException` ("Fonte de dados climáticos indisponível");
  - `enum MotivoRecusa { NAO_ENCONTRADA, INATIVA }`;
  - `ValidacaoRegiao.verificar(long regionId): Optional<MotivoRecusa>` (vazio = região ativa), que S2 e S4 vão usar.

Algoritmo de `obter()`. Ele é o que os testes não determinam sozinhos:

```
1. cache.obter() presente → (snapshot, false)
2. sql = snapshot do SQL (findAllByRemovidaFalse… + última BuscaCatalogo); se sql.vigente(agora) → cache.guardar(sql); (sql, false)
3. @Transactional (TransactionTemplate): controle = controleRepo.travar()
   3a. relê a última busca; se vigente → guardar no cache e (sql, false)   // outra instância buscou
   3b. se controle.ultimaFalhaEm != null && agora < ultimaFalhaEm + 15min → fallback()
   3c. try nimbus.listarRegioes(): reconcilia, salva regiões, insere BuscaCatalogo(agora, n),
       ultimaFalhaEm = null, commit, cache.guardar(novo) → (novo, false)
       catch NimbusIndisponivelException: ultimaFalhaEm = agora, commit → fallback()
fallback(): existe busca gravada → (snapshot do SQL, true); senão → FonteIndisponivelException
```

`atualizar()` faz os passos 2 e 3, ignorando o L1/L2. `ValidacaoRegiao.verificar` usa `obter()`: ausente da lista → `NAO_ENCONTRADA`, `inativa` → `INATIVA`.

- [ ] **Step 1: Escrever os testes que falham (extends `IntegracaoBase`, `Clock` mutável, MockWebServer com a contagem de requisições conferida em cada teste)**
  - `primeiraBuscaGravaEResponde` (S1-AC-02)
  - `catalogoEmCacheNaoChamaNimbus` (S1-AC-01: busca às 12:00Z, relógio 14:00Z, 0 requisições novas)
  - `catalogoVencidoBuscaDeNovo` (S1-AC-03: relógio +25 h, 1 requisição, `obtidoEm` novo)
  - `atualizarDentroDe24hNaoChama` (S1-AC-04)
  - `removidaSaiDaLista` (S1-AC-06)
  - `inativaApareceNaLista` (S1-AC-05: 1706 com `inactive=true` → `inativa=true` no snapshot)
  - `redisForaServeDoSql` (S1-AC-11, `spring.data.redis.port=1`: catálogo gravado há 2 h no SQL → resultado normal, 0 requisições, log "Falha no cache L2")
  - `nimbusForaComCatalogo` (S1-AC-08: `desatualizado=true`, `obtidoEm` antigo)
  - `nimbusForaSemCatalogo` (S1-AC-09: `FonteIndisponivelException`)
  - `credencialRecusadaLogaErro` (S1-AC-10, `OutputCaptureExtension`: "recusou as credenciais", sem as chaves)
  - `listaVaziaNaoGravaEMarcaDesatualizado` (S1-AC-14)
  - `falhaRespeitaIntervaloDe15Minutos` (S1-FR-15): falha às 12:00Z; às 12:10Z, 0 requisições e `desatualizado=true`; às 12:16Z, 1 requisição.
  - `concorrenciaFazUmaBusca` (S1-AC-13): 8 threads chamam `obter()` com o catálogo vencido; o MockWebServer responde com 300 ms de atraso; exatamente 1 requisição.
  - `ValidacaoRegiaoIT.verificaAtivaInativaRemovidaInexistente` (S1-AC-07: 1359 → vazio; 1706 → `INATIVA`; 1800 → `NAO_ENCONTRADA`; 99999 → `NAO_ENCONTRADA`)
- [ ] **Step 2:** rodar `-Dtest='CatalogoRegioesServiceIT,ValidacaoRegiaoIT'`. Esperado: FAIL.
- [ ] **Step 3:** implementar seguindo o algoritmo acima. Cada teste deve limpar as tabelas e o cache entre execuções (`@BeforeEach`).
- [ ] **Step 4:** rodar o mesmo. Esperado: PASS.
- [ ] **Step 5:** commit `feat: serviço do catálogo de regiões com fallback e validação`.

### Task 6: API REST e tratamento de erros

**Files:**
- Create: `src/main/java/br/com/neoviaengenharia/clima/ui/rest/RegioesController.java`
- Create: `src/main/java/br/com/neoviaengenharia/clima/ui/dto/CatalogoRegioesResponse.java`, `RegiaoResponse.java`, `Response.java`
- Create: `src/main/java/br/com/neoviaengenharia/clima/ui/exceptions/ExceptionManager.java`, `RegiaoRecusadaException.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/ui/rest/RegioesControllerTest.java`, `src/test/java/br/com/neoviaengenharia/clima/ui/rest/RegioesApiIT.java`

**Interfaces:**
- Consumes: `CatalogoRegioesService`, `CatalogoResultado`, `FonteIndisponivelException`, `MotivoRecusa`.
- Produces:
  - `record CatalogoRegioesResponse(List<RegiaoResponse> regioes, Instant obtidoEm, boolean desatualizado)`;
  - `record RegiaoResponse(long regionId, String nome, String alias, boolean inativa, long estadoId)`;
  - `RegiaoRecusadaException(MotivoRecusa)`, que o `ExceptionManager` mapeia para `NAO_ENCONTRADA` → 404 "Região não encontrada" e `INATIVA` → 422 "Região inativa". S2 e S4 vão usá-la;
  - `FonteIndisponivelException` → 503 "Fonte de dados climáticos indisponível";
  - `Exception` → 500 "Erro inesperado no clima-service.", com log ERROR e sem detalhes na resposta.

- [ ] **Step 1: Escrever os testes que falham**
  - `RegioesControllerTest` (`@WebMvcTest`, serviço mockado):
    - `getListaRegioes`: corpo exatamente no formato da spec §5.1, com `obtidoEm` `"2026-10-15T12:00:00Z"`.
    - `postAtualizarMesmoFormato`
    - `fonteIndisponivel503`: `{"status":"SERVICE_UNAVAILABLE","message":"Fonte de dados climáticos indisponível"}`.
    - `regiaoRecusada404e422`: endpoint de teste dentro do teste, ou chamada direta ao `ExceptionManager`.
    - `erroInesperado500SemDetalhes`
  - `RegioesApiIT` (extends `IntegracaoBase`): `GET /api/clima/regioes` ponta a ponta com MockWebServer → 200 com 2 regiões; e `/api-docs` contém `/api/clima/regioes` e `/api/clima/regioes/atualizar` (S1-FR-13).
- [ ] **Step 2:** rodar `-Dtest='RegioesControllerTest,RegioesApiIT'`. Esperado: FAIL.
- [ ] **Step 3: Implementar**
  - Controller com `@Operation`/`@ApiResponse` (200, 503), no estilo do `ToDoListController` do template.
  - Jackson com `WRITE_DATES_AS_TIMESTAMPS=false` (`spring.jackson.serialization.write-dates-as-timestamps=false`).
- [ ] **Step 4:** rodar o mesmo. Esperado: PASS.
- [ ] **Step 5:** commit `feat: endpoints REST do catálogo de regiões`.

### Task 7: Deploy (Docker, CI e Kubernetes)

**Files:**
- Modify: `Dockerfile`
- Modify: `.github/workflows/dev.yml`, `.github/workflows/qa.yml`, `.github/workflows/main.yml`
- Create: `k8s/02-configmap.yaml`, `k8s/03-serviceaccount.yaml`, `k8s/04-deployment.yaml`, `k8s/05-service.yaml`, `k8s/07-networkpolicy.yaml`
- Test: `src/test/java/br/com/neoviaengenharia/clima/DeployManifestosTest.java`

- [ ] **Step 1: Escrever o teste que falha** (lê os YAMLs com o SnakeYAML do Boot)
  - `deploymentLeNimbusDoSecret`: env `NIMBUS_API_KEY`/`NIMBUS_AUTHORIZATION` via `secretKeyRef`, Secret `clima-service-nimbus`; datasource e Redis via `secretKeyRef` do Secret `secret` (`SPRING_DATASOURCE_*`, `SPRING_DATA_REDIS_HOST`/`PORT`/`DATABASE`).
  - `nenhumValorLiteralDeCredencial`: nenhum `env` com nome `NIMBUS_*` ou `SPRING_DATASOURCE_PASSWORD` tem `value:` literal.
  - `serviceEhClusterIP`
  - `semIngress`: nenhum arquivo em `k8s/` tem `kind: Ingress`.
  - `workflowsPublicamClimaService`: os 3 workflows usam `/clima-service:` e não contêm `service-api`.
- [ ] **Step 2:** rodar `-Dtest=DeployManifestosTest`. Esperado: FAIL.
- [ ] **Step 3: Escrever os manifests, o Dockerfile e os workflows**
  - Manifests no padrão de `pgo-api-service/k8s/*` (`04-deployment.yaml:60-78` para o env, probes `/actuator/health/liveness|readiness`, `securityContext` não root, `readOnlyRootFilesystem` + `emptyDir /tmp`).
  - Imagem `neoviaportaldevregistry.azurecr.io/clima-service`.
  - Egress: DNS, 1433, 443 e 6379. Ingress: pods do namespace `default`.
  - Dockerfile com estágio de execução `eclipse-temurin:17-jre-alpine` e `USER 1000:1000`, já que o deployment exige `runAsNonRoot`.
  - Workflows: trocar `service-api` por `clima-service` e o nome do `main.yml` para "Build and push clima-service".
- [ ] **Step 4:** rodar `./mvnw -q test` (suíte toda). Esperado: PASS. Rodar também `docker build -t clima-service:local .`, que deve terminar com sucesso.
- [ ] **Step 5:** commit `chore: manifests Kubernetes, Dockerfile não root e CI do clima-service`.
