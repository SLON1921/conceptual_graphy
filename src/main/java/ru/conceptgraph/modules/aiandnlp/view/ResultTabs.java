package ru.conceptgraph.modules.aiandnlp.view;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import ru.conceptgraph.modules.aiandnlp.service.ConceptGraphPipeline;
import ru.conceptgraph.modules.aiandnlp.service.FailureLogReader;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.EntityType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final TextArea statisticsArea = new TextArea();
    private final Tab errorsTab;

    private Consumer<Long> onSelect = id -> { };
    private boolean selecting; // выбор из кода, а не кликом: не зовём onSelect

    public ResultTabs(TextArea logArea, GraphView.Theme theme) {
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

        statisticsArea.setEditable(false);

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
                new Tab("Статистика", statisticsArea),
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
        statisticsArea.setText(buildStatistics(result));
        errorsTab.setText(result.errors().isEmpty() ? "Ошибки" : "Ошибки (" + result.errors().size() + ")");
    }

    private String buildStatistics(ConceptGraphPipeline.Result result) {
        StringBuilder sb = new StringBuilder();
        int mentions = result.frequency().values().stream().mapToInt(Integer::intValue).sum();
        sb.append(String.format("%-20s %d%n", "Документов", result.documents().size()));
        sb.append(String.format("%-20s %d%n", "Уникальных понятий", result.nodes().size()));
        sb.append(String.format("%-20s %d%n", "Вхождений", mentions));
        sb.append(String.format("%-20s %d%n", "Связей", result.edges().size()));
        sb.append(String.format("%-20s %d%n", "Дат", result.dates().size()));
        sb.append(String.format("%-20s %d%n", "Ошибок", result.errors().size()));
        sb.append(String.format("%-20s %s%n%n", "Сохранено в БД", result.savedToDb() ? "да" : "нет (локальные ID)"));

        Map<EntityType, Integer> byType = new EnumMap<>(EntityType.class);
        for (ConceptEntity node : result.nodes()) {
            byType.merge(node.type(), 1, Integer::sum);
        }
        sb.append("По типам\n");
        for (EntityType type : EntityType.values()) {
            sb.append(String.format("  %-12s %d%n", GraphView.typeName(type), byType.getOrDefault(type, 0)));
        }

        sb.append("\nВремя по этапам\n");
        long total = 0;
        for (Map.Entry<String, Long> stage : result.stageMillis().entrySet()) {
            sb.append(String.format("  %-36s %6d мс%n", stage.getKey(), stage.getValue()));
            total += stage.getValue();
        }
        sb.append(String.format("  %-36s %6d мс%n", "Всего", total));
        return sb.toString();
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
