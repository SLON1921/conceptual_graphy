package ru.conceptgraph.modules.infrastructure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Инфраструктурный сетевой клиент для интеграции с MediaWiki API Википедии.
 * Отвечает за асинхронный сбор семантических метаданных и статистики понятий.
 *
 * @author Рома (Разработчик Б — Инфраструктура и БД)
 * @version 1.1
 * @since Этап 1
 */
public class WikipediaClient {

    /**
     * Компактная структура данных (Java Record) для передачи результатов.
     * Служит неизменяемым контейнером-переносчиком данных между слоями бэкенда.
     * Поля полностью соответствуют требованиям ТЗ и структуре таблицы кэша 'wiki_cache'.
     */
    public record WikiData(String url, String snippet, int linksCount, int wordsCount) {}

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Конструктор класса. Вызывается автоматически при создании объекта через 'new WikipediaClient()'.
     * Инициализирует и настраивает внутренние инструменты.
     */
    public WikipediaClient() {
        this.httpClient = HttpClient.newBuilder()
                // РЕДИРЕКТЫ: Настраиваем автоматическое следование за перенаправлениями сервера.
                // Если слово передано во множественном числе, Википедия перенаправит на официальную статью в единственном.
                // Режим NORMAL — промышленный стандарт: он блокирует переходы с безопасного HTTPS на незащищенный HTTP,
                // полностью закрывая требования ТЗ к надежности и безопасности программного продукта.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Асинхронный метод отправки GET-запроса к API Русской Википедии.
     * Возвращает CompletableFuture — контейнер для результата, который появится в будущем.
     * Сетевое ожидание переносится в фоновые потоки ForkJoinPool, благодаря чему
     * главный UI-поток графического интерфейса JavaFX никогда не зависает,
     * тк все ядра компьютера-1 выделяются под Async, в один под фронт.
     *
     * @param concept Проверяемое текстовое понятие (существительное), присланное лингвистическим модулем
     * @return Асинхронный контейнер с укомплектованным объектом WikiData
     */
    public CompletableFuture<WikiData> fetchConceptInfoAsync(String concept) {
        if (concept == null || concept.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Понятие не может быть пустым"));
        }
         try {
             // URL-КОДИРОВАНИЕ: Переводим русские буквы и пробелы в безопасный шестнадцатеричный код (процентное кодирование).
             // В интернет-ссылках (URL) кириллица запрещена.
             String encodedConcept = URLEncoder.encode(concept.trim(), StandardCharsets.UTF_8);

             // 1. prop=extracts|info|links — просим вернуть краткий текст, общие свойства и внутренние ссылки.
             // 2. exintro=1 — приказ вырезать основное тело статьи, взяв только преамбулу.
             // 3. explaintext=1 — полная очистка от HTML-разметки.
             // 4. exsentences=2 — ограничиваем первыми двумя предложениями.
             // 5. pllimit=max — требуем выдать абсолютно ВСЕ внутренние ссылки статьи (до 500 шт).
             String url = "https://wikipedia.org|info|links"
                     + "&exintro=1&explaintext=1&exsentences=2&pllimit=max"
                     + "&title=" + encodedConcept;

             HttpRequest request = HttpRequest.newBuilder()
                     .uri(URI.create(url))
                     .GET()
                     // По строгим правилам Википедии, автоматические программы обязаны представляться и оставлять e-mail разработчика.
                     // Если удалить эту строку, сервер Википедии заблокирует IP-адрес вашего компьютера.
                     .header("User-Agent", "ConceptGraphBot/1.0 (roman@conceptgraph.ru)")
                     .build();

             return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                     .thenApply(HttpResponse::body)
                     .thenApply(jsonBody -> parseWikipediaResponse(jsonBody, concept));
         } catch (Exception e){
             return CompletableFuture.failedFuture(e);
         }
    }

    /**
     * Парсинг  и навигация по иерархическому JSON-дереву ответа Википедии через Jackson Databind.
     * Метод работает в фоновом режиме внутри асинхронного конвейера.
     */
    private WikiData parseWikipediaResponse(String jsonBody,String originalConcept) {
        try {
            // Корень дерева
            JsonNode root = objectMapper.readTree(jsonBody);

            // Если не будет найден такой путь, то вернет заглушку MissingNode.
            JsonNode pagesNode = root.path("query").path("pages");

            if (pagesNode.isMissingNode() || !pagesNode.fieldNames().hasNext()) {
                throw new RuntimeException("Статья в Википедии не найдена для понятия: " + originalConcept);
            }

            String pageId = pagesNode.fieldNames().next();
            JsonNode pageData = pagesNode.get(pageId);

            // Если вы искали абракадабру, Википедия вернет ответ, но запишет в ID маркер "-1".
            if ("-1".equals(pageId)) {
                throw new RuntimeException("Статья в Википедии не существует для понятия: " + originalConcept);
            }

            String title = pageData.path("title").asText();
            String url = "https://wikipedia.org" + URLEncoder.encode(title, StandardCharsets.UTF_8);

            // Преамбула
            String snippet = pageData.path("extract").asText("Описание отсутствует").trim();

            int wordsCount = pageData.path("length").asInt(0);

            int linksCount = 0;
            JsonNode linksNode = pageData.path("links");
            if (linksNode.isArray()) {
                linksCount = linksNode.size();
            }
            return new WikiData(url, snippet, linksCount, wordsCount);
        } catch (IOException e) {
            throw new RuntimeException("Ошибка парсинга JSON-структуры ответа Википедии: " + e.getMessage(), e);
        }
    }
}
