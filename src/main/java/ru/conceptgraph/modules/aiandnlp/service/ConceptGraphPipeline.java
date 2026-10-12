package ru.conceptgraph.modules.aiandnlp.service;

import ru.conceptgraph.core.errors.AppExceptionHandler;
import ru.conceptgraph.modules.infrastructure.repository.ConceptRepository;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.Edge;
import ru.conceptgraph.shared.EntityType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Общий конвейер анализа документов для консольной версии и окна:
 * люди, даты и существительные -> омонимы (Википедия) -> тип (словари, Ollama) -> БД -> рёбра графа.
 *
 * <p>Понятия склеиваются по всем документам сразу: одно слово в разных падежах и в разных
 * файлах — один узел графа.</p>
 */
public class ConceptGraphPipeline {

    /** Документ на вход: имя, путь, размер файла в байтах и извлечённый текст */
    public record DocumentInput(String name, String path, long size, String text) {
    }

    /** Итог по одному документу для вкладки «Документы» */
    public record DocumentReport(String name, String path, int chars, int mentions, int persons,
                                 int dates, long millis) {
    }

    /** Дата, найденная в документе */
    public record DateFound(String document, DateExtractor.DateMention date) {
    }

    /**
     * Результат анализа.
     *
     * @param nodes       уникальные понятия (узлы графа); startChar/endChar — первое вхождение
     * @param frequency   сколько раз понятие встретилось во всех документах (по ID узла)
     * @param edges       рёбра графа между ID узлов
     * @param documents   отчёт по каждому документу
     * @param dates       все найденные даты
     * @param errors      ошибки этого запуска (Ollama, БД, Википедия)
     * @param stageMillis время по этапам, мс
     * @param savedToDb   true — ID из PostgreSQL; false — база недоступна, ID локальные (1..n)
     */
    public record Result(List<ConceptEntity> nodes,
                         Map<Long, Integer> frequency,
                         List<Edge> edges,
                         List<DocumentReport> documents,
                         List<DateFound> dates,
                         List<String> errors,
                         Map<String, Long> stageMillis,
                         boolean savedToDb) {
    }

    /** Одно вхождение понятия в документ */
    private record Mention(int doc, String surface, EntityType type, int start, int end) {
    }

    /** Все вхождения одного понятия */
    private static final class Group {
        final List<Mention> mentions = new ArrayList<>();
        String fixedValue;       // для людей и омонимов значение задано заранее
        EntityType fixedType;
        ConceptEntity node;      // итоговый узел
    }

    private final OpenNlpEngine nlpEngine;
    private final AiGraphEngine aiEngine;
    private final AppExceptionHandler exceptionHandler;
    private final Consumer<String> log;
    private final PersonExtractor personExtractor = new PersonExtractor();
    private final DateExtractor dateExtractor = new DateExtractor();
    private final HomonymResolver homonymResolver = new HomonymResolver();
    private MeaningSource meaningSource;

    private List<String> errors;

    public ConceptGraphPipeline(OpenNlpEngine nlpEngine,
                                AiGraphEngine aiEngine,
                                AppExceptionHandler exceptionHandler,
                                Consumer<String> log) {
        this.nlpEngine = nlpEngine;
        this.aiEngine = aiEngine;
        this.exceptionHandler = exceptionHandler;
        this.log = log;
    }

    /** Включает выбор значений омонимов (например, new WikipediaMeaningSource()); null — выключить */
    public ConceptGraphPipeline withMeaningSource(MeaningSource meaningSource) {
        this.meaningSource = meaningSource;
        return this;
    }

    public Result process(String name, String path, long size, String text) {
        return processAll(List.of(new DocumentInput(name, path, size, text)));
    }

