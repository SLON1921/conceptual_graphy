package ru.conceptgraph;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Background;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import ru.conceptgraph.core.config.AppConfig;
import ru.conceptgraph.core.preloader.SystemPreloader;
import ru.conceptgraph.core.errors.AppExceptionHandler;
import ru.conceptgraph.modules.aiandnlp.service.AiGraphEngine;
import ru.conceptgraph.modules.aiandnlp.service.ConceptGraphPipeline;
import ru.conceptgraph.modules.aiandnlp.service.OpenNlpEngine;
import ru.conceptgraph.modules.aiandnlp.service.SupportedFormats;
import ru.conceptgraph.modules.aiandnlp.service.WikipediaMeaningSource;
import ru.conceptgraph.modules.aiandnlp.view.GraphView;
import ru.conceptgraph.modules.aiandnlp.view.ResultTabs;
import ru.conceptgraph.modules.infrastructure.repository.ConnectionPool;
import ru.conceptgraph.modules.infrastructure.service.TikaParserEngine;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.Edge;
import ru.conceptgraph.shared.EntityType;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Точка входа и главное окно: сначала SystemPreloader проверяет Java, настройки и PostgreSQL,
 * потом открывается окно — дерево файлов слева, граф понятий в центре, карточка понятия и вкладки
 * справа, строка статуса внизу. Оформление и все цвета — в window.css.
 */
public class MainApplication extends Application {

    /** Стили окна лежат рядом с GraphView и ResultTabs */
    private static final String WINDOW_CSS = "/ru/conceptgraph/modules/aiandnlp/view/window.css";

    private File currentFolder;
    private File selectedFile;

    private ConceptGraphPipeline.Result lastResult;
    private final Map<Long, ConceptEntity> conceptsById = new HashMap<>();
    private final Set<EntityType> hiddenTypes = EnumSet.noneOf(EntityType.class);
    private final Map<EntityType, ToggleButton> typeChips = new EnumMap<>(EntityType.class);

    /** Порядок типов в фильтрах над графом */
    private static final EntityType[] CHIP_ORDER = {
            EntityType.PERSON, EntityType.GEOGRAPHY, EntityType.TECHNOLOGY, EntityType.PRODUCT};

    private GraphView.Theme theme;
    private GraphView graphView;
    private ResultTabs resultTabs;
    private TextArea logArea;
    private VBox card;

    private TextField pathField;
    private final DirectoryChooser folderChooser = new DirectoryChooser();
    private MenuItem analyzeFolderItem;
    private MenuItem analyzeFileItem;
    private MenuItem cancelItem;
    private MenuItem savePngItem;
    private MenuItem exportCsvItem;

    private Button folderButton;
    private Button fileButton;
    private Button cancelButton;
    private CheckBox wikiCheckBox;
    private Slider topSlider;
    private Label topValue;
    private ProgressBar progressBar;
    private Label progressText;
    private Label countsText;
    private Circle dbDot;
    private Label dbText;
    private Circle ollamaDot;
    private Label ollamaText;

    private Thread worker;
    private final AtomicBoolean cancelled = new AtomicBoolean();

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

        BorderPane root = new BorderPane();
        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(MainApplication.class.getResource(WINDOW_CSS).toExternalForm());
        theme = readTheme(root);

        root.setTop(new VBox(buildMenuBar(stage), buildTopBar(stage)));
        root.setCenter(buildBody());
        root.setBottom(buildStatusBar());

        stage.setTitle("Понятийный граф");
        stage.setScene(scene);
        stage.setMinWidth(980);
        stage.setMinHeight(600);
        stage.show();
        Platform.setImplicitExit(true);

