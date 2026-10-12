package ru.conceptgraph.modules.aiandnlp.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ищет даты в тексте: «12.10.2026», «12 октября 2026 г.», «октябрь 2026», «1941 год», «в 1812 г.».
 */
public class DateExtractor {

    /** Найденная дата: как написано в тексте, в нормальном виде (2026-10-12 / 2026-10 / 1941) и позиции */
    public record DateMention(String text, String normalized, int startChar, int endChar) {
    }

    // Корни месяцев: «октябр» подходит под «октябрь», «октября», «октябре»
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("январ", 1), Map.entry("феврал", 2), Map.entry("март", 3),
            Map.entry("апрел", 4), Map.entry("ма", 5), Map.entry("июн", 6),
            Map.entry("июл", 7), Map.entry("август", 8), Map.entry("сентябр", 9),
            Map.entry("октябр", 10), Map.entry("ноябр", 11), Map.entry("декабр", 12)
    );
    private static final String MONTH =
            "(январ[ьяе]|феврал[ьяе]|марта?|марте|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|августа?|августе|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе])";

    // 12.10.2026, 1.5.1945, 12/10/2026
    private static final Pattern NUMERIC = Pattern.compile(
            "(?<!\\d)(\\d{1,2})[./](\\d{1,2})[./](\\d{4})(?!\\d)");
    // 12 октября 2026 г. / 9 мая 1945 года / 12 октября (без года)
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile(
            "(?<!\\d)(\\d{1,2})\\s+" + MONTH + "(?:\\s+(\\d{4})(?:\\s*(?:г\\.|года?|году))?)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // октябрь 2026 / в мае 1945 г.
    private static final Pattern MONTH_YEAR = Pattern.compile(
            "(?<![\\p{L}\\d])" + MONTH + "\\s+(\\d{4})(?:\\s*(?:г\\.|года?|году))?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // 1941 год / в 1812 г. / 2026 году — голое число без «год» не берём, это может быть что угодно
    private static final Pattern YEAR = Pattern.compile(
            "(?<!\\d)(\\d{3,4})\\s*(?:г\\.|гг\\.|год[уа]?(?![\\p{L}]))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public List<DateMention> extractDates(String text) {
        List<DateMention> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        // Порядок важен: сначала полные даты, потом короткие. Короткий шаблон не должен
        // ещё раз найти «2026 г.» внутри уже найденного «12 октября 2026 г.»
        Matcher m = NUMERIC.matcher(text);
        while (m.find()) {
            int day = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            if (isValid(day, month)) {
                add(result, m, String.format("%s-%02d-%02d", m.group(3), month, day));
            }
        }

        m = DAY_MONTH_YEAR.matcher(text);
        while (m.find()) {
            int day = Integer.parseInt(m.group(1));
            int month = monthNumber(m.group(2));
            if (isValid(day, month)) {
                String year = m.group(3) != null ? m.group(3) : "????";
                add(result, m, String.format("%s-%02d-%02d", year, month, day));
            }
        }

        m = MONTH_YEAR.matcher(text);
        while (m.find()) {
            add(result, m, String.format("%s-%02d", m.group(2), monthNumber(m.group(1))));
        }

        m = YEAR.matcher(text);
        while (m.find()) {
            add(result, m, m.group(1));
        }

        result.sort(Comparator.comparingInt(DateMention::startChar));
        return result;
    }

    /** Добавляет дату, если она не пересекается с уже найденной (более полной) */
    private void add(List<DateMention> result, Matcher m, String normalized) {
        for (DateMention existing : result) {
            if (m.start() < existing.endChar() && existing.startChar() < m.end()) {
                return;
            }
        }
        result.add(new DateMention(m.group().trim(), normalized, m.start(), m.end()));
    }

    private int monthNumber(String word) {
        String lower = word.toLowerCase();
        // «ма» короче остальных корней, поэтому сначала ищем самый длинный подходящий корень
        String best = null;
        for (String root : MONTHS.keySet()) {
            if (lower.startsWith(root) && (best == null || root.length() > best.length())) {
                best = root;
            }
        }
        return best == null ? 0 : MONTHS.get(best);
    }

    private boolean isValid(int day, int month) {
        return day >= 1 && day <= 31 && month >= 1 && month <= 12;
    }
}
