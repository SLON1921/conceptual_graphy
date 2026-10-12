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

    /** Число ссылок в статье (для wiki_cache); 0, если источник этого не умеет */
    default int countLinks(String title) {
        return 0;
    }
}
