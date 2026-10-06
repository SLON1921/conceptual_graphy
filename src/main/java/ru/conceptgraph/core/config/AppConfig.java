package ru.conceptgraph.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * <h2>Центральный реестр конфигурации приложения </h2>
 * <p>Класс отвечает за загрузку, потокобезопасное хранение в оперативной памяти
 * и валидацию системных настроек из файла {@code application.properties}.</p>
 *
 * <p>Инкапсулирует в себе конфигурационные данные, предоставляя остальным модулям
 * системы (лингвистическому, инфраструктурному, аналитическому) неизменяемый (immutable)
 * доступ к параметрам СУБД и API-ключам искусственного интеллекта.</p>
 *
 * @author Рома
 * @version 1.0
 */
public class AppConfig {
    /**
     * Внутреннее потокобезопасное хранилище пар "ключ-значение".
     * Модификатор {@code private} обеспечивает инкапсуляцию данных от прямого вмешательства извне.
     * Модификатор {@code static} гарантирует существование объекта в единственном экземпляре в памяти JVM.
     * Модификатор {@code final} защищает ссылку от переинициализации и предотвращает утечки памяти.
     */
    private static final Properties properties = new Properties();

    /**
     * @return URL-строка подключения к СУБД PostgreSQL
     */
    public static String getDbUrl() {
        return properties.getProperty("db.url");
    }

    /**
     * @return Имя пользователя для авторизации в базе данных PostgreSQL
     */
    public static String getDbUsername() {
        return properties.getProperty("db.username");
    }

    /**
     * @return Пароль для авторизации в базе данных PostgreSQL
     */
    public static String getDbPassword() {
        return properties.getProperty("db.password");
    }

    /**
     * @return Авторизационный токен/API-ключ для интеграции с ИИшкой
     */
    public static int getMaxPoolSize() {
        return Integer.parseInt(properties.getProperty("db.pool.max-size").trim());
    }

    /**
     * <h3>Загрузка и потоковая обработка файла конфигурации</h3>
     * <p>Метод вызывается строго один раз главным потоком {@code Main Thread} при старте метода {@code main}.
     * Использует механизм {@code ClassLoader} для извлечения файла из путей сборки (Classpath),
     * что обеспечивает корректную кроссплатформенную работу приложения после его упаковки в итоговый {@code .jar} архив.</p>
     *
     * <p>Применяет конструкцию {@code try-with-resources} для автоматического управления жизненным циклом
     * файловых потоков.</p>
     *
     * @throws RuntimeException Если файл отсутствует в ресурсах, поврежден или не прошел Fail-Fast валидацию ключей.
     */
    public static void loadProperties() {
        System.out.println("[CONFIG] Чтение конфигурационного файла application.properties...");

        try (InputStream input = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                throw new RuntimeException("Критическая ошибка: Файл application.properties не найден в директории resources!");
            }

            properties.load(input);

            // ПРОВЕРКА НА ПУСТОТУ
            validateProperty("db.url");
            validateProperty("db.user");
            validateProperty("db.password");
            validateProperty("llm.api.key");

            System.out.println("[CONFIG] Конфигурация успешно загружена. Все обязательные ключи на месте.");

        } catch (IOException ex) {
            throw new RuntimeException("Не удалось обработать поток данных application.properties", ex);
        }
    }

    /**
     * <h3>Внутреннее защитное условие (Guard Clause)</h3>
     * <p>Выполняет строгую проверку переданного текстового ключа на предмет его физического наличия в конфигурации,
     * а также на отсутствие пустых значений или строк, состоящих только из пробелов.</p>
     *
     * <p>Модификатор {@code private} скрывает метод от автокомплита в других модулях (Инкапсуляция процесса валидации).
     * Модификатор {@code static} позволяет вызывать метод без привязки к экземпляру класса.</p>
     *
     * @param key Строковое имя проверяемого параметра (например, "db.url")
     * @throws RuntimeException Если ключ отсутствует или значение является пустым/содержит только пробелы.
     */
    private static void validateProperty(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new RuntimeException("Ошибка валидации настроек! Отсутствует или пуст обязательный параметр: " + key);
        }
    }
}

