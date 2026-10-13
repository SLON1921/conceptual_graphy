package ru.conceptgraph.modules.aiandnlp.view;

import javafx.scene.Cursor;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import ru.conceptgraph.shared.ConceptEntity;
import ru.conceptgraph.shared.Edge;
import ru.conceptgraph.shared.EntityType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Холст графа понятий: пружинная раскладка, фильтры, выбор узла, масштаб колесом и сдвиг мышью.
 *
 * <p>Раскладка считается один раз на результат анализа ({@link #computeLayout}), её можно
 * вызывать в фоновом потоке. Фильтры только скрывают узлы и не двигают остальные.</p>
 */
public class GraphView extends Pane {

    /** Сколько самых частых понятий раскладываем; остальные на графе не показываются */
    public static final int LAYOUT_LIMIT = 300;

    /** Цвета холста; берутся из window.css */
    public record Theme(Color background, Color edge, Color accent, Color text, Map<EntityType, Color> types) {
        public Color typeColor(EntityType type) {
            return types.getOrDefault(type, edge);
        }
    }

    /** Готовая раскладка: узлы по убыванию частоты и их координаты */
    public record Layout(List<ConceptEntity> nodes, double[] x, double[] y) {
    }

    /** Подпись типа понятия для интерфейса */
    public static String typeName(EntityType type) {
        if (type == null) {
            return "—";
        }
        return switch (type) {
            case PERSON -> "Люди";
            case GEOGRAPHY -> "Места";
            case TECHNOLOGY -> "Технологии";
            case PRODUCT -> "Предметы";
        };
    }

    private final Canvas canvas = new Canvas();
    private final Theme theme;

    private Layout layout = new Layout(List.of(), new double[0], new double[0]);
    private Map<Long, Integer> frequency = Map.of();
    private List<Edge> edges = List.of();
    private final Map<Long, Integer> indexById = new HashMap<>();

    private final Set<EntityType> hiddenTypes = EnumSet.noneOf(EntityType.class);
    private String query = "";
    private int topN = 50;
    private double minWeight = 0.2;
    private Long selectedId;

    // виртуальная камера: масштаб и сдвиг в пикселях экрана
    private double zoom = 1.0;
    private double panX = 0.0;
    private double panY = 0.0;

    private double pressX;
    private double pressY;
    private double lastX;
    private double lastY;
    private boolean dragged;

    // то, что нарисовано сейчас (для попадания мышью и строки статуса)
    private boolean[] visible = new boolean[0];
    private double[] screenX = new double[0];
    private double[] screenY = new double[0];
    private double[] radius = new double[0];

    private Consumer<Long> onSelect = id -> { };
    private Consumer<int[]> onStats = stats -> { };

    public GraphView(Theme theme) {
        this.theme = theme;
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        canvas.widthProperty().addListener(o -> redraw());
        canvas.heightProperty().addListener(o -> redraw());
        setMinSize(0, 0);

        canvas.setOnScroll(e -> {
            double factor = e.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
            zoomAt(factor, e.getX(), e.getY());
        });
        canvas.setOnMousePressed(e -> {
            pressX = lastX = e.getX();
            pressY = lastY = e.getY();
            dragged = false;
        });
        canvas.setOnMouseDragged(e -> {
            if (Math.hypot(e.getX() - pressX, e.getY() - pressY) > 3) {
                dragged = true;
            }
            panX += e.getX() - lastX;
            panY += e.getY() - lastY;
            lastX = e.getX();
            lastY = e.getY();
            canvas.setCursor(Cursor.CLOSED_HAND);
            redraw();
        });
        canvas.setOnMouseReleased(e -> {
            canvas.setCursor(nodeAt(e.getX(), e.getY()) >= 0 ? Cursor.HAND : Cursor.DEFAULT);
            if (dragged) {
                return;
            }
            int hit = nodeAt(e.getX(), e.getY());
            Long id = hit < 0 ? null : layout.nodes().get(hit).id();
            if (id != null && id.equals(selectedId)) {
                id = null; // повторный клик снимает выбор
            }
            selectedId = id;
            redraw();
            onSelect.accept(id);
        });
        canvas.setOnMouseMoved(e -> canvas.setCursor(nodeAt(e.getX(), e.getY()) >= 0 ? Cursor.HAND : Cursor.DEFAULT));
    }

    // ---------------- данные и фильтры ----------------

    public void setResult(Layout layout, Map<Long, Integer> frequency, List<Edge> edges) {
        this.layout = layout;
        this.frequency = frequency;
        this.edges = edges;
        indexById.clear();
        for (int i = 0; i < layout.nodes().size(); i++) {
            indexById.put(layout.nodes().get(i).id(), i);
        }
        selectedId = null;
        resetView();
    }

    public void setHiddenTypes(Set<EntityType> types) {
        hiddenTypes.clear();
        hiddenTypes.addAll(types);
        redraw();
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        redraw();
    }

    public void setTopN(int topN) {
        this.topN = topN;
        redraw();
    }

    public void setMinWeight(double minWeight) {
        this.minWeight = minWeight;
        redraw();
    }

    /** Выбрать узел программно (из таблицы или карточки), без вызова onSelect */
    public void select(Long id) {
        selectedId = id;
        redraw();
    }

    public void setOnSelect(Consumer<Long> onSelect) {
        this.onSelect = onSelect;
    }

    /** Получает {узлов на графе, всего узлов, связей на графе} после каждой перерисовки */
    public void setOnStats(Consumer<int[]> onStats) {
        this.onStats = onStats;
    }

    public void resetView() {
        zoom = 1.0;
        panX = 0.0;
        panY = 0.0;
        redraw();
    }

    public void zoomBy(double factor) {
        zoomAt(factor, canvas.getWidth() / 2, canvas.getHeight() / 2);
    }

    private void zoomAt(double factor, double sx, double sy) {
        double newZoom = Math.max(0.3, Math.min(5.0, zoom * factor));
        double applied = newZoom / zoom;
        // точка под курсором остаётся на месте
        double cx = canvas.getWidth() / 2 + panX;
        double cy = canvas.getHeight() / 2 + panY;
        panX += (sx - cx) * (1 - applied);
        panY += (sy - cy) * (1 - applied);
        zoom = newZoom;
        redraw();
    }

    /** Сохраняет то, что сейчас на холсте, в PNG */
    public void saveSnapshot(File file) throws IOException {
        WritableImage image = canvas.snapshot(new SnapshotParameters(), null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        PixelReader reader = image.getPixelReader();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                out.setRGB(px, py, reader.getArgb(px, py));
            }
        }
        ImageIO.write(out, "png", file);
    }

    // ---------------- раскладка ----------------

    /**
     * Пружинная раскладка (Фрюхтерман — Рейнгольд): связанные понятия притягиваются, все отталкиваются.
     * Детерминирована: одинаковые данные дают одинаковую картинку.
     */
    public static Layout computeLayout(List<ConceptEntity> allNodes, Map<Long, Integer> frequency, List<Edge> allEdges) {
        List<ConceptEntity> nodes = new ArrayList<>(allNodes);
        nodes.sort((a, b) -> Integer.compare(frequency.getOrDefault(b.id(), 0), frequency.getOrDefault(a.id(), 0)));
        if (nodes.size() > LAYOUT_LIMIT) {
            nodes = new ArrayList<>(nodes.subList(0, LAYOUT_LIMIT));
        }
        int n = nodes.size();
        double[] x = new double[n];
        double[] y = new double[n];
        if (n == 0) {
            return new Layout(nodes, x, y);
        }

        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(nodes.get(i).id(), i);
        }
        List<int[]> links = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        int[] degree = new int[n];
        for (Edge e : allEdges) {
            Integer a = index.get(e.sourceConceptId());
            Integer b = index.get(e.targetConceptId());
            if (a != null && b != null && !a.equals(b)) {
                links.add(new int[]{a, b});
                weights.add(e.weight());
                degree[a]++;
                degree[b]++;
            }
        }

        double k = 60.0;
        double start = Math.sqrt(n) * 30;
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            double r = start * (0.5 + 0.5 * ((i * 7919) % 97) / 97.0);
            x[i] = r * Math.cos(angle);
            y[i] = r * Math.sin(angle);
        }

        int iterations = 300;
        double temperature = start / 2;
        double[] dx = new double[n];
        double[] dy = new double[n];
        for (int it = 0; it < iterations; it++) {
            java.util.Arrays.fill(dx, 0);
            java.util.Arrays.fill(dy, 0);
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double ddx = x[i] - x[j];
                    double ddy = y[i] - y[j];
                    double d = Math.max(0.01, Math.hypot(ddx, ddy));
                    double force = k * k / d;
                    dx[i] += ddx / d * force;
                    dy[i] += ddy / d * force;
                    dx[j] -= ddx / d * force;
                    dy[j] -= ddy / d * force;
                }
            }
            for (int l = 0; l < links.size(); l++) {
                int a = links.get(l)[0];
                int b = links.get(l)[1];
                double ddx = x[a] - x[b];
                double ddy = y[a] - y[b];
                double d = Math.max(0.01, Math.hypot(ddx, ddy));
                // у понятий с множеством связей притяжение слабее, иначе плотные группы слипаются в точку
                double force = d * d / k * (0.3 + weights.get(l)) / Math.sqrt(Math.max(1, Math.min(degree[a], degree[b])));
                dx[a] -= ddx / d * force;
                dy[a] -= ddy / d * force;
                dx[b] += ddx / d * force;
                dy[b] += ddy / d * force;
            }
            for (int i = 0; i < n; i++) {
                // лёгкое притяжение к центру, чтобы несвязанные понятия не улетали
                dx[i] -= x[i] * 0.12;
                dy[i] -= y[i] * 0.12;
                double len = Math.max(0.01, Math.hypot(dx[i], dy[i]));
                double step = Math.min(len, temperature);
                x[i] += dx[i] / len * step;
                y[i] += dy[i] / len * step;
            }
            temperature = Math.max(0.5, temperature * 0.985);
        }

        // приводим картинку к квадрату [-1; 1], чтобы зазор между узлами не зависел от размера графа
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, x[i]);
            maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]);
            maxY = Math.max(maxY, y[i]);
        }
        double scale = 2 / Math.max(1e-6, Math.max(maxX - minX, maxY - minY));
        for (int i = 0; i < n; i++) {
            x[i] = (x[i] - (minX + maxX) / 2) * scale;
            y[i] = (y[i] - (minY + maxY) / 2) * scale;
        }

        // раздвигаем узлы, которые всё равно легли друг на друга
        double minDist = Math.max(0.06, Math.min(0.5, 1.6 / Math.sqrt(n)));
        for (int it = 0; it < 200; it++) {
            boolean moved = false;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double ddx = x[i] - x[j];
                    double ddy = y[i] - y[j];
                    double d = Math.hypot(ddx, ddy);
                    if (d >= minDist) {
                        continue;
                    }
                    if (d < 0.01) {
                        ddx = Math.cos(i + j);
                        ddy = Math.sin(i + j);
                        d = 1;
                    }
                    double push = (minDist - d) / 2;
                    x[i] += ddx / d * push;
                    y[i] += ddy / d * push;
                    x[j] -= ddx / d * push;
                    y[j] -= ddy / d * push;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
        return new Layout(nodes, x, y);
    }

    // ---------------- отрисовка ----------------

    private int nodeAt(double sx, double sy) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < visible.length; i++) {
            if (!visible[i]) {
                continue;
            }
            double d = Math.hypot(sx - screenX[i], sy - screenY[i]);
            if (d <= radius[i] + 4 && d < bestDist) {
                best = i;
                bestDist = d;
            }
        }
        return best;
    }

    public void redraw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        gc.setGlobalAlpha(1);
        gc.setFill(theme.background());
        gc.fillRect(0, 0, w, h);

        List<ConceptEntity> nodes = layout.nodes();
        int n = nodes.size();
        visible = new boolean[n];
        screenX = new double[n];
        screenY = new double[n];
        radius = new double[n];
        if (n == 0) {
            gc.setFill(theme.text().deriveColor(0, 1, 1, 0.5));
            gc.setFont(Font.font(13));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("Выберите папку слева и нажмите «Анализ папки» или «Анализ файла».", w / 2, h / 2);
            onStats.accept(new int[]{0, 0, 0});
            return;
        }

        // какие узлы видны: топ-N по частоте без скрытых типов
        int shown = 0;
        for (int i = 0; i < n && i < topN; i++) {
            visible[i] = !hiddenTypes.contains(nodes.get(i).type());
            if (visible[i]) {
                shown++;
            }
        }

        // масштаб «вписать в холст» по видимым узлам
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (!visible[i]) {
                continue;
            }
            minX = Math.min(minX, layout.x()[i]);
            maxX = Math.max(maxX, layout.x()[i]);
            minY = Math.min(minY, layout.y()[i]);
            maxY = Math.max(maxY, layout.y()[i]);
        }
        if (shown == 0) {
            minX = maxX = minY = maxY = 0;
        }
        // раскладка лежит в квадрате [-1; 1]; одиночный узел не растягиваем на весь холст
        double spanX = Math.max(0.5, maxX - minX);
        double spanY = Math.max(0.5, maxY - minY);
        double fit = Math.max(1, Math.min((w - 140) / spanX, (h - 90) / spanY));
        double midX = (minX + maxX) / 2;
        double midY = (minY + maxY) / 2;
        double cx = w / 2 + panX;
        double cy = h / 2 + panY;
        for (int i = 0; i < n; i++) {
            screenX[i] = cx + (layout.x()[i] - midX) * fit * zoom;
            screenY[i] = cy + (layout.y()[i] - midY) * fit * zoom;
            int f = frequency.getOrDefault(nodes.get(i).id(), 1);
            radius[i] = Math.min(22, 4 + Math.sqrt(f) * 1.6);
        }

        Integer selectedIndex = selectedId == null ? null : indexById.get(selectedId);
        if (selectedIndex != null && !visible[selectedIndex]) {
            selectedIndex = null;
        }
        Set<Integer> neighbours = new HashSet<>();
        int edgesShown = 0;

        // 1. рёбра
        for (Edge e : edges) {
            Integer a = indexById.get(e.sourceConceptId());
            Integer b = indexById.get(e.targetConceptId());
            if (a == null || b == null || !visible[a] || !visible[b] || e.weight() < minWeight) {
                continue;
            }
            edgesShown++;
            boolean hot = selectedIndex != null && (a.equals(selectedIndex) || b.equals(selectedIndex));
            if (hot) {
                neighbours.add(a.equals(selectedIndex) ? b : a);
            }
            double alpha = selectedIndex != null && !hot ? 0.10 : hot ? 0.95 : 0.30 + e.weight() * 0.4;
            gc.setGlobalAlpha(Math.max(0, Math.min(1, alpha)));
            gc.setStroke(hot ? theme.accent() : theme.edge());
            gc.setLineWidth(0.6 + e.weight() * 3.0);
            gc.strokeLine(screenX[a], screenY[a], screenX[b], screenY[b]);
        }

        // 2. подписи выбранных связей (общее «связан» не пишем)
        gc.setGlobalAlpha(1);
        if (selectedIndex != null) {
            gc.setFont(Font.font("Consolas", 10));
            gc.setTextAlign(TextAlignment.CENTER);
            for (Edge e : edges) {
                Integer a = indexById.get(e.sourceConceptId());
                Integer b = indexById.get(e.targetConceptId());
                if (a == null || b == null || !visible[a] || !visible[b] || e.weight() < minWeight) {
                    continue;
                }
                if (!(a.equals(selectedIndex) || b.equals(selectedIndex))) {
                    continue;
                }
                String label = e.label();
                if (label == null || label.isBlank() || label.equalsIgnoreCase("связан")) {
                    continue;
                }
                double mx = (screenX[a] + screenX[b]) / 2;
                double my = (screenY[a] + screenY[b]) / 2 - 4;
                haloText(gc, label.toLowerCase(Locale.ROOT), mx, my, theme.accent());
            }
        }

        // 3. узлы и подписи
        boolean labelAll = shown <= 30;
        for (int i = n - 1; i >= 0; i--) { // частые рисуем последними, поверх
            if (!visible[i]) {
                continue;
            }
            ConceptEntity c = nodes.get(i);
            boolean isSelected = selectedIndex != null && selectedIndex == i;
            boolean match = !query.isEmpty() && c.value().toLowerCase(Locale.ROOT).contains(query);
            boolean dim = (selectedIndex != null && !isSelected && !neighbours.contains(i))
                    || (!query.isEmpty() && !match);
            gc.setGlobalAlpha(dim ? 0.18 : 1.0);

            double r = radius[i];
            gc.setFill(theme.typeColor(c.type()));
            gc.fillOval(screenX[i] - r, screenY[i] - r, r * 2, r * 2);
            gc.setStroke(isSelected || match ? theme.text() : theme.background());
            gc.setLineWidth(isSelected || match ? 2.5 : 1.5);
            gc.strokeOval(screenX[i] - r, screenY[i] - r, r * 2, r * 2);

            boolean label = labelAll || i < 25 || isSelected || match || neighbours.contains(i);
            if (label) {
                gc.setFont(Font.font("Segoe UI", isSelected ? FontWeight.BOLD : FontWeight.NORMAL, r > 10 ? 12 : 11));
                gc.setTextAlign(TextAlignment.CENTER);
                haloText(gc, c.value(), screenX[i], screenY[i] + r + 13, theme.text());
            }
        }
        gc.setGlobalAlpha(1);
        onStats.accept(new int[]{shown, n, edgesShown});
    }

    /** Текст с обводкой цветом фона, чтобы линии не мешали читать */
    private void haloText(GraphicsContext gc, String text, double x, double y, Color color) {
        gc.setStroke(theme.background());
        gc.setLineWidth(3);
        gc.strokeText(text, x, y);
        gc.setFill(color);
        gc.fillText(text, x, y);
    }
}
