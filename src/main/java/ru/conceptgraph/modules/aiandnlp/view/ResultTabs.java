package ru.conceptgraph.modules.aiandnlp.view;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import ru.conceptgraph.modules.aiandnlp.service.ConceptGraphPipeline;
import ru.conceptgraph.modules.aiandnlp.service.FailureLogReader;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.EntityType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Вкладки результатов по плану: Понятия, Документы, Даты, Ошибки, Статистика, Лог.
 * Таблица понятий связана с графом: выбор строки выделяет узел и наоборот.
 */
public class ResultTabs {

    /** Строка таблицы понятий */
    public record ConceptRow(Long id, String value, EntityType type, int count) {
    }

    private final TabPane tabPane = new TabPane();
    private final TableView<ConceptRow> conceptsTable = new TableView<>();
    private final ObservableList<ConceptRow> conceptSource = FXCollections.observableArrayList();
    private final FilteredList<ConceptRow> conceptRows = new FilteredList<>(conceptSource);
    private final TableView<ConceptGraphPipeline.DocumentReport> documentsTable = new TableView<>();
    private final TableView<ConceptGraphPipeline.DateFound> datesTable = new TableView<>();
    private final ListView<String> errorsList = new ListView<>();
    /** Дашборд вкладки «Статистика»: плитки с числами и три диаграммы */
    private final FlowPane statTiles = new FlowPane(8, 8);
    private final PieChart typesChart = new PieChart();
    private final BarChart<Number, String> topChart = new BarChart<>(new NumberAxis(), new CategoryAxis());
    private final BarChart<Number, String> stagesChart = new BarChart<>(new NumberAxis(), new CategoryAxis());
    private final GraphView.Theme theme;
    private final Tab errorsTab;

    private Consumer<Long> onSelect = id -> { };
    private boolean selecting; // выбор из кода, а не кликом: не зовём onSelect

