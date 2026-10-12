package ru.conceptgraph.modules.aiandnlp.view;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import ru.conceptgraph.modules.aiandnlp.service.ConceptGraphPipeline;
import ru.conceptgraph.modules.aiandnlp.service.FailureLogReader;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.EntityType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Вкладки результатов по плану: Лог, Документы, Понятия, Даты, Ошибки, Статистика.
 */
public class ResultTabs {

    /** Строка таблицы понятий */
    public record ConceptRow(Long id, String value, EntityType type, int count) {
    }

    private final TabPane tabPane = new TabPane();
    private final TableView<ConceptGraphPipeline.DocumentReport> documentsTable = new TableView<>();
    private final TableView<ConceptRow> conceptsTable = new TableView<>();
    private final TableView<ConceptGraphPipeline.DateFound> datesTable = new TableView<>();
    private final ListView<String> errorsList = new ListView<>();
    private final TextArea statisticsArea = new TextArea();

    public ResultTabs(TextArea logArea) {
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        documentsTable.getColumns().add(column("Файл", r -> r.name()));
        documentsTable.getColumns().add(column("Символов", r -> r.chars()));
        documentsTable.getColumns().add(column("Понятий", r -> r.mentions()));
        documentsTable.getColumns().add(column("Людей", r -> r.persons()));
        documentsTable.getColumns().add(column("Дат", r -> r.dates()));
        documentsTable.getColumns().add(column("Время, мс", r -> r.millis()));
        documentsTable.getColumns().add(column("Путь", r -> r.path()));

        conceptsTable.getColumns().add(column("ID", r -> r.id()));
        conceptsTable.getColumns().add(column("Понятие", r -> r.value()));
        conceptsTable.getColumns().add(column("Тип", r -> r.type()));
        conceptsTable.getColumns().add(column("Вхождений", r -> r.count()));

        datesTable.getColumns().add(column("Как в тексте", r -> r.date().text()));
        datesTable.getColumns().add(column("Дата", r -> r.date().normalized()));
        datesTable.getColumns().add(column("Позиция", r -> r.date().startChar()));
        datesTable.getColumns().add(column("Файл", r -> r.document()));

        Button loadFailures = new Button("Загрузить журнал ошибок из БД");
        loadFailures.setOnAction(e -> loadFailuresFromDb());
        VBox errorsBox = new VBox(6, loadFailures, errorsList);
        VBox.setVgrow(errorsList, Priority.ALWAYS);

        statisticsArea.setEditable(false);
        statisticsArea.setFont(Font.font("Consolas", 12));

        for (TableView<?> table : List.of(documentsTable, conceptsTable, datesTable)) {
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        }

        tabPane.getTabs().addAll(
                new Tab("Лог", logArea),
                new Tab("Документы", documentsTable),
                new Tab("Понятия", conceptsTable),
                new Tab("Даты", datesTable),
                new Tab("Ошибки", errorsBox),
                new Tab("Статистика", statisticsArea)
        );
    }

    public TabPane getPane() {
        return tabPane;
    }

    /** Заполняет все вкладки; вызывать в потоке JavaFX */
    public void show(ConceptGraphPipeline.Result result) {
        documentsTable.setItems(FXCollections.observableArrayList(result.documents()));

        List<ConceptRow> rows = new ArrayList<>();
        for (ConceptEntity node : result.nodes()) {
            rows.add(new ConceptRow(node.id(), node.value(), node.type(),
                    result.frequency().getOrDefault(node.id(), 0)));
        }
        rows.sort((a, b) -> Integer.compare(b.count(), a.count()));
        conceptsTable.setItems(FXCollections.observableArrayList(rows));

        datesTable.setItems(FXCollections.observableArrayList(result.dates()));
        errorsList.setItems(FXCollections.observableArrayList(result.errors()));
        statisticsArea.setText(buildStatistics(result));

        if (!result.errors().isEmpty()) {
            tabPane.getTabs().get(4).setText("Ошибки (" + result.errors().size() + ")");
        } else {
            tabPane.getTabs().get(4).setText("Ошибки");
        }
    }

    private String buildStatistics(ConceptGraphPipeline.Result result) {
        StringBuilder sb = new StringBuilder();
        int mentions = result.frequency().values().stream().mapToInt(Integer::intValue).sum();
        sb.append("Документов:        ").append(result.documents().size()).append('\n');
        sb.append("Вхождений понятий: ").append(mentions).append('\n');
        sb.append("Уникальных понятий:").append(' ').append(result.nodes().size()).append('\n');
        sb.append("Рёбер графа:       ").append(result.edges().size()).append('\n');
        sb.append("Дат:               ").append(result.dates().size()).append('\n');
        sb.append("Ошибок:            ").append(result.errors().size()).append('\n');
        sb.append("Сохранено в БД:    ").append(result.savedToDb() ? "да" : "нет (локальные ID)").append("\n\n");

        Map<EntityType, Integer> byType = new EnumMap<>(EntityType.class);
        for (ConceptEntity node : result.nodes()) {
            byType.merge(node.type(), 1, Integer::sum);
        }
        sb.append("Понятия по типам:\n");
        for (EntityType type : EntityType.values()) {
            sb.append(String.format("  %-11s %d%n", type, byType.getOrDefault(type, 0)));
        }

        sb.append("\nВремя по этапам:\n");
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
            } catch (Exception e) {
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
