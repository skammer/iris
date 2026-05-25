(ns agent.api.handlers.skills
  (:require
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.skills :as skills]
   [clojure.string :as str]))

(defn list-skills [system _request]
  (responses/json-response 200
                           {:data (mapv ser/skill->response
                                        (skills/list-skills (:skills-registry system)))}))

(defn- parse-long* [value]
  (cond
    (integer? value) value
    (number? value) (long value)
    (string? value) (when-not (str/blank? value)
                      (Long/parseLong value))
    :else nil))

(defn slash-commands [system request]
  (let [query (get-in request [:parameters :query])
        page (parse-long* (:page query))
        page-size (parse-long* (or (:page_size query) (:page-size query)))
        result (skills/slash-commands-page
                (:skills-registry system)
                {:prefix (:prefix query)
                 :page page
                 :page-size page-size})]
    (responses/json-response
     200
     {:object "iris.slash_commands_page"
      :items (mapv ser/skill->response (:items result))
      :total (:total result)
      :has_more (:has-more result)
      :page (:page result)
      :page_size (:page-size result)})))
