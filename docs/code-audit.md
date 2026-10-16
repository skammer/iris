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
