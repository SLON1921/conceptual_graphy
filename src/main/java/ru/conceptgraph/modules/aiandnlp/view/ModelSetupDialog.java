package ru.conceptgraph.modules.aiandnlp.view;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import ru.conceptgraph.modules.aiandnlp.service.ollama.AiGraphEngine;
import ru.conceptgraph.modules.aiandnlp.service.ollama.HardwareInfo;
import ru.conceptgraph.modules.aiandnlp.service.ollama.ModelAdvisor;
import ru.conceptgraph.modules.aiandnlp.service.ollama.OllamaModels;

import java.util.List;
import java.util.Locale;

/**
 * Окно «Модель Ollama»: определяет оперативку и видеокарту (их можно поправить руками),
 * советует модель под это железо и ставит её. Если Ollama нет — сначала ставит саму Ollama.
 */
public class ModelSetupDialog {

    /** Те же стили, что у главного окна: тёмная тема «Графит» */
    private static final String WINDOW_CSS = "/ru/conceptgraph/modules/aiandnlp/view/window.css";

    private final Stage dialog = new Stage();
    private final OllamaModels models = new OllamaModels(new AiGraphEngine().getOllamaUrl());

    private final Spinner<Integer> ramSpinner = new Spinner<>(1, 512, 8);
    private final Spinner<Integer> vramSpinner = new Spinner<>(0, 96, 0);
    private final Label gpuLabel = new Label("Определяю железо…");
    private final Label adviceLabel = new Label();
    private final ComboBox<String> modelBox = new ComboBox<>();
    private final Label installedLabel = new Label();
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Label statusLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button installButton = new Button("Установить");
    private final Button closeButton = new Button("Закрыть");

    private List<String> installed = List.of();
    private boolean ollamaRunning;
    private volatile boolean closing;

    public ModelSetupDialog(Window owner) {
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Модель Ollama");
    }

