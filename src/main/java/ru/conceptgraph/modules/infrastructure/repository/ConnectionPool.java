package ru.conceptgraph.modules.infrastructure.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import java.io.InputStream;
import java.util.Properties;
import java.sql.Connection;
import java.sql.SQLException;
import java.io.IOException;

/**
 * Компонент инфраструктурного слоя для управления пулом соединений с БД PostgreSQL.
 * Реализует высокопроизводительное управление подключениями с помощью библиотеки HikariCP
 * и автоматически управляет миграциями схемы базы данных через Flyway.
 *
 * <p>Класс автоматически инициализирует пул при первом обращении в статическом блоке,
 * считывая параметры конфигурации из файла {@code application.properties}, и выполняет проверку
 * и обновление таблиц БД через Flyway, и предоставляет потокобезопасный доступ к соединениям.</p>
 *
 * <p><b>Примененные оптимизации кэширования для PostgreSQL:</b></p>
 * <ul>
 *   <li>{@code cachePrepStmts} — включено кэширование подготовленных выражений (PreparedStatement)</li>
 *   <li>{@code prepStmtCacheSize} — размер кэша установлен на 250 выражений</li>
 *   <li>{@code prepStmtCacheSqlLimit} — максимальная длина кэшируемого SQL-запроса (2048 символов)</li>
 * </ul>
 *
 * @author Рома (Разработчик Б — Инфраструктура и БД)
 * @version 1.1
 * @since Этап 1
 */
public class ConnectionPool {

    /** Экземпляр источника данных HikariCP, разделяемый между всеми потоками приложения */
    private static final HikariDataSource dataSource;

    /** Приватный конструктор для предотвращения создания экземпляров утилитарного класса */
    private ConnectionPool() {}

    // Статический блок инициализации пула соединений
    static {
        try (InputStream input = ConnectionPool.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input == null) {
                throw new RuntimeException("Не существует файла application.properties");
            }
            Properties properties = new Properties();
            properties.load(input);

            HikariConfig config = new HikariConfig();

            // Основные параметры авторизации в БД
            config.setJdbcUrl(properties.getProperty("db.url"));
            config.setUsername(properties.getProperty("db.username"));
            config.setPassword(properties.getProperty("db.password"));

            // Настройка размеров пула
            int maxPoolSize = Integer.parseInt(properties.getProperty("db.pool.max-size", "10"));
            config.setMaximumPoolSize(maxPoolSize);
            config.setMinimumIdle(2);

            // Настройка таймаутов (в миллисекундах)
            config.setIdleTimeout(30000);        // 30 секунд удержания простаивающего соединения
            config.setConnectionTimeout(20000);   // 20 секунд ожидания свободного соединения из пула

            // Оптимизация производительности работы с PreparedStatement
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

            dataSource = new HikariDataSource(config);
            System.out.println("[HikariCP] Пул соединений с PostgreSQL успешно запущен.");

            System.out.println("[Flyway] Проверка и применение миграций базы данных...");
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();
            flyway.migrate();
            System.out.println("[Flyway] Миграции успешно применены. База данных актуальна.");

        } catch (IOException e) {
            throw new ExceptionInInitializerError("Не удалось прочесть файл свойств БД: " + e.getMessage());
        } catch (Exception e) {
            throw new ExceptionInInitializerError("Ошибка инициализации пула HikariCP: " + e.getMessage());
        }
    }

    /**
     * Запрашивает активное соединение с базой данных из пула HikariCP.
     *
     * @return Объект {@link Connection} готовый для выполнения SQL-запросов
     * @throws SQLException Если пул не инициализирован или исчерпан таймаут ожидания соединения
     */
    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("Пул соединений не был инициализирован!");
        }
        return dataSource.getConnection();
    }

    /**
     * Возвращает прямой экземпляр источника данных HikariDataSource.
     * Требуется для интеграции со сторонними библиотеками миграции или тестирования (Flyway).
     *
     * @return Текущий экземпляр {@link HikariDataSource}
     */
    public static HikariDataSource getDataSource(){
        return dataSource;
    }

    /**
     * Корректно останавливает пул соединений HikariCP и закрывает все активные
     * физические подключения к PostgreSQL, предотвращая утечки ресурсов.
     */
    public static void shutdown(){
        if (dataSource != null) {
            dataSource.close();
            System.out.println("[HikariCP] Пул соединений успешно остановлен.");
        }
    }
}