    public Result processAll(List<DocumentInput> documents) {
        errors = new ArrayList<>();
        Map<String, Long> stageMillis = new LinkedHashMap<>();
        List<DocumentReport> reports = new ArrayList<>();
        List<DateFound> dates = new ArrayList<>();

        // 1. Люди, даты и существительные по каждому документу
        long t = System.currentTimeMillis();
        Map<String, Group> groups = new LinkedHashMap<>();
        for (int d = 0; d < documents.size(); d++) {
            long docStart = System.currentTimeMillis();
            DocumentInput doc = documents.get(d);
            String text = doc.text() == null ? "" : doc.text();

            List<PersonExtractor.PersonMention> persons = personExtractor.extractPersons(text);
            List<DateExtractor.DateMention> docDates = dateExtractor.extractDates(text);
            List<ConceptEntity> nouns = nlpEngine.extractNouns(text);

            for (PersonExtractor.PersonMention person : persons) {
                Group group = groups.computeIfAbsent("person:" + person.key(), k -> new Group());
                group.fixedValue = person.canonical();
                group.fixedType = EntityType.PERSON;
                group.mentions.add(new Mention(d, person.canonical(), EntityType.PERSON,
                        person.startChar(), person.endChar()));
            }
            int nounCount = 0;
            for (ConceptEntity noun : nouns) {
                // Части имён («Михаил», «Ломоносов») и дат («октября», «году») уже учтены целиком
                if (overlapsPerson(noun, persons) || overlapsDate(noun, docDates)) {
                    continue;
                }
                groups.computeIfAbsent("word:" + RussianMorphology.stemKey(noun.value()), k -> new Group())
                        .mentions.add(new Mention(d, noun.value(), noun.type(), noun.startChar(), noun.endChar()));
                nounCount++;
            }
            for (DateExtractor.DateMention date : docDates) {
                dates.add(new DateFound(doc.name(), date));
            }

            reports.add(new DocumentReport(doc.name(), doc.path(), text.length(), nounCount + persons.size(),
                    persons.size(), docDates.size(), System.currentTimeMillis() - docStart));
            log.accept(doc.name() + ": слов " + nounCount + ", людей " + persons.size() + ", дат " + docDates.size());
        }
        stageMillis.put("Извлечение (OpenNLP, люди, даты)", System.currentTimeMillis() - t);

        // 2. Омонимы: разные значения одного слова становятся разными узлами
        if (meaningSource != null) {
            t = System.currentTimeMillis();
            groups = splitHomonyms(groups, documents);
            stageMillis.put("Омонимы (Википедия)", System.currentTimeMillis() - t);
        }
        log.accept("Уникальных понятий: " + groups.size());

        // 3. Значение и тип каждого узла; Ollama уточняет тип, если запущена
        t = System.currentTimeMillis();
        boolean ollama = aiEngine.isAvailable();
        if (!ollama) {
            log.accept("[Ollama]: не запущена, тип берётся из словарей, рёбра — по общим предложениям.");
        }
        for (Group group : groups.values()) {
            group.node = buildNode(group, documents, ollama);
        }
        stageMillis.put(ollama ? "Тип понятий (Ollama)" : "Тип понятий (словари)", System.currentTimeMillis() - t);

        // 4. Сохранение в БД; без базы — локальные ID
        t = System.currentTimeMillis();
        boolean savedToDb = saveToDatabase(groups, documents);
        if (!savedToDb) {
            log.accept("[БД]: база недоступна, граф строится без сохранения (локальные ID).");
            long localId = 1;
            for (Group group : groups.values()) {
                group.node = withId(group.node, localId++);
            }
        }
        stageMillis.put("База данных", System.currentTimeMillis() - t);

        // Два разных ключа могут дать одно значение в БД (value UNIQUE) — тогда это один узел
        Map<Long, ConceptEntity> nodesById = new LinkedHashMap<>();
        Map<Long, Integer> frequency = new HashMap<>();
        for (Group group : groups.values()) {
            nodesById.putIfAbsent(group.node.id(), group.node);
            frequency.merge(group.node.id(), group.mentions.size(), Integer::sum);
        }
        List<ConceptEntity> nodes = new ArrayList<>(nodesById.values());

        // 5. Рёбра: Ollama, а если она не дала ни одного — по совместным предложениям
        t = System.currentTimeMillis();
        List<Edge> edges = new ArrayList<>();
        if (ollama) {
            edges = buildAiEdges(groups, documents);
        }
        if (edges.isEmpty()) {
            edges = buildCooccurrenceEdges(groups, documents);
        }
        if (savedToDb && !edges.isEmpty()) {
            try {
                ConceptRepository.saveGraphEdges(edges);
            } catch (Exception | LinkageError e) {
                logError("[БД]: не удалось сохранить рёбра графа: " + e.getMessage());
            }
        }
        stageMillis.put("Рёбра графа", System.currentTimeMillis() - t);

        return new Result(nodes, frequency, edges, reports, dates, errors, stageMillis, savedToDb);
    }

