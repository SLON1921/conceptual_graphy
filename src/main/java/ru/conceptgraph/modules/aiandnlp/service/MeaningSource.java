package ru.conceptgraph.modules.aiandnlp.service;

import java.util.List;

/**
 * Откуда брать варианты значений слова для HomonymResolver.
 * Основная реализация — WikipediaMeaningSource; в тестах можно подставить свой список.
 *
 * Сам файл ничего не делает, в нём только описание одного метода: «дай мне по слову список значений».
 * Реальную работу выполняет WikipediaMeaningSource: он ходит в Википедию и возвращает
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
