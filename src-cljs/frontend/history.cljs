(ns frontend.history
  (:require [clojure.string :as string]
            [frontend.analytics :as analytics]
            [frontend.routes :as routes]
            [frontend.utils :as utils :include-macros true]
            [goog.events :as events]
            [goog.history.Html5History :as html5-history]
            [goog.window :as window]
            [secretary.core :as sec])
  (:import [goog.history Html5History]
           [goog.events EventType Event BrowserEvent]
           [goog History Uri]))


;; see this.transformer_ at http://goo.gl/ZHLdwa
(def ^{:doc "Custom token transformer that preserves hashes"}
  token-transformer
  (let [transformer (js/Object.)]
    (set! (.-retrieveToken transformer)
          (fn [path-prefix location]
            (str (subs (.-pathname location) (count path-prefix))
                 (when-let [query (.-search location)]
                   query)
                 (when-let [hash (second (string/split (.-href location) #"#"))]
                   (str "#" hash)))))

    (set! (.-createUrl transformer)
          (fn [token path-prefix location]
            (str path-prefix token)))

    transformer))

(defn set-current-token!
  "Lets us keep track of the history state, so that we don't dispatch twice on the same URL"
  [history-imp & [token]]
  (set! (.-_current_token history-imp) (or token (.getToken history-imp))))

(defn setup-dispatcher! [history-imp]
  (events/listen history-imp goog.history.EventType.NAVIGATE
                 #(do (set-current-token! history-imp)
                      (routes/dispatch! (str "/" (.-token %)))
                      (analytics/track-path (str "/" (.-token %))))))

(defn bootstrap-dispatcher!
  "We need lots of control over when we start listening to navigation events because
   we may want to ignore the first event if the server sends an error status code (e.g. 401)
   This function lets us ignore the first event that history-imp fires when we enable it. We'll
   manually dispatch if there is no error code from the server."
  [history-imp]
  (events/listenOnce history-imp goog.history.EventType.NAVIGATE #(setup-dispatcher! history-imp)))

(defn disable-erroneous-popstate!
  "Stops the browser's popstate from triggering NAVIGATION events unless the url has really
   changed. Fixes duplicate dispatch in Safari and the build machines."
  [history-imp]
  ;; get this history instance's version of window, might make for easier testing later
  (let [window (.-window_ history-imp)]
    (events/removeAll window goog.events.EventType.POPSTATE)
    (events/listen window goog.events.EventType.POPSTATE
                   #(if (= (.getToken history-imp)
                           (.-_current_token history-imp))
                      (utils/mlog "Ignoring duplicate dispatch event to" (.getToken history-imp))
                      (.onHistoryEvent_ history-imp)))))

(defn route-fragment
  "Returns the route fragment if this is a route that we've don't dispatch
  on fragments for."
  [path]
  (-> path
      sec/locate-route
      :params
      :_fragment))

(defn path-matches?
  "True if the two tokens are the same except for the fragment"
  [token-a token-b]
  (= (first (string/split token-a #"#"))
     (first (string/split token-b #"#"))))

(defn new-window-click? [event]
  (or (.isButton event goog.events.BrowserEvent.MouseButton.MIDDLE)
      (and (.-platformModifierKey event)
           (.isButton event goog.events.BrowserEvent.MouseButton.LEFT))))

(defn closest-tag [element query-tag]
  "Handle SVG element properly."
  (let [dom-helper (goog.dom.DomHelper.)
        upper-query (string/upper-case query-tag)
        lower-query (string/lower-case query-tag)
        ]
    (or (when (= (.-tagName element) upper-query) element)
        (.getAncestorByTagNameAndClass dom-helper element upper-query)
        (when (instance? js/SVGElement element)
          (loop [e element]
            (let [e-tag (.-tagName e)]  ; SVG tags are case sensitive
              (condp = e-tag
                lower-query e
                "svg" nil
                (recur (.-parentElement e)))))))))

(defn closest-a-tag-and-populate-properties [target]
  "Find closest <a> ancestor of target and add some properties to it."
  (let [a (closest-tag target "A")]
    (cond (instance? js/SVGElement a)   ; SVG
          (let [href (-> a (.-href) (.-baseVal))]
            {:attr-href href
             :attr-target (-> a .-target .-baseVal)
             :host-name (let [uri-info (goog.Uri.parse href)
                              domain (.getDomain uri-info)]
                          (if (empty? domain)
                            (-> js/window .-location .-hostname)
                            domain))})
          (instance? js/HTMLElement a)  ; HTML
          (let [href (let [path (str (.-pathname a) (.-search a) (.-hash a))]
                       (when-not (empty? path)
                         path))]
            {:attr-href href
             :attr-target (-> a .-target)
             :host-name (-> a .-hostname)}))))

(defn setup-link-dispatcher! [history-imp top-level-node]
  (events/listen
   top-level-node "click"
     #(let [-target (.. % -target)
            {:keys [attr-href attr-target host-name]} (closest-a-tag-and-populate-properties -target)
            new-token (when (seq attr-href) (subs attr-href 1))]
      (when (and (= (-> js/window .-location .-hostname)
                    host-name)
                 (not (or (new-window-click? %)
                          (= attr-target "_blank"))))
        (.preventDefault %)
        (if (and (route-fragment attr-href)
                 (path-matches? (.getToken history-imp) new-token))

          (do (utils/mlog "scrolling to hash for" attr-href)
              ;; don't break the back button
              (.replaceToken history-imp new-token))

          (do (utils/mlog "navigating to" attr-href)
              (.setToken history-imp new-token)))))))

(defn new-history-imp [top-level-node]
  ;; need a history element, or goog will overwrite the entire dom
  (let [dom-helper (goog.dom.DomHelper.)
        node (.createDom dom-helper "input" #js {:class "history hide"})]
    (.append dom-helper node))
  (doto (goog.history.Html5History. js/window token-transformer)
    (.setUseFragment false)
    (.setPathPrefix "/")
    (bootstrap-dispatcher!)
    (set-current-token!) ; Stop Safari from double-dispatching
    (disable-erroneous-popstate!) ; Stop Safari from double-dispatching
    (.setEnabled true) ; This will fire a navigate event with the current token
    (setup-link-dispatcher! top-level-node)))
