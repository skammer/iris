(ns agent.ui.catalog
  "Standalone design-system catalogue served at /ui."
  (:require
   [agent.ui.render :as ui-render]))

;; Static assets are cached for an hour. A boot-specific URL prevents fresh
;; catalogue markup from being rendered with an older stylesheet or component.
(defonce ^:private asset-version
  (str (System/currentTimeMillis)))

(defn- asset-href [path]
  (str path "?v=" asset-version))

(defn- icon [glyph]
  [:span.ui-icon {:aria-hidden "true"} glyph])

(defn- section [id eyebrow title description & content]
  [:section.ui-catalog-section {:id id}
   [:header.ui-section-heading
    [:span.ui-eyebrow eyebrow]
    [:h2 title]
    [:p description]]
   (into [:div.ui-showcase-grid] content)])

(defn- swatch [class-name label token]
  [:div.ui-swatch
   [:span.ui-swatch__color {:class class-name}]
   [:strong label]
   [:code token]])

(defn- status [kind label]
  [:span.ui-status {:class (str "ui-status--" kind)}
   [:span.dot {:class (str "dot--" kind)}]
   label])

(def ^:private card-tab-items
  [{:id "observe"
    :tone "sky"
    :label "Observe"
    :title "Live operations"
    :meta "12 signals · 3 active"
    :description "Runtime health, active sessions and recent tool activity in one operational card."
    :items ["Provider latency within target" "Telegram adapter connected" "No approvals waiting"]}
   {:id "automate"
    :tone "violet"
    :label "Automate"
    :title "Scheduled work"
    :meta "8 jobs · next in 14m"
    :description "Recurring jobs, delivery policy and execution history stay grouped by intent."
    :items ["Daily office report" "Memory review" "Weekly deployment audit"]}
   {:id "review"
    :tone "graphite"
    :label "Review"
    :title "Decision queue"
    :meta "4 proposals · 1 urgent"
    :description "Approvals and agent decisions use a focused, high-contrast review surface."
    :items ["Filesystem write" "New memory candidate" "Provider fallback"]}
   {:id "remember"
    :tone "mint"
    :label "Remember"
    :title "Memory catalogue"
    :meta "248 notes · indexed"
    :description "Durable knowledge, recent updates and retrieval quality share one recognisable home."
    :items ["User preferences" "Deployment notes" "Project decisions"]}
   {:id "focus"
    :tone "black"
    :label "Focus"
    :title "Focused execution"
    :meta "1 task · uninterrupted"
    :description "A near-black section isolates the current task when everything else should recede."
    :items ["Single active objective" "No pending interruptions" "Exit path preserved"]}
   {:id "attention"
    :tone "yellow"
    :label "Attention"
    :title "Needs attention"
    :meta "3 checks · non-blocking"
    :description "Warm yellow groups work that needs an operator soon without reading as a failure."
    :items ["Battery below target" "Schedule overlaps" "Review due today"]}
   {:id "incident"
    :tone "red"
    :label "Incident"
    :title "Incident response"
    :meta "1 failure · action required"
    :description "Red is reserved for broken state, destructive consequence and immediate recovery work."
    :items ["Delivery unavailable" "Retries exhausted" "Rollback ready"]}])

(defn- card-tab [orientation {:keys [id tone label]} selected?]
  [:button.ui-card-tab
   {:id (str orientation "-tab-" id)
    :class (str "ui-card-tab--" tone)
    :type "button"
    :role "tab"
    :aria-controls (str orientation "-panel-" id)
    :aria-selected (str selected?)
    :tabindex (if selected? 0 -1)
    :data-card-tab id}
   [:span.ui-card-tab__label label]])

