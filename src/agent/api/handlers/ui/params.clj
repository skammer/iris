(ns agent.api.handlers.ui.params
  "Bounded query parameters shared by UI handlers."
  (:require
   [clojure.string :as str]))

(defn ^:no-doc request-ui-limit
  ([request] (request-ui-limit request 20 100))
  ([request default maximum]
   (let [raw (-> request :parameters :query :limit)]
     (try
       (-> (if (str/blank? raw) default (Long/parseLong raw))
           (max 1)
           (min maximum)
           long)
       (catch NumberFormatException _ default)))))
