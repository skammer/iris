(ns agent.tools.common.skills
  (:require
   [agent.skills :as skills]
   [agent.tools.core :as tools]))

(def ^:private default-limit 50)
(def ^:private max-limit 200)

(defn- normalized-limit [limit]
  (min max-limit (long (or limit default-limit))))

(defn- public-skill [{:keys [name description]}]
  {:name name
   :description description})

(defn create-skills-list-tool [skills-registry]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :skills_list
     "List available slash skills by name and description. Does not return skill bodies."
     :category :system
     :input-schema [:map {:closed true}
                    [:prefix {:optional true} [:maybe :string]]
                    [:limit {:optional true} [:maybe [:int {:min 1}]]]]
     :operation :read
     :routing-categories #{:read :search :plan :write :run}
     :parallel-safe? true
     :source :builtin)
    :execute-fn
    (fn [{:keys [prefix limit]} _context]
      (let [limit* (normalized-limit limit)
            catalog (skills/filter-catalog
                     (skills/skill-catalog skills-registry)
                     prefix)
            items (subvec (vec catalog) 0 (min limit* (count catalog)))]
        {:skills (mapv public-skill items)
         :count (count items)
         :truncated? (> (count catalog) limit*)}))
    :health-fn
    (fn []
      {:healthy true
       :details {:count (count (skills/skill-catalog skills-registry))}})}))
