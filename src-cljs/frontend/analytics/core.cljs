(ns frontend.analytics.core
  (:require [frontend.analytics.segment :as segment]
            [frontend.analytics.common :as common-analytics]
            [frontend.models.build :as build-model]
            [frontend.models.project :as project-model]
            [frontend.utils :refer [merror]]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.build :as build-util]
            [frontend.intercom :as intercom]
            [frontend.utils.vcs-url :as vcs-url]
            [schema.core :as s]
            [om.core :as om :include-macros true]
            [goog.style]
            [goog.string :as gstr]))

;; Below are the lists of our supported events.
;; Events should NOT be view specific. They should be view agnostic and
;; include a view in the properties.
;; Add new events here and keep each list of event types sorted alphabetically
(def supported-events
  ;; There are two kinds of events:
  ;;    click and impression events and
  ;;    action events
  ;; click and impression events should be in the format <item>-<clicked or impression>.
  ;; action events should be in the format <item>-<action in the past tense>
  ;;    examples: project-followed or banner-dismissed
  #{:account-settings-clicked
    :add-more-containers-clicked
    :add-project-clicked
    :authorize-vcs-clicked
    :beta-accept-terms-clicked
    :beta-join-clicked
    :beta-leave-clicked
    :branch-clicked
    :branch-picker-project-settings-clicked
    :build-head-parallelism-clicked
    :build-page-project-settings-clicked
    :build-page-tab-clicked
    :build-canceled
    :build-head-pr-link-clicked
    :build-head-revision-link-clicked
    :build-insights-upsell-clicked
    :build-insights-upsell-impression
    :build-link-clicked
    :build-row-cancel-build-clicked
    :build-row-pr-link-clicked
    :build-row-rebuild-clicked
    :build-row-revision-link-clicked
    :build-row-status-clicked
    :build-timing-upsell-clicked
    :build-timing-upsell-impression
    :cancel-build-clicked
    :cancel-plan-clicked
    :change-image-clicked
    :container-filter-changed
    :container-selected
    :dismiss-trial-offer-banner-clicked
    :header-follow-project-clicked
    :header-org-settings-link-clicked
    :expand-repo-toggled
    :insights-bar-clicked
    :insights-header-parallelism-clicked
    :invite-teammates-clicked
    :invite-teammates-dismissed
    :invite-teammates-impression
    :invite-teammates-select-all-clicked
    :invite-teammates-select-none-clicked
    :login-clicked
    :new-plan-clicked
    :no-plan-banner-impression
    :org-clicked
    :pr-link-clicked
    :parallelism-clicked
    :project-branch-changed
    :project-clicked
    :project-enabled
    :project-followed
    :project-settings-clicked
    :select-plan-clicked
    :set-up-junit-clicked
    :show-all-branches-toggled
    :signup-clicked
    :signup-impression
    :sort-branches-toggled
    :start-trial-clicked
    :teammates-invited
    :trial-offer-banner-impression
    :update-parallelism-clicked
    :update-plan-clicked
    :web-notifications-permissions-banner-dismissed
    :web-notifications-permissions-banner-impression
    :web-notifications-permissions-set})

(def supported-api-response-events
  ;; TODO: All these events should be server side.
  ;;       They all represent a change to data in our database, so they should be server side
  ;;       where we update the data in the db.
  ;; These are the api response events.
  ;; They are in the format of <object>-<action in the past tense>
  #{:project-builds-stopped
    :project-followed
    :project-unfollowed})

(def SupportedEvents
  (apply s/enum
         (concat supported-events
                 supported-api-response-events)))

(def CoreAnalyticsEvent
  {:event-type s/Keyword
   :current-state {s/Any s/Any}
   (s/optional-key :properties) (s/maybe {s/Keyword s/Any})})

(defn analytics-event-schema
  ([] (analytics-event-schema {}))
  ([schema]
   (merge CoreAnalyticsEvent schema)))

(def AnalyticsEvent
  (analytics-event-schema {:event-type SupportedEvents}))

