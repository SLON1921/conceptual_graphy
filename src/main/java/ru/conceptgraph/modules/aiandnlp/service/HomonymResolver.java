package ru.conceptgraph.modules.aiandnlp.service;

import ru.conceptgraph.shared.EntityType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Выбор значения омонима по контексту: «Сходня» — река или город.
 * Сравнивает слова предложения с первыми абзацами статей-кандидатов и берёт статью с наибольшим score.
 */
public class HomonymResolver {

    /** Один вариант значения: заголовок статьи («Сходня (река)») и её первые абзацы */
    public record Meaning(String title, String snippet) {
    }

    /** Выбранное значение и его оценка (сколько общих слов с предложением) */
    public record Choice(Meaning meaning, int score) {
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "это", "этот", "который", "которая", "также", "после", "через", "между",
            "более", "около", "было", "были", "будет", "году", "года", "своей", "своих"
    );

    /**
     * @param sentence   предложение, где стоит слово (OpenNlpEngine.sentenceAround)
     * @param candidates варианты в порядке популярности (первый — самый частый)
     * @return лучший вариант; при равном score — более популярный; null, если вариантов нет
     */
    public Choice choose(String sentence, List<Meaning> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Set<String> context = stems(sentence);

        Choice best = null;
        for (Meaning candidate : candidates) {
            Set<String> words = stems(candidate.title() + " " + candidate.snippet());
            words.retainAll(context); // остаются только общие с предложением слова
            int score = words.size();
            if (best == null || score > best.score()) { // строго больше: при равенстве остаётся более популярный
                best = new Choice(candidate, score);
            }
        }
        return best;
    }

    /**
     * Оставляет только статьи вида «Слово (уточнение)» — так в Википедии называются разные значения
     * одного слова. Если таких меньше двух, слово не омоним и выбирать нечего.
     */
    public static List<Meaning> homonymCandidates(String word, List<Meaning> meanings) {
        List<Meaning> result = new ArrayList<>();
        if (word == null || meanings == null) {
            return result;
        }
        String prefix = normalize(word) + " (";
        for (Meaning meaning : meanings) {
            if (normalize(meaning.title()).startsWith(prefix)) {
                result.add(meaning);
            }
        }
        return result.size() >= 2 ? result : List.of();
    }

    /** Тип по уточнению в скобках: «Орёл (река)» -> GEOGRAPHY, «Орёл (город)» -> GEOGRAPHY */
    public static EntityType typeHint(String title) {
        int open = title.indexOf('(');
        int close = title.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        for (String word : title.substring(open + 1, close).split("[^\\p{L}-]+")) {
            EntityType type = EntityDictionaries.lookup(word);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    private static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    /**
     * Разбивает текст на слова и обрезает их до 5 букв, чтобы разные падежи совпадали:
     * «станции» и «станция» -> «станц». Короткие слова (меньше 4 букв) и служебные выбрасываем.
     */
    private Set<String> stems(String text) {
        Set<String> result = new HashSet<>();
        if (text == null) {
            return result;
        }
        for (String word : normalize(text).split("[^\\p{L}]+")) {
            if (word.length() < 4 || STOP_WORDS.contains(word)) {
                continue;
            }
            result.add(word.length() > 5 ? word.substring(0, 5) : word);
        }
        return result;
    }
}
