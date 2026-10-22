# Clojure-аудит и исправления — 2026-10-16

База сравнения: `1c90074`. Проверены структура `src`/`test`, сборка контекста,
метрики памяти, API-fixtures, runner, SQL-обёртки и REPL-helpers. Очередь чата,
streaming и чтение веток истории просмотрены на уровне границ и контрактов;
полной проверки каждой функции репозитория этот проход не заменяет.

## Исправленные проблемы

| Приоритет | Причина | Изменение |
|---|---|---|
| P1 | `compact-excess-tool-results` сериализовал все tool results после каждого сокращения | Начальная сумма + разница стоимости изменённого сообщения |
| P1 | Выбор prefix summary пересчитывал всю оставшуюся историю для каждой границы | Prefix sums; ограниченный summary input вычисляется один раз |
| P1 | Ручной список runner пропускал `magi.core-test`, `tools.approvals-test`, `telegram.tool-summary-test` | Автопоиск `*_test.clj`, сортировка и загрузка внутри изоляции |
| P1 | API-fixtures создавали систему, заменяли её store и сервисы, оставляли первоначальные ресурсы открытыми | Одна система с заданной конфигурацией; закрытие системы после HTTP-сервера, включая ошибку запуска |
| P1 | Первый SQLite connection истекал через 30 секунд | Стек показал `SQLiteJDBCLoader.cleanup → Files.list → readdir` общего temp-каталога. Runner задаёт собственный `org.sqlite.tmpdir`, затем восстанавливает property |
| P2 | `IRIS_DATA_DIR` обходил изоляцию; непустой временный каталог оставался после runner | Изолированы config/data defaults; рекурсивная очистка в `finally` |
| P2 | `embedding-coverage` строил множество approved paths для каждого chunk | Множество строится один раз: O(notes + chunks) вместо O(notes × chunks) |
| P2 | Дублирование механизма truncation и повторная оценка сообщений в budget report | Единый механизм с явным predicate/action; один проход для категорий бюджета |
| P2 | Lint не видел HugSQL-generated vars | Явные `declare`, как в остальных SQL namespaces; проверки не отключены |
| P2 | `clojure -M:dev` не компилировался из-за удалённых API | Events направлены в SQLite; удалены устаревшие fact/prompt helpers; memory tool использует `memory_recall` |

## Архитектура

В `context-pack` чистый выбор границы вынесен в `prefix-summary-cut`.
Вызов внешнего summarizer остаётся в orchestration-функции и выполняется один раз
после выбора границы. Общий механизм truncation принимает правило применимости
и тип решения явно. Состояние расчёта — immutable local values; глобального кеша
сообщений или новых runtime atoms нет.

Изменения сохраняют порядок сообщений, защищённый последний user turn/tool loop,
решения о сокращении, token/budget reports и fallback при отказе summarizer.
Пороговые значения и политика удаления контекста не менялись.

## Тесты

Удалены две проверки содержания `DESIGN.md`: количество reference images и
перечисление CSS tokens. Проверка текста Clojure-исходника для бренда заменена
проверкой отрендеренной ссылки, текста и доступного имени. Убрана ещё одна
проверка исходника `ui.clj` из dark-theme теста.

API теперь тестируется с настоящей компоновкой сервисов. Вместо фиксированного
числа 16 tools проверяются соответствие `/health` каталогу и наличие ключевых
инструментов. Fixture теста истории явно задаёт достаточный лимит контекста;
vault smoke явно разрешает свой временный корень в filesystem policy.

Docker smoke исключён из обычного runner без фиктивного успешного assertion;
включается `AGENT_RELEASE_SMOKE=1`. Сохранены тесты протокола, security, ветвления,
streaming и конкурентности. Добавлены проверки discovery/cleanup, fallback и
масштабирования работы без хрупких wall-clock порогов.

## Производительность

Один JVM, одинаковые детерминированные входы и конфигурация для обеих версий:
600 символов на сообщение/result, summary input cap 128, 3 прогрева, медиана
7 чередующихся пар замеров. `scripts/benchmark_context_pack.clj` также сравнивает
полные результаты в 72 сценариях, включая отказ summarizer: все совпали.

| Сценарий | Сообщений | До, мс | После, мс | Ускорение |
|---|---:|---:|---:|---:|
| Prefix summary | 102 | 109.99 | 13.54 | 8.1× |
| Prefix summary | 302 | 886.65 | 38.19 | 23.2× |
| Prefix summary | 602 | 3442.43 | 76.20 | 45.2× |
| Tool-result budget | 202 | 167.57 | 15.53 | 10.8× |
| Tool-result budget | 602 | 1242.67 | 48.47 | 25.6× |
| Tool-result budget | 1202 | 4446.43 | 79.97 | 55.6× |