(def ExternalClickEvent
  (analytics-event-schema {:event SupportedEvents}))

(def PageviewEvent
  (analytics-event-schema {:navigation-point s/Keyword}))

(def BuildEvent
  (analytics-event-schema {:build {s/Keyword s/Any}}))

(defn- add-properties-to-track-from-state [current-state]
  "Get a map of the mutable properties we want to track out of the
  state. Also add a timestamp."
  {:user (get-in current-state state/user-login-path) 
   :view (get-in current-state state/current-view-path)
   :repo (get-in current-state state/navigation-repo-path)
   :org (get-in current-state state/navigation-org-path)})

(defn- supplement-tracking-properties [{:keys [properties current-state]}]
  "Fill in any unsuppplied property values with those supplied
  in the current app state."
  (-> current-state
      (add-properties-to-track-from-state)
      (merge properties)))

(defn- current-subpage
  "Get the subpage for a pageview. If there is a subpage as well as a tab, the subpage
  takes preference since it is a step higher in the UI'f information hierarchy."
  [current-state]
  (or (get-in current-state state/navigation-subpage-path)
      (get-in current-state state/navigation-tab-path)
      :default))

(defn- current-build-tab
  "Get the tab for a build."
  [build current-state]
  (let [subpage (current-subpage current-state)]
    (if-not (= :default subpage)
      subpage
      (build-util/default-tab build (get-in current-state state/project-scopes-path)))))

(defn build-properties [build current-state]
  (merge {:running (build-model/running? build)
          :tab (current-build-tab build current-state)
          :build-num (:build_num build)
          :repo (vcs-url/repo-name (:vcs_url build))
          :org (vcs-url/org-name (:vcs_url build))
          :oss (boolean (:oss build))
          :outcome (:outcome build)}
         (when (:stop_time build)
           {:elapsed_hours (/ (- (.getTime (js/Date.))
                                 (.getTime (js/Date. (:stop_time build))))
                              1000 60 60)})))

(defmulti track (fn [data]
                  (when (frontend.config/analytics-enabled?)
                    (:event-type data))))

(s/defmethod track :default [event-data :- AnalyticsEvent]
  (let [{:keys [event-type properties current-state]} event-data]
    (segment/track-event event-type (supplement-tracking-properties {:properties properties
                                                                     :current-state current-state}))))

(s/defmethod track :external-click [event-data :- ExternalClickEvent]
  (let [{:keys [event properties current-state]} event-data]
    (segment/track-external-click event (supplement-tracking-properties {:properties properties
                                                                         :current-state current-state}))))

(s/defmethod track :pageview [event-data :- PageviewEvent]
  (let [{:keys [navigation-point properties current-state]} event-data]
    (segment/track-pageview navigation-point
                            (current-subpage current-state)
                            (supplement-tracking-properties {:properties properties
                                                             :current-state current-state}))))

(s/defmethod track :build-triggered [event-data :- BuildEvent]
  (let [{:keys [build properties current-state]} event-data
        props (merge {:project (vcs-url/project-name (:vcs_url build))
                      :build-num (:build_num build)
                      :retry? true}
                     properties)]
    (segment/track-event :build-triggered (supplement-tracking-properties {:properties props
                                                                           :current-state current-state}))))

(s/defmethod track :view-build [event-data :- BuildEvent]
  (let [{:keys [build properties current-state]} event-data
        props (merge (build-properties build current-state) properties)]
    (segment/track-event :view-build (supplement-tracking-properties {:properties props
                                                                      :current-state current-state}))))

(defn- get-user-properties-from-state [current-state]
  (let [analytics-id (get-in current-state state/user-analytics-id-path)
        user-data (get-in current-state state/user-path)]
    {:id analytics-id
     :user-properties (select-keys user-data (keys common-analytics/UserProperties))}))

(s/defmethod track :init-user [event-data :- CoreAnalyticsEvent]
  (segment/identify (get-user-properties-from-state (:current-state event-data))))
