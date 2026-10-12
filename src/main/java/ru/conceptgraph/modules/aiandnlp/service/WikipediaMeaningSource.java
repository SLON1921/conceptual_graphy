package ru.conceptgraph.modules.aiandnlp.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Варианты значений слова из русской Википедии: поиск статей + первые два предложения каждой.
 *
 * <p>Один запрос на слово (generator=search + prop=extracts), ответы кэшируются в памяти.
 * После первой сетевой ошибки источник выключается до конца запуска, чтобы без интернета
 * анализ не ждал таймаут на каждом слове.</p>
 *
 * <p>Это временная реализация в модуле NLP. Когда WikipediaClient (инфраструктура) научится
 * искать варианты статей, этот класс можно заменить обёрткой над ним.</p>
 */
public class WikipediaMeaningSource implements MeaningSource {

    private static final String API = "https://ru.wikipedia.org/w/api.php"
            + "?action=query&format=json&generator=search&gsrlimit=6"
            + "&prop=extracts|info&exintro=1&explaintext=1&exsentences=2&exlimit=max&gsrsearch=";
    private static final String LINKS_API = "https://ru.wikipedia.org/w/api.php"
            + "?action=query&format=json&prop=links&pllimit=max&titles=";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, List<HomonymResolver.Meaning>> cache = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    @Override
    public List<HomonymResolver.Meaning> findMeanings(String word) {
        if (!available || word == null || word.isBlank()) {
            return List.of();
        }
        return cache.computeIfAbsent(word.toLowerCase(), k -> request(word));
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Число внутренних ссылок статьи (одна страница ответа API, до 500 ссылок —
     * как и в WikipediaClient). Нужно только для заполнения wiki_cache.
     */
    @Override
    public int countLinks(String title) {
        String body = get(LINKS_API + URLEncoder.encode(title, StandardCharsets.UTF_8), title);
        if (body == null) {
            return 0;
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("query") || !root.getAsJsonObject("query").has("pages")) {
            return 0;
        }
        for (Map.Entry<String, JsonElement> page : root.getAsJsonObject("query").getAsJsonObject("pages").entrySet()) {
            JsonObject data = page.getValue().getAsJsonObject();
            if (data.has("links")) {
                return data.getAsJsonArray("links").size();
            }
        }
        return 0;
    }

    private List<HomonymResolver.Meaning> request(String word) {
        String body = get(API + URLEncoder.encode(word.trim(), StandardCharsets.UTF_8), word);
        return body == null ? List.of() : parse(body);
    }

    /** GET-запрос к API; null при ошибке. После сетевой ошибки источник выключается */
    private String get(String url, String what) {
        if (!available) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "ConceptGraphBot/1.0 (student project)")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[Wikipedia] HTTP " + response.statusCode() + " для '" + what + "'");
                return null;
            }
            return response.body();
        } catch (Exception e) {
            available = false;
            System.err.println("[Wikipedia] недоступна, дальше не опрашивается: " + e.getMessage());
            return null;
        }
    }

    /** Разбор ответа API; страницы сортируются по месту в поиске (поле index) */
    static List<HomonymResolver.Meaning> parse(String json) {
        List<HomonymResolver.Meaning> result = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("query") || !root.getAsJsonObject("query").has("pages")) {
            return result;
        }
        JsonObject pages = root.getAsJsonObject("query").getAsJsonObject("pages");

        List<JsonObject> sorted = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : pages.entrySet()) {
            sorted.add(entry.getValue().getAsJsonObject());
        }
        sorted.sort(Comparator.comparingInt(p -> p.has("index") ? p.get("index").getAsInt() : Integer.MAX_VALUE));

        for (JsonObject page : sorted) {
            String title = page.has("title") ? page.get("title").getAsString() : "";
            String extract = page.has("extract") ? page.get("extract").getAsString() : "";
            int length = page.has("length") ? page.get("length").getAsInt() : 0;
            if (!title.isBlank()) {
                result.add(new HomonymResolver.Meaning(title, extract, length));
            }
        }
        return result;
    }
}