Проверка количества вызовов token estimator при увеличении истории 40 → 120:

- Prefix summary: до 1192 → 8352; после 269 → 749.
- Tool budget: до 2173 → 16093; после 611 → 1811.

Новый тест масштабирования действительно отвергает старый алгоритм.
Это синтетические локальные замеры, не latency LLM и не production throughput.
Размер cap влияет на стоимость построения текста summary; абсолютные цифры
нельзя переносить на другие конфигурации.

Воспроизведение:

```bash
git show 1c90074:src/agent/runtime/context_pack.clj > target/context-pack-before.clj
clojure -M scripts/benchmark_context_pack.clj target/context-pack-before.clj
clojure -M:test -m agent.test-runner
clj-kondo --lint src test dev/user.clj scripts/benchmark_context_pack.clj
clojure -M:dev -e ':dev-loaded (shutdown-agents)'
```

## Проверка и границы

Базовый runner: 802 теста, 3369 assertions, 0 failures, 1 SQLite timeout.
Итоговый runner: **824 теста, 3358 assertions, 0 failures, 0 errors**.
Тестов стало больше за счёт ранее пропущенных namespaces; лишние проверки
документации удалены. Первое SQLite-подключение больше не истекает по таймауту.
Lint: 0 errors, 0 warnings вместо 72 errors / 10 warnings.
`-M:dev` загружается; `git diff --check` проходит.

Не выполнялись deployment, реальная отправка Telegram и Docker release smoke.
На сервере остаётся проверить длительный streaming и end-to-end latency.

Память разделена по независимым контрактам в следующем этапе, описанном ниже.
`branch-path` пока читает все entries сессии для построения выбранной ветки.
Изменение его SQL требует замера на ветвящихся длинных сессиях.

## Основания