    // ---------------------------------------------------------------- омонимы

    private Map<String, Group> splitHomonyms(Map<String, Group> groups, List<DocumentInput> documents) {
        Map<String, Group> result = new LinkedHashMap<>();
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            Group group = entry.getValue();
            if (group.fixedType == EntityType.PERSON) {
                result.put(entry.getKey(), group);
                continue;
            }
            String word = mostFrequentSurface(group);
            List<HomonymResolver.Meaning> candidates;
            try {
                candidates = HomonymResolver.homonymCandidates(word, meaningSource.findMeanings(word));
            } catch (Exception e) {
                logError("[Википедия] " + word + ": " + e.getMessage());
                candidates = List.of();
            }
            if (candidates.isEmpty()) {
                result.put(entry.getKey(), group);
                continue;
            }
            // Значение выбираем для каждого вхождения по его предложению
            for (Mention mention : group.mentions) {
                String sentence = OpenNlpEngine.sentenceAround(
                        documents.get(mention.doc()).text(), mention.start(), mention.end());
                HomonymResolver.Choice choice = homonymResolver.choose(sentence, candidates);
                String title = choice.meaning().title();
                Group meaningGroup = result.computeIfAbsent(entry.getKey() + "#" + title, k -> new Group());
                meaningGroup.fixedValue = title;
                meaningGroup.fixedType = HomonymResolver.typeHint(title);
                meaningGroup.mentions.add(mention);
            }
            log.accept("[Омоним] " + word + ": варианты " + candidates.stream()
                    .map(HomonymResolver.Meaning::title).toList());
        }
        return result;
    }

    // ---------------------------------------------------------------- узлы

    private ConceptEntity buildNode(Group group, List<DocumentInput> documents, boolean ollama) {
        Mention first = group.mentions.get(0);
        String value = group.fixedValue != null ? group.fixedValue : mostFrequentSurface(group);
        EntityType type = group.fixedType != null ? group.fixedType : bestType(group);
        ConceptEntity node = new ConceptEntity(value, type, first.start(), first.end());

        // Людей и омонимы с уточнённым типом Ollama не переспрашиваем
        if (ollama && group.fixedType == null) {
            String sentence = OpenNlpEngine.sentenceAround(documents.get(first.doc()).text(), first.start(), first.end());
            try {
                ConceptEntity resolved = aiEngine.resolveOmonymAndType(node, sentence);
                if (resolved != null) {
                    node = resolved;
                }
            } catch (Exception e) {
                reportFailure(value, e);
            }
        }
        return node;
    }

    /** Форма слова для узла — та, что чаще всего встречается в тексте (при равенстве — первая) */
    private static String mostFrequentSurface(Group group) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Mention mention : group.mentions) {
            counts.merge(mention.surface(), 1, Integer::sum);
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    /** Тип из словаря важнее PRODUCT «по умолчанию»: если хоть одно вхождение узнано — берём его */
    private static EntityType bestType(Group group) {
        for (Mention mention : group.mentions) {
            if (mention.type() != EntityType.PRODUCT) {
                return mention.type();
            }
        }
        return EntityType.PRODUCT;
    }

    // ---------------------------------------------------------------- рёбра

    private List<Edge> buildAiEdges(Map<String, Group> groups, List<DocumentInput> documents) {
        Map<String, Edge> merged = new LinkedHashMap<>();
        for (int d = 0; d < documents.size(); d++) {
            List<ConceptEntity> docNodes = new ArrayList<>();
            for (Group group : groups.values()) {
                final int doc = d;
                if (group.mentions.stream().anyMatch(m -> m.doc() == doc)) {
                    docNodes.add(group.node);
                }
            }
            try {
                for (Edge edge : aiEngine.buildSemanticConnections(docNodes, documents.get(d).text())) {
                    mergeEdge(merged, edge.sourceConceptId(), edge.targetConceptId(), edge.weight());
                }
            } catch (Exception e) {
                reportFailure("EDGES_" + documents.get(d).name(), e);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * Запасные рёбра без ИИ: два понятия связаны, если стоят в одном предложении.
     * Вес растёт с числом таких предложений: 1 раз — 0.3, 2 — 0.5, 3 — 0.7 … максимум 1.0.
     */
    private List<Edge> buildCooccurrenceEdges(Map<String, Group> groups, List<DocumentInput> documents) {
        // (документ, начало предложения) -> узлы в этом предложении
        Map<String, Set<Long>> sentences = new LinkedHashMap<>();
        for (Group group : groups.values()) {
            for (Mention mention : group.mentions) {
                String text = documents.get(mention.doc()).text();
                String sentenceKey = mention.doc() + ":" + OpenNlpEngine.sentenceStartOf(text, mention.start());
                sentences.computeIfAbsent(sentenceKey, k -> new LinkedHashSet<>()).add(group.node.id());
            }
        }

        Map<String, Integer> pairCounts = new LinkedHashMap<>();
        for (Set<Long> ids : sentences.values()) {
            List<Long> list = new ArrayList<>(ids);
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    long a = Math.min(list.get(i), list.get(j));
                    long b = Math.max(list.get(i), list.get(j));
                    pairCounts.merge(a + "-" + b, 1, Integer::sum);
                }
            }
        }

        List<Edge> edges = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : pairCounts.entrySet()) {
            String[] ids = entry.getKey().split("-");
            double weight = Math.min(1.0, 0.3 + 0.2 * (entry.getValue() - 1));
            edges.add(new Edge(Long.parseLong(ids[0]), Long.parseLong(ids[1]), weight, "связан"));
        }
        return edges;
    }

    private static void mergeEdge(Map<String, Edge> merged, Long a, Long b, double weight) {
        long min = Math.min(a, b);
        long max = Math.max(a, b);
        String key = min + "-" + max;
        Edge existing = merged.get(key);
        if (existing == null || existing.weight() < weight) {
            merged.put(key, new Edge(min, max, weight, "связан"));
        }
    }

    // ---------------------------------------------------------------- БД

    /*
     * Пул соединений инициализируется в статическом блоке и при недоступной базе
     * бросает ExceptionInInitializerError / NoClassDefFoundError (это Error, а не Exception),
     * поэтому ловим ещё и LinkageError.
     */
    private boolean saveToDatabase(Map<String, Group> groups, List<DocumentInput> documents) {
        Long[] documentIds = new Long[documents.size()];
        for (int d = 0; d < documents.size(); d++) {
            DocumentInput doc = documents.get(d);
            try {
                documentIds[d] = ConceptRepository.saveDocument(doc.name(), doc.path(), doc.size());
            } catch (Exception | LinkageError e) {
                logError("[БД]: не удалось сохранить документ " + doc.name() + ": " + e.getMessage());
                return false;
            }
        }

        for (Group group : groups.values()) {
            Long conceptId;
            try {
                conceptId = ConceptRepository.saveConcept(group.node.value(), group.node.type());
            } catch (Exception | LinkageError e) {
                logError("[БД]: не удалось сохранить понятие " + group.node.value() + ": " + e.getMessage());
                return false;
            }
            group.node = withId(group.node, conceptId);

            for (Mention mention : group.mentions) {
                try {
                    ConceptRepository.saveOccurrences(documentIds[mention.doc()], conceptId, mention.start());
                } catch (Exception | LinkageError e) {
                    logError("[БД]: не удалось сохранить позицию " + mention.start()
                            + " слова " + group.node.value() + ": " + e.getMessage());
                    break;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- служебное

    private static boolean overlapsPerson(ConceptEntity noun, List<PersonExtractor.PersonMention> persons) {
        for (PersonExtractor.PersonMention p : persons) {
            if (noun.startChar() < p.endChar() && p.startChar() < noun.endChar()) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsDate(ConceptEntity noun, List<DateExtractor.DateMention> dates) {
        for (DateExtractor.DateMention d : dates) {
            if (noun.startChar() < d.endChar() && d.startChar() < noun.endChar()) {
                return true;
            }
        }
        return false;
    }

    /** Журнал ошибок пишет в БД; если база лежит, это не должно ронять анализ */
    private void reportFailure(String word, Exception error) {
        logError("[Ошибка] " + word + ": " + error.getMessage());
        try {
            exceptionHandler.CatchTheFailure(word, error);
        } catch (Exception | LinkageError ignored) {
            // уже записано в errors и в лог
        }
    }

    private void logError(String message) {
        errors.add(message);
        log.accept(message);
    }

    private static ConceptEntity withId(ConceptEntity concept, Long id) {
        return new ConceptEntity(id, concept.value(), concept.type(), concept.startChar(), concept.endChar());
    }
}
