package ru.conceptgraph.modules.aiandnlp.service;

import ru.conceptgraph.core.errors.AppExceptionHandler;
import ru.conceptgraph.modules.infrastructure.repository.ConceptRepository;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.Edge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Общий конвейер анализа одного документа для консольной версии и окна:
 * OpenNLP -> уточнение типа в Ollama -> сохранение в БД -> рёбра графа.
 *
 * <p>Раньше MainApplication и MainWindow делали это каждый по-своему и с разными ошибками
 * (весь документ уходил в Ollama, тип от ИИ терялся, одно слово превращалось во много узлов,
 * сбой Ollama обрывал анализ в окне).</p>
 */
public class ConceptGraphPipeline {

    /**
     * Результат анализа документа.
     *
     * @param nodes       уникальные понятия (узлы графа), по одному на слово
     * @param occurrences все вхождения слов с позициями в тексте
     * @param edges       рёбра графа между ID узлов
     * @param savedToDb   true, если узлы получили настоящие ID из PostgreSQL;
     *                    false — база недоступна, ID локальные (1..n) и в БД ничего не записано
     */
    public record Result(List<ConceptEntity> nodes,
                         List<ConceptEntity> occurrences,
                         List<Edge> edges,
                         boolean savedToDb) {
    }

    private final OpenNlpEngine nlpEngine;
    private final AiGraphEngine aiEngine;
    private final AppExceptionHandler exceptionHandler;
    private final Consumer<String> log;

    public ConceptGraphPipeline(OpenNlpEngine nlpEngine,
                                AiGraphEngine aiEngine,
                                AppExceptionHandler exceptionHandler,
                                Consumer<String> log) {
        this.nlpEngine = nlpEngine;
        this.aiEngine = aiEngine;
        this.exceptionHandler = exceptionHandler;
        this.log = log;
    }

    public Result process(String name, String path, long size, String text) {
        // 1. Все вхождения существительных с позициями
        List<ConceptEntity> occurrences = nlpEngine.extractNouns(text);
        log.accept("Извлечено вхождений (OpenNLP): " + occurrences.size());

        // 2. Схлопываем вхождения в уникальные понятия (без учёта регистра:
        //    «Москва» в начале предложения и «москва» — один узел)
        Map<String, List<ConceptEntity>> byValue = new LinkedHashMap<>();
        for (ConceptEntity occurrence : occurrences) {
            String key = occurrence.value().toLowerCase(Locale.ROOT);
            byValue.computeIfAbsent(key, k -> new ArrayList<>()).add(occurrence);
        }
        log.accept("Уникальных понятий: " + byValue.size());

        // 3. Тип уточняем в Ollama один раз на понятие, по предложению первого вхождения
        List<ConceptEntity> resolvedNodes = new ArrayList<>();
        for (List<ConceptEntity> group : byValue.values()) {
            resolvedNodes.add(resolveType(group.get(0), text));
        }

        // 4. Сохраняем документ, понятия и позиции в БД
        boolean savedToDb = true;
        List<ConceptEntity> nodes = new ArrayList<>();
        Long documentId = saveDocument(name, path, size);
        if (documentId == null) {
            savedToDb = false;
        } else {
            int i = 0;
            for (List<ConceptEntity> group : byValue.values()) {
                ConceptEntity node = resolvedNodes.get(i++);
                Long conceptId = saveConcept(node);
                if (conceptId == null) {
                    savedToDb = false;
                    break;
                }
                nodes.add(withId(node, conceptId));
                saveOccurrences(documentId, conceptId, group);
            }
        }

        // База недоступна: даём узлам локальные ID, чтобы граф всё равно построился в окне
        if (!savedToDb) {
            log.accept("[БД]: база недоступна, граф строится без сохранения (локальные ID).");
            nodes.clear();
            long localId = 1;
            for (ConceptEntity node : resolvedNodes) {
                nodes.add(withId(node, localId++));
            }
        }

        // 5. Рёбра графа
        List<Edge> edges = new ArrayList<>();
        try {
            edges = aiEngine.buildSemanticConnections(nodes, text);
        } catch (Exception e) {
            reportFailure("EDGES_" + name, e);
        }

        if (savedToDb && !edges.isEmpty()) {
            try {
                ConceptRepository.saveGraphEdges(edges);
            } catch (Exception | LinkageError e) {
                log.accept("[БД]: не удалось сохранить рёбра графа: " + e.getMessage());
            }
        }

        return new Result(nodes, occurrences, edges, savedToDb);
    }

    /**
     * Уточняет тип через Ollama. Сбой ИИ на одном слове не должен обрывать весь анализ:
     * пишем ошибку в журнал и оставляем тип, который определил OpenNLP.
     */
    private ConceptEntity resolveType(ConceptEntity concept, String text) {
        String sentence = OpenNlpEngine.sentenceAround(text, concept.startChar(), concept.endChar());
        try {
            ConceptEntity resolved = aiEngine.resolveOmonymAndType(concept, sentence);
            return resolved != null ? resolved : concept;
        } catch (Exception e) {
            reportFailure(concept.value(), e);
            return concept;
        }
    }

    /** Журнал ошибок пишет в БД; если база лежит, это не должно ронять анализ */
    private void reportFailure(String word, Exception error) {
        try {
            exceptionHandler.CatchTheFailure(word, error);
        } catch (Exception | LinkageError e) {
            log.accept("[Ошибка] " + word + ": " + error.getMessage());
        }
    }

    /*
     * Пул соединений Ромы инициализируется в статическом блоке и при недоступной базе
     * бросает ExceptionInInitializerError / NoClassDefFoundError (это Error, а не Exception),
     * поэтому ловим ещё и LinkageError.
     */

    private Long saveDocument(String name, String path, long size) {
        try {
            return ConceptRepository.saveDocument(name, path, size);
        } catch (Exception | LinkageError e) {
            log.accept("[БД]: не удалось сохранить документ " + name + ": " + e.getMessage());
            return null;
        }
    }

    private Long saveConcept(ConceptEntity concept) {
        try {
            return ConceptRepository.saveConcept(concept.value(), concept.type());
        } catch (Exception | LinkageError e) {
            log.accept("[БД]: не удалось сохранить понятие " + concept.value() + ": " + e.getMessage());
            return null;
        }
    }

    private void saveOccurrences(Long documentId, Long conceptId, List<ConceptEntity> group) {
        for (ConceptEntity occurrence : group) {
            try {
                ConceptRepository.saveOccurrences(documentId, conceptId, occurrence.startChar());
            } catch (Exception | LinkageError e) {
                log.accept("[БД]: не удалось сохранить позицию " + occurrence.startChar()
                        + " слова " + occurrence.value() + ": " + e.getMessage());
                return;
            }
        }
    }

    private static ConceptEntity withId(ConceptEntity concept, Long id) {
        return new ConceptEntity(id, concept.value(), concept.type(), concept.startChar(), concept.endChar());
    }
}
