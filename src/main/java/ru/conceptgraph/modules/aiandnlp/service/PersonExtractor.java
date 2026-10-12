package ru.conceptgraph.modules.aiandnlp.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Находит людей в тексте и склеивает разные записи одного человека:
 * «Михаил Ломоносов», «М. Ломоносов», «Ломоносов М.В.», «М.В. Ломоносова» -> один человек.
 */
public class PersonExtractor {

    /**
     * Одно упоминание человека в тексте.
     *
     * @param canonical общее имя для всех записей этого человека («Михаил Ломоносов»)
     * @param key       ключ склейки: основа фамилии + первая буква имени
     */
    public record PersonMention(String text, String canonical, String key, int startChar, int endChar) {
    }

    private static final String UPPER = "[А-ЯЁ]";
    private static final String WORD = UPPER + "[а-яё]+";
    private static final String SURNAME = UPPER + "[а-яё]{2,}(?:-" + UPPER + "[а-яё]+)?";

    // М. Ломоносов / М.В. Ломоносов / М. В. Ломоносов
    private static final Pattern INITIALS_SURNAME = Pattern.compile(
            "(?<![\\p{L}])(" + UPPER + ")\\.\\s?(?:(" + UPPER + ")\\.\\s?)?(" + SURNAME + ")");
    // Ломоносов М.В. / Ломоносов М. В.
    private static final Pattern SURNAME_INITIALS = Pattern.compile(
            "(?<![\\p{L}])(" + SURNAME + ")\\s(" + UPPER + ")\\.\\s?(?:(" + UPPER + ")\\.)?");
    // Михаил Ломоносов / Михаил Васильевич Ломоносов (имя проверяется по словарю)
    private static final Pattern NAME_SURNAME = Pattern.compile(
            "(?<![\\p{L}])(" + WORD + ")\\s+(?:(" + WORD + "(?:вич|вна|ична|ич)[а-я]{0,2})\\s+)?(" + SURNAME + ")");

    // Типичные окончания фамилий: Петров, Ковалева, Пушкин, Достоевский, Толстая
    private static final Pattern SURNAME_SUFFIX = Pattern.compile(
            ".*(ов|ев|ёв|ин|ын|ск|цк)(а|ой|у|ым|ом|е|ых|ий|ая|ого|ой)?$|.*(ский|цкий|ская|цкая)$");

    private record Raw(int start, int end, String firstName, String initial, String surname) {
    }

    public List<PersonMention> extractPersons(String text) {
        List<PersonMention> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        List<Raw> raws = new ArrayList<>();

        Matcher m = NAME_SURNAME.matcher(text);
        while (m.find()) {
            String first = m.group(1);
            String surname = m.group(3);
            // «Иван Пётр» — два имени, а «Иван Петров» — имя и фамилия (у «Петров» основа как у «Пётр»)
            if (EntityDictionaries.isFirstName(first)
                    && (!EntityDictionaries.isFirstName(surname) || SURNAME_SUFFIX.matcher(surname).matches())) {
                raws.add(new Raw(m.start(), m.end(), first, first.substring(0, 1), surname));
            }
        }
        m = INITIALS_SURNAME.matcher(text);
        while (m.find()) {
            raws.add(new Raw(m.start(), m.end(), null, m.group(1), m.group(3)));
        }
        m = SURNAME_INITIALS.matcher(text);
        while (m.find()) {
            raws.add(new Raw(m.start(), m.end(), null, m.group(2), m.group(1)));
        }

        // Пересекающиеся находки: оставляем более длинную
        raws.sort(Comparator.comparingInt(Raw::start).thenComparing(r -> -(r.end() - r.start())));
        List<Raw> mentions = new ArrayList<>();
        int lastEnd = -1;
        for (Raw raw : raws) {
            if (raw.start() >= lastEnd) {
                mentions.add(raw);
                lastEnd = raw.end();
            }
        }

        // Каноническое имя для каждого человека: первая полная запись «Имя Фамилия», иначе «И. Фамилия»
        Map<String, String> canonicalByKey = new LinkedHashMap<>();
        for (Raw raw : mentions) {
            String key = key(raw);
            if (raw.firstName() != null && !hasFullName(canonicalByKey.get(key))) {
                canonicalByKey.put(key, raw.firstName() + " " + raw.surname());
            } else {
                canonicalByKey.putIfAbsent(key, raw.initial() + ". " + raw.surname());
            }
        }

        for (Raw raw : mentions) {
            String key = key(raw);
            result.add(new PersonMention(text.substring(raw.start(), raw.end()).trim(),
                    canonicalByKey.get(key), key, raw.start(), raw.end()));
        }
        return result;
    }

    /** Склеиваем по фамилии (без падежа) и первой букве имени: «Ломоносова» + «М» = «Ломоносов» + «М» */
    private static String key(Raw raw) {
        return RussianMorphology.stemKey(raw.surname()) + "|" + raw.initial().toUpperCase();
    }

    private static boolean hasFullName(String canonical) {
        return canonical != null && !canonical.matches(UPPER + "\\. .*");
    }
}