    public void show() {
        ramSpinner.setEditable(true);
        vramSpinner.setEditable(true);
        ramSpinner.valueProperty().addListener((o, a, b) -> updateAdvice(true));
        vramSpinner.valueProperty().addListener((o, a, b) -> updateAdvice(true));

        modelBox.getItems().addAll(ModelAdvisor.MODELS);
        modelBox.setEditable(true); // можно вписать любую модель Ollama
        modelBox.setMaxWidth(Double.MAX_VALUE);
        modelBox.valueProperty().addListener((o, a, b) -> updateInstallButton());

        GridPane hardware = new GridPane();
        hardware.setHgap(10);
        hardware.setVgap(8);
        hardware.addRow(0, new Label("Оперативка, ГБ:"), ramSpinner);
        hardware.addRow(1, new Label("Память видеокарты NVIDIA, ГБ:"), vramSpinner);
        hardware.add(gpuLabel, 0, 2, 2, 1);
        gpuLabel.getStyleClass().add("status-text");

        adviceLabel.setWrapText(true);
        adviceLabel.setMinHeight(Region.USE_PREF_SIZE); // иначе длинный совет обрезается многоточием
        adviceLabel.setStyle("-fx-font-weight: bold;");
        installedLabel.setWrapText(true);
        installedLabel.getStyleClass().add("status-text");
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.managedProperty().bind(progressBar.visibleProperty());
        busy.setPrefSize(18, 18);
        busy.managedProperty().bind(busy.visibleProperty());
        busy.setVisible(false);

        installButton.getStyleClass().add("primary");
        installButton.setDefaultButton(true);
        installButton.setDisable(true);
        installButton.setOnAction(e -> onInstallClicked());
        closeButton.setCancelButton(true);
        closeButton.setOnAction(e -> dialog.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, spacer, installButton, closeButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        HBox modelRow = new HBox(10, new Label("Модель:"), modelBox);
        modelRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modelBox, Priority.ALWAYS);
        HBox statusRow = new HBox(8, busy, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.setMinHeight(40); // место под две строки, чтобы кнопки не уезжали

        Label hint = new Label("Железо определено автоматически. Если что-то не так, поправьте.");
        hint.getStyleClass().add("status-text");

        VBox root = new VBox(12, hint, hardware, adviceLabel, modelRow, installedLabel,
                progressBar, statusRow, buttons);
        root.setPadding(new Insets(16));
        root.setPrefWidth(560);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(ModelSetupDialog.class.getResource(WINDOW_CSS).toExternalForm());
        dialog.setScene(scene);
        dialog.setOnHidden(e -> closing = true);
        dialog.show();
        loadInBackground();
    }

    /** nvidia-smi и запуск Ollama могут занять несколько секунд — не держим окно */
    private void loadInBackground() {
        setBusy("Определяю железо…");
        Thread loader = new Thread(() -> {
            HardwareInfo hardware = HardwareInfo.detect();
            Platform.runLater(() -> {
                if (hardware.ramGb() > 0) {
                    ramSpinner.getValueFactory().setValue(hardware.ramGb());
                }
                vramSpinner.getValueFactory().setValue(hardware.vramGb());
                gpuLabel.setText(hardware.gpuName().isEmpty()
                        ? "Видеокарта NVIDIA не найдена. Если она есть, впишите её память сами."
                        : "Видеокарта: " + hardware.gpuName());
                String saved = OllamaModels.savedModel();
                updateAdvice(saved == null);
                if (saved != null) {
                    modelBox.setValue(saved);
                }
                checkOllama(false);
            });
        }, "model-setup");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * Смотрит, отвечает ли Ollama; если нет — пробует запустить её сама.
     * @param thenInstallModel после этого сразу ставить выбранную модель (после установки Ollama)
     */
    private void checkOllama(boolean thenInstallModel) {
        installButton.setDisable(true);
        setBusy("Проверяю Ollama…");
        Thread checker = new Thread(() -> {
            List<String> found = null;
            try {
                found = models.installed();
            } catch (Exception notRunning) {
                Platform.runLater(() -> setBusy("Ollama не запущена, запускаю…"));
                try {
                    if (models.tryStart()) {
                        found = models.installed();
                    }
                } catch (Exception e) {
                    found = null;
                }
            }
            List<String> installedModels = found;
            Platform.runLater(() -> {
                ollamaRunning = installedModels != null;
                installed = installedModels == null ? List.of() : installedModels;
                showOllamaState();
                if (thenInstallModel && ollamaRunning) {
                    onInstallClicked();
                }
            });
        }, "ollama-check");
        checker.setDaemon(true);
        checker.start();
    }

    private void updateAdvice(boolean selectAdvice) {
        ModelAdvisor.Recommendation advice =
                ModelAdvisor.recommend(ramSpinner.getValue(), vramSpinner.getValue());
        adviceLabel.setText(String.format(Locale.ROOT, "Советуем: %s (скачать около %.1f ГБ). %s",
                advice.model(), advice.sizeGb(), advice.reason()));
        if (selectAdvice) {
            modelBox.setValue(advice.model());
        }
    }

    /** Подпись кнопки говорит, что именно случится по нажатию */
    private void updateInstallButton() {
        if (!ollamaRunning) {
            installButton.setText(OllamaModels.canInstallOllama() ? "Установить Ollama" : "Открыть ollama.com");
        } else if (installed.contains(selectedModel())) {
            installButton.setText("Использовать");
        } else {
            installButton.setText("Установить");
        }
    }

    private void showOllamaState() {
        setIdle();
        Platform.runLater(dialog::sizeToScene); // текст стал другой длины — подгоняем окно
        updateInstallButton();
        installButton.setDisable(false);
        if (!ollamaRunning) {
            installedLabel.setText("");
            statusLabel.setText(OllamaModels.canInstallOllama()
                    ? "Ollama не установлена. Нажмите «Установить Ollama»: программа скачает и поставит её, "
                    + "а потом сразу модель."
                    : "Ollama не установлена. Установите её с ollama.com и откройте это окно снова.");
            return;
        }
        installedLabel.setText(installed.isEmpty()
                ? "Скачанных моделей пока нет."
                : "Уже скачаны: " + String.join(", ", installed));
        String saved = OllamaModels.savedModel();
        statusLabel.setText(saved == null ? "Модель ещё не выбрана." : "Сейчас используется: " + saved);
    }

    private void onInstallClicked() {
        if (!ollamaRunning) {
            if (OllamaModels.canInstallOllama()) {
                installOllama();
            } else {
                openOllamaSite();
            }
            return;
        }
        String model = selectedModel();
        if (model.isEmpty()) {
            statusLabel.setText("Выберите модель.");
            return;
        }
        if (installed.contains(model)) {
            save(model); // уже скачана — просто запоминаем выбор
            return;
        }
        pullModel(model);
    }

    private void installOllama() {
        lockControls();
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        setBusy("Скачиваю установщик Ollama…");
        Thread worker = new Thread(() -> {
            try {
                models.installOllama(this::showProgress, () -> closing);
                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    checkOllama(true); // Ollama стоит — запускаем её и ставим модель
                });
            } catch (Exception e) {
                Platform.runLater(() -> fail("Не получилось установить Ollama: " + e.getMessage()));
            }
        }, "ollama-install");
        worker.setDaemon(true);
        worker.start();
    }

