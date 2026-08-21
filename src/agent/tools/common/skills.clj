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

(defn create-skills-read-tool [skills-registry]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :skills_read
     "Load one skill's full instructions by exact name after discovering it with skills_list."
     :category :system
     :input-schema [:map {:closed true}
                    [:name :string]]
     :operation :read
     :routing-categories #{:read :search :plan :write :run :web}
     :parallel-safe? true
     :source :builtin)
    :execute-fn
    (fn [{skill-name :name} _context]
      (if-let [{:keys [name description body path]}
               (get (skills/skill-map skills-registry) skill-name)]
        (str "# Skill /" name "\n\n"
             "Revision: " (skills/content-revision (slurp path)) "\n\n"
             description "\n\n"
             body)
        (throw (tools/validation-error
                "skill not found"
                {:name skill-name
                 :available (mapv :name (skills/skill-catalog skills-registry))}))))
    :health-fn
    (fn []
      {:healthy true
       :details {:count (count (skills/skill-catalog skills-registry))}})}))
