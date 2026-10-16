(ns agent.ui.magi
  "MAGI invocation and decision views."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.ui.render :as ui-render]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn- magi-decision-events [system limit]
  (concat
   (sqlite/list-events (:store system)
                       {:event-type :tool.approval.magi_evaluated
                        :limit limit})
   (sqlite/list-events (:store system)
                       {:event-type :memory.vault.magi_evaluated
                        :limit limit})))

(defn- magi-tool-event? [{:keys [event-type payload]}]
  (and (= "tool-execution-end" event-type)
       (= "magi" (:tool-name payload))))

(defn- magi-invocation-events [system limit]
  (let [approval-events (magi-decision-events system limit)
        tool-events (->> (sqlite/list-events (:store system)
                                             {:event-type :tool-execution-end
                                              :limit (min 1000 (* 5 limit))})
                         (filter magi-tool-event?))]
    (->> (concat approval-events tool-events)
         (sort-by #(long (or (:id %) 0)) >)
         (take limit)
         vec)))

(defn- decision-class [decision]
  (str "magi-decision magi-decision--" (or (some-> decision name) "unknown")))

(defn- magi-agent-panel [label agent]
  (let [response (:response agent)]
    [:div.magi-node {:class (str "magi-node--" (or (some-> response name) "unknown"))}
     [:div.magi-node__head
      [:span.magi-node__label label]
      [:strong.magi-node__response (or (some-> response name str/upper-case) "-")]]
     (when-let [comment (some-> (:comment agent) str str/trim not-empty)]
       [:span.magi-node__comment comment])]))

(defn- compact-json [value]
  (json/generate-string value {:pretty true}))

(defn- decision-label [decision]
  (or (some-> decision name str/upper-case) "-"))

(defn- magi-event-result [{:keys [payload] :as event}]
  (if (contains? #{"tool.approval.magi_evaluated" "memory.vault.magi_evaluated"}
                 (:event-type event))
    payload
    (or (:result payload)
        (get-in payload [:receipt :result]))))

(defn- magi-event-view [{:keys [id event-type entity-id created-at payload] :as event}]
  (let [approval? (= "tool.approval.magi_evaluated" event-type)
        memory? (= "memory.vault.magi_evaluated" event-type)
        result (magi-event-result event)
        judge* (or (:judge result)
                   (select-keys result [:decision :reason]))
        filter* (:filter result)
        agents (:agents result)
        input* (or (:input payload)
                   (get-in payload [:receipt :input])
                   (get-in payload [:receipt :call :input])
                   (:input result)
                   (get-in filter* [:context :input]))
        source (cond approval? "approval"
                     memory? "vault-note"
                     :else "tool")
        tool-name (or (when memory? "vault-note")
                      (:tool-name payload)
                      (:tool-name result)
                      (get-in filter* [:context :tool-name])
                      "magi")
        duration-ms (or (:duration-ms payload) (:duration-ms result))
        reason (or (:reason judge*) (:reason result) (:reason payload))]
    {:id id
     :entity-id entity-id
     :created-at created-at
     :source source
     :tool-name tool-name
     :duration-ms duration-ms
     :reason reason
     :decision (:decision judge*)
     :input input*
     :filter filter*
     :agents agents
     :judge judge*
     :providers (or (:providers result) (:providers payload))}))

(defn- magi-decision-card [event]
  (let [{:keys [id entity-id created-at tool-name input filter agents judge
                providers duration-ms]} (magi-event-view event)]
    [:article.magi-decision-card
     [:div.magi-head
      [:div.magi-titleblock
       [:div.magi-kicker (str "#" (or id "-"))]
       [:h2 (or tool-name "MAGI")]
       [:div.magi-file (or entity-id "-")]]
      [:div.magi-status {:class (decision-class (:decision judge))}
       [:span "DECISION"]
       [:strong (decision-label (:decision judge))]]]
     [:div.magi-agent-grid
      (magi-agent-panel "MELCHIOR" (:melchior agents))
      (magi-agent-panel "BALTHASAR" (:balthasar agents))
      (magi-agent-panel "CASPER" (:casper agents))]
     [:div.magi-details
      [:div.magi-readout
       [:span "input"]
       [:pre (compact-json input)]]
      [:div.magi-readout
       [:span "filter"]
       [:pre (compact-json filter)]]
      [:div.magi-readout
       [:span "judge"]
       [:pre (compact-json judge)]]
      [:div.magi-readout
       [:span "providers"]
       [:pre (compact-json providers)]]]
     [:div.magi-footer
      [:span (str "approval: " (or entity-id "-"))]
      [:span (str "duration: " (or duration-ms 0) "ms")]
      [:span (or created-at "-")]]]))

(defn- magi-invocation-row [{:keys [id created-at] :as event}]
  (let [{:keys [source tool-name decision duration-ms reason]} (magi-event-view event)]
    [:details.magi-invocation
     {:id (str "magi-invocation-" id)
      "data-preserve-attr" "open"}
     [:summary.magi-invocation__summary
      {"data-on:click" (str "@get('/ui/magi/" id "/detail')")}
      [:span.magi-log-id (str "#" (or id "-"))]
      [:span source]
      [:span (or tool-name "-")]
      [:strong {:class (decision-class decision)}
       (decision-label decision)]
      [:span.magi-log-reason (or reason "-")]
      [:span (str (or duration-ms 0) "ms")]
      [:span (or created-at "-")]]
     [:div.magi-invocation__detail
      {:id (str "magi-detail-" id)}
      [:div.empty-line "Loading details…"]]]))

(defn magi-detail-fragment [event]
  (ui-render/render
   (if event
     [:div.magi-invocation__detail {:id (str "magi-detail-" (:id event))}
      (magi-decision-card event)
      [:div.magi-readout.magi-readout--wide
       [:span "event"]
       [:pre (compact-json event)]]]
     [:div#magi-detail-missing.magi-invocation__detail
      [:div.empty-line "MAGI event not found."]])))

(defn magi-fragment
  ([system] (magi-fragment system {:limit 25}))
  ([system {:keys [limit] :or {limit 25}}]
   (let [invocations (magi-invocation-events system limit)
        decision-events (filter #(str/ends-with? (:event-type %) ".magi_evaluated") invocations)
        latest (first invocations)]
     (ui-render/render
      [:section#magi-panel.panel.magi-panel
      [:div.magi-console
       [:div.panel-head.magi-console__header
        [:h2 "MAGI"]
        [:div.panel-head__form
         [:span.reload-status "decision log"]
         [:button.magi-refresh
          {:type "button"
           "data-on:click" (str "@get('/ui/magi?limit=" limit "')")}
          "Refresh"]]]
       [:div.fact-strip.magi-facts
        [:span.fact
         [:span.fact__label "records"]
         [:span.fact__value (str (count invocations))]]
        [:span.fact
         [:span.fact__label "reviews"]
         [:span.fact__value (str (count decision-events))]]
        [:span.fact
         [:span.fact__label "latest"]
         [:span.fact__value (or (:created-at latest) "-")]]]
       (if latest
         [:div.magi-log
          [:section.magi-invocation-log
           [:div.magi-section-head
            [:h2 "Invocation Log"]
            [:span (str "latest " (count invocations) " records")]]
           [:div.magi-invocation__head
            [:span "id"]
            [:span "source"]
            [:span "tool"]
            [:span "decision"]
            [:span "reason"]
            [:span "time"]
            [:span "created"]]
           (for [event invocations]
             (magi-invocation-row event))
           (when (= (count invocations) limit)
             [:button.chat-history-more
              {:type "button"
               "data-on:click" (str "@get('/ui/magi?limit=" (min 200 (+ limit 25)) "')")}
              "Load 25 older"])] ]
         [:div.magi-empty
          [:div.empty "No MAGI decisions yet."]])]]))))