    private void pullModel(String model) {
        lockControls();
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        setBusy("Скачиваю " + model + "…");
        Thread puller = new Thread(() -> {
            try {
                models.pull(model, (status, fraction) ->
                        showProgress(fraction < 0 ? "Ollama: " + status : "Скачиваю " + model, fraction), () -> closing);
                Platform.runLater(() -> {
                    progressBar.setProgress(1);
                    installed = List.copyOf(addTo(installed, model));
                    save(model);
                });
            } catch (Exception e) {
                Platform.runLater(() -> fail("Не получилось скачать " + model + ": " + e.getMessage()));
            }
        }, "model-pull");
        puller.setDaemon(true);
        puller.start();
    }

    /** Вызывается из фонового потока: обновляет полоску и подпись */
    private void showProgress(String text, double fraction) {
        Platform.runLater(() -> {
            progressBar.setProgress(fraction < 0 ? ProgressBar.INDETERMINATE_PROGRESS : fraction);
            statusLabel.setText(fraction < 0 ? text + "…"
                    : String.format(Locale.ROOT, "%s: %.0f%%", text, fraction * 100));
        });
    }

    private void save(String model) {
        try {
            OllamaModels.saveModel(model);
        } catch (Exception e) {
            fail("Модель есть, но выбор не сохранился: " + e.getMessage());
            return;
        }
        setIdle();
        progressBar.setVisible(false);
        statusLabel.setText("✓ Готово: используется " + model + ". Окно сейчас закроется.");
        installButton.setDisable(true);
        // Пауза, чтобы успеть прочитать, что всё получилось
        PauseTransition pause = new PauseTransition(Duration.seconds(1.5));
        pause.setOnFinished(e -> dialog.close());
        pause.play();
    }

    private void fail(String message) {
        setIdle();
        progressBar.setVisible(false);
        statusLabel.setText(message);
        modelBox.setDisable(false);
        installButton.setDisable(false);
    }

    private void lockControls() {
        installButton.setDisable(true);
        modelBox.setDisable(true);
    }

    private void setBusy(String text) {
        busy.setVisible(true);
        statusLabel.setText(text);
    }

    private void setIdle() {
        busy.setVisible(false);
    }

    private String selectedModel() {
        return modelBox.getValue() == null ? "" : modelBox.getValue().trim();
    }

    private static List<String> addTo(List<String> list, String item) {
        List<String> copy = new java.util.ArrayList<>(list);
        copy.add(item);
        return copy;
    }

    private void openOllamaSite() {
        Thread opener = new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://ollama.com/download"));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Откройте в браузере: https://ollama.com/download"));
            }
        }, "open-ollama-site");
        opener.setDaemon(true);
        opener.start();
    }
}