        showCard(null);
        updateButtons();
        checkServices();
    }

    // ---------------- разметка ----------------

    /** Меню окна: те же действия, что и кнопки, плюс экспорт понятий в CSV */
    private MenuBar buildMenuBar(Stage stage) {
        MenuItem openItem = new MenuItem("Открыть папку…");
        openItem.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        openItem.setOnAction(e -> chooseFolder(stage));

        savePngItem = new MenuItem("Сохранить граф в PNG…");
        savePngItem.setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        savePngItem.setOnAction(e -> savePng(stage));

        exportCsvItem = new MenuItem("Экспорт понятий в CSV…");
        exportCsvItem.setAccelerator(KeyCombination.keyCombination("Shortcut+E"));
        exportCsvItem.setOnAction(e -> exportCsv(stage));

        MenuItem exitItem = new MenuItem("Выход");
        exitItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Q"));
        exitItem.setOnAction(e -> Platform.exit());

        Menu fileMenu = new Menu("Файл", null,
                openItem, new SeparatorMenuItem(), savePngItem, exportCsvItem, new SeparatorMenuItem(), exitItem);

        analyzeFolderItem = new MenuItem("Анализ папки");
        analyzeFolderItem.setAccelerator(KeyCombination.keyCombination("F5"));
        analyzeFolderItem.setOnAction(e -> analyzeFolder());

        analyzeFileItem = new MenuItem("Анализ файла");
        analyzeFileItem.setAccelerator(KeyCombination.keyCombination("Shortcut+F5"));
        analyzeFileItem.setOnAction(e -> analyzeFile());

        cancelItem = new MenuItem("Отменить анализ");
        cancelItem.setOnAction(e -> cancelButton.fire());

        Menu analysisMenu = new Menu("Анализ", null, analyzeFolderItem, analyzeFileItem, cancelItem);

        MenuItem resetViewItem = new MenuItem("Сбросить вид графа");
        resetViewItem.setAccelerator(KeyCombination.keyCombination("Shortcut+0"));
        resetViewItem.setOnAction(e -> graphView.resetView());
        Menu viewMenu = new Menu("Вид", null, resetViewItem);

        return new MenuBar(fileMenu, analysisMenu, viewMenu);
    }

    private void chooseFolder(Window owner) {
        File folder = folderChooser.showDialog(owner);
        if (folder != null) {
            folderChooser.setInitialDirectory(folder.getParentFile());
            pathField.setText(folder.getAbsolutePath());
            openFolder(folder);
        }
    }

    private HBox buildTopBar(Stage stage) {
        Label brand = new Label("Понятийный граф");
        brand.getStyleClass().add("brand");

        pathField = new TextField();
        pathField.setPromptText("Папка с документами не выбрана");
        pathField.setEditable(false);
        pathField.setFocusTraversable(false);
        pathField.getStyleClass().add("path-field");
        HBox.setHgrow(pathField, Priority.ALWAYS);

        Button openButton = new Button("Открыть папку…");
        openButton.setOnAction(e -> chooseFolder(stage));

        HBox bar = new HBox(brand, pathField, openButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    private TreeView<File> fileTree;
    private Label filesLabel;

    private SplitPane buildBody() {
        // --- слева: файлы и запуск ---
        filesLabel = new Label("ФАЙЛЫ");
        filesLabel.getStyleClass().add("section-label");
        filesLabel.setPadding(new Insets(12, 12, 6, 12));

        fileTree = new TreeView<>();
        fileTree.setShowRoot(true);
        fileTree.setCellFactory(tv -> new FileCell());
        VBox.setVgrow(fileTree, Priority.ALWAYS);
        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            File f = item == null ? null : item.getValue();
            selectedFile = f != null && SupportedFormats.isSupported(f) ? f : null;
            updateButtons();
        });
        fileTree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && selectedFile != null && worker == null) {
                analyzeFile();
            }
        });

        folderButton = new Button("Анализ папки");
        folderButton.getStyleClass().add("primary");
        folderButton.setMaxWidth(Double.MAX_VALUE);
        folderButton.setOnAction(e -> analyzeFolder());

        fileButton = new Button("Анализ файла");
        fileButton.setMaxWidth(Double.MAX_VALUE);
        fileButton.setTooltip(new Tooltip("Или двойной клик по файлу в дереве"));
        fileButton.setOnAction(e -> analyzeFile());

        wikiCheckBox = new CheckBox("Уточнять омонимы по Википедии");
        wikiCheckBox.setWrapText(true);

        VBox actions = new VBox(folderButton, fileButton, wikiCheckBox);
        actions.getStyleClass().add("side-actions");

        VBox side = new VBox(filesLabel, fileTree, actions);
        side.getStyleClass().add("side");
        side.setMinWidth(200);

        // --- центр: фильтры и граф ---
        graphView = new GraphView(theme);
        graphView.setOnSelect(this::selectConcept);
        graphView.setOnStats(stats -> countsText.setText(stats[1] == 0 ? ""
                : stats[0] + " из " + stats[1] + " понятий · " + stats[2] + " связей"));

        StackPane canvasPane = new StackPane(graphView);
        canvasPane.getStyleClass().add("canvas-pane");
        canvasPane.setMinSize(0, 0);

        Button zoomIn = zoomButton("+", "Приблизить", () -> graphView.zoomBy(1.2));
        Button zoomOut = zoomButton("−", "Отдалить", () -> graphView.zoomBy(1 / 1.2));
        Button zoomReset = zoomButton("⟲", "Сбросить вид", graphView::resetView);
        VBox zoom = new VBox(4, zoomIn, zoomOut, zoomReset);
        zoom.setPickOnBounds(false);
        zoom.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(zoom, Pos.TOP_RIGHT);
        StackPane.setMargin(zoom, new Insets(10));

        Label hint = new Label("Колесо: масштаб · перетаскивание: сдвиг · клик по узлу: подробности");
        hint.getStyleClass().add("canvas-hint");
        hint.setMouseTransparent(true);
        StackPane.setAlignment(hint, Pos.BOTTOM_LEFT);
        StackPane.setMargin(hint, new Insets(8, 10, 8, 10));

        Button savePng = new Button("Сохранить PNG");
        savePng.setOnAction(e -> savePng(savePng.getScene().getWindow()));
        StackPane.setAlignment(savePng, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(savePng, new Insets(8, 10, 8, 10));

        canvasPane.getChildren().addAll(zoom, hint, savePng);

        VBox center = new VBox(buildGraphToolbar(), canvasPane);
        VBox.setVgrow(canvasPane, Priority.ALWAYS);
        center.setMinWidth(400);

        // --- справа: карточка понятия и вкладки ---
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        resultTabs = new ResultTabs(logArea, theme);
        resultTabs.setOnSelect(this::selectConcept);

        card = new VBox();
        card.getStyleClass().add("concept-card");

        VBox right = new VBox(card, resultTabs.getPane());
        VBox.setVgrow(resultTabs.getPane(), Priority.ALWAYS);
        right.getStyleClass().add("right-pane");
        right.setMinWidth(300);

        SplitPane body = new SplitPane(side, center, right);
        body.setOrientation(Orientation.HORIZONTAL);
        body.setDividerPositions(0.18, 0.67);
        SplitPane.setResizableWithParent(side, false);
        SplitPane.setResizableWithParent(right, false);
        return body;
    }

    private FlowPane buildGraphToolbar() {
        HBox chips = new HBox(6);
        chips.setAlignment(Pos.CENTER_LEFT);
        for (EntityType type : CHIP_ORDER) {
            ToggleButton chip = new ToggleButton(GraphView.typeName(type));
            chip.setSelected(true);
            chip.setGraphic(new Circle(4.5, theme.typeColor(type)));
            chip.getStyleClass().add("type-chip");
            chip.setTooltip(new Tooltip("Показать или скрыть на графе"));
            chip.selectedProperty().addListener((obs, old, on) -> {
                chip.getStyleClass().remove("off");
                if (on) {
                    hiddenTypes.remove(type);
                } else {
                    hiddenTypes.add(type);
                    chip.getStyleClass().add("off");
                }
                graphView.setHiddenTypes(hiddenTypes);
            });
            typeChips.put(type, chip);
            chips.getChildren().add(chip);
        }

        TextField search = new TextField();
        search.setPromptText("Найти понятие");
        search.getStyleClass().add("search-field");
        search.textProperty().addListener((obs, old, text) -> {
            graphView.setQuery(text);
            resultTabs.setQuery(text);
        });

        Label topLabel = new Label("Топ");
        topLabel.getStyleClass().add("toolbar-label");
        topSlider = new Slider(5, 50, 50);
        topSlider.setPrefWidth(120);
        topValue = new Label("50");
        topValue.getStyleClass().add("toolbar-value");
        topSlider.valueProperty().addListener((obs, old, v) -> {
            topValue.setText(String.valueOf(v.intValue()));
            graphView.setTopN(v.intValue());
        });
        HBox top = new HBox(6, topLabel, topSlider, topValue);
        top.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(top, new Tooltip("Сколько самых частых понятий показать"));

        Label weightLabel = new Label("Вес связи от");
        weightLabel.getStyleClass().add("toolbar-label");
        Slider weightSlider = new Slider(0.0, 0.9, 0.2);
        weightSlider.setPrefWidth(120);
        Label weightValue = new Label("0.20");
        weightValue.getStyleClass().add("toolbar-value");
        weightSlider.valueProperty().addListener((obs, old, v) -> {
            weightValue.setText(String.format(Locale.ROOT, "%.2f", v.doubleValue()));
            graphView.setMinWeight(v.doubleValue());
        });
        HBox weight = new HBox(6, weightLabel, weightSlider, weightValue);
        weight.setAlignment(Pos.CENTER_LEFT);

        FlowPane toolbar = new FlowPane(chips, search, top, weight);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("graph-toolbar");
        return toolbar;
    }

    private HBox buildStatusBar() {
        dbDot = new Circle(4);
        dbDot.getStyleClass().add("status-dot");
        dbText = statusLabel("БД: проверка…");
        ollamaDot = new Circle(4);
        ollamaDot.getStyleClass().add("status-dot");
        ollamaText = statusLabel("Ollama: проверка…");
        countsText = statusLabel("");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        progressText = statusLabel("Готов к работе");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(180);
        progressBar.setPrefHeight(8);
        cancelButton = new Button("Отмена");
        cancelButton.setMinWidth(Region.USE_PREF_SIZE);
        progressText.setMinWidth(Region.USE_PREF_SIZE);
        cancelButton.setDisable(true);
        cancelButton.setOnAction(e -> {
            cancelled.set(true);
            cancelButton.setDisable(true);
            progressText.setText("Отменяю…");
        });

        HBox db = new HBox(6, dbDot, dbText);
        db.setAlignment(Pos.CENTER_LEFT);
        HBox ollama = new HBox(6, ollamaDot, ollamaText);
        ollama.setAlignment(Pos.CENTER_LEFT);
        HBox progress = new HBox(8, progressText, progressBar, cancelButton);
        progress.setAlignment(Pos.CENTER_LEFT);

        HBox bar = new HBox(db, ollama, countsText, spacer, progress);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private static Label statusLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("status-text");
        return label;
    }

    private static Button zoomButton(String text, String tooltip, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("zoom-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(e -> action.run());
        return button;
    }

    // ---------------- дерево файлов ----------------

    private void openFolder(File folder) {
        currentFolder = folder;
        selectedFile = null;
        TreeItem<File> rootItem = createTreeNodes(folder);
        rootItem.setExpanded(true);
        fileTree.setRoot(rootItem);
        int[] counts = countFiles(folder);
        filesLabel.setText("ФАЙЛЫ · " + counts[0] + " ИЗ " + counts[1]);
        logArea.setText("Папка: " + folder.getAbsolutePath() + "\n");
        updateButtons();
    }

    private TreeItem<File> createTreeNodes(File file) {
        TreeItem<File> item = new TreeItem<>(file);
        File[] children = file.listFiles();
        if (children == null) {
            return item;
        }
        // сначала папки, потом файлы, по алфавиту
        Arrays.sort(children, Comparator.comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        for (File child : children) {
            if (child.isHidden() || child.getName().startsWith(".") || child.getName().startsWith("~$")) {
                continue;
            }
            if (child.isDirectory()) {
                TreeItem<File> sub = createTreeNodes(child);
                sub.setExpanded(true);
                item.getChildren().add(sub);
            } else {
                item.getChildren().add(new TreeItem<>(child));
            }
        }
        return item;
    }

    /** {поддерживаемых файлов, всех файлов} в папке и подпапках */
    private static int[] countFiles(File folder) {
        int[] counts = new int[2];
        File[] children = folder.listFiles();
        if (children == null) {
            return counts;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                int[] sub = countFiles(child);
                counts[0] += sub[0];
                counts[1] += sub[1];
            } else if (!child.isHidden() && !child.getName().startsWith(".") && !child.getName().startsWith("~$")) {
                counts[1]++;
                if (SupportedFormats.isSupported(child)) {
                    counts[0]++;
                }
            }
        }
        return counts;
    }

    /** Те же файлы, что берёт FolderScanner: все поддерживаемые, включая вложенные папки */
    private static void collectSupported(File folder, List<File> out) {
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectSupported(child, out);
            } else if (SupportedFormats.isSupported(child)) {
                out.add(child);
            }
        }
    }

    /** Строка дерева: имя файла и его формат; неподдерживаемые приглушены */
    private static final class FileCell extends TreeCell<File> {
        @Override
        protected void updateItem(File file, boolean empty) {
            super.updateItem(file, empty);
            getStyleClass().removeAll("folder", "unsupported");
            setTooltip(null);
            if (empty || file == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(file.getName());
            if (file.isDirectory()) {
                getStyleClass().add("folder");
                setGraphic(null);
                return;
            }
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            Label badge = new Label(dot == -1 ? "?" : name.substring(dot + 1).toLowerCase(Locale.ROOT));
            badge.getStyleClass().add("ext-badge");
            if (!SupportedFormats.isSupported(file)) {
                getStyleClass().add("unsupported");
                badge.getStyleClass().add("unsupported");
                setTooltip(new Tooltip("Формат не поддерживается, файл будет пропущен"));
            }
            setGraphic(badge);
            setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
            setGraphicTextGap(8);
        }
    }

    // ---------------- запуск анализа ----------------

    private void analyzeFolder() {
        if (currentFolder == null) {
            return;
        }
        List<File> files = new ArrayList<>();
        collectSupported(currentFolder, files);
        runAnalysis("папка " + currentFolder.getName(), files);
    }

    private void analyzeFile() {
        if (selectedFile != null) {
            runAnalysis(selectedFile.getName(), List.of(selectedFile));
        }
    }

    private void updateButtons() {
        boolean busy = worker != null;
        folderButton.setDisable(busy || currentFolder == null);
        fileButton.setDisable(busy || selectedFile == null);
        analyzeFolderItem.setDisable(folderButton.isDisable());
        analyzeFileItem.setDisable(fileButton.isDisable());
        cancelItem.setDisable(!busy);
        savePngItem.setDisable(lastResult == null);
        exportCsvItem.setDisable(lastResult == null);
    }

    private void runAnalysis(String title, List<File> files) {
        if (worker != null) {
            return;
        }
        cancelled.set(false);
        boolean useWikipedia = wikiCheckBox.isSelected();
        long started = System.currentTimeMillis();

        logArea.appendText("\n— Анализ: " + title + " (" + files.size() + " файлов) —\n");
        progressBar.setProgress(0);
        progressText.setText("Текст: 0 из " + files.size());
        cancelButton.setDisable(false);

        worker = new Thread(() -> {
            try {
                // 1. Текст из файлов; между файлами можно отменить
                List<ConceptGraphPipeline.DocumentInput> documents = new ArrayList<>();
                TikaParserEngine parser = new TikaParserEngine();
                for (int i = 0; i < files.size(); i++) {
                    if (cancelled.get()) {
                        finish("Анализ отменён", 0);
                        return;
                    }
                    File file = files.get(i);
                    String text = parser.extractText(file);
                    documents.add(new ConceptGraphPipeline.DocumentInput(
                            file.getName(), file.getAbsolutePath(), file.length(), text));
                    int done = i + 1;
                    Platform.runLater(() -> {
                        progressBar.setProgress(done / (double) files.size() * 0.3);
                        progressText.setText("Текст: " + done + " из " + files.size());
                    });
                }
                log("Текст извлечён: " + documents.size() + " док.");

                // 2. Понятия, типы, БД и связи
                Platform.runLater(() -> {
                    progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    progressText.setText("Поиск понятий и связей…");
                });
                ConceptGraphPipeline pipeline = new ConceptGraphPipeline(
                        new OpenNlpEngine(),
                        new AppExceptionHandler(),
                        this::log
                ).withMeaningSource(useWikipedia ? new WikipediaMeaningSource() : null);
                ConceptGraphPipeline.Result result = pipeline.processAll(documents);
                GraphView.Layout layout = GraphView.computeLayout(result.nodes(), result.frequency(), result.edges());

                if (cancelled.get()) {
                    finish("Анализ отменён", 0);
                    return;
                }
                double seconds = (System.currentTimeMillis() - started) / 1000.0;
                Platform.runLater(() -> showResult(result, layout));
                finish(String.format(Locale.forLanguageTag("ru"), "Готово · %d док. за %.1f с", documents.size(), seconds), 1);
            } catch (Throwable e) {
                log("[Ошибка]: " + e.getMessage());
                finish("Ошибка: " + e.getMessage(), 0);
            }
        }, "analysis");
        worker.setDaemon(true); // не держим приложение открытым после закрытия окна
        updateButtons();
        worker.start();
    }

    private void log(String line) {
        Platform.runLater(() -> {
            logArea.appendText(line + "\n");
            if (progressBar.getProgress() < 0) {
                progressText.setText(line.length() > 60 ? line.substring(0, 57) + "…" : line);
            }
        });
    }

    private void finish(String status, double progress) {
        Platform.runLater(() -> {
            worker = null;
            progressBar.setProgress(progress);
            progressText.setText(status);
            cancelButton.setDisable(true);
            updateButtons();
        });
    }

    private void showResult(ConceptGraphPipeline.Result result, GraphView.Layout layout) {
        lastResult = result;
        conceptsById.clear();
        for (ConceptEntity c : result.nodes()) {
            conceptsById.put(c.id(), c);
        }
        Map<EntityType, Integer> byType = new EnumMap<>(EntityType.class);
        for (ConceptEntity c : result.nodes()) {
            byType.merge(c.type(), 1, Integer::sum);
        }
        typeChips.forEach((type, chip) -> chip.setText(GraphView.typeName(type) + "  " + byType.getOrDefault(type, 0)));

        int laidOut = layout.nodes().size();
        topSlider.setMax(Math.max(5, laidOut));
        topSlider.setValue(Math.min(50, Math.max(5, laidOut)));
        graphView.setTopN((int) topSlider.getValue());

        graphView.setResult(layout, result.frequency(), result.edges());
        resultTabs.show(result);
        setStatus(dbDot, dbText, result.savedToDb() ? "ok" : "bad",
                result.savedToDb() ? "БД: сохранено" : "БД недоступна",
                result.savedToDb() ? "Результаты сохранены в PostgreSQL" : "Граф строится без сохранения, ID локальные");
        selectConcept(null);
    }

    // ---------------- выбор понятия ----------------

    /** Общая точка выбора: из графа, таблицы или карточки */
    private void selectConcept(Long id) {
        graphView.select(id);
        resultTabs.selectConcept(id);
        showCard(id == null ? null : conceptsById.get(id));
    }

    private void showCard(ConceptEntity concept) {
        card.getChildren().clear();
        Label section = new Label("ПОНЯТИЕ");
        section.getStyleClass().add("section-label");
        card.getChildren().add(section);
        if (concept == null) {
            Label empty = new Label("Кликните по узлу графа или по строке во вкладке «Понятия».");
            empty.getStyleClass().add("card-empty");
            empty.setWrapText(true);
            card.getChildren().add(empty);
            return;
        }

        Label title = new Label(concept.value());
        title.getStyleClass().add("concept-title");
        title.setWrapText(true);
        Label badge = new Label(GraphView.typeName(concept.type()));
        badge.getStyleClass().add("type-badge");
        badge.setStyle("-fx-background-color: " + toCss(theme.typeColor(concept.type())) + ";");
        HBox head = new HBox(8, title, badge);
        head.setAlignment(Pos.CENTER_LEFT);

        // все связи понятия, сильные сверху
        List<Edge> links = new ArrayList<>();
        for (Edge e : lastResult.edges()) {
            if (concept.id().equals(e.sourceConceptId()) || concept.id().equals(e.targetConceptId())) {
                links.add(e);
            }
        }
        links.sort((a, b) -> Double.compare(b.weight(), a.weight()));

        GridPane kv = new GridPane();
        kv.setHgap(10);
        kv.setVgap(3);
        addRow(kv, 0, lastResult.savedToDb() ? "ID в БД" : "Локальный ID", String.valueOf(concept.id()));
        addRow(kv, 1, "Вхождений", String.valueOf(lastResult.frequency().getOrDefault(concept.id(), 0)));
        addRow(kv, 2, "Первое вхождение", "символы " + concept.startChar() + "–" + concept.endChar());

        Label linksLabel = new Label("СВЯЗИ · " + links.size());
        linksLabel.getStyleClass().add("section-label");

        VBox linkRows = new VBox(3);
        for (Edge e : links) {
            Long otherId = concept.id().equals(e.sourceConceptId()) ? e.targetConceptId() : e.sourceConceptId();
            ConceptEntity other = conceptsById.get(otherId);
            if (other == null) {
                continue;
            }
            Hyperlink link = new Hyperlink(other.value());
            link.setOnAction(ev -> selectConcept(other.id()));
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            Label w = new Label(String.format(Locale.ROOT, "%.2f", e.weight()));
            w.getStyleClass().add("link-weight");
            HBox row = new HBox(6, new Circle(4, theme.typeColor(other.type())), link);
            if (e.label() != null && !e.label().isBlank() && !e.label().equalsIgnoreCase("связан")) {
                Label rel = new Label(e.label());
                rel.getStyleClass().add("card-key");
                row.getChildren().add(rel);
            }
            row.getChildren().addAll(gap, w);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("link-row");
            linkRows.getChildren().add(row);
        }
        if (links.isEmpty()) {
            Label none = new Label("Связей нет");
            none.getStyleClass().add("card-empty");
            linkRows.getChildren().add(none);
        }
        ScrollPane scroll = new ScrollPane(linkRows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(130, Math.max(20, links.size() * 22)));
        scroll.getStyleClass().add("links-scroll");

        card.getChildren().addAll(head, kv, linksLabel, scroll);
    }

    private static void addRow(GridPane grid, int row, String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("card-key");
        Label v = new Label(value);
        v.getStyleClass().add("card-value");
        v.setWrapText(true);
        grid.addRow(row, k, v);
    }

    // ---------------- сервисы и прочее ----------------

    /** Проверяет БД и Ollama в фоне, чтобы окно открылось сразу */
    private void checkServices() {
        Thread checker = new Thread(() -> {
            boolean ollama = false;
            try {
                ollama = new AiGraphEngine().isAvailable();
            } catch (Throwable ignored) {
                // считаем, что Ollama нет
            }
            boolean ollamaOk = ollama;
            Platform.runLater(() -> setStatus(ollamaDot, ollamaText, ollamaOk ? "ok" : "warn",
                    ollamaOk ? "Ollama на связи" : "Ollama не отвечает",
                    ollamaOk ? "Ollama запущена, но граф её сейчас не использует"
                            : "Типы берутся из словарей, связи — по расстоянию в тексте"));

            boolean db = false;
            try (Connection ignored = ConnectionPool.getConnection()) {
                db = true;
            } catch (Throwable ignored) {
                // база не настроена или не запущена
            }
            boolean dbOk = db;
            Platform.runLater(() -> {
                if (lastResult == null) {
                    setStatus(dbDot, dbText, dbOk ? "ok" : "bad",
                            dbOk ? "БД подключена" : "БД недоступна",
                            dbOk ? "Результаты сохраняются в PostgreSQL" : "Граф строится без сохранения, ID локальные");
                }
            });
        }, "service-check");
        checker.setDaemon(true);
        checker.start();
    }

    private static void setStatus(Circle dot, Label text, String state, String message, String details) {
        dot.getStyleClass().removeAll("ok", "warn", "bad");
        dot.getStyleClass().add(state);
        text.setText(message);
        text.setTooltip(new Tooltip(details));
    }

    private void savePng(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Сохранить граф");
        chooser.setInitialFileName("граф.png");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File file = chooser.showSaveDialog(owner);
        if (file == null) {
            return;
        }
        try {
            graphView.saveSnapshot(file);
            progressText.setText("Граф сохранён: " + file.getName());
        } catch (Exception e) {
            progressText.setText("Не удалось сохранить PNG: " + e.getMessage());
        }
    }

    /**
     * Понятия последнего анализа в CSV: id, понятие, тип, вхождения.
     * Разделитель «;» и BOM в начале, чтобы Excel сразу открыл кириллицу по столбцам.
     */
    private void exportCsv(Window owner) {
        if (lastResult == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Экспорт понятий");
        chooser.setInitialFileName("понятия.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = chooser.showSaveDialog(owner);
        if (file == null) {
            return;
        }
        List<ConceptEntity> nodes = new ArrayList<>(lastResult.nodes());
        Map<Long, Integer> frequency = lastResult.frequency();
        nodes.sort((a, b) -> Integer.compare(frequency.getOrDefault(b.id(), 0), frequency.getOrDefault(a.id(), 0)));
        try (BufferedWriter out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            out.write('\uFEFF');
            out.write("id;понятие;тип;вхождений");
            out.newLine();
            for (ConceptEntity c : nodes) {
                out.write(c.id() + ";" + csvCell(c.value()) + ";" + GraphView.typeName(c.type()) + ";"
                        + frequency.getOrDefault(c.id(), 0));
                out.newLine();
            }
            progressText.setText("Понятия сохранены: " + file.getName() + " (" + nodes.size() + ")");
        } catch (IOException e) {
            progressText.setText("Не удалось сохранить CSV: " + e.getMessage());
        }
    }

    private static String csvCell(String text) {
        if (text.contains(";") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /**
     * Цвета холста берём из window.css: подставляем имя цвета в фон скрытого элемента и читаем результат.
     * Так все цвета окна живут в одном файле.
     */
    private static GraphView.Theme readTheme(BorderPane root) {
        Map<EntityType, Color> types = new EnumMap<>(EntityType.class);
        types.put(EntityType.PERSON, themeColor(root, "cg-type-person"));
        types.put(EntityType.GEOGRAPHY, themeColor(root, "cg-type-geography"));
        types.put(EntityType.TECHNOLOGY, themeColor(root, "cg-type-technology"));
        types.put(EntityType.PRODUCT, themeColor(root, "cg-type-product"));
        return new GraphView.Theme(
                themeColor(root, "cg-canvas-bg"),
                themeColor(root, "cg-edge-color"),
                themeColor(root, "cg-accent"),
                themeColor(root, "cg-text"),
                types);
    }

    private static Color themeColor(BorderPane root, String name) {
        Region probe = new Region();
        probe.setManaged(false);
        probe.setVisible(false);
        probe.setStyle("-fx-background-color: " + name + ";");
        root.getChildren().add(probe);
        probe.applyCss();
        root.getChildren().remove(probe);
        Background bg = probe.getBackground();
        if (bg != null && !bg.getFills().isEmpty()) {
            Paint paint = bg.getFills().get(0).getFill();
            if (paint instanceof Color color) {
                return color;
            }
        }
        return Color.GRAY;
    }

    private static String toCss(Color c) {
        return String.format("#%02X%02X%02X", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
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
