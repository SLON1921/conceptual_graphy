package ru.conceptgraph.modules.aiandnlp.service.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.Edge;
import ru.conceptgraph.shared.EntityType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class AiGraphEngine {

    private final HttpClient httpClient;
    private final String modelName = "gemma:2b"; // Имя модели для локального запуска

    public AiGraphEngine() {
        //  клиент Java для отправки HTTP-запросов к Ollama
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }


//      Очистка омонимии и исправление типов через прямой запрос к API Ollama
    public ConceptEntity resolveOmonymAndType(ConceptEntity rawEntity, String sentence) {
        String prompt = String.format(
                "Проанализируй слово '%s' в предложении: '%s'. " +
                        "Определи его категорию строго из четырех вариантов: PERSON, GEOGRAPHY, TECHNOLOGY, PRODUCT. " +
                        "Выведи в ответе только ОДНО это слово-категорию большими буквами, без лишнего текста и знаков препинания.",
                rawEntity.value(), sentence
        );

        // Формируем JSON-запрос для API Ollama согласно документации
        String jsonRequestBody = String.format(
                "{\"model\": \"%s\", \"prompt\": \"%s\", \"stream\": false}",
                modelName, prompt.replace("\"", "\\\"")
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRequestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            // Отправляем запрос синхронно
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Парсим ответ от Ollama с помощью библиотеки Gson
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                String aiText = jsonResponse.get("response").getAsString().trim().toUpperCase();

                if (aiText.contains("PERSON")) {
                    return new ConceptEntity(rawEntity.value(), EntityType.PERSON, rawEntity.startChar(), rawEntity.endChar());
                } else if (aiText.contains("GEOGRAPHY")) {
                    return new ConceptEntity(rawEntity.value(), EntityType.GEOGRAPHY, rawEntity.startChar(), rawEntity.endChar());
                } else if (aiText.contains("TECHNOLOGY")) {
                    return new ConceptEntity(rawEntity.value(), EntityType.TECHNOLOGY, rawEntity.startChar(), rawEntity.endChar());
                }
            }
        } catch (Exception e) {
            System.err.println("[Ollama HTTP Error]: Не удалось связаться с локальной моделью, используем базовый тип.");
        }

        return rawEntity;
    }

//     Строит связи между сущностями на основе расстояния в тексте
    public List<Edge> buildSemanticConnections(List<ConceptEntity> entities, String textContext) {
        List<Edge> edges = new ArrayList<>();
        if (entities == null || entities.size() < 2) return edges;

        for (int i = 0; i < entities.size(); i++) {
            for (int j = i + 1; j < entities.size(); j++) {
                ConceptEntity source = entities.get(i);
                ConceptEntity target = entities.get(j);

                if (source.value().equalsIgnoreCase(target.value())) continue;

                int distance = Math.abs(source.startChar() - target.startChar());
                double weight = 0.0;

                if (distance < 60) weight = 0.50;
                else if (distance < 150) weight = 0.25;

                if (weight > 0.0) {
                    edges.add(new Edge((long) i + 1, (long) j + 1, weight));
                }
            }
        }
        return edges;
    }
}