(defn- card-tab-clip-defs []
  [:svg.ui-card-tab-clip-defs {:aria-hidden "true" :width "0" :height "0"}
   [:defs
    [:clipPath {:id "ui-card-tab-horizontal" :clipPathUnits "objectBoundingBox"}
     [:path {:d "M 0 1 L 0.11 0.18 L 0.115 0.145 Q 0.13 0 0.25 0 H 0.75 Q 0.87 0 0.885 0.145 L 0.89 0.18 L 1 1 Z"}]]
    [:clipPath {:id "ui-card-tab-vertical" :clipPathUnits "objectBoundingBox"}
     [:path {:d "M 0 0 L 0.82 0.11 L 0.855 0.115 Q 1 0.13 1 0.25 V 0.75 Q 1 0.87 0.855 0.885 L 0.82 0.89 L 0 1 Z"}]]
    [:clipPath {:id "ui-card-tab-bottom" :clipPathUnits "objectBoundingBox"}
     [:path {:d "M 0 0 L 0.11 0.82 L 0.115 0.855 Q 0.13 1 0.25 1 H 0.75 Q 0.87 1 0.885 0.855 L 0.89 0.82 L 1 0 Z"}]]
    [:clipPath {:id "ui-card-tab-left" :clipPathUnits "objectBoundingBox"}
     [:path {:d "M 1 0 L 0.18 0.11 L 0.145 0.115 Q 0 0.13 0 0.25 V 0.75 Q 0 0.87 0.145 0.885 L 0.18 0.89 L 1 1 Z"}]]]])

(defn- card-panel [orientation index {:keys [id tone label title meta description items]} selected?]
  [:article.ui-catalog-card
   {:id (str orientation "-panel-" id)
    :class (str "ui-catalog-card--" tone)
    :role "tabpanel"
    :aria-labelledby (str orientation "-tab-" id)
    :data-card-panel id
    :hidden (not selected?)}
   [:header
    [:span.ui-card-kicker label]
    [:span.ui-card-index (format "%02d" (inc index))]]
   [:h4 title]
   [:p.ui-card-meta meta]
   [:p.ui-card-description description]
   [:ul.ui-card-list
    (for [item items]
      [:li [:span {:aria-hidden "true"} "↳"] item])]
   [:footer
    [:span "Open section"]
    [:span {:aria-hidden "true"} "↗"]]])