    public ResultTabs(TextArea logArea, GraphView.Theme theme) {
        this.theme = theme;
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        TableColumn<ConceptRow, ConceptRow> nameColumn = new TableColumn<>("Понятие");
        nameColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        nameColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(ConceptRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(row.value());
                    setGraphic(new Circle(4, theme.typeColor(row.type())));
                }
            }
        });
        nameColumn.setComparator((a, b) -> a.value().compareToIgnoreCase(b.value()));
        conceptsTable.getColumns().add(nameColumn);
        conceptsTable.getColumns().add(column("Тип", r -> GraphView.typeName(r.type())));
        TableColumn<ConceptRow, Integer> countColumn = column("Вхожд.", ConceptRow::count);
        countColumn.setStyle("-fx-alignment: center-right;");
        conceptsTable.getColumns().add(countColumn);
        SortedList<ConceptRow> sorted = new SortedList<>(conceptRows);
        sorted.comparatorProperty().bind(conceptsTable.comparatorProperty());
        conceptsTable.setItems(sorted);
        conceptsTable.setPlaceholder(new Label("Здесь появятся понятия после анализа"));
        conceptsTable.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> {
            if (!selecting) {
                onSelect.accept(row == null ? null : row.id());
            }
        });

        documentsTable.getColumns().add(column("Файл", ConceptGraphPipeline.DocumentReport::name));
        documentsTable.getColumns().add(column("Символов", ConceptGraphPipeline.DocumentReport::chars));
        documentsTable.getColumns().add(column("Понятий", ConceptGraphPipeline.DocumentReport::mentions));
        documentsTable.getColumns().add(column("Людей", ConceptGraphPipeline.DocumentReport::persons));
        documentsTable.getColumns().add(column("Дат", ConceptGraphPipeline.DocumentReport::dates));
        documentsTable.getColumns().add(column("мс", ConceptGraphPipeline.DocumentReport::millis));
        documentsTable.setPlaceholder(new Label("Документов пока нет"));

        datesTable.getColumns().add(column("Как в тексте", r -> r.date().text()));
        datesTable.getColumns().add(column("Дата", r -> r.date().normalized()));
        datesTable.getColumns().add(column("Файл", ConceptGraphPipeline.DateFound::document));
        datesTable.setPlaceholder(new Label("Даты пока не найдены"));

        Button loadFailures = new Button("Загрузить журнал ошибок из БД");
        loadFailures.setOnAction(e -> loadFailuresFromDb());
        VBox errorsBox = new VBox(6, errorsList, loadFailures);
        errorsBox.setStyle("-fx-padding: 0 0 8 0;");
        VBox.setVgrow(errorsList, Priority.ALWAYS);
        errorsList.setPlaceholder(new Label("Ошибок нет"));

        ScrollPane statisticsPane = buildDashboard();

        for (TableView<?> table : List.of(conceptsTable, documentsTable, datesTable)) {
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        }
        nameColumn.setPrefWidth(170);

        errorsTab = new Tab("Ошибки", errorsBox);
        tabPane.getTabs().addAll(
                new Tab("Понятия", conceptsTable),
                new Tab("Документы", documentsTable),
                new Tab("Даты", datesTable),
                errorsTab,
                new Tab("Статистика", statisticsPane),
                new Tab("Лог", logArea)
        );
    }

    public TabPane getPane() {
        return tabPane;
    }

    public void setOnSelect(Consumer<Long> onSelect) {
        this.onSelect = onSelect;
    }

    /** Выделяет строку понятия (или снимает выделение); onSelect при этом не вызывается */
    public void selectConcept(Long id) {
        selecting = true;
        try {
            if (id == null) {
                conceptsTable.getSelectionModel().clearSelection();
                return;
            }
            for (ConceptRow row : conceptsTable.getItems()) {
                if (row.id().equals(id)) {
                    conceptsTable.getSelectionModel().select(row);
                    conceptsTable.scrollTo(row);
                    return;
                }
            }
            conceptsTable.getSelectionModel().clearSelection();
        } finally {
            selecting = false;
        }
    }

    /** Фильтр таблицы понятий по строке поиска */
    public void setQuery(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        selecting = true;
        try {
            conceptRows.setPredicate(q.isEmpty() ? null : row -> row.value().toLowerCase(Locale.ROOT).contains(q));
        } finally {
            selecting = false;
        }
    }

    /** Заполняет все вкладки; вызывать в потоке JavaFX */
    public void show(ConceptGraphPipeline.Result result) {
        List<ConceptRow> rows = new ArrayList<>();
        for (ConceptEntity node : result.nodes()) {
            rows.add(new ConceptRow(node.id(), node.value(), node.type(),
                    result.frequency().getOrDefault(node.id(), 0)));
        }
        rows.sort((a, b) -> Integer.compare(b.count(), a.count()));
        selecting = true;
        try {
            conceptSource.setAll(rows);
        } finally {
            selecting = false;
        }

        documentsTable.setItems(FXCollections.observableArrayList(result.documents()));
        datesTable.setItems(FXCollections.observableArrayList(result.dates()));
        errorsList.setItems(FXCollections.observableArrayList(result.errors()));
        showStatistics(result);
        errorsTab.setText(result.errors().isEmpty() ? "Ошибки" : "Ошибки (" + result.errors().size() + ")");
    }

    private ScrollPane buildDashboard() {
        typesChart.setTitle("Понятия по типам");
        typesChart.setLegendVisible(false);
        typesChart.setLabelLineLength(8);
        typesChart.setPrefHeight(240);
        typesChart.setAnimated(false);

        topChart.setTitle("Топ-10 понятий по вхождениям");
        stagesChart.setTitle("Время по этапам, мс");
        for (BarChart<Number, String> chart : List.of(topChart, stagesChart)) {
            chart.setLegendVisible(false);
            chart.setAnimated(false);
            chart.setCategoryGap(3);
            chart.setBarGap(0);
            chart.setPrefHeight(260);
            NumberAxis axis = (NumberAxis) chart.getXAxis();
            axis.setMinorTickVisible(false);
            axis.setTickLabelFormatter(new NumberAxis.DefaultFormatter(axis) {
                @Override
                public String toString(Number value) {
                    return String.valueOf(value.longValue());
                }
            });
        }

        statTiles.getStyleClass().add("stat-tiles");
        Label empty = new Label("Здесь появится статистика после анализа");
        empty.getStyleClass().add("card-empty");
        statTiles.getChildren().add(empty);

        VBox box = new VBox(10, statTiles, typesChart, topChart, stagesChart);
        box.setPadding(new Insets(10));
        box.getStyleClass().add("dashboard");
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("dashboard-scroll");
        return scroll;
    }

    /** Заполняет дашборд: числа сверху, под ними диаграммы по типам, по частоте и по времени этапов */
    private void showStatistics(ConceptGraphPipeline.Result result) {
        int mentions = result.frequency().values().stream().mapToInt(Integer::intValue).sum();
        long totalMillis = result.stageMillis().values().stream().mapToLong(Long::longValue).sum();
        statTiles.getChildren().setAll(
                tile("Документов", String.valueOf(result.documents().size())),
                tile("Понятий", String.valueOf(result.nodes().size())),
                tile("Вхождений", String.valueOf(mentions)),
                tile("Связей", String.valueOf(result.edges().size())),
                tile("Дат", String.valueOf(result.dates().size())),
                tile("Ошибок", String.valueOf(result.errors().size())),
                tile("Время этапов", String.format(Locale.forLanguageTag("ru"), "%.1f с", totalMillis / 1000.0)),
                tile("Сохранено в БД", result.savedToDb() ? "да" : "нет"));

        Map<EntityType, Integer> byType = new EnumMap<>(EntityType.class);
        for (ConceptEntity node : result.nodes()) {
            byType.merge(node.type(), 1, Integer::sum);
        }
        typesChart.getData().clear();
        for (EntityType type : EntityType.values()) {
            int count = byType.getOrDefault(type, 0);
            if (count == 0) {
                continue;
            }
            PieChart.Data slice = new PieChart.Data(GraphView.typeName(type) + " " + count, count);
            typesChart.getData().add(slice);
            // цвет сектора как у узлов этого типа на графе
            slice.getNode().setStyle("-fx-pie-color: " + toCss(theme.typeColor(type)) + ";");
        }

        // горизонтальные столбцы рисуются снизу вверх, поэтому самое частое добавляем последним
        List<ConceptEntity> top = new ArrayList<>(result.nodes());
        top.sort((a, b) -> Integer.compare(result.frequency().getOrDefault(b.id(), 0),
                result.frequency().getOrDefault(a.id(), 0)));
        top = top.subList(0, Math.min(10, top.size()));
        XYChart.Series<Number, String> topSeries = new XYChart.Series<>();
        Set<String> used = new HashSet<>();
        for (int i = top.size() - 1; i >= 0; i--) {
            ConceptEntity node = top.get(i);
            String name = shorten(node.value(), 22);
            while (!used.add(name)) {
                name = name + " "; // одинаковые подписи склеились бы в одну категорию
            }
            topSeries.getData().add(new XYChart.Data<>(result.frequency().getOrDefault(node.id(), 0), name));
        }
        topChart.getData().setAll(List.of(topSeries));

        XYChart.Series<Number, String> stageSeries = new XYChart.Series<>();
        List<Map.Entry<String, Long>> stages = new ArrayList<>(result.stageMillis().entrySet());
        for (int i = stages.size() - 1; i >= 0; i--) {
            stageSeries.getData().add(new XYChart.Data<>(stages.get(i).getValue(), shorten(stages.get(i).getKey(), 24)));
        }
        stagesChart.getData().setAll(List.of(stageSeries));
    }

    private static VBox tile(String title, String value) {
        Label v = new Label(value);
        v.getStyleClass().add("stat-value");
        Label k = new Label(title);
        k.getStyleClass().add("stat-key");
        VBox tile = new VBox(2, v, k);
        tile.getStyleClass().add("stat-tile");
        return tile;
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String toCss(javafx.scene.paint.Color c) {
        return String.format("#%02X%02X%02X", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    private void loadFailuresFromDb() {
        errorsList.setItems(FXCollections.observableArrayList("Загрузка..."));
        Thread loader = new Thread(() -> {
            List<String> lines = new ArrayList<>();
            try {
                for (FailureLogReader.Failure f : FailureLogReader.loadRecent(200)) {
                    lines.add(f.happenedAt() + " | " + f.word() + " | " + f.message());
                }
                if (lines.isEmpty()) {
                    lines.add("Журнал ошибок в БД пуст.");
                }
            } catch (Throwable e) {
                lines.add("Не удалось прочитать журнал: " + e.getMessage());
            }
            Platform.runLater(() -> errorsList.setItems(FXCollections.observableArrayList(lines)));
        });
        loader.setDaemon(true);
        loader.start();
    }

    private static <S, T> TableColumn<S, T> column(String title, Function<S, T> getter) {
        TableColumn<S, T> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(getter.apply(cell.getValue())));
        return column;
    }
}
