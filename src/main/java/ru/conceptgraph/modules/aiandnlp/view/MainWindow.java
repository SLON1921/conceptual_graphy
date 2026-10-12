package ru.conceptgraph.modules.aiandnlp.view;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import ru.conceptgraph.core.config.AppConfig;
import ru.conceptgraph.core.errors.AppExceptionHandler;
import ru.conceptgraph.modules.aiandnlp.service.ConceptGraphPipeline;
import ru.conceptgraph.modules.aiandnlp.service.OpenNlpEngine;
import ru.conceptgraph.modules.aiandnlp.service.AiGraphEngine;
import ru.conceptgraph.modules.aiandnlp.service.SupportedFormats;
import ru.conceptgraph.modules.infrastructure.service.TikaParserEngine;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.Edge;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainWindow extends Application {

    private File currentFolder = null;
    private File selectedFileFromTree = null;

    private final List<ConceptEntity> currentResolvedConcepts = new ArrayList<>();
    private final List<Edge> currentEdges = new ArrayList<>();

    // МАТЕМАТИКА КИБЕР-ДВИЖКА 2026: Вращение, Масштаб и Панорамирование (MS Paint Style)
    private double currentAngleOffset = 0.0;
    private double minWeightThreshold = 0.20;
    private double graphRadiusScale = 1.0;

    // Сдвиги виртуальной камеры для перемещения по холсту зажатым колесиком
    private double graphTranslateX = 0.0;
    private double graphTranslateY = 0.0;

    // Вспомогательные переменные мышиных осей
    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;

    // Стили киберпанка 2026
    private final String darkBg = "-fx-background-color: #0B0C10;";
    private final String neonText = "-fx-text-fill: #66FCF1; -fx-font-weight: bold;";
    private final String btnStyle = "-fx-background-color: #45A29E; -fx-text-fill: #0B0C10; -fx-font-weight: bold; -fx-background-radius: 5; -fx-cursor: hand;";
    private final String inputStyle = "-fx-background-color: #1F2833; -fx-text-fill: #FFF; -fx-border-color: #45A29E; -fx-border-radius: 4; -fx-padding: 8;";

    @Override
    public void start(Stage primaryStage) {
        // Настройки БД из application.properties: без этого пул соединений не стартует.
        // Если файла нет, окно всё равно открываем — граф построится без сохранения в БД.
        try {
            AppConfig.loadProperties();
        } catch (RuntimeException e) {
            System.err.println("[CONFIG] " + e.getMessage());
        }

        primaryStage.setTitle("Concepter Graph OS - Enterprise Split & Navigation Engine");

        // --- ШАПКА ПРИЛОЖЕНИЯ ---
        Label titleLabel = new Label("🎨 CONCEPTER ENTERPRISE");
        titleLabel.setStyle(neonText + " -fx-font-size: 22px;");

        TextField pathField = new TextField();
        pathField.setPromptText("Путь к базе знаний не выбран...");
        pathField.setEditable(false);
        pathField.setStyle(inputStyle);
        HBox.setHgrow(pathField, Priority.ALWAYS);

        Button browseButton = new Button("📁 ОБЗОР ПАПКИ");
        browseButton.setStyle(btnStyle + " -fx-padding: 8 20;");

        HBox topBar = new HBox(20);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color: #1F2833; -fx-padding: 15; -fx-border-color: #45A29E; -fx-border-width: 0 0 2 0;");
        topBar.getChildren().addAll(titleLabel, pathField, browseButton);

        // --- СЛЕВА: ДЕРЕВО ПАПОК (TREEVIEW) ---
        VBox sidebar = new VBox(12);
        sidebar.setPrefWidth(260);
        sidebar.setPadding(new Insets(15));
        sidebar.setStyle("-fx-background-color: #151A21; -fx-border-color: #45A29E; -fx-border-width: 0 1 0 0;");

        Label sidebarTitle = new Label("🧠 ДЕРЕВО ДИРЕКТОРИЙ");
        sidebarTitle.setStyle("-fx-text-fill: #8892B0; -fx-font-weight: bold; -fx-font-size: 12px;");

        TreeView<File> fileTreeView = new TreeView<>();
        fileTreeView.setStyle("-fx-background-color: #1F2833; -fx-control-inner-background: #1F2833; -fx-border-color: #45A29E; -fx-border-radius: 4;");
        VBox.setVgrow(fileTreeView, Priority.ALWAYS);

        fileTreeView.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-text-fill: white;");
                } else {
                    setText(item.getName());
                    if (item.isDirectory()) {
                        setStyle("-fx-text-fill: #45A29E; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #C5C6C7;");
                    }
                }
            }
        });

        sidebar.getChildren().addAll(sidebarTitle, fileTreeView);
        // --- ЦЕНТРАЛЬНАЯ РАБОЧАЯ ОБЛАСТЬ ---
        VBox centerLayout = new VBox(15);
        centerLayout.setPadding(new Insets(20));
        centerLayout.setStyle(darkBg);
        HBox.setHgrow(centerLayout, Priority.ALWAYS);

        Button runButton = new Button("🚀 СИНТЕЗИРОВАТЬ СЕМАНТИЧЕСКИЙ ГРАФ ДОКУМЕНТА");
        runButton.setMaxWidth(Double.MAX_VALUE);
        runButton.setDisable(true);
        runButton.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-background-color: #66FCF1; -fx-text-fill: #0B0C10; -fx-padding: 12; -fx-background-radius: 6; -fx-cursor: hand;");

        // Панели управления слайдерами
        Label weightLabel = new Label("Порог веса связи (Мин: 0.20):");
        weightLabel.setStyle("-fx-text-fill: #8892B0; -fx-font-weight: bold; -fx-min-width: 220px;");
        Slider weightSlider = new Slider(0.0, 1.0, 0.20);
        HBox.setHgrow(weightSlider, Priority.ALWAYS);
        HBox weightBar = new HBox(10);
        weightBar.getChildren().addAll(weightLabel, weightSlider);

        Label scaleLabel = new Label("Масштаб круга графа (100%):");
        scaleLabel.setStyle("-fx-text-fill: #8892B0; -fx-font-weight: bold; -fx-min-width: 220px;");
        Slider scaleSlider = new Slider(0.4, 2.5, 1.0);
        HBox.setHgrow(scaleSlider, Priority.ALWAYS);
        HBox scaleBar = new HBox(10);
        scaleBar.getChildren().addAll(scaleLabel, scaleSlider);

        VBox controlsPane = new VBox(8);
        controlsPane.getChildren().addAll(weightBar, scaleBar);

        // ИСПРАВЛЕНА РАЗМЕТКА: Профессиональный SplitPane для регулирования окон сдвигом текста!
        SplitPane splitPane = new SplitPane();
        splitPane.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        // Левый контейнер под адаптивный холст
        VBox canvasContainer = new VBox();
        canvasContainer.setStyle("-fx-background-color: #12161C; -fx-border-color: #45A29E; -fx-border-radius: 4;");

        Canvas graphCanvas = new Canvas(400, 480); // Стартовый размер, дальше будет растягиваться!
        GraphicsContext gc = graphCanvas.getGraphicsContext2D();
        canvasContainer.getChildren().add(graphCanvas);

        // Автоматически привязываем размеры холста к размерам контейнера при сдвиге текста!
        graphCanvas.widthProperty().bind(canvasContainer.widthProperty().subtract(4));
        graphCanvas.heightProperty().bind(canvasContainer.heightProperty().subtract(4));

        // Правый контейнер под статистику
        TextArea logArea = new TextArea();
        logArea.setPromptText("Текстовые результаты анализа документов...");
        logArea.setEditable(false);
        logArea.setStyle("-fx-control-inner-background: #1F2833; -fx-text-fill: #C5C6C7; -fx-font-family: 'Consolas'; -fx-border-color: #45A29E;");

        // Добавляем элементы в сплит-панель и выставляем стартовые пропорции 40% на 60%
        splitPane.getItems().addAll(canvasContainer, logArea);
        splitPane.setDividerPositions(0.40);

        centerLayout.getChildren().addAll(runButton, controlsPane, splitPane);

        HBox bodyLayout = new HBox();
        bodyLayout.getChildren().addAll(sidebar, centerLayout);
        VBox.setVgrow(bodyLayout, Priority.ALWAYS);

        VBox mainLayout = new VBox();
        mainLayout.getChildren().addAll(topBar, bodyLayout);

        // --- ДИНАМИЧЕСКИЙ РЕРАЙТ ХОЛСТА ПРИ РЕСАЙЗЕ ОКНА И СДВИГЕ ТЕКСТА ---
        graphCanvas.widthProperty().addListener(o -> {
            if (!currentResolvedConcepts.isEmpty()) redrawGraph(gc, graphCanvas);
        });
        graphCanvas.heightProperty().addListener(o -> {
            if (!currentResolvedConcepts.isEmpty()) redrawGraph(gc, graphCanvas);
        });

        weightSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            minWeightThreshold = newVal.doubleValue();
            weightLabel.setText(String.format("Порог веса связи (Мин: %.2f):", minWeightThreshold));
            if (!currentResolvedConcepts.isEmpty()) redrawGraph(gc, graphCanvas);
        });

        scaleSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            graphRadiusScale = newVal.doubleValue();
            scaleLabel.setText(String.format("Масштаб круга графа (%d%%):", (int) (graphRadiusScale * 100)));
            if (!currentResolvedConcepts.isEmpty()) redrawGraph(gc, graphCanvas);
        });

        // --- ИНТЕРАКТИВНАЯ НАВИГАЦИЯ МЫШИ (MS PAINT STYLE + ВРАЩЕНИЕ) ---
        graphCanvas.setOnMousePressed(e -> {
            lastMouseX = e.getX();
            lastMouseY = e.getY();
        });

        graphCanvas.setOnMouseDragged(e -> {
            if (currentResolvedConcepts.isEmpty()) return;
            double deltaX = e.getX() - lastMouseX;
            double deltaY = e.getY() - lastMouseY;
            lastMouseX = e.getX();
            lastMouseY = e.getY();

            // ФИЧА МИШИ: Если зажато КОЛЁСИКО мыши (или ЛКМ + КЛАВИША SHORTCUT) — двигаем холст (панорамирование)
            if (e.getButton() == MouseButton.MIDDLE || e.isControlDown()) {
                graphTranslateX += deltaX;
                graphTranslateY += deltaY;
            } else {
                // Если зажата обычная левая кнопка — вращаем граф вокруг оси
                currentAngleOffset += deltaX * 0.01;
            }
            redrawGraph(gc, graphCanvas);
        });

        // --- ЛОГИКА ДИРЕКТОРИЙ И КОНВЕЙЕРА ИИ ---
        DirectoryChooser directoryChooser = new DirectoryChooser();
        browseButton.setOnAction(event -> {
            File selectedFolder = directoryChooser.showDialog(primaryStage);
            if (selectedFolder != null) {
                this.currentFolder = selectedFolder;
                pathField.setText(selectedFolder.getAbsolutePath());
                TreeItem<File> rootItem = createTreeNodes(selectedFolder);
                rootItem.setExpanded(true);
                fileTreeView.setRoot(rootItem);
                logArea.setText("[Система]: Директория загружена. Выберите файл в дереве.");
            }
        });

        fileTreeView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && newValue.getValue().isFile()) {
                selectedFileFromTree = newValue.getValue();
                runButton.setDisable(false);
            } else {
                selectedFileFromTree = null;
                runButton.setDisable(true);
            }
        });
        runButton.setOnAction(event -> {
            if (selectedFileFromTree == null) return;

            logArea.clear();
            logArea.appendText("=================================================================\n");
            logArea.appendText(" 🪐 СИНТЕЗ КИБЕР-ГРАФА: " + selectedFileFromTree.getName().toUpperCase() + "\n");
            logArea.appendText("=================================================================\n");

            File fileToAnalyze = selectedFileFromTree;
            runButton.setDisable(true);

            Thread worker = new Thread(() -> {
                try {
                    TikaParserEngine tikaParser = new TikaParserEngine();
                    String rawText = tikaParser.parseFile(fileToAnalyze.toPath());

                    javafx.application.Platform.runLater(() -> {
                        logArea.appendText("[1/3] Извлечение контента через Apache Tika выполнено.\n");
                        logArea.appendText("[2/3] Анализ ИИ Ollama и дедупликация понятий...\n");
                    });

                    ConceptGraphPipeline pipeline = new ConceptGraphPipeline(
                            new OpenNlpEngine(),
                            new AiGraphEngine(),
                            new AppExceptionHandler(),
                            line -> javafx.application.Platform.runLater(() -> logArea.appendText("  " + line + "\n"))
                    );

                    // Весь анализ идёт в фоновом потоке, а списки для отрисовки меняем только в потоке JavaFX
                    ConceptGraphPipeline.Result result = pipeline.process(
                            fileToAnalyze.getName(),
                            fileToAnalyze.getAbsolutePath(),
                            fileToAnalyze.length(),
                            rawText
                    );

                    javafx.application.Platform.runLater(() -> {
                        currentResolvedConcepts.clear();
                        currentResolvedConcepts.addAll(result.nodes());
                        currentEdges.clear();
                        currentEdges.addAll(result.edges());
                        currentAngleOffset = 0.0;
                        graphTranslateX = 0.0; // Сбрасываем сдвиги камеры на старт
                        graphTranslateY = 0.0;

                        logArea.appendText("[3/3] Генерация связей и рендеринг интерактивной 2D-карты...\n\n");
                        clearCanvas(gc, graphCanvas);
                        redrawGraph(gc, graphCanvas);

                        Map<Long, String> namesById = new HashMap<>();
                        for (ConceptEntity c : currentResolvedConcepts) {
                            namesById.put(c.id(), c.value());
                            String icon = c.type() == ru.conceptgraph.shared.EntityType.PERSON ? "👤" :
                                    c.type() == ru.conceptgraph.shared.EntityType.GEOGRAPHY ? "🌍" :
                                            c.type() == ru.conceptgraph.shared.EntityType.TECHNOLOGY ? "💻" : "📦";
                            logArea.appendText(String.format("  %s Узел [%s] (ID: %d) | Тип: %s (%d -> %d)\n",
                                    icon, c.value(), c.id(), c.type(), c.startChar(), c.endChar()));
                        }

                        logArea.appendText("\n🌐 СФОРМИРОВАННЫЕ СЕМАНТИЧЕСКИЕ СВЯЗИ (EDGES):\n");
                        for (Edge edge : currentEdges) {
                            String srcW = namesById.getOrDefault(edge.sourceConceptId(), "Узел_" + edge.sourceConceptId());
                            String trgW = namesById.getOrDefault(edge.targetConceptId(), "Узел_" + edge.targetConceptId());
                            logArea.appendText(String.format("  • Связь: [%s] 🔗 [%s] | Вес: %.2f\n", srcW, trgW, edge.weight()));
                        }
                        logArea.appendText("\n=================================================================\n");
                        logArea.appendText(" 📊 [НАВИГАЦИЯ]: Нажмите CTRL+ЛКМ или зажмите КОЛЁСИКО мыши для ПЕРЕМЕЩЕНИЯ графа!\n");
                        logArea.appendText("=================================================================\n");
                    });

                } catch (Exception e) {
                    javafx.application.Platform.runLater(() -> logArea.appendText("[Ошибка]: " + e.getMessage() + "\n"));
                } finally {
                    javafx.application.Platform.runLater(() -> runButton.setDisable(selectedFileFromTree == null));
                }
            });
            worker.setDaemon(true); // не держим приложение открытым после закрытия окна
            worker.start();
        });

        Scene scene = new Scene(mainLayout, 1200, 800);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void redrawGraph(GraphicsContext gc, Canvas canvas) {
        int nodeCount = currentResolvedConcepts.size();
        if (nodeCount == 0) return;

        gc.setFill(Color.web("#12161C"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // Базовый динамический центр холста (меняется на лету при сдвиге панели текста!)
        // ИСПРАВЛЕНО: Добавляем виртуальные смещения камеры graphTranslateX и graphTranslateY!
        double centerX = canvas.getWidth() / 2 + graphTranslateX;
        double centerY = canvas.getHeight() / 2 + graphTranslateY;

        // Базовый радиус кольца адаптирован под новые большие размеры окна
        double radius = (Math.min(canvas.getWidth(), canvas.getHeight()) / 2 - 85) * graphRadiusScale;

        double[] coordsX = new double[nodeCount];
        double[] coordsY = new double[nodeCount];

        for (int i = 0; i < nodeCount; i++) {
            double angle = 2 * Math.PI * i / nodeCount + currentAngleOffset;
            coordsX[i] = centerX + radius * Math.cos(angle);
            coordsY[i] = centerY + radius * Math.sin(angle);
        }

        // Рёбра ссылаются на ID из БД, а точки лежат по индексам списка: строим карту ID -> индекс
        Map<Long, Integer> indexById = new HashMap<>();
        for (int i = 0; i < nodeCount; i++) {
            indexById.put(currentResolvedConcepts.get(i).id(), i);
        }

        // 1. Отрисовка неоновых ребер и названий семантических связей
        gc.setLineWidth(1.8);
        for (Edge edge : currentEdges) {
            if (edge.weight() < minWeightThreshold) continue;

            Integer srcIdx = indexById.get(edge.sourceConceptId());
            Integer trgIdx = indexById.get(edge.targetConceptId());

            if (srcIdx != null && trgIdx != null) {
                double x1 = coordsX[srcIdx];
                double y1 = coordsY[srcIdx];
                double x2 = coordsX[trgIdx];
                double y2 = coordsY[trgIdx];

                // --- ЗАЩИТА JAVAFX UI ОТ ПАДЕНИЙ (Opacity Range Check) ---
                double calculatedOpacity = edge.weight() * 1.3;
                if (calculatedOpacity < 0.0) calculatedOpacity = 0.0;
                if (calculatedOpacity > 1.0) calculatedOpacity = 1.0;

                // Рисуем неоновую линию связи
                gc.setStroke(Color.web("#66FCF1", calculatedOpacity));
                gc.strokeLine(x1, y1, x2, y2);

                // Отрисовка текста связи (Предиката) по центру линии
                if (edge.label() != null && !edge.label().isBlank()) {
                    double midX = (x1 + x2) / 2;
                    double midY = (y1 + y2) / 2;

                    gc.save();
                    gc.setFont(javafx.scene.text.Font.font("Consolas", 9));
                    String labelText = edge.label().toLowerCase();

                    double textWidth = labelText.length() * 6.0;
                    double textHeight = 12.0;

                    // Рисуем темную плашку-подложку, чтобы линии не перекрывали буквы
                    gc.setFill(Color.web("#12161C", 0.85));
                    gc.fillRect(midX - textWidth / 2 - 4, midY - textHeight / 2, textWidth + 8, textHeight);

                    // Рендерим само действие
                    gc.setFill(Color.web("#66FCF1"));
                    gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
                    gc.fillText(labelText, midX, midY + 3);

                    gc.restore();
                }
            }
        }

        // 2. Отрисовка кружочков и адаптивного выравнивания текста
        for (int i = 0; i < nodeCount; i++) {
            ConceptEntity concept = currentResolvedConcepts.get(i);
            double x = coordsX[i];
            double y = coordsY[i];

            Color nodeColor = Color.web("#45A29E");
            if (concept.type() == ru.conceptgraph.shared.EntityType.PERSON) nodeColor = Color.web("#FF007F");
            else if (concept.type() == ru.conceptgraph.shared.EntityType.GEOGRAPHY) nodeColor = Color.web("#00FFCC");
            else if (concept.type() == ru.conceptgraph.shared.EntityType.TECHNOLOGY) nodeColor = Color.web("#FFFF00");

            gc.setFill(nodeColor);
            gc.fillOval(x - 7, y - 7, 14, 14);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(1.0);
            gc.strokeOval(x - 7, y - 7, 14, 14);

            gc.setFill(Color.WHITE);
            gc.setFont(javafx.scene.text.Font.font("Consolas", nodeCount > 25 ? 9 : 11));

            if (x > centerX) {
                gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
                gc.fillText(concept.value(), x + 12, y + 3);
            } else {
                gc.setTextAlign(javafx.scene.text.TextAlignment.RIGHT);
                gc.fillText(concept.value(), x - 12, y + 3);
            }
        }
    }

    private void clearCanvas(GraphicsContext gc, Canvas canvas) {
        gc.setFill(Color.web("#12161C"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private TreeItem<File> createTreeNodes(File file) {
        TreeItem<File> item = new TreeItem<>(file);
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    if (child.isDirectory()) {
                        item.getChildren().add(createTreeNodes(child));
                    } else if (SupportedFormats.isSupported(child)) {
                        // тот же список форматов, что и у сканера папок
                        item.getChildren().add(new TreeItem<>(child));
                    }
                }
            }
        }
        return item;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
