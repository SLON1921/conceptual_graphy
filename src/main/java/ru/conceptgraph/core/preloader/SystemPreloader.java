package ru.conceptgraph.core.preloader;

import javafx.application.Platform;
import javafx.application.Preloader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.InputStream;
import java.sql.Connection;
import java.util.Properties;

public class SystemPreloader extends Preloader {

    private Stage preloaderStage;
    private ProgressBar progressBar;
    private Label statusLabel;

    @Override
    public void start(Stage primaryStage) {
        this.preloaderStage = primaryStage;

        // Настройка легковесного UI без стандартных рамок ОС
        primaryStage.initStyle(StageStyle.UNDECORATED);

        progressBar = new ProgressBar(0.0);
        progressBar.setPrefWidth(350);

        statusLabel = new Label("Инициализация ядра системы...");
        statusLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 12px;");

        Label titleLabel = new Label("Понятийный Граф");
        titleLabel.setStyle("-fx-text-fill: #1E88E5; -fx-font-size: 20px; -fx-font-weight: bold;");

        VBox root = new VBox(15, titleLabel, progressBar, statusLabel);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #D3D3D3; -fx-border-width: 1;");

        Scene scene = new Scene(root, 400, 180);
        primaryStage.setScene(scene);
        primaryStage.show();

        // Запуск сквозного тестирования окружения в фоновом потоке
        Thread validationThread = new Thread(this::runSystemChecks);
        validationThread.setDaemon(true);
        validationThread.start();
    }

    /**
     * Конвейер проверок готовности инфраструктуры
     */
    private void runSystemChecks() {
        try {
            // Шаг 1: Проверка версии Java Runtime Environment (JRE)
            updateProgress("Проверка версии JRE...", 0.2);
            validateJreVersion();
            Thread.sleep(300); // Искусственная микро-пауза для плавности UI

            // Шаг 2: Валидация файла конфигурации application.properties
            updateProgress("Чтение конфигурации приложения...", 0.5);
            Properties config = loadAndValidateProperties();
            Thread.sleep(300);

            // Шаг 3: Тестирование доступности пула соединений PostgreSQL
            updateProgress("Проверка пула соединений PostgreSQL...", 0.8);
            validateDatabaseConnection(config);

            // Финализация
            updateProgress("Все системы готовы к работе", 1.0);
            Thread.sleep(400);

            // Закрытие прелоадера и переход к LoginFrame
            Platform.runLater(() -> preloaderStage.close());

        } catch (Exception e) {
            Platform.runLater(() -> {
                showCriticalErrorAlert(e.getMessage());
                System.exit(1); // Блокировка дальнейшего запуска
            });
        }
    }

    /**
     * Валидация версии JRE (Требование ТЗ: Версия 21+)
     */
    private void validateJreVersion() {
        String version = System.getProperty("java.version");
        int majorVersion = Runtime.version().feature();

        if (majorVersion < 21) {
            throw new IllegalStateException(
                    "Неподдерживаемая версия JRE: " + version + ". Требуется версия Java 21 или выше."
            );
        }
    }

    /**
     * Проверка структуры и наличия файла настроек application.properties
     */
    private Properties loadAndValidateProperties() throws Exception {
        Properties props = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                throw new IllegalStateException("Критическая ошибка: Файл application.properties не найден в resources.");
            }
            props.load(input);
        }

        // Проверка обязательных ключей
        String[] requiredKeys = {"db.url", "db.user", "db.password"};
        for (String key : requiredKeys) {
            if (!props.containsKey(key) || props.getProperty(key).trim().isEmpty()) {
                throw new IllegalArgumentException("Ошибка конфигурации: Ключ '" + key + "' отсутствует или пуст.");
            }
        }
        return props;
    }

    /**
     * Тестовое поднятие пула соединений HikariCP и пинг СУБД PostgreSQL
     */
    private void validateDatabaseConnection(Properties props) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(props.getProperty("db.url"));
        config.setUsername(props.getProperty("db.user"));
        config.setPassword(props.getProperty("db.password"));

        // Жесткие лимиты тайм-аута, чтобы прелоадер не завис навечно при "лежащей" базе
        config.setConnectionTimeout(15000); // 4 секунды на ожидание
        config.setValidationTimeout(2000);
        config.setMaximumPoolSize(2);      // Минимальный тестовый размер

        try (HikariDataSource dataSource = new HikariDataSource(config);
             Connection connection = dataSource.getConnection()) {

            if (connection == null || connection.isClosed()) {
                throw new IllegalStateException("Не удалось получить активное соединение из пула HikariCP.");
            }
            // Выполняем базовый пинг-запрос
            try (var statement = connection.createStatement()) {
                statement.executeQuery("SELECT 1");
            }
        } catch (Exception e) {
            throw new RuntimeException("PostgreSQL недоступен: " + e.getMessage(), e);
        }
    }

    /**
     * Безопасное обновление состояния графических элементов из фонового потока
     */
    private void updateProgress(String status, double progress) {
        Platform.runLater(() -> {
            statusLabel.setText(status);
            progressBar.setProgress(progress);
        });
    }

    /**
     * Вывод модального окна в случае падения одной из проверок
     */
    private void showCriticalErrorAlert(String errorMessage) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Ошибка запуска приложения");
        alert.setHeaderText("Критический сбой окружения (SystemPreloader)");
        alert.setContentText(errorMessage);
        alert.showAndWait();
    }
}
