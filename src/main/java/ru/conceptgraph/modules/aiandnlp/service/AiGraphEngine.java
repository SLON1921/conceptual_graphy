package ru.conceptgraph.modules.aiandnlp.service;

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
    private final String modelName = "llama3";

    public AiGraphEngine() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public ConceptEntity resolveOmonymAndType(ConceptEntity rawEntity, String sentence) {
        String prompt = "Analyze '" + rawEntity.value()
                + "' in sentence: '" + sentence
                + "'. Determine its category: PERSON, GEOGRAPHY, TECHNOLOGY, PRODUCT. "
                + "Output ONLY ONE uppercase word.";

        try {
            String aiText = generateText(prompt).trim().toUpperCase();

            if (aiText.contains("PERSON")) {
                return new ConceptEntity(rawEntity.value(), EntityType.PERSON,
                        rawEntity.startChar(), rawEntity.endChar());
            }

            if (aiText.contains("GEOGRAPHY")) {
                return new ConceptEntity(rawEntity.value(), EntityType.GEOGRAPHY,
                        rawEntity.startChar(), rawEntity.endChar());
            }

            if (aiText.contains("TECHNOLOGY")) {
                return new ConceptEntity(rawEntity.value(), EntityType.TECHNOLOGY,
                        rawEntity.startChar(), rawEntity.endChar());
            }

            if (aiText.contains("PRODUCT")) {
                return new ConceptEntity(rawEntity.value(), EntityType.PRODUCT,
                        rawEntity.startChar(), rawEntity.endChar());
            }

        } catch (Exception e) {
            System.err.println("[Ollama Error]: " + e.getMessage());

            throw new RuntimeException("Сбой локальной Ollama при разрешении омонимии для '" + rawEntity.value() + "'", e);
        }

        return rawEntity;
    }


    public List<Edge> buildSemanticConnections(
            List<ConceptEntity> entities,
            String textContext) {

        List<Edge> edges = new ArrayList<>();

        if (entities == null || entities.size() < 2) {
            return edges;
        }

        StringBuilder prompt = new StringBuilder();

        prompt.append("Analyze the following text and find semantic connections ")
                .append("between the provided concepts.\n\n");

        prompt.append("TEXT:\n")
                .append(textContext)
                .append("\n\n");

        prompt.append("CONCEPTS:\n");

        for (ConceptEntity entity : entities) {
            if (entity != null
                    && entity.id() != null
                    && entity.value() != null) {

                prompt.append(entity.value())
                        .append("\n");
            }
        }

        prompt.append("\n")
                .append("Return connections ONLY in this exact format:\n")
                .append("WORD1->WORD2:WEIGHT:связан\n")
                .append("WEIGHT must be from 0.1 to 1.0.\n")
                .append("DO NOT output any action verbs, predicates, or custom labels. ")
                .append("Use ONLY the word 'связан' as LABEL.\n")
                .append("Output ONLY raw lines.");

        String aiResponse = generateText(prompt.toString());

        if (aiResponse == null) {
            return edges;
        }

        aiResponse = aiResponse.trim();

        System.out.println(
                "\n=== [DEBUG OLLAMA TEXT PARSE] ===\n"
                        + aiResponse
                        + "\n=================================\n"
        );

        if (aiResponse.isBlank()) {
            return edges;
        }

        String[] lines = aiResponse.split("\\r?\\n");

        for (String line : lines) {

            String cleanLine = line
                    .trim()
                    .replace("`", "");

            if (!cleanLine.contains("->")
                    || !cleanLine.contains(":")) {
                continue;
            }

            try {
                int arrow = cleanLine.indexOf("->");
                int firstColon = cleanLine.indexOf(":", arrow + 2);

                if (arrow <= 0 || firstColon == -1) {
                    continue;
                }

                int secondColon =
                        cleanLine.indexOf(":", firstColon + 1);

                String word1 = cleanLine
                        .substring(0, arrow)
                        .trim()
                        .toLowerCase();

                String word2 = cleanLine
                        .substring(arrow + 2, firstColon)
                        .trim()
                        .toLowerCase();

                if (word1.isBlank() || word2.isBlank()) {
                    continue;
                }

                String weightPart;

                if (secondColon != -1) {
                    weightPart = cleanLine
                            .substring(firstColon + 1, secondColon)
                            .trim();
                } else {
                    weightPart = cleanLine
                            .substring(firstColon + 1)
                            .trim();
                }

                String numericWeight = weightPart
                        .replace(",", ".")
                        .replaceAll("[^0-9.]", "");

                if (numericWeight.isBlank()) {
                    continue;
                }

                double weight = Double.parseDouble(numericWeight);

                if (Double.isNaN(weight)
                        || Double.isInfinite(weight)) {
                    continue;
                }

                if (weight < 0.1) {
                    weight = 0.1;
                }

                if (weight > 1.0) {
                    weight = 1.0;
                }

                Long srcId = null;
                Long trgId = null;

                /*
                 * Ищем реальный ID первой концепции.
                 */
                String bW1 = word1.length() > 4
                        ? word1.substring(0, word1.length() - 2)
                        : word1;

                for (ConceptEntity entity : entities) {

                    if (entity == null
                            || entity.id() == null
                            || entity.value() == null) {
                        continue;
                    }

                    String entityVal = entity.value()
                            .trim()
                            .toLowerCase();

                    if (entityVal.isBlank()) {
                        continue;
                    }

                    String bEnt = entityVal.length() > 4
                            ? entityVal.substring(0, entityVal.length() - 2)
                            : entityVal;

                    if (entityVal.equals(word1)
                            || entityVal.contains(word1)
                            || word1.contains(entityVal)
                            || bEnt.contains(bW1)) {

                        srcId = entity.id();
                        break;
                    }
                }

                /*
                 * Ищем реальный ID второй концепции.
                 *
                 * Нельзя использовать ту же концепцию,
                 * которая уже была выбрана как source.
                 */
                String bW2 = word2.length() > 4
                        ? word2.substring(0, word2.length() - 2)
                        : word2;

                for (ConceptEntity entity : entities) {

                    if (entity == null
                            || entity.id() == null
                            || entity.value() == null) {
                        continue;
                    }

                    if (srcId != null
                            && entity.id().equals(srcId)) {
                        continue;
                    }

                    String entityVal = entity.value()
                            .trim()
                            .toLowerCase();

                    if (entityVal.isBlank()) {
                        continue;
                    }

                    String bEnt = entityVal.length() > 4
                            ? entityVal.substring(0, entityVal.length() - 2)
                            : entityVal;

                    if (entityVal.equals(word2)
                            || entityVal.contains(word2)
                            || word2.contains(entityVal)
                            || bEnt.contains(bW2)) {

                        trgId = entity.id();
                        break;
                    }
                }

                /*
                 * Создаём ребро только между двумя
                 * разными реальными ID из базы.
                 */
                if (srcId != null
                        && trgId != null
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
    public String generateText(String prompt) {
        String cleanPrompt = prompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        String jsonRequestBody = "{\"model\": \"" + modelName
                + "\", \"prompt\": \"" + cleanPrompt
                + "\", \"stream\": false}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRequestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 200) {
                JsonObject jsonResponse = JsonParser
                        .parseString(response.body())
                        .getAsJsonObject();

                return jsonResponse.get("response")
                        .getAsString()
                        .trim();
            }

            System.err.println("[Ollama HTTP Error]: " + response.statusCode());

        } catch (Exception e) {
            System.err.println("[Ollama Chat Error]: " + e.getMessage());
        }

        return "";
    }
}
