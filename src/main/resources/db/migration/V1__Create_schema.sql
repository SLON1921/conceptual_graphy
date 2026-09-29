CREATE TABLE documents (
                           id BIGSERIAL PRIMARY KEY,
                           name VARCHAR(255) NOT NULL,
                           path TEXT NOT NULL UNIQUE,
                           size BIGINT NOT NULL,
                           parsed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE documents IS 'Основная таблица метаданных. Разделяет физический файл на диске и его текстовое содержимое. Нужна для регистрации обработанных файлов, исключения повторного парсинга (через UNIQUE path) и связи с будущей таблицей вхождений (occurrences).';
COMMENT ON COLUMN documents.id IS 'ID Документа';
COMMENT ON COLUMN documents.name IS 'Название файла с разрешением';
COMMENT ON COLUMN documents.path IS 'Уникальный путь к файлу на компе';
COMMENT ON COLUMN documents.size IS 'Размер файла в байтах';
COMMENT ON COLUMN documents.parsed_at IS 'когда обработан док с точным местным временем.';

CREATE TYPE concept_type AS ENUM ('TECHNOLOGY', 'PRODUCT', 'GEOGRAPHY', 'PERSON');

CREATE TABLE concepts (
                          id BIGSERIAL PRIMARY KEY,
                          value VARCHAR(255) NOT NULL UNIQUE,
                          type concept_type NOT NULL
);

COMMENT ON TABLE concepts IS
    'Справочник уникальных очищенных понятий. Исключает дублирование строк с одинаковыми именами';

COMMENT ON COLUMN concepts.id IS 'ID понятия';
COMMENT ON COLUMN concepts.value IS 'Очищенное слово';
COMMENT ON COLUMN concepts.type IS 'Категория сущности, ограниченная списком ENUM (TECHNOLOGY, PRODUCT, GEOGRAPHY, PERSON).';

CREATE TABLE occurrences (
                             id BIGSERIAL PRIMARY KEY,
    -- REFERENCES связывает таблицы. ON DELETE CASCADE означает: если удалить документ,
    -- все его вхождения вычистятся базой автоматически.
                             document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                             concept_id BIGINT NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
                             position INT NOT NULL,

    -- Ограничение: в одном и том же документе на одной и той же позиции не может быть двух разных слов
                             CONSTRAINT unique_occurrence UNIQUE(document_id, concept_id, position)
);

COMMENT ON TABLE occurrences IS
    'Связующая таблица (Многие-ко-многим). Хранит точные координаты (position) каждого конкретного вхождения слова в текст документа для Аналитика.';

COMMENT ON COLUMN occurrences.document_id IS 'Ссылка на ID документа из таблицы documents.';
COMMENT ON COLUMN occurrences.concept_id IS 'Ссылка на ID понятия из таблицы concepts.';
COMMENT ON COLUMN occurrences.position IS 'Символьное смещение от начала файла, по которому Аналитик будет считать расстояния между словами.';

CREATE TABLE wiki_cache (
                            id BIGSERIAL PRIMARY KEY,
    -- REFERENCES связывает кэш со справочником понятий concepts.
    -- ON DELETE CASCADE: если Миша или ИИ удалят понятие, его кэш сотрется автоматически.
                            concept_id BIGINT NOT NULL UNIQUE REFERENCES concepts(id) ON DELETE CASCADE,
                            url TEXT NOT NULL,
                            snippet TEXT NOT NULL,
                            links_count INT NOT NULL,
                            words_count INT NOT NULL
);

COMMENT ON TABLE wiki_cache IS
    'Локальный кэш Wikipedia API. Предотвращает медленные повторные HTTP-запросы к сети интернет, сохраняя данные статей локально.';

COMMENT ON COLUMN wiki_cache.id IS 'ID записи в кэше.';
COMMENT ON COLUMN wiki_cache.concept_id IS 'Ссылка на ID понятия из таблицы concepts, к которому привязана эта статья Википедии.';
COMMENT ON COLUMN wiki_cache.url IS 'Прямой URL-адрес статьи в Википедии';
COMMENT ON COLUMN wiki_cache.snippet IS 'Содержимое первых двух абзацев статьи. Для омонинов';
COMMENT ON COLUMN wiki_cache.links_count IS 'Суммарное количество внутренних и внешних ссылок (для популярности)';
COMMENT ON COLUMN wiki_cache.words_count IS 'Общий объем текста вики-страницы в количестве символов(для значимости)';

CREATE TABLE graph_edges (
                             id BIGSERIAL PRIMARY KEY,
                             source_concept_id BIGINT NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
                             target_concept_id BIGINT NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
                             weight DOUBLE PRECISION NOT NULL,

                             CONSTRAINT unique_edge UNIQUE(source_concept_id, target_concept_id)
);

COMMENT ON TABLE graph_edges IS
    'Таблица ребер понятийного графа. Хранит математические связи между парами сущностей и силу этих связей (weight), рассчитанную Аналитиком.';

COMMENT ON COLUMN graph_edges.id IS 'ID ребра графа.';
COMMENT ON COLUMN graph_edges.source_concept_id IS 'Ссылка на ID исходного понятия (откуда идет связь).';
COMMENT ON COLUMN graph_edges.target_concept_id IS 'Ссылка на ID целевого понятия (куда направлена связь).';
COMMENT ON COLUMN graph_edges.weight IS 'Сила связи(расстояние между понятиями).';

CREATE TABLE entity_failures (
                                 id BIGSERIAL PRIMARY KEY,
                                 word VARCHAR(255) NOT NULL,
                                 error_message TEXT NOT NULL,
                                 happened_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE entity_failures IS
    'Журнал технических сбоев и ошибок распознавания.';

COMMENT ON COLUMN entity_failures.id IS 'ID записи о сбое.';
COMMENT ON COLUMN entity_failures.word IS 'Понятие или слово, при обработке которого во внешних API произошла ошибка.';
COMMENT ON COLUMN entity_failures.error_message IS 'Полное текстовое описание возникшего Java-исключения (Exception).';

-- Миша и Ваня будут постоянно искать слова конкретного документа и наоборот.
CREATE INDEX idx_occurrences_document ON occurrences(document_id);
CREATE INDEX idx_occurrences_concept ON occurrences(concept_id);

-- Миша на фронтенде будет мгновенно скачивать граф, фильтруя по исходным и целевым узлам.
CREATE INDEX idx_graph_edges_source ON graph_edges(source_concept_id);
CREATE INDEX idx_graph_edges_target ON graph_edges(target_concept_id);

-- с concepts индекс по concept_id гарантирует моментальный поиск в локальном кэше.
CREATE INDEX idx_wiki_cache_concept ON wiki_cache(concept_id);

-- Позволит быстро фильтровать ошибки.
CREATE INDEX idx_failures_word ON entity_failures(word);
CREATE INDEX idx_failures_time ON entity_failures(happened_at DESC); -- Сортировка от новых к старым
