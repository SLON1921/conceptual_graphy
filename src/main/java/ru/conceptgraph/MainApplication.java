package ru.conceptgraph;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import ru.conceptgraph.core.config.AppConfig;
import ru.conceptgraph.core.errors.AppExceptionHandler;
import ru.conceptgraph.core.preloader.SystemPreloader;
import ru.conceptgraph.modules.aiandnlp.view.MainWindow;

/**
 * Точка входа: автотесты, потом SystemPreloader проверяет Java, настройки и PostgreSQL,
 * потом открывается главное окно. Само окно — в MainWindow.
 */
public class MainApplication extends Application {

    /**
     * Ждём, пока прелоадер пройдёт проверки: окно открывается только после них.
     * При ошибке прелоадер сам показывает сообщение и закрывает программу.
     */
    @Override
    public void init() throws InterruptedException {
        if (System.getProperty("javafx.preloader") != null) {
            // Окно прелоадера закроется раньше, чем откроется наше: без этого JavaFX решит, что окон нет, и выйдет
            Platform.setImplicitExit(false);
            SystemPreloader.awaitChecks();
        }
    }

    @Override
    public void start(Stage stage) {
        // Настройки БД из application.properties: без этого пул соединений не стартует.
        // Если файла нет, окно всё равно открываем — граф построится без сохранения в БД.
        try {
            AppConfig.loadProperties();
        } catch (RuntimeException e) {
            System.err.println("[CONFIG] " + e.getMessage());
        }

        new MainWindow(stage).show();
        Platform.setImplicitExit(true);
    }

    public static void main(String[] args) {
        System.out.println("[SYSTEM] Запуск режима автоматического самотестирования (Self-test mode)...");
        boolean testsPassed = runSelfTests();

        if (!testsPassed) {
            System.err.println("[CRITICAL ERROR] Автотесты не пройдены! Запуск графического интерфейса заблокирован.");
            System.exit(1); // Аварийно выключаем программу, не открывая окно
        }
        System.out.println("[SYSTEM] Все автотесты из src/tests успешно пройдены.");
        // Кастомный перехватчик необработанных ошибок
        Thread.setDefaultUncaughtExceptionHandler(new AppExceptionHandler());

        // Регистрируем SystemPreloader: JavaFX покажет его до главного окна
        System.setProperty("javafx.preloader", "ru.conceptgraph.core.preloader.SystemPreloader");
        launch(args);
    }

    private static boolean runSelfTests(){
        try {
            org.junit.platform.launcher.LauncherDiscoveryRequest request =
                    org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request()
                            .selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage("ru.conceptgraph"))
                            .build();

            org.junit.platform.launcher.Launcher launcher =
                    org.junit.platform.launcher.core.LauncherFactory.create();

            org.junit.platform.launcher.listeners.SummaryGeneratingListener listener =
                    new org.junit.platform.launcher.listeners.SummaryGeneratingListener();

            launcher.registerTestExecutionListeners(listener);

            // Выполнение тестов
            launcher.execute(request);

            org.junit.platform.launcher.listeners.TestExecutionSummary summary = listener.getSummary();

            // Логирование результатов в консоль разработчика
            System.out.printf("[SELF-TEST] Всего тестов запущено: %d%n", summary.getTestsStartedCount());
            System.out.printf("[SELF-TEST] Успешно: %d%n", summary.getTestsSucceededCount());
            System.out.printf("[SELF-TEST] Сбоев: %d%n", summary.getTestsFailedCount());

            if (summary.getTestsFailedCount() > 0) {
                System.err.println("[SELF-TEST] Детализация ошибок выполнения тестов:");
                summary.printFailuresTo(new java.io.PrintWriter(System.err));
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("[SELF-TEST ERROR] Не удалось инициализировать JUnit движок: " + e.getMessage());
            return false;
        }
    }
}