package ru.conceptgraph.modules.aiandnlp.service;

import java.util.List;

/**
 * Откуда брать варианты значений слова для HomonymResolver.
 * Основная реализация — WikipediaMeaningSource; в тестах можно подставить свой список.
 */
public interface MeaningSource {

    /**
     * @return варианты в порядке популярности; пустой список, если вариантов нет или источник недоступен
     */
    List<HomonymResolver.Meaning> findMeanings(String word);
}
