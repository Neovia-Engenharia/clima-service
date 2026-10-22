package br.com.neoviaengenharia.clima.infra.nimbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.neoviaengenharia.clima.domain.observado.Estacao;
import br.com.neoviaengenharia.clima.domain.observado.HoraNimbus;
import br.com.neoviaengenharia.clima.domain.observado.LeituraHora;
import br.com.neoviaengenharia.clima.infra.nimbus.NimbusIndisponivelException.Motivo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class NimbusObservadoClientTest {

    private static final String PUBLICA = "chave-publica-teste";
    private static final String PRIVADA = "chave-privada-teste";
    private static final Instant INICIO = Instant.parse("2026-09-23T00:00:00Z"); // 1790121600000
    private static final Instant FIM = Instant.parse("2026-09-24T00:00:00Z"); // 1790208000000
    private static final String ITEM_DOC = """
            {"datetime":1790121600000,"period":"Wed, 23 Sep 2026 00:00:00 GMT","station_id":3078,
             "station_name":"Prudentópolis","station_code":"mg_h-823","precipitation_sum":0,"precipitation_max":0,
             "precipitation_max_datetime":1790121600000,"precipitation_first":null,
             "precipitation_first_datetime":null,"is_interpolated":false,"aggregation":"hourly"}""";

    private MockWebServer server;

    @BeforeEach
    void subir() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void descer() throws IOException {
        server.shutdown();
    }

    private NimbusClient cliente(Duration readTimeout) {
        NimbusProperties props = new NimbusProperties(server.url("/").uri(), PUBLICA, PRIVADA,
                Duration.ofSeconds(2), readTimeout);
        return new NimbusClient(NimbusConfig.criarRestClient(props), new ObjectMapper());
    }

    private List<HoraNimbus> buscar() {
        return cliente(Duration.ofSeconds(5)).buscarObservado(1674, INICIO, FIM);
    }

    private static String corpo(long regionId, String data) {
        return """
                {"region_id":%d,"variable":"precipitation","period":{"start":1790121600000,"end":1790208000000},
                 "data":%s,"metadata":{"total_points":1,"stations_used":[3078]}}""".formatted(regionId, data);
    }

    private void responder(int status, String corpo) {
        server.enqueue(new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(corpo));
    }

    private void assertMotivo(Motivo motivo, Integer status) {
        assertThatThrownBy(this::buscar).isInstanceOfSatisfying(NimbusIndisponivelException.class, e -> {
            assertThat(e.motivo()).isEqualTo(motivo);
            assertThat(e.status()).isEqualTo(status);
        });
    }

    @Test
    void enviaQueryEHeaders() throws InterruptedException {
        responder(200, corpo(1674, "[]"));
        buscar();
        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/manage/representative-stations/1674/data-consistency"
                + "?start_date=1790121600000&end_date=1790208000000&aggregation=hourly&variable=precipitation");
        assertThat(req.getHeader("api_key")).isEqualTo(PUBLICA);
        assertThat(req.getHeader("authorization")).isEqualTo(PRIVADA);
    }

    @Test
    void traduzCampos() {
        responder(200, corpo(1674, "[" + ITEM_DOC + "]"));
        assertThat(buscar()).containsExactly(new HoraNimbus(INICIO, new LeituraHora(
                new Estacao(3078, "Prudentópolis", "mg_h-823"), BigDecimal.ZERO, BigDecimal.ZERO, INICIO,
                null, null, false)));
    }

    @Test
    void ignoraPeriodMetadataECampoDesconhecido() {
        responder(200, corpo(1674, "[" + ITEM_DOC.replace("\"aggregation\"", "\"extra\":1,\"aggregation\"") + "]"));
        assertThat(buscar()).hasSize(1);
    }

    @Test
    void dataVaziaEhListaVazia() {
        responder(200, corpo(1674, "[]"));
        assertThat(buscar()).isEmpty();
    }

    @Test
    void regionIdDivergenteNaRaizEhCorpoInvalido() {
        responder(200, corpo(1675, "[" + ITEM_DOC + "]"));
        assertMotivo(Motivo.CORPO_INVALIDO, null);
    }

    @Test
    void regionIdDivergenteNoItemEhCorpoInvalido() {
        responder(200, corpo(1674, "[" + ITEM_DOC.replace("{\"datetime\"", "{\"region_id\":1675,\"datetime\"") + "]"));
        assertMotivo(Motivo.CORPO_INVALIDO, null);
    }

    @Test
    void semDataEhCorpoInvalido() {
        responder(200, "{\"region_id\":1674,\"variable\":\"precipitation\"}");
        assertMotivo(Motivo.CORPO_INVALIDO, null);
    }

    @Test
    void itemSemDatetimeEhCorpoInvalido() {
        responder(200, corpo(1674, "[" + ITEM_DOC.replace("\"datetime\":1790121600000,", "") + "]"));
        assertMotivo(Motivo.CORPO_INVALIDO, null);
    }

    @Test
    void itemSemStationIdEhCorpoInvalido() {
        responder(200, corpo(1674, "[" + ITEM_DOC.replace("\"station_id\":3078,", "") + "]"));
        assertMotivo(Motivo.CORPO_INVALIDO, null);
    }

    @Test
    void corpoNaoJsonEhCorpoInvalido() {
        responder(200, "<html>oops</html>");
        assertMotivo(Motivo.CORPO_INVALIDO, null);
    }

    @Test
    void status404EhRegiaoRecusada() {
        responder(404, "{}");
        assertThatThrownBy(this::buscar).isInstanceOf(NimbusRegiaoRecusadaException.class)
                .hasMessage("Nimbus recusou a região 1674");
    }

    @Test
    void status401e403(CapturedOutput saida) {
        responder(401, "{}");
        assertMotivo(Motivo.CREDENCIAL_RECUSADA, 401);
        responder(403, "{}");
        assertMotivo(Motivo.CREDENCIAL_RECUSADA, 403);
        assertThat(saida.getAll()).contains("ERROR").contains("recusou as credenciais");
    }

    @Test
    void status503() {
        responder(503, "{}");
        assertMotivo(Motivo.ERRO_HTTP, 503);
    }

    @Test
    void timeout() {
        server.enqueue(new MockResponse().setBody(corpo(1674, "[]")).setBodyDelay(1, TimeUnit.SECONDS));
        assertThatThrownBy(() -> cliente(Duration.ofMillis(200)).buscarObservado(1674, INICIO, FIM))
                .isInstanceOfSatisfying(NimbusIndisponivelException.class,
                        e -> assertThat(e.motivo()).isEqualTo(Motivo.TIMEOUT));
    }

    @Test
    void logTemRegiaoIntervaloStatusEDuracaoSemCredencial(CapturedOutput saida) {
        responder(200, corpo(1674, "[]"));
        buscar();
        responder(503, "{}");
        assertThatThrownBy(this::buscar).isInstanceOf(NimbusIndisponivelException.class);
        assertThat(saida.getAll()).contains("data-consistency").contains("regiao=1674")
                .contains("1790121600000").contains("1790208000000").contains("status=200").contains("status=503")
                .contains("duracaoMs").doesNotContain(PUBLICA).doesNotContain(PRIVADA);
    }
}