(defn- card-tabs [orientation]
  (let [horizontal-axis? (contains? #{"horizontal" "bottom"} orientation)
        vertical-axis? (not horizontal-axis?)
        items (if horizontal-axis?
                card-tab-items
                (filterv #(contains? #{"observe" "focus" "attention" "incident"} (:id %))
                         card-tab-items))
        orientation-label (case orientation
                            "horizontal" "Top"
                            "vertical" "Right"
                            "bottom" "Bottom"
                            "left" "Left")]
    [:catalog-card-tabs.ui-card-tabs
     {:class (str "ui-card-tabs--" orientation)}
     (into [:div.ui-card-tab-list
            (cond-> {:role "tablist"
                     :aria-label (str orientation-label " section tabs")}
              vertical-axis? (assoc :aria-orientation "vertical"))]
           (map-indexed #(card-tab orientation %2 (zero? %1)) items))
     (into [:div.ui-card-panel-stack]
           (map-indexed #(card-panel orientation %1 %2 (zero? %1)) items))]))

(defn- metric-card []
  [:article.ui-showcase.ui-showcase--span-2
   [:div.ui-card-header
    [:div [:span.ui-eyebrow "LIVE / 7D"] [:h3 "Request performance"]]
    [:div.ui-segmented [:button.active "7D"] [:button "14D"] [:button "1M"]]]
   [:div.ui-metric-layout
    [:svg.ui-chart {:viewBox "0 0 560 180" :role "img" :aria-label "Requests line chart"}
     [:path {:class "ui-chart__grid" :d "M0 30H560 M0 90H560 M0 150H560"}]
     [:path {:class "ui-chart__area" :d "M0 145 L80 140 L150 35 L220 145 L300 80 L380 145 L470 130 L560 140 L560 180 L0 180 Z"}]
     [:path {:class "ui-chart__line" :d "M0 145 L80 140 L150 35 L220 145 L300 80 L380 145 L470 130 L560 140"}]]
    [:div.ui-metric-grid
     [:div [:span "Total requests"] [:strong "1,284"]]
     [:div [:span "Users"] [:strong "42"]]
     [:div [:span "Errors"] [:strong "3"]]
     [:div [:span "Avg latency"] [:strong "1.4s"]]]]])

(defn- runs-table []
  [:article.ui-showcase.ui-showcase--span-2
   [:div.ui-card-header [:h3 "Recent runs"] [:button.ui-button.ui-button--primary "New run"]]
   [:div.ui-table-wrap
    [:table.ui-table
     [:thead [:tr [:th "Status"] [:th "Agent"] [:th "Provider"] [:th "Tokens"] [:th "Latency"] [:th "Owner"]]]
     [:tbody
      [:tr [:td (status "ok" "DONE")] [:td "Support triage"] [:td "NeuralDeep"] [:td "8,420"] [:td "1.2s"] [:td [:span.ui-avatar "AS"]]]
      [:tr [:td (status "info" "RUNNING")] [:td "Research"] [:td "OpenRouter"] [:td "12,081"] [:td "3.8s"] [:td [:span.ui-avatar "MK"]]]
      [:tr [:td (status "pending" "WAITING")] [:td "Memory audit"] [:td "NeuralDeep"] [:td "—"] [:td "—"] [:td [:span.ui-avatar "IR"]]]]]]
   [:div.ui-pagination
    [:span "1–3 OF 24"]
    [:div
     [:button.ui-icon-button {:disabled true :aria-label "Previous page"} "←"]
     [:button.ui-icon-button.active {:aria-label "Page 1"} "1"]
     [:button.ui-icon-button {:aria-label "Page 2"} "2"]
     [:button.ui-icon-button {:aria-label "Next page"} "→"]]]])

(defn- variants-card []
  [:article.ui-showcase.ui-showcase--span-2
   [:div.ui-card-header
    [:div [:span.ui-eyebrow "AGENT / VARIANTS"] [:h3 "Deployment candidates"]]
    [:button.ui-button "Compare all"]]
   [:div.ui-variant-grid
    [:article.ui-variant.ui-variant--new
     [:span.ui-eyebrow "NEW VARIANT"]
     [:strong "+"]
     [:p "Create from current configuration"]]
    [:article.ui-variant
     [:div [:span.ui-eyebrow "VARIANT 9"] (status "ok" "DEPLOYED")]
     [:strong "NeuralDeep / v3"]
     [:p "Accuracy 90% · Avg latency 1.4s"]]
    [:article.ui-variant
     [:div [:span.ui-eyebrow "VARIANT 8"] (status "pending" "DRAFT")]
     [:strong "OpenRouter / Claude"]
     [:p "Accuracy 86% · Avg latency 2.1s"]]]])

(defn page []
  (str "<!doctype html>"
       (ui-render/render
   [:html {:lang "en" :data-theme "light"}
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "Iris UI system"]
     [:link {:rel "stylesheet" :href (asset-href "/public/app.css")}]
     [:script {:type "module" :src (asset-href "/public/web-components.js")}]]
    [:body.ui-catalog-page
     (card-tab-clip-defs)
     [:div.ui-catalog-shell
      [:aside.ui-catalog-nav
       [:a.ui-brand {:href "#foundations"}
        [:span [:strong "IRIS"] [:small "UI SYSTEM / 1.0"]]]
       [:nav {:aria-label "Catalogue sections"}
        [:span.ui-nav-group "FOUNDATIONS"]
        [:a {:href "#foundations"} "Tokens"]
        [:a {:href "#actions"} "Actions"]
        [:a {:href "#forms"} "Forms"]
        [:span.ui-nav-group "DATA"]
        [:a {:href "#status"} "Status"]
        [:a {:href "#data"} "Cards & tables"]
        [:a {:href "#navigation"} "Navigation"]
        [:a {:href "#card-tabs"} "Card tabs"]
        [:span.ui-nav-group "PATTERNS"]
        [:a {:href "#layouts"} "Layouts"]
        [:a {:href "#workflow"} "Workflow"]
        [:a {:href "#feedback"} "Feedback"]]
       [:div.ui-catalog-nav__footer
        [:a {:href "/"} "← Control plane"]
        [:theme-toggle
         [:button#theme-toggle.theme-toggle {:type "button" :aria-label "Toggle theme"} "Light"]]]]

      [:main.ui-catalog-main
       [:header.ui-catalog-hero
        [:div
         [:span.ui-eyebrow "DESIGN REFERENCE"]
         [:h1 "Operational clarity.\nPhysical response."]
         [:p "Complete inventory for Iris surfaces, derived from the current control plane and Agentic UI references."]]
        [:div.ui-hero-meta
         (status "ok" "SYSTEM ONLINE")
         [:span "UPDATED 2026-07-11"]]]

       (section "foundations" "01 / FOUNDATION" "Tokens" "Semantic color, type, spacing, shape and depth primitives."
        [:article.ui-showcase.ui-showcase--span-2
         [:h3 "Semantic color"]
         [:div.ui-swatches
          (swatch "ui-swatch--canvas" "Canvas" "--canvas")
          (swatch "ui-swatch--surface" "Surface" "--surface")
          (swatch "ui-swatch--ink" "Ink" "--text-display")
          (swatch "ui-swatch--primary" "Primary" "--primary")
          (swatch "ui-swatch--success" "Success" "--success")
          (swatch "ui-swatch--warning" "Warning" "--warning")
          (swatch "ui-swatch--danger" "Danger" "--danger")]]
        [:article.ui-showcase
         [:h3 "Typography"]
         [:div.ui-type-specimen
          [:div.ui-display-type "Aa"]
          [:div [:strong "Display / 32"] [:p "Control plane intelligence"]]
          [:div.ui-body-type "Aa"]
          [:div [:strong "Body / 14"] [:p "Dense, readable operational copy."]]
          [:div.ui-label-type "AG"]
          [:div [:strong "Label / 11"] [:p "MONO UPPERCASE"]]]]
        [:article.ui-showcase
         [:h3 "Spacing & shape"]
         [:div.ui-space-scale
          (for [[n size] [[1 "4"] [2 "8"] [3 "12"] [4 "16"] [5 "24"] [6 "32"]]]
            [:div [:span {:style (str "width:" size "px")}]
             [:code (str "--space-" n " / " size "px")]])]
         [:div.ui-radius-row
          [:span.ui-radius-sm "4"] [:span.ui-radius-md "8"] [:span.ui-radius-pill "PILL"]]])

       (section "actions" "02 / COMPONENTS" "Actions" "Hierarchy stays obvious: one primary action, quieter alternatives, destructive actions explicit."
        [:article.ui-showcase
         [:h3 "Buttons"]
         [:div.ui-demo-row
          [:button.ui-button.ui-button--primary "Deploy"]
          [:button.ui-button "Configure"]
          [:button.ui-button.ui-button--ghost "Preview"]
          [:button.ui-button.ui-button--danger "Delete"]]
         [:div.ui-demo-row
          [:button.ui-button.ui-button--primary {:disabled true} "Deploying"]
          [:button.ui-icon-button {:aria-label "Settings"} (icon "⌘")]
          [:button.ui-icon-button {:aria-label "Add"} (icon "+")]]]
        [:article.ui-showcase
         [:h3 "Action tiles"]
         [:div.ui-action-grid
          [:button.ui-action-tile (icon "⚙") [:strong "Configure agent"] [:span "Define model and tools"] [:b "↗"]]
          [:button.ui-action-tile (icon "◇") [:strong "Create variant"] [:span "Fork current setup"] [:b "↗"]]
          [:button.ui-action-tile (icon "⇄") [:strong "View performance"] [:span "Compare live runs"] [:b "↗"]]]])

       (section "forms" "03 / COMPONENTS" "Forms" "Controls map directly to their effect and expose validation beside the field."
        [:article.ui-showcase
         [:h3 "Inputs"]
         [:label.ui-field [:span "Agent name"] [:input {:value "Support specialist" :read-only true}]]
         [:label.ui-field [:span "Search"] [:div.ui-search (icon "⌕") [:input {:placeholder "Search sessions"}] [:kbd "⌘ K"]]]
         [:label.ui-field.ui-field--error [:span "Webhook URL"] [:input {:value "agent.local" :aria-invalid true :read-only true}] [:small "Enter an HTTPS URL."]]]
        [:article.ui-showcase
         [:h3 "Selection"]
         [:label.ui-field [:span "Provider"] [:select [:option "NeuralDeep"] [:option "OpenRouter"]]]
         [:div.ui-demo-row
          [:label.ui-check [:input {:type "checkbox" :checked true :read-only true}] "Tools enabled"]
          [:label.ui-check [:input {:type "radio" :name "mode" :checked true :read-only true}] "Automatic"]]
         [:label.ui-switch [:input {:type "checkbox" :checked true :read-only true}] [:span] "Live events"]]
        [:article.ui-showcase.ui-showcase--span-2
         [:h3 "Radio cards"]
         [:div.ui-choice-grid
          [:label.ui-choice-card
           [:input {:type "radio" :name "billing" :checked true :read-only true}]
           [:span [:strong "Monthly"] [:small "Flexible billing"]]
           [:b "$49 / month"]]
          [:label.ui-choice-card
           [:input {:type "radio" :name "billing" :read-only true}]
           [:span [:strong "Yearly"] [:small "Save two months"]]
           [:b "$490 / year"]]]]
        [:article.ui-showcase.ui-showcase--span-2
         [:h3 "Composer"]
         [:div.ui-composer
          [:textarea {:placeholder "Ask Iris to inspect runtime state…" :aria-label "Message"}]
          [:div.ui-composer__actions
           [:button.ui-icon-button {:aria-label "Attach"} "+"]
           [:span "⌘ ↵ to send"]
           [:button.ui-send-button {:aria-label "Send"} "↑"]]]])

       (section "status" "04 / COMPONENTS" "Status & progress" "Color supplements text; it never carries meaning alone."
        [:article.ui-showcase
         [:h3 "Status chips"]
         [:div.ui-status-list
          (status "queued" "QUEUED")
          (status "pending" "PENDING")
          (status "ok" "SUCCESS")
          (status "err" "FAILED")
          (status "info" "RUNNING")]]
        [:article.ui-showcase
         [:h3 "Progress"]
         [:div.ui-progress-list
          [:label [:span "Context"] [:progress {:value 10 :max 100}] [:b "10%"]]
          [:label [:span "Memory"] [:progress {:value 56 :max 100}] [:b "56%"]]
          [:label [:span "Tools"] [:progress {:value 91 :max 100}] [:b "91%"]]]]
        [:article.ui-showcase.ui-showcase--span-2
         [:h3 "Step sequence"]
         [:ol.ui-stepper
          [:li.ui-stepper__done [:span "1"] [:div [:b "Configuration"] [:small "Agent identity"]]]
          [:li.ui-stepper__done [:span "2"] [:div [:b "Knowledge"] [:small "Vault indexed"]]]
          [:li.ui-stepper__active [:span "3"] [:div [:b "Integration"] [:small "Connect tools"]]]
          [:li [:span "4"] [:div [:b "Automation"] [:small "Set policy"]]]]])

       (section "data" "05 / DATA" "Cards, metrics & tables" "Dense information uses alignment, proximity and restrained borders."
        (metric-card)
        (variants-card)
        (runs-table))

       (section "navigation" "06 / NAVIGATION" "Navigation patterns" "Use persistent navigation for workspaces, tabs for sibling views and breadcrumbs for location."
        [:article.ui-showcase
         [:h3 "Sidebar"]
         [:nav.ui-mini-sidebar
          [:strong "MONITOR"]
          [:a.active {:href "#navigation"} "Activity dashboard"]
          [:a {:href "#navigation"} "Call history"]
          [:a {:href "#navigation"} "Live calls"]
          [:strong "ORCHESTRATE"]
          [:a {:href "#navigation"} "Agents"]
          [:a {:href "#navigation"} "Playbooks"]]]
        [:article.ui-showcase
         [:h3 "Tabs & breadcrumb"]
         [:nav.ui-breadcrumb [:a {:href "#navigation"} "Agents"] [:span "/"] [:a {:href "#navigation"} "Support"] [:span "/"] [:b "Runs"]]
         [:div.ui-segmented.ui-segmented--wide [:button.active "Overview"] [:button "Runs"] [:button "Settings"]]
         [:details.ui-menu
          [:summary "Last 7 days"]
          [:div [:button "Today"] [:button "Last 7 days"] [:button "Last 30 days"] [:button "Year to date"]]]])

       (section "card-tabs" "07 / NAVIGATION" "Card tabs" "Sections become recognisable objects: a coloured card carries context while attached index tabs switch sibling views."
        [:article.ui-showcase.ui-showcase--span-2.ui-card-tabs-showcase
         [:div.ui-card-tabs-heading
          [:div [:h3 "Horizontal tabs"] [:p "Best for wide workspaces and primary section switching."]]
          [:code "orientation / horizontal"]]
         (card-tabs "horizontal")]
        [:article.ui-showcase.ui-showcase--span-2.ui-card-tabs-showcase
         [:div.ui-card-tabs-heading
          [:div [:h3 "Vertical tabs"] [:p "Best for card stacks, inspectors and dense side panels."]]
          [:code "orientation / vertical"]]
         (card-tabs "vertical")]
        [:article.ui-showcase.ui-showcase--span-2.ui-card-tabs-showcase
         [:div.ui-card-tabs-heading
          [:div [:h3 "Bottom tabs"] [:p "Best when the card header must stay visually quiet."]]
          [:code "orientation / bottom"]]
         (card-tabs "bottom")]
        [:article.ui-showcase.ui-showcase--span-2.ui-card-tabs-showcase
         [:div.ui-card-tabs-heading
          [:div [:h3 "Left tabs"] [:p "Mirrors edge tabs for right-aligned inspectors and utilities."]]
          [:code "orientation / left"]]
         (card-tabs "left")])

       (section "layouts" "08 / PATTERNS" "Layout recipes" "Reference layouts are reusable compositions, not page-specific one-offs."
        [:article.ui-showcase.ui-layout-card
         [:h3 "Dashboard shell"]
         [:div.ui-layout-preview.ui-layout-dashboard
          [:aside] [:header] [:section] [:section] [:section]]
         [:p "Persistent sidebar + utility bar + metric/card grid."]]
        [:article.ui-showcase.ui-layout-card
         [:h3 "Configuration workspace"]
         [:div.ui-layout-preview.ui-layout-workspace
          [:header] [:aside] [:section] [:section] [:footer]]
         [:p "Global steps + local steps + form canvas + live preview."]]
        [:article.ui-showcase.ui-layout-card
         [:h3 "Focused task"]
         [:div.ui-layout-preview.ui-layout-focus
          [:header] [:section] [:footer]]
         [:p "Centered single-purpose flow for onboarding or confirmation."]]
        [:article.ui-showcase.ui-layout-card
         [:h3 "Data explorer"]
         [:div.ui-layout-preview.ui-layout-data
          [:aside] [:header] [:section] [:footer]]
         [:p "Filter rail + table/canvas + persistent pagination."]])

       (section "workflow" "09 / PATTERNS" "Workflow canvas" "Node graphs use direct manipulation, visible connection state and explicit zoom controls."
        [:article.ui-showcase.ui-showcase--span-2
         [:div.ui-workflow-toolbar
          [:div
           [:button.ui-icon-button {:aria-label "Undo"} "↶"]
           [:button.ui-icon-button {:aria-label "Redo"} "↷"]]
          [:strong "MEMORY PIPELINE 2"]
          [:div [:button.ui-button "Test"] [:button.ui-button.ui-button--primary "Publish"]]]
         [:div.ui-workflow-canvas
          [:svg.ui-workflow-links {:viewBox "0 0 100 100"
                                   :preserveAspectRatio "none"
                                   :aria-hidden "true"}
           [:path {:d "M 50 30 L 27 43"}]
           [:path {:d "M 27 63 L 72 66"}]]
          [:article.ui-node.ui-node--start [:header "START"] [:p "Receive session transcript"] [:span "●"]]
          [:article.ui-node.ui-node--active [:header "EXTRACT FACTS"] [:p "High-confidence candidate notes"] [:span "●"]]
          [:article.ui-node [:header "INDEX VAULT"] [:p "Write and refresh search index"] [:span "●"]]
          [:div.ui-zoom
           [:button {:aria-label "Zoom out"} "−"]
           [:span "95%"]
           [:button {:aria-label "Zoom in"} "+"]]]])

       (section "feedback" "10 / PATTERNS" "Feedback & overlays" "Status, warning and errors appear near their cause; overlays preserve escape routes."
        [:article.ui-showcase
         [:h3 "Inline feedback"]
         [:div.ui-alert.ui-alert--success [:strong "Deployment complete"] [:p "iris.jar passed its health check."]]
         [:div.ui-alert.ui-alert--warning [:strong "Approval required"] [:p "Filesystem write awaits operator decision."]]
         [:div.ui-alert.ui-alert--danger [:strong "Provider unavailable"] [:p "Request failed after 3 attempts."]]]
        [:article.ui-showcase
         [:h3 "Toast & tooltip"]
         [:div.ui-toast (status "ok" "SAVED") [:span "Configuration updated"] [:button.ui-icon-button {:aria-label "Dismiss"} "×"]]
         [:div.ui-tooltip-demo
          [:button.ui-icon-button {:aria-label "Provider health info"
                                   :aria-describedby "tooltip-example"} "?"]
          [:span#tooltip-example.ui-tooltip {:role "tooltip"} "Current provider health"]]]
        [:article.ui-showcase.ui-showcase--span-2
         [:h3 "Empty conversation"]
         [:div.ui-empty-conversation
          [:div
           [:span.ui-eyebrow "NO MESSAGES YET"]
           [:h2 "Start with a concrete task"]
           [:p "Inspect runtime health, search memory, or ask Iris to run an approved tool."]
           [:div.ui-prompt-suggestions
            [:button "Inspect current health"]
            [:button "Summarize recent events"]
            [:button "Search memory for deployment notes"]]]
          [:div.ui-composer
            [:textarea {:placeholder "Ask Iris…" :aria-label "Message"}]
           [:div.ui-composer__actions
            [:button.ui-icon-button {:aria-label "Attach"} "+"]
            [:span "⌘ ↵ to send"]
            [:button.ui-send-button {:aria-label "Send"} "↑"]]]]]
        [:article.ui-showcase.ui-showcase--span-2
         [:h3 "Dialog / onboarding"]
         [:div.ui-dialog-demo
          [:div.ui-dialog
           [:span.ui-eyebrow "WELCOME / 01"]
           [:h2 "Build first Iris workflow"]
           [:p "Connect a provider, select tools, then test with a real session before publishing."]
           [:div.ui-dialog__steps [:span.active] [:span] [:span]]
           [:div.ui-dialog__actions [:button.ui-button "Skip"] [:button.ui-button.ui-button--primary "Start setup"]]]]])

       [:footer.ui-catalog-footer
        [:span "IRIS UI SYSTEM / 1.0"]
        [:span "Source of truth: DESIGN.md"]]]]]])))
