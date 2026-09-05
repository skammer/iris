(require '[agent.api-test :as fixture] '[agent.persistence.sqlite :as sqlite]
         '[nrepl.server :as nrepl] '[clojure.java.io :as io])
(.mkdirs (io/file "target/ui-review"))
(def review (fixture/started-test-system "target/ui-review/review.db" 17331 identity))
(def review-store (get-in review [:system :store]))
(when (empty? (sqlite/list-sessions review-store))
  (let [session (sqlite/create-session! review-store "Проверка UI — chat, tools & typography")
        sid (:id session)]
    (doseq [n (range 12)]
      (sqlite/create-session! review-store (str "Daily-research-very-long-ephemeral-session-title-" n) {:kind :cron}))
    (sqlite/append-message! review-store sid "user" "Проверь интерфейс на маленьком экране. Что изменилось?")
    (sqlite/append-message! review-store sid "assistant" "Проверяю историю и инструменты."
      {:tool-calls [{:id "call-review-1234567890" :type "function" :function {:name "shell" :arguments "{\"command\":\"git status --short\"}"}}]})
    (sqlite/append-message! review-store sid "tool" "Working tree clean.\nElapsed: 12 ms." {:tool-call-id "call-review-1234567890"})
    (sqlite/append-message! review-store sid "assistant" "## Результат\n\nКомпактная типографика, читаемые tool calls и длинные имена сессий. **Clojure + Datastar**, тёплая тёмная палитра.\n\n```clojure\n(map inc [1 2 3])\n```\n\n| Проверка | Статус |\n|---|---|\n| Desktop | OK |\n| Mobile | Проверяем |\n\n- Ширина колонки\n- Перенос текста\n- Скорость обновлений")))
(def review-repl (nrepl/start-server :bind "127.0.0.1" :port 17332))
(println "UI review http://127.0.0.1:17331 — nREPL 17332")
@(promise)
