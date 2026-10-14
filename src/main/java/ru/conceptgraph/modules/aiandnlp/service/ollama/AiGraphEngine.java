package ru.conceptgraph.modules.aiandnlp.service.ollama;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.conceptgraph.core.errors.AiException;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.Edge;
import ru.conceptgraph.shared.EntityType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class AiGraphEngine {

    private final HttpClient httpClient;

    /**Он запускает весь анализ по шагам и отдаёт окну готовый результат. */
    private final String ollamaUrl;
    private final String modelName;

    public AiGraphEngine() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        Properties settings = loadSettings();
        String url = settings.getProperty("ollama.url", "http://localhost:11434").trim();
        this.ollamaUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String saved = OllamaModels.savedModel();
        this.modelName = saved != null ? saved : settings.getProperty("ollama.model", "llama3").trim();
    }

    public String getOllamaUrl() {
        return ollamaUrl;
    }
    /**
     * Читает application.properties напрямую: AppConfig хранит только настройки БД
     * и проверяет их как обязательные, а для Ollama достаточно значений по умолчанию.
     */
    private static Properties loadSettings() {
        Properties properties = new Properties();
        try (InputStream in = AiGraphEngine.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            System.err.println("[Ollama] Не удалось прочитать application.properties: " + e.getMessage());
        }
        return properties;
    }

    /**
     * Быстрая проверка, запущена ли Ollama (GET /api/tags, 2 секунды).
     * Без неё каждый запрос к выключенной Ollama молча возвращает пустую строку,
     * и конвейер зря делает десятки запросов.
     */
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public ConceptEntity resolveOmonymAndType(
            ConceptEntity rawEntity,
            String sentence
    ) {

        if (rawEntity == null) {
            return null;
        }

        String prompt = "Analyze '" + rawEntity.value()
                + "' in sentence: '" + sentence
                + "'. Determine its category: PERSON, GEOGRAPHY, TECHNOLOGY, PRODUCT. "
                + "Output ONLY ONE uppercase word.";

        try {
            String aiText = generateText(prompt)
                    .trim()
                    .toUpperCase();

            EntityType resolvedType = null;

            if (aiText.contains("PERSON")) {
                resolvedType = EntityType.PERSON;
            } else if (aiText.contains("GEOGRAPHY")) {
                resolvedType = EntityType.GEOGRAPHY;
            } else if (aiText.contains("TECHNOLOGY")) {
                resolvedType = EntityType.TECHNOLOGY;
            } else if (aiText.contains("PRODUCT")) {
                resolvedType = EntityType.PRODUCT;
            }

            /*
             * ВАЖНО:
             * сохраняем ID исходной сущности.
             *
             * Раньше здесь использовался конструктор без ID,
             * из-за чего реальный PostgreSQL ID терялся.
             */
            if (resolvedType != null) {
                return new ConceptEntity(
                        rawEntity.id(),
                        rawEntity.value(),
                        resolvedType,
                        rawEntity.startChar(),
                        rawEntity.endChar()
                );
            }

        } catch (Exception e) {

            System.err.println(
                    "[Ollama Error]: " + e.getMessage()
            );

            throw new AiException(
                    "Сбой Ollama для '" + rawEntity.value() + "'",
                    e
            );
        }

        return rawEntity;
    }


    public List<Edge> buildSemanticConnections(
            List<ConceptEntity> entities,
            String textContext
    ) {

        List<Edge> edges = new ArrayList<>();

        if (entities == null || entities.size() < 2) {
            return edges;
        }

        /*
         * В граф отправляем только сущности,
         * которые уже сохранены в PostgreSQL
         * и имеют реальные положительные ID.
         */
        List<ConceptEntity> persistedEntities = new ArrayList<>();

        for (ConceptEntity entity : entities) {

            if (entity == null) {
                continue;
            }

            if (entity.id() == null || entity.id() <= 0) {
                continue;
            }

            if (entity.value() == null || entity.value().isBlank()) {
                continue;
            }

            persistedEntities.add(entity);
        }

        if (persistedEntities.size() < 2) {
            return edges;
        }


        StringBuilder prompt = new StringBuilder();

        prompt.append(
                "Analyze the following text and find semantic connections "
        ).append(
                "between the provided concepts.\n\n"
        );

        prompt.append("TEXT:\n")
                .append(textContext == null ? "" : textContext)
                .append("\n\n");

        prompt.append("CONCEPTS:\n");

        for (ConceptEntity entity : persistedEntities) {
            prompt.append(entity.value())
                    .append("\n");
        }

        prompt.append("\n")
                .append("Return connections ONLY in this exact format:\n")
                .append("WORD1->WORD2:WEIGHT:связан\n")
                .append("WEIGHT must be from 0.1 to 1.0.\n")
                .append(
                        "DO NOT output any action verbs, predicates, or custom labels. "
                )
                .append("Use ONLY the word 'связан' as LABEL.\n")
                .append("Output ONLY raw lines.");


        String aiResponse = generateText(
                prompt.toString()
        );

        if (aiResponse == null || aiResponse.isBlank()) {
            return edges;
        }

        aiResponse = aiResponse.trim();


        System.out.println(
                "\n=== [DEBUG OLLAMA TEXT PARSE] ===\n"
                        + aiResponse
                        + "\n=================================\n"
        );


        String[] lines = aiResponse.split("\\r?\\n");


        for (String line : lines) {

            if (line == null) {
                continue;
            }

            String cleanLine = line
                    .trim()
                    .replace("`", "");

            if (cleanLine.isBlank()) {
                continue;
            }

            if (!cleanLine.contains("->")
                    || !cleanLine.contains(":")) {
                continue;
            }


            try {

                int arrow =
                        cleanLine.indexOf("->");

                int firstColon =
                        cleanLine.indexOf(
                                ":",
                                arrow + 2
                        );

                if (arrow <= 0 || firstColon == -1) {
                    continue;
                }


                int secondColon =
                        cleanLine.indexOf(
                                ":",
                                firstColon + 1
                        );


                /*
                 * Формат обязан быть:
                 *
                 * WORD1->WORD2:WEIGHT:связан
                 */
                if (secondColon == -1) {
                    continue;
                }


                String word1 = cleanLine
                        .substring(
                                0,
                                arrow
                        )
                        .trim()
                        .toLowerCase();


                String word2 = cleanLine
                        .substring(
                                arrow + 2,
                                firstColon
                        )
                        .trim()
                        .toLowerCase();


                if (word1.isBlank()
                        || word2.isBlank()) {
                    continue;
                }


                String label = cleanLine
                        .substring(
                                secondColon + 1
                        )
                        .trim()
                        .toLowerCase();


                /*
                 * По ТЗ разрешена только связь "связан".
                 */
                if (!"связан".equals(label)) {
                    continue;
                }


                String numericWeight =
                        getNumericWeight(
                                secondColon,
                                cleanLine,
                                firstColon
                        );


                if (numericWeight.isBlank()) {
                    continue;
                }


                double weight =
                        Double.parseDouble(
                                numericWeight
                        );


                if (Double.isNaN(weight)
                        || Double.isInfinite(weight)) {
                    continue;
                }


                /*
                 * Вес всегда ограничиваем диапазоном
                 * от 0.1 до 1.0.
                 */
                if (weight < 0.1) {
                    weight = 0.1;
                }

                if (weight > 1.0) {
                    weight = 1.0;
                }


                Long srcId = findEntityId(
                        persistedEntities,
                        word1,
                        null
                );


                if (srcId == null) {
                    continue;
                }


                Long trgId = findEntityId(
                        persistedEntities,
                        word2,
                        srcId
                );


                /*
                 * Создаём ребро только между
                 * двумя разными реальными ID PostgreSQL.
                 */
                if (trgId != null
                        && !srcId.equals(trgId)) {

                    edges.add(
                            new Edge(
                                    srcId,
                                    trgId,
                                    weight,
                                    "связан"
                            )
                    );
                }


            } catch (NumberFormatException e) {

                System.err.println(
                        "[Edge Parse Error] Invalid weight: "
                                + cleanLine
                );

            } catch (Exception e) {

                System.err.println(
                        "[Edge Parse Error]: "
                                + e.getMessage()
                                + " | line: "
                                + cleanLine
                );
            }
        }


        return edges;
    }


    /**
     * Ищет реальный PostgreSQL ID концепции
     * по слову, которое вернула Ollama.
     */
    private Long findEntityId(
            List<ConceptEntity> entities,
            String searchedWord,
            Long excludedId
    ) {

        if (searchedWord == null
                || searchedWord.isBlank()) {
            return null;
        }


        String word = searchedWord
                .trim()
                .toLowerCase();


        /*
         * Сначала ищем точное совпадение.
         * Это самый безопасный вариант.
         */
        for (ConceptEntity entity : entities) {

            if (!isValidEntity(entity)) {
                continue;
            }


            if (excludedId != null
                    && excludedId.equals(entity.id())) {
                continue;
            }


            String entityValue = entity.value()
                    .trim()
                    .toLowerCase();


            if (entityValue.equals(word)) {
                return entity.id();
            }
        }


        /*
         * Если точного совпадения нет —
         * пробуем мягкое совпадение.
         */
        String baseWord = createComparisonBase(
                word
        );


        for (ConceptEntity entity : entities) {

            if (!isValidEntity(entity)) {
                continue;
            }


            if (excludedId != null
                    && excludedId.equals(entity.id())) {
                continue;
            }


            String entityValue = entity.value()
                    .trim()
                    .toLowerCase();


            String baseEntity =
                    createComparisonBase(
                            entityValue
                    );


            if (entityValue.contains(word)
                    || word.contains(entityValue)
                    || baseEntity.equals(baseWord)) {

                return entity.id();
            }
        }


        return null;
    }


    private boolean isValidEntity(
            ConceptEntity entity
    ) {

        return entity != null
                && entity.id() != null
                && entity.id() > 0
                && entity.value() != null
                && !entity.value().isBlank();
    }


    /**
     * Небольшое упрощение слова только для
     * сопоставления ответа Ollama с концепцией.
     *
     * Реальное значение ConceptEntity
     * этим методом НЕ изменяется.
     */
    private String createComparisonBase(
            String value
    ) {

        if (value == null) {
            return "";
        }

        String result = value
                .trim()
                .toLowerCase();


        if (result.length() <= 4) {
            return result;
        }


        if (result.length() > 6) {
            return result.substring(
                    0,
                    result.length() - 2
            );
        }


        return result;
    }


    private static String getNumericWeight(
            int secondColon,
            String cleanLine,
            int firstColon
    ) {

        String weightPart;

        if (secondColon != -1) {

            weightPart = cleanLine
                    .substring(
                            firstColon + 1,
                            secondColon
                    )
                    .trim();

        } else {

            weightPart = cleanLine
                    .substring(
                            firstColon + 1
                    )
                    .trim();
        }


        return weightPart
                .replace(",", ".")
                .replaceAll(
                        "[^0-9.]",
                        ""
                );
    }


    public String generateText(
            String prompt
    ) {

        if (prompt == null
                || prompt.isBlank()) {
            return "";
        }


        String cleanPrompt = prompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");


        String jsonRequestBody =
                "{\"model\": \""
                        + modelName
                        + "\", \"prompt\": \""
                        + cleanPrompt
                        + "\", \"stream\": false}";


        try {

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            ollamaUrl + "/api/generate"
                                    )
                            )
                            .header(
                                    "Content-Type",
                                    "application/json"
                            )
                            .POST(
                                    HttpRequest.BodyPublishers
                                            .ofString(
                                                    jsonRequestBody
                                            )
                            )
                            .timeout(
                                    Duration.ofSeconds(
                                            30
                                    )
                            )
                            .build();


            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers
                                    .ofString()
                    );


            if (response.statusCode() == 200) {

                JsonObject jsonResponse =
                        JsonParser
                                .parseString(
                                        response.body()
                                )
                                .getAsJsonObject();


                if (!jsonResponse.has("response")
                        || jsonResponse.get("response").isJsonNull()) {

                    return "";
                }


                return jsonResponse
                        .get("response")
                        .getAsString()
                        .trim();
            }


            System.err.println(
                    "[Ollama HTTP Error]: "
                            + response.statusCode()
            );


        } catch (Exception e) {

            System.err.println(
                    "[Ollama Chat Error]: "
                            + e.getMessage()
            );
        }


        return "";
    }
}