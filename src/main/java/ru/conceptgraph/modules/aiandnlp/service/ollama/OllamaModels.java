package ru.conceptgraph.modules.aiandnlp.service.ollama;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;

/**
 * Модели Ollama: какие уже скачаны, скачивание новой и запоминание выбранной.
 * Выбор хранится в папке пользователя (~/.conceptgraph/ollama.properties), а не в application.properties:
 * тот лежит внутри программы, и у каждого из команды своё железо и своя модель.
 */
public final class OllamaModels {

    /** Прогресс скачивания: что делает Ollama и доля от 0 до 1 (-1 — неизвестно) */
    public interface PullProgress {
        void update(String status, double fraction);
    }

    private static final Path SETTINGS_FILE =
            Path.of(System.getProperty("user.home"), ".conceptgraph", "ollama.properties");

    private static final String OLLAMA_SETUP_URL = "https://ollama.com/download/OllamaSetup.exe";

    private final String ollamaUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public OllamaModels(String ollamaUrl) {
        this.ollamaUrl = ollamaUrl.endsWith("/") ? ollamaUrl.substring(0, ollamaUrl.length() - 1) : ollamaUrl;
    }

    /** Модель, которую пользователь выбрал в окне, или null, если ещё не выбирал */
    public static String savedModel() {
        if (!Files.exists(SETTINGS_FILE)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(SETTINGS_FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            return null;
        }
        String model = properties.getProperty("ollama.model", "").trim();
        return model.isEmpty() ? null : model;
    }

    public static void saveModel(String model) throws IOException {
        Files.createDirectories(SETTINGS_FILE.getParent());
        Properties properties = new Properties();
        properties.setProperty("ollama.model", model);
        try (Writer writer = Files.newBufferedWriter(SETTINGS_FILE, StandardCharsets.UTF_8)) {
            properties.store(writer, "Модель Ollama, выбранная в окне «Модель Ollama»");
        }
    }

    /**
     * Пробует запустить Ollama («ollama serve») и ждёт до 15 секунд, пока она ответит.
     * false — Ollama не установлена или так и не ответила.
     */
    public boolean tryStart() throws InterruptedException {
        if (!startServe("ollama")) {
            // Сразу после установки PATH у нашей программы ещё старый — ищем ollama.exe там, куда её ставит установщик
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null
                    || !startServe(Path.of(localAppData, "Programs", "Ollama", "ollama.exe").toString())) {
                return false; // не установлена
            }
        }
        for (int i = 0; i < 30; i++) {
            try {
                installed();
                return true;
            } catch (IOException e) {
                Thread.sleep(500); // ещё запускается
            }
        }
        return false;
    }

    private static boolean startServe(String command) {
        try {
            new ProcessBuilder(command, "serve")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Автоустановка есть только для Windows: там у Ollama обычный установщик */
    public static boolean canInstallOllama() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Скачивает установщик Ollama для Windows и запускает его без вопросов (/SILENT:
     * видно только маленькое окно с прогрессом). Возвращается, когда установка закончилась.
     */
    public void installOllama(PullProgress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Path installer = Files.createTempFile("OllamaSetup", ".exe");
        HttpClient downloader = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL) // ссылка ведёт на GitHub
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_SETUP_URL))
                .GET()
                .build();
        HttpResponse<InputStream> response = downloader.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("ollama.com ответил " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = response.body();
             var out = Files.newOutputStream(installer)) {
            byte[] buffer = new byte[1 << 16];
            long done = 0;
            int read;
            long lastReport = 0;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) {
                    throw new InterruptedException("Скачивание отменено");
                }
                out.write(buffer, 0, read);
                done += read;
                if (done - lastReport > (1 << 20)) { // не чаще раза в мегабайт
                    lastReport = done;
                    progress.update("Скачиваю Ollama", total > 0 ? (double) done / total : -1);
                }
            }
        }

        progress.update("Устанавливаю Ollama", -1);
        Process setup = new ProcessBuilder(installer.toString(), "/SILENT", "/NORESTART").start();
        int code = setup.waitFor();
        Files.deleteIfExists(installer);
        if (code != 0) {
            throw new IOException("установщик Ollama завершился с кодом " + code);
        }
    }

    /** Имена скачанных моделей (GET /api/tags), например «qwen2.5:7b» */
    public List<String> installed() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Ollama ответила " + response.statusCode());
        }
        List<String> names = new ArrayList<>();
        JsonArray models = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("models");
        if (models != null) {
            for (JsonElement model : models) {
                names.add(model.getAsJsonObject().get("name").getAsString());
            }
        }
        return names;
    }

    /**
     * Скачивает модель (POST /api/pull). Ollama присылает строки JSON с прогрессом,
     * последняя — {"status":"success"}. Долго: несколько ГБ, поэтому вызывать не из потока окна.
     *
     * @param cancelled вернёт true — скачивание прерывается
     */
    public void pull(String model, PullProgress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", true);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/pull"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            if (response.statusCode() != 200) {
                throw new IOException("Ollama ответила " + response.statusCode() + ": " + reader.readLine());
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled.getAsBoolean()) {
                    throw new InterruptedException("Скачивание отменено");
                }
                if (line.isBlank()) {
                    continue;
                }
                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                if (event.has("error")) {
                    throw new IOException(event.get("error").getAsString());
                }
                String status = event.has("status") ? event.get("status").getAsString() : "";
                double fraction = -1;
                if (event.has("total") && event.has("completed")) {
                    long total = event.get("total").getAsLong();
                    if (total > 0) {
                        fraction = event.get("completed").getAsDouble() / total;
                    }
                }
                progress.update(status, fraction);
                if ("success".equals(status)) {
                    return;
                }
            }
        }
        throw new IOException("Ollama оборвала скачивание");
    }
}