- [Clojure: atoms](https://clojure.org/reference/atoms): чистые функции обновления состояния.
- [Clojure: transducers](https://clojure.org/reference/transducers): отделение преобразования от источника/накопления.
- [SQLite JDBC 3.46.1.3 loader](https://github.com/xerial/sqlite-jdbc/blob/3.46.1.3/src/main/java/org/sqlite/SQLiteJDBCLoader.java): cleanup и `org.sqlite.tmpdir`. Context7 не нашёл документацию JDBC; проверен исходник используемой версии.

## Дополнительный аудит UI-тестов

Первый проход не был полным аудитом качества тестов. Повторный просмотр нашёл
проверки точного CSS, текста заголовков и фиксированного числа demo-компонентов,
которые не подтверждали поведение UI.

- Удалены ещё 6 тестов: dark-theme source markers, список подписей каталога,
  толщина border, текст hover rules, CSS/JS markers навигации и CSS cron actions.
  В `test` больше нет проверок чтением исходников `src`, `public`, `DESIGN.md`.
- В каталоге фиксированные числа и цвета заменены связностью tabs/panels,
  уникальностью IDs и доступными именами.
- Markdown renderer проверяется через DOM: содержимое таблиц, checked/unchecked
  конкретных checkbox, разрешение ссылки на footnote, изображения, безопасный HTML,
  literal code и запрет script/event handlers/iframe/javascript URLs.
- Cron editor проверяет значения полей, job ID/revision, допустимые notification
  policies и соответствие lazy-result target сохранённому run. Убраны проверки
  порядка HTML-атрибутов, точных вложений и текста JavaScript-выражений из этого теста.

Сравнение строк само по себе не бесполезно: текст ответа, escaped content,
streaming payload и отсутствие утечки чужих данных — проверяемые контракты.
Но поиск строки в HTML/JS не доказывает, что click, keyboard navigation или
scroll действительно работают. Оставшиеся wiring-проверки этого не заменяют;
браузерный прогон в этой доработке не выполнялся.

Проверка доработки: 65 тестов / 415 assertions, 0 failures/errors; lint чистый.
Две намеренные поломки — все checkbox отмечены и footnote ведёт в отсутствующий
элемент — обнаружены новыми тестами без runtime exceptions.
DOM-проверки также обнаружили реальный дефект ARIA: boolean значения Hiccup
давали `aria-selected="aria-selected"`. Исправлены selected/pressed в Cron и
Memory, hidden у декоративных элементов чата.


## Декомпозиция больших файлов

Разделение по ответственности, без изменения алгоритмов и бизнес-правил:

| Было | Теперь |
| --- | --- |
| `memory/core.clj`: 1762 строки | Фасад, поиск, заметки, proposals, extraction, quality, path policy |
| `ui.clj`: 1248 | Shell плюс overview, sessions, logs, MAGI, approvals и routes |
| `config.clj`: 1207 | Загрузка/редактирование, defaults, migration, validation |
| `memory/vault.clj`: 1013 | Файловые операции/индексация, формат заметки, аудит и embeddings |
| SQLite `sessions.clj`: 907 | Records, messages, entries, channels и общие HugSQL queries |
| UI handlers: 830 | Chat/live orchestration, cron, memory и approvals |
| UI renderer: 773 | Message rendering, Markdown sanitation и formatting |
| Memory tools: 714 | Tool contracts и ограниченные текстовые представления результатов |

Публичные функции фасадов сохранены; модули реализации не требуют свой фасад.
HugSQL definitions загружаются один раз в `sessions.queries`; транзакции,
проверки ревизий, rollback и streaming lifecycle остались на прежних границах.

Семь больших тестовых файлов (755–2021 строк) разделены по подсистемам.
Общие провайдеры/фикстуры находятся в `test-support`, без зависимости тестов
от других тестовых namespaces. Тела всех 282 перенесённых тестов сравнены
с исходными: совпадают полностью до обновления одного обращения к перенесённой
private-функции обработчика. Проверка SQL query plan обращается к новому
namespace queries. Тела 645 исходных определений также совпадают после обратной
нормализации квалификаторов namespace и private metadata. Отдельные тесты Telegram streaming сохранены.

Максимум после разделения: исходник 645 строк, тестовый файл 564 строки.
Разделение не объявляется ускорением: алгоритмы и SQL в этом этапе не менялись.

Финальная проверка: `clojure -M:test -m agent.test-runner` — **818 тестов,
3283 assertions, 0 failures/errors**, столько же, сколько до разделения.
`clj-kondo --lint src test dev/user.clj` — 0 errors/warnings;
`clojure -M:dev -e ':dev-loaded (shutdown-agents)'` и `git diff --check` проходят.
Deployment и браузерный smoke в этом этапе не выполнялись.

## Повторный аудит сопровождения — 2026-10-22

Срез: `d5a49ae`, исходное рабочее дерево чистое. Проверены сборка/reload системы,
runtime loop, доставка событий UI/Telegram, чтение истории, поиск памяти,
тестовый runner, CI и документация. Ниже — результаты статического анализа;
новые сценарии отказов отдельно не воспроизводились. Это не построчная проверка
всего репозитория. Runtime-код в рамках этого прохода не изменялся.

### Приоритеты

1. **P1: устранить расхождение startup и soft reload.**
   `system/components.clj:create-system-components` передаёт MAGI
   `:tool-registry-fn`, использует `create-skills-registry-from-config` и собирает
   event sink с observer/trace. `system.clj:rebuild-hot-system` не передаёт
   callback MAGI, создаёт skills без вычисленных WASM bundle dirs и сохраняет
   старый event sink при замене observer/trace. Следствия: file review после
   reload получает пустой список инструментов, bundle skills могут исчезнуть,
   системные события продолжают использовать старые observer/trace.
   До: две независимые сборки зависимостей. После: общие фабрики MAGI/skills/
   event sink, явно переданные сохраняемые ресурсы и таблица hot/full reload.
   Сохранение активной chat queue обязательно. Проверка: одинаковые зависимости
   при startup/reload, доступность bundle skill, file-review tools и новый trace.

2. **P1: гарантировать очистку при частичном старте и неудачном reload.**
   В `system.clj:start-api!` HTTP запускается до Telegram; ошибка Telegram
   пробрасывается без остановки уже запущенного HTTP. В `full-reload-now!`
   старые сервисы остановлены до запуска новых; при ошибке нет компенсации
   или закрытия нового store. В `components/create-system-components` также
   нет очистки store при ошибке последующей фабрики.
   До: последовательность side effects в `let`. После: учёт приобретённых
   ресурсов и закрытие в обратном порядке при исключении; явное состояние
   failed reload и определённая стратегия восстановления. Общий механизм
   оправдан наличием нескольких путей запуска. Проверка: инъекция отказа
   после открытия DB, после запуска HTTP и при запуске нового runtime.

3. **P1: восстановить автоматический тестовый барьер.**
   В `.github/workflows` активен `public-release-scan.yml`; build/test/lint
   находятся в `ci-cd.yml.disabled`. В отключённом workflow тесты вызываются
   через `run-all-tests` без преобразования failure count в exit code.
   Создать небольшой workflow только для изолированного
   `clojure -M:test -m agent.test-runner` и lint. Не требуется включать вместе
   с ним публикацию Docker и уведомления. Проверка: заведомо упавший тест
   делает job красным.

4. **P2: единая обработка потерь событий для разных способов чтения.**
   `api/streaming.clj:take!` проверяет `:broker/dropped-count`, но
   `api/handlers/ui.clj:session-live-response` читает через `async/alts!!`
   и сразу извлекает `:payload`, обходя эту проверку. Потерянное событие
   завершения/обновления может оставить наблюдающую вкладку устаревшей;
   heartbeat сам по себе историю не обновляет. POST chat имеет отдельный
   fallback по результату задачи — его нужно сохранить.
   До: проверка потери встроена только в блокирующий take. После: отдельная
   проверка envelope, применяемая после take/alts, с полной синхронизацией UI
   или явным reconnect. Проверка: переполнение, потеря terminal event,
   восстановление вкладки без нового пользовательского сообщения.

5. **P2: измерить стоимость чтения выбранной ветки.**
   `persistence/sqlite/sessions/entries.clj:branch-path` загружает и разбирает
   JSON всех entries сессии, строит `by-id`, затем выбирает одну ветку.
   `current-llm-context` применяет compaction только после этого чтения.
   Стоимость зависит от всей сессии, включая чужие ветки и старую историю.
   Сначала benchmark длинной ветвящейся сессии с allocation/числом строк;
   затем сравнить выборку предков в SQL с текущим алгоритмом. Сохранить
   порядок, selected leaf, compaction и excluded messages. Ускорение пока
   не измерено; задача уже отмечена в TODO, это не новое поручение на её реализацию.

6. **P2: ограничить рассылку событий областью подписчика.**
   `broker/core.clj:event->subjects` всегда возвращает `events.all`.
   UI и Telegram подписываются на все события и фильтруют session-id после
   получения. С ростом числа активных клиентов каждое событие доставляется
   всем подписчикам; чужая генерация расходует их буферы.
   До: общая очередь с локальной фильтрацией. После: session subjects для
   session consumers и общий поток для операторского журнала. Нужны проверки
   отсутствия дублей, сохранения replay и сопоставление нагрузки до/после.
   Это кандидат оптимизации, не утверждение о текущем production bottleneck.

7. **P2: продолжить отделение решений runtime от выполнения эффектов.**
   `runtime/loop.clj:run!` остаётся примерно 240 строками: submission,
   pre/post validation, retries, approvals, streaming buffers, persistence
   events и переходы состояния. Повторяются сборка следующего состояния,
   terminal/retry events и решения flush/discard.
   Развить существующий `turn-policy`: чистые решения о следующем состоянии
   и действиях, отдельное выполнение событий/инструментов. Не вводить новый
   framework и не менять порядок persistence/terminal events. Проверять
   таблицей сценариев retry, cancellation, approval, truncated tool calls,
   invalid result и persistence failure; перенос строк между файлами сам
   по себе проблему не решает.

8. **P2: сделать деградацию vector search наблюдаемой.**
   `memory/search.clj:vector-vault-results` превращает любое Exception в `[]`.
   Ошибка provider/DB выглядит как отсутствие semantic matches.
   Сохранить lexical fallback, добавить метрику/структурированное событие
   причины деградации без содержимого запроса и секретов. Проверка: сбой
   embeddings не ломает recall, но отличается от корректного пустого результата.

### Документация для сопровождения

- Дополнить `docs/configuration.md` матрицей soft/full reload: что заменяется,
  что сохраняется, когда применяются лимиты chat, что происходит при отказе.
  Сейчас рекомендация soft reload для skills не описывает найденное расхождение.
- Добавить developer architecture: component graph, владельцы DB/executors/
  subscriptions, порядок start/stop, допустимые зависимости между фасадами
  и реализацией. Список namespaces в README этого не объясняет.
- Описать контракт событий: request-id/session-id, порядок message/turn/agent
  events, обязательная persistence, terminal semantics, cancellation ownership,
  overflow/reconnect и различия UI/Telegram.
- Добавить руководство contributor: изолированные и focused tests, lint,
  fault injection, benchmark и отдельные границы browser/Telegram/production
  acceptance. В `docs/README.md` добавить ссылки на memory architecture и аудиты.

### Порядок работ

Сначала reload parity и очистка ресурсов, затем CI и stream gap recovery.
После этого — runtime policy refactor. SQL и broker оптимизировать после
замеров. Документировать контракты вместе с соответствующими изменениями.

### Проверка этого прохода

- Изолированный `clojure -M:test -m agent.test-runner`: 835 тестов,
  3381 assertions, 0 failures/errors.
- `clj-kondo --lint src test dev/user.clj`: 0 errors/warnings.
- `git diff --check`: без замечаний.

Зелёный текущий suite не доказывает покрытие перечисленных сценариев отказа.
Нагрузочные замеры, браузер, настоящий Telegram и production не проверялись.
Уверенность: высокая для перечисленных расхождений кода; эффект оптимизаций
требует измерений.


## Исправления по повторному аудиту — 2026-10-22

Все восемь пунктов реализованы. Deployment не выполнялся.

| Пункт | Исправление | Проверка |
|---|---|---|
| 1. Startup/reload parity | Общий `rebuild-runtime-components`: MAGI callback, bundle skills, observer/trace/event sink | `system.lifecycle-test`: реальный каталог bundle skills, актуальный registry, вызовы нового observer/trace; chat queue сохраняется |
| 2. Partial startup/reload | `system.resources/with-rollback`, обратная очистка, восстановление предыдущего runtime | Fault injection после DB, после HTTP, при запуске cron/Telegram и нового runtime; исходное исключение сохраняется |
| 3. CI | Активный `tests.yml`: тесты, lint, минимальные permissions; без deploy/уведомлений | YAML разобран; реальные упавшие тесты дают exit 1 через `check-test-runner-exit.sh`; обычный suite даёт exit 0 |
| 4. Stream gap | Общая проверка envelope, resync наблюдающей вкладки, authoritative state после потери POST deltas | Реальное переполнение broker, вытеснение terminal event, один resync на loss count; history window сохраняется; существующие final fallback тесты проходят |
| 5. Branch reads | Session-scoped recursive CTE, разбор только предков, защита от циклов | Паритет benchmark, selected leaf, compaction, excluded messages, чужая сессия, отсутствующий leaf и цикл |
| 6. Broker scope | Session subjects вне `events.>`, индекс точных подписок, scoped replay | Одна доставка оператору, отсутствие чужих событий/overflow, replay с after-id, benchmark |
| 7. Runtime policy | Общий чистый `advance`, result retry budget и terminal decision; отдельные эффекты submission/execution | Таблица переходов плюс существующие runtime/persistence/cancellation/approval/result-tool регрессии |
| 8. Vector degradation | Сохранён lexical fallback, структурированное событие с безопасной фиксированной причиной | Ошибка DB/embedding search отличается от пустого результата; запрос и текст исключения не логируются |

Документация: [development](development.md), [events](events.md),
[матрица reload](configuration.md#reload-boundaries); обновлён индекс docs.
`TODO.md` отмечает выполненный profiling веток.

### Замеры

Локально, синтетические данные, один JVM для сравниваемых вариантов, 2 прогрева
и медиана 5 измерений. Воспроизведение — команды в `docs/development.md`.

| Сценарий | До | После |
|---|---|---|
| 1 ветка × 500 entries | 14,46 мс; 500 decoded rows | 14,98 мс; 500 decoded rows |
| 10 веток × 500 entries, выбрана одна | 93,47 мс; 5000 decoded rows | 10,23 мс; 500 decoded rows |
| 100 подписчиков, 500 событий: общий поток против session scope + exact index | 10,62 мс; 50 000 доставок | 0,81 мс; 500 доставок |

Первый broker-прогон без exact index сократил доставки, но не CPU-время
(10,75 против 11,65 мс). Поэтому добавлен индекс, а не заявлено ускорение только
на основании меньшего числа доставок. На линейной истории SQL-изменение не
показало ускорения; выигрыш относится к чтению одной ветки среди многих.

### Границы подтверждения

Полный изолированный suite: 851 тест, 3474 assertions, 0 failures/errors. Lint и
`git diff --check` чистые. CI workflow проверен локально; запуск GitHub Actions
потребует публикации коммита и здесь не заявляется. Browser, настоящий Telegram,
production и длительная нагрузка не проверялись. Новые тесты доказывают
перечисленные локальные сценарии, а не end-to-end приёмку развёрнутой системы.
