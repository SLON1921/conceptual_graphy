package ru.conceptgraph.modules.aiandnlp.service.ollama;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.conceptgraph.modules.aiandnlp.service.ConceptGraphPipeline;
import ru.conceptgraph.modules.aiandnlp.service.RussianMorphology;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.Edge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Смысловой граф: Ollama читает текст кусками и говорит, какие понятия связаны по смыслу
 * (герой и место, где он живёт; герои, которые общаются; причина и следствие).
 * Граф Вани связывает слова, которые просто стоят рядом; этот — по смыслу, через весь текст.
 *
 * <p>Узлы те же, что нашёл конвейер, поэтому два графа можно сравнить и объединить
 * (см. {@link GraphComparison}). Чтобы модель понимала весь текст, а не только кусок,
 * к каждому куску прикладываем краткое содержание всего прочитанного до него —
 * Ollama сама обновляет его в каждом ответе.</p>
 */
public class SemanticGraphEngine {

    /** Что окно хочет знать по ходу работы. Вызывается из фонового потока. */
    public interface Progress {
        void chunkDone(int done, int total);

        void log(String line);
    }

    /** Примерно столько слов в одном куске: короткий кусок модель читает внимательнее и находит больше связей */
    static final int CHUNK_WORDS = 800;
    /** Больше понятий в одном запросе модель путает, берём самые частые в куске */
    static final int MAX_CONCEPTS_PER_CHUNK = 80;
    /** Окно контекста Ollama: по умолчанию оно маленькое и обрезало бы кусок */
    private static final int CONTEXT_TOKENS = 8192;
    /** Краткое содержание длиннее этого режем, чтобы оно не съело место под текст */
    private static final int MAX_SUMMARY_CHARS = 1200;
    /**
     * Длина ответа модели в токенах. Обычный ответ — 500–2000 токенов; без лимита маленькая модель
     * иногда зацикливается и пишет одни и те же связи по кругу, а кусок считается минутами.
     */
    private static final int MAX_ANSWER_TOKENS = 3000;
    /** Связь [a, b, w] или [a, b] в обрезанном ответе */
    private static final Pattern LINK = Pattern.compile("\\[\\s*\"?(\\d+)\"?\\s*,\\s*\"?(\\d+)\"?\\s*(?:,\\s*([0-9.]+)\\s*)?]");
    private static final Pattern SUMMARY = Pattern.compile("\"summary\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String ollamaUrl;
    private final String model;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public SemanticGraphEngine() {
        AiGraphEngine settings = new AiGraphEngine();
        this.ollamaUrl = settings.getOllamaUrl();
        this.model = settings.getModelName();
    }

    public String model() {
        return model;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Строит смысловые рёбра по всем документам результата. Долго: запускать в фоновом потоке.
     *
     * @return рёбра с весом 0..1 (как у графа Вани), или null, если отменили
     */
    public List<Edge> build(ConceptGraphPipeline.Result result, Progress progress) {
        cancelled.set(false);
        Map<Long, ConceptEntity> nodes = new HashMap<>();
        for (ConceptEntity node : result.nodes()) {
            nodes.put(node.id(), node);
        }

        // Куски всех документов заранее, чтобы честно показывать «кусок 3 из 20»
        List<int[]> work = new ArrayList<>(); // {документ, начало, конец}
        for (int d = 0; d < result.inputs().size(); d++) {
            for (TextChunker.Chunk chunk : TextChunker.split(result.inputs().get(d).text(), CHUNK_WORDS)) {
                work.add(new int[]{d, chunk.start(), chunk.end()});
            }
        }
        progress.log("[Смысл] модель " + model + ", кусков текста: " + work.size());

        Map<String, double[]> weights = new LinkedHashMap<>();
        String summary = "";
        int currentDoc = -1;
        for (int i = 0; i < work.size(); i++) {
            if (cancelled.get()) {
                return null;
            }
            int doc = work.get(i)[0];
            int start = work.get(i)[1];
            int end = work.get(i)[2];
            if (doc != currentDoc) {
                summary = ""; // новый документ — своё содержание
                currentDoc = doc;
            }

            Map<String, Long> concepts = conceptsInChunk(result, nodes, doc, start, end);
            if (concepts.size() >= 2) {
                long t = System.currentTimeMillis();
                try {
                    String text = result.inputs().get(doc).text().substring(start, end);
                    JsonObject answer = ask(prompt(summary, text, concepts.keySet(), i + 1, work.size()));
                    int[] found = addLinks(answer, concepts, weights);
                    if (answer.has("summary") && answer.get("summary").isJsonPrimitive()) {
                        summary = cut(answer.get("summary").getAsString(), MAX_SUMMARY_CHARS);
                    }
                    progress.log(String.format(Locale.ROOT,
                            "[Смысл] кусок %d из %d: понятий %d, модель предложила связей %d, принято %d, %.1f с",
                            i + 1, work.size(), concepts.size(), found[0], found[1],
                            (System.currentTimeMillis() - t) / 1000.0));
                } catch (Exception e) {
                    progress.log("[Смысл] кусок " + (i + 1) + " пропущен: " + e.getMessage());
                }
            }
            progress.chunkDone(i + 1, work.size());
        }
        return normalize(weights);
    }

    /**
     * Понятия, которые встречаются в куске: «как назвать в запросе» → ID узла.
     * Самые частые в куске идут первыми, лишние отрезаем.
     */
    private static Map<String, Long> conceptsInChunk(ConceptGraphPipeline.Result result, Map<Long, ConceptEntity> nodes,
                                                     int doc, int start, int end) {
        Map<Long, Integer> counts = new HashMap<>();
        for (ConceptGraphPipeline.NodeMention mention : result.mentions()) {
            if (mention.doc() == doc && mention.start() >= start && mention.start() < end) {
                counts.merge(mention.nodeId(), 1, Integer::sum);
            }
        }
        List<Map.Entry<Long, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        Map<String, Long> concepts = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : sorted) {
            ConceptEntity node = nodes.get(entry.getKey());
            if (node == null || node.value() == null || node.value().isBlank()) {
                continue;
            }
            concepts.putIfAbsent(node.value().trim(), node.id());
            if (concepts.size() == MAX_CONCEPTS_PER_CHUNK) {
                break;
            }
        }
        return concepts;
    }

    static String prompt(String summary, String text, Iterable<String> concepts, int part, int parts) {
        StringBuilder p = new StringBuilder();
        p.append("Ты читаешь текст на русском языке по частям и строишь граф смысловых связей между понятиями.\n\n");
        p.append("Краткое содержание всего, что было до этой части:\n")
                .append(summary.isBlank() ? "(это начало текста)" : summary)
                .append("\n\n");
        p.append("ЧАСТЬ ").append(part).append(" ИЗ ").append(parts).append(":\n\"\"\"\n")
                .append(text.strip())
                .append("\n\"\"\"\n\n");
        p.append("ПОНЯТИЯ из этой части (номер. понятие):\n");
        int number = 1;
        for (String concept : concepts) {
            p.append(number++).append(". ").append(concept).append('\n');
        }
        p.append("""

                Задача: найди пары понятий из списка, которые связаны ПО СМЫСЛУ: герой и место, где он живёт или бывает;
                герои, которые общаются, любят или враждуют; предмет и его владелец; причина и следствие; часть и целое.
                Не связывай понятия только потому, что они стоят рядом в тексте.
                Пройди по списку и для КАЖДОГО понятия укажи 1–3 других понятия из списка,
                с которыми оно связано по смыслу сильнее всего. Пропусти понятие, только если оно совсем ни с чем не связано.
                Каждая связь — три числа [a, b, w]: a и b — НОМЕРА понятий из списка,
                w — сила связи от 0.1 (слабая) до 1.0 (очень важная для смысла текста).

                Ответь только JSON такого вида:
                {"summary": "краткое содержание всего текста до конца этой части, не больше 4 предложений",
                 "links": [[1, 4, 0.7], [2, 4, 0.4], [3, 1, 0.2]]}
                """);
        return p.toString();
    }

    /** Один запрос к Ollama; format=json заставляет модель отвечать корректным JSON */
    private JsonObject ask(String prompt) throws IOException, InterruptedException {
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0);
        options.addProperty("num_ctx", CONTEXT_TOKENS);
        options.addProperty("num_predict", MAX_ANSWER_TOKENS);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", prompt);
        body.addProperty("stream", false);
        body.addProperty("format", "json");
        body.add("options", options);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(3)) // обычный кусок — секунды; дольше — модель застряла, пропускаем
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Ollama ответила " + response.statusCode() + ": " + cut(response.body(), 200));
        }
        String answer = JsonParser.parseString(response.body()).getAsJsonObject().get("response").getAsString();
        try {
            JsonElement parsed = JsonParser.parseString(answer);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception e) {
            // ответ оборвался на лимите токенов — JSON не закрыт, ниже достаём, что успела написать
        }
        return salvage(answer);
    }

    /** Из оборванного ответа берём краткое содержание и все целые связи [a, b, w] */
    static JsonObject salvage(String answer) {
        JsonObject result = new JsonObject();
        Matcher summary = SUMMARY.matcher(answer);
        if (summary.find()) {
            try {
                result.addProperty("summary", JsonParser.parseString("\"" + summary.group(1) + "\"").getAsString());
            } catch (Exception ignored) {
                // кривое экранирование — обойдёмся без содержания
            }
        }
        JsonArray links = new JsonArray();
        Matcher link = LINK.matcher(answer);
        while (link.find()) {
            JsonArray triple = new JsonArray();
            triple.add(Integer.parseInt(link.group(1)));
            triple.add(Integer.parseInt(link.group(2)));
            if (link.group(3) != null) {
                try {
                    triple.add(Double.parseDouble(link.group(3)));
                } catch (NumberFormatException ignored) {
                    // вес не разобрали — addLinks поставит средний
                }
            }
            links.add(triple);
        }
        result.add("links", links);
        return result;
    }

    /**
     * Разбирает «links» из ответа и складывает веса пар. Понятие модель может назвать номером из списка
     * (так просим) или словом; слово сверяем без учёта регистра, а потом по основе слова —
     * модель часто меняет падеж («Олесе» → «Олеся»). Пары с неизвестным понятием выбрасываем.
     *
     * @return {сколько связей предложила модель, сколько принято}
     */
    static int[] addLinks(JsonObject answer, Map<String, Long> concepts, Map<String, double[]> weights) {
        List<Long> byNumber = new ArrayList<>(concepts.values());
        Map<String, Long> byName = new HashMap<>();
        Map<String, Long> byStem = new HashMap<>();
        concepts.forEach((name, id) -> {
            byName.putIfAbsent(name.toLowerCase(Locale.ROOT), id);
            byStem.putIfAbsent(stems(name), id);
        });

        JsonElement links = answer.get("links");
        if (links == null || !links.isJsonArray()) {
            return new int[]{0, 0};
        }
        int offered = 0;
        int accepted = 0;
        Set<String> seen = new HashSet<>(); // зациклившаяся модель повторяет пары — считаем каждую один раз
        for (JsonElement element : (JsonArray) links) {
            // Связь приходит как [a, b, w] (так просим — короче, модель отвечает быстрее) или как {"a", "b", "w"}
            JsonElement first;
            JsonElement second;
            JsonElement weight;
            if (element.isJsonArray() && element.getAsJsonArray().size() >= 2) {
                JsonArray link = element.getAsJsonArray();
                first = link.get(0);
                second = link.get(1);
                weight = link.size() > 2 ? link.get(2) : null;
            } else if (element.isJsonObject()) {
                JsonObject link = element.getAsJsonObject();
                first = link.get("a");
                second = link.get("b");
                weight = link.get("w");
            } else {
                continue;
            }
            offered++;
            Long a = resolve(first, byNumber, byName, byStem);
            Long b = resolve(second, byNumber, byName, byStem);
            if (a == null || b == null || a.equals(b)) {
                continue;
            }
            double w = 0.5;
            try {
                if (weight != null) {
                    w = weight.getAsDouble();
                }
            } catch (Exception ignored) {
                // вес не числом — оставляем средний
            }
            if (Double.isNaN(w) || Double.isInfinite(w)) {
                continue;
            }
            w = Math.max(0.1, Math.min(1.0, w));
            if (!seen.add(GraphComparison.pairKey(a, b))) {
                continue;
            }
            weights.computeIfAbsent(GraphComparison.pairKey(a, b), k -> new double[1])[0] += w;
            accepted++;
        }
        return new int[]{offered, accepted};
    }

    /** Номер из списка («3» или 3), точное название или то же слово в другом падеже */
    private static Long resolve(JsonElement value, List<Long> byNumber, Map<String, Long> byName,
                                Map<String, Long> byStem) {
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        String text = value.getAsString().trim().replace("\"", "");
        if (text.isEmpty()) {
            return null;
        }
        String digits = text.replaceFirst("^(\\d+).*", "$1"); // «3», «3.» и «3. Олесе» → 3
        if (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit)) {
            int number = Integer.parseInt(digits);
            return number >= 1 && number <= byNumber.size() ? byNumber.get(number - 1) : null;
        }
        Long id = byName.get(text.toLowerCase(Locale.ROOT));
        return id != null ? id : byStem.get(stems(text));
    }

    /** Основы всех слов через пробел: «Ивана Тимофеевича» и «Иван Тимофеевич» совпадут */
    private static String stems(String phrase) {
        StringBuilder key = new StringBuilder();
        for (String word : phrase.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (!word.isEmpty()) {
                key.append(RussianMorphology.stemKey(word)).append(' ');
            }
        }
        return key.toString().trim();
    }

    /**
     * Веса из всех кусков складывались — приводим к 0..1, как у графа Вани. Берём корень от доли максимума:
     * иначе пара, упомянутая один раз, получала бы вес около 0.05 и пряталась ползунком «Вес связи от».
     */
    static List<Edge> normalize(Map<String, double[]> weights) {
        double max = 0;
        for (double[] w : weights.values()) {
            max = Math.max(max, w[0]);
        }
        List<Edge> edges = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : weights.entrySet()) {
            long[] ids = GraphComparison.parsePairKey(entry.getKey());
            edges.add(new Edge(ids[0], ids[1], Math.sqrt(entry.getValue()[0] / max), "связан"));
        }
        return edges;
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
