(ns kaocha.report
  "Reporters generate textual output during a test run, providing real-time
  information on test progress, failures, errors, and so forth. They are in
  nature imperative and side-effectful, they generate output on an output
  stream (typically stdout), based on test events. Some reporters are also used
  to track state. This is unfortunate as it goes against Kaocha's functional
  design, but since we want test runs to be interruptible it is somewhat
  inevitable.

  The concept of reporters is directly taken from clojure.test, but is used in
  Kaocha also when running other types of tests.

  A reporter is a function which takes a single argument, a map. The map will
  have a `:type` key indicating the type of event, e.g. `:begin-test-var`,
  `:fail`, `:pass`, or `:summary`.

  Reporters as imagined in `clojure.test` are a flawed design, we try to make
  the best of it. See also the monkeypatching of `clojure.test/do-test` in
  `kaocha.monkey-patch`, which is necessary to be able to intercept failures
  quickly in case the users runs with `--fail-fast` enabled. The patch also
  ensures that the current testable is always available in the event map under
  `:kaocha/testable`,

  Kaocha differs from stock `clojure.test` in that multiple reporters can be
  active at the same time. On the command line you can specify `--reporter`
  multiple times, in `tests.edn` you can pass a vector to `:kaocha/reporter`,
  and/or point at a var which itself defines a vector of functions. Each of the
  given functions will be called in turn for each event generated.

  This has allowed Kaocha to split the functionality of reporters up, making
  them more modular. E.g. `kaocha.report/report-counters` only keeps the
  fail/error/pass/test counters, without concerning itself with output, making
  it reusable.

  This namespace implements the reporters provided by Kaocha out of the box that
  don't need extra dependencies. Others like e.g. the progress bar are in their
  own namespace to prevent loading files we don't need, and thus slowing down
  startup.

  ### Issues with clojure.test reporters

  `clojure.test` provides reporters as a way to extend the library. By default
  `clojure.test/report` is a multimethod which dispatches on `:type`, and so
  libraries can extend this multimethod to add support for their own event
  types. A good example is the `:mismatch` event generated by
  matcher-combinators.

  Tools can also rebind `clojure.test/report`, and use it as an interface for
  capturing test run information.

  The problem is that these two approaches don't mesh. When tools (like Kaocha,
  CIDER, Cursive, etc.) rebind `clojure.test/report`, then any custom extensions
  to the multimethod disappear.

  This can also cause troubles when a library which extends
  `clojure.test/report` gets loaded after it has been rebound. This was an issue
  for a while in test.check, which assumed `report` would always be a
  multimethod (this has been rectified). For this reasons Kaocha only rebinds
  `report` *after* the \"load\" step.

  Kaocha tries to work around these issues to some extent by forwarding any keys
  it does not know about to the original `clojure.test/report` multimethod. This
  isn't ideal, as these extensions are not aware of Kaocha's formatting and
  output handling, but it does provide some level of compatiblity with third
  party libraries.

  For popular libraries we will include reporter implementations that handle
  these events in a way that makes sense within Kaocha, see e.g.
  `kaocha.matcher-combinators`. Alternatively library authors can
  themselves strive for Kaocha compatiblity, we try to give them the tools to
  enable this, through keyword derivation and custom multimethods.

  ### Custom event types

  `kaocha.report` makes use of Clojure's keyword hierarchy feature to determine
  the type of test events. To make Kaocha aware of your custom event, first add
  a derivation from `:kaocha/known-type`, this will stop the event from being
  propagated to the original `clojure.test/report`

  ``` clojure
  (kaocha.hierarchy/derive! :mismatch :kaocha/known-key)
  ```

  If the event signals an error or failure which causes the test to fail, then
  derive from `:kaocha/fail-type`. This will make Kaocha's existing reporters
  compatible with your custom event.

  ``` clojure
  (kaocha.hierarchy/derive! :mismatch :kaocha/fail-type)
  ```

  "
  (:require [kaocha.core-ext :refer :all]
            [kaocha.output :as output]
            [kaocha.plugin.capture-output :as capture]
            [kaocha.stacktrace :as stacktrace]
            [kaocha.testable :as testable]
            [clojure.test :as t]
            [slingshot.slingshot :refer [throw+]]
            [clojure.string :as str]
            [kaocha.history :as history]
            [kaocha.testable :as testable]
            [kaocha.hierarchy :as hierarchy]
            [kaocha.jit :refer [jit]]))

(defonce clojure-test-report t/report)

(defn dispatch-extra-keys
  "Call the original clojure.test/report multimethod when dispatching an unknown
  key. This is to support libraries like nubank/matcher-combinators that extend
  clojure.test/assert-expr, as well as clojure.test/report, to signal special
  conditions."
  [m]
  (when (and (not (hierarchy/known-key? m))
             (not= (get-method clojure-test-report :default)
                   (get-method clojure-test-report (:type m))))
    (clojure-test-report m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti dots* :type :hierarchy #'hierarchy/hierarchy)
(defmethod dots* :default [_])

(defmethod dots* :pass [_]
  (t/with-test-out
    (print ".")
    (flush)))

(defmethod dots* :kaocha/fail-type [_]
  (t/with-test-out
    (print (output/colored :red "F"))
    (flush)))

(defmethod dots* :error [_]
  (t/with-test-out
    (print (output/colored :red "E"))
    (flush)))

(defmethod dots* :kaocha/pending [_]
  (t/with-test-out
    (print (output/colored :yellow "P"))
    (flush)))

(defmethod dots* :kaocha/begin-group [_]
  (t/with-test-out
    (print "(")
    (flush)))

(defmethod dots* :kaocha/end-group [_]
  (t/with-test-out
    (print ")")
    (flush)))

(defmethod dots* :begin-test-suite [_]
  (t/with-test-out
    (print "[")
    (flush)))

(defmethod dots* :end-test-suite [_]
  (t/with-test-out
    (print "]")
    (flush)))

(defmethod dots* :summary [_]
  (t/with-test-out
    (println)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti report-counters :type :hierarchy #'hierarchy/hierarchy)

(defmethod report-counters :default [_])

(defmethod report-counters :pass [m]
  (t/inc-report-counter :pass))

(defmethod report-counters :kaocha/fail-type [m]
  (t/inc-report-counter :fail))

(defmethod report-counters :error [m]
  (t/inc-report-counter :error))

(defmethod report-counters :kaocha/pending [m]
  (t/inc-report-counter :pending))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti result :type :hierarchy #'hierarchy/hierarchy)
(defmethod result :default [_])

(defn testing-vars-str
  "Returns a string representation of the current test. Renders names
  in :testing-vars as a list, then the source file and line of current
  assertion."
  [{:keys [file line testing-vars kaocha/testable] :as m}]
  (let [file (or file (some-> testable ::testable/meta :file))
        line (or line (some-> testable ::testable/meta :line))]
    (str
     ;; Uncomment to include namespace in failure report:
     ;;(ns-name (:ns (meta (first *testing-vars*)))) "/ "
     (or (some-> (:kaocha.testable/id testable) str (subs 1))
         (and (seq testing-vars)
              (reverse (map #(:name (meta %)) testing-vars))))
     " (" file ":" line ")")))

(defn print-output [m]
  (let [output (get-in m [:kaocha/testable ::capture/output])
        buffer (get-in m [:kaocha/testable ::capture/buffer])
        out (or output (and buffer (capture/read-buffer buffer)))]
    (when (seq out)
      (println "╭───── Test output ───────────────────────────────────────────────────────")
      (println (str/replace (str/trim-newline out)
                            #"(?m)^" "│ "))
      (println "╰─────────────────────────────────────────────────────────────────────────"))))

(defn assertion-type
  "Given a clojure.test event, return the first symbol in the expression inside (is)."
  [m]
  (if-let [s (and (seq? (:expected m)) (seq (:expected m)))]
    (first s)
    :default))

(defmulti print-expr
  assertion-type
  :hierarchy #'hierarchy/hierarchy)

(defmethod print-expr :default [m]
  (when (contains? m :expected)
    (println "expected:" (pr-str (:expected m))))
  (when (contains? m :actual)
    (println "  actual:" (pr-str (:actual m)))))

(defmethod print-expr '= [m]
  (let [printer (output/printer)]
    (if (seq? (:actual m))
      (let [[_ expected & actuals] (-> m :actual second)]

        (output/print-doc
         [:span
          "Expected:" :line
          [:nest (output/format-doc expected printer)]
          :break
          "Actual:" :line
          (into [:nest]
                (interpose :break)
                (for [actual actuals]
                  (output/format-doc ((jit lambdaisland.deep-diff/diff) expected actual)
                                     printer)))]))

      (output/print-doc
       [:span
        "Expected:" :line
        [:nest (output/format-doc (:expected m) printer)]
        :break
        "Actual:" :line
        [:nest (output/format-doc (:actual m) printer)]]))))

(defmulti fail-summary :type :hierarchy #'hierarchy/hierarchy)

(defmethod fail-summary :kaocha/fail-type [{:keys [testing-contexts testing-vars] :as m}]
  (println (str "\n" (output/colored :red "FAIL") " in") (testing-vars-str m))
  (when (seq testing-contexts)
    (println (str/join " " (reverse testing-contexts))))
  (when-let [message (:message m)]
    (println message))
  (print-expr m)
  (print-output m))

(defmethod fail-summary :error [{:keys [testing-contexts testing-vars] :as m}]
  (println (str "\n" (output/colored :red "ERROR") " in") (testing-vars-str m))
  (when (seq testing-contexts)
    (println (str/join " " (reverse testing-contexts))))
  (when-let [message (:message m)]
    (println message))
  (print-output m)
  (print "Exception: ")
  (let [actual (:actual m)]
    (if (throwable? actual)
      (stacktrace/print-cause-trace actual t/*stack-trace-depth*)
      (prn actual))))

(defmethod result :summary [m]
  (t/with-test-out
    (let [failures (filter hierarchy/fail-type? @history/*history*)]
      (doseq [{:keys [testing-contexts testing-vars] :as m} failures]
        (binding [t/*testing-contexts* testing-contexts
                  t/*testing-vars* testing-vars]
          (fail-summary m))))

    (doseq [deferred (filter hierarchy/deferred? @history/*history*)]
      (clojure-test-report deferred))

    (let [{:keys [test pass fail error pending] :or {pass 0 fail 0 error 0 pending 0}} m
          failed? (pos-int? (+ fail error))
          pending? (pos-int? pending)]
      (println (output/colored (if failed? :red (if pending? :yellow :green))
                               (str test " tests, "
                                    (+ pass fail error) " assertions, "
                                    (when (pos-int? error)
                                      (str error " errors, "))
                                    (when pending?
                                      (str pending " pending, "))
                                    fail " failures."))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fail-fast
  "Fail fast reporter, add this as a final reporter to interrupt testing as soon
  as a failure or error is encountered."
  [m]
  (when (and testable/*fail-fast?*
             (hierarchy/fail-type? m)
             (not (:kaocha.result/exception m))) ;; prevent handled exceptions from being re-thrown
    (throw+ {:kaocha/fail-fast true})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def doc-printed-contexts (atom nil))

(defn doc-print-contexts [contexts & [suffix]]
  (let [printed-contexts @doc-printed-contexts]
    (let [contexts     (reverse contexts)
          printed      (reverse printed-contexts)
          pairwise     (map vector (concat printed (repeat nil)) contexts)
          nesting      (->> pairwise (take-while (fn [[x y]] (= x y))) count)
          new-contexts (->> pairwise (drop-while (fn [[x y]] (= x y))) (map last))]
      (when (seq new-contexts)
        (doseq [[ctx idx] (map vector new-contexts (range))
                :let [nesting (+ nesting idx)]]
          (print (str "\n"
                      "    "
                      (apply str (repeat nesting "  "))
                      ctx))
          (flush))))

    (reset! doc-printed-contexts contexts)))

(defmulti doc :type :hierarchy #'hierarchy/hierarchy)
(defmethod doc :default [_])

(defmethod doc :begin-test-suite [m]
  (t/with-test-out
    (reset! doc-printed-contexts (list))
    (print "---" (-> m :kaocha/testable :kaocha.testable/desc) "---------------------------")
    (flush)))

(defmethod doc :kaocha/begin-group [m]
  (t/with-test-out
    (reset! doc-printed-contexts (list))
    (print (str "\n" (-> m
                         :kaocha/testable
                         :kaocha.testable/desc)))
    (flush)))

(defmethod doc :kaocha/end-group [m]
  (t/with-test-out
    (println)))

(defmethod doc :kaocha/begin-test [m]
  (t/with-test-out
    (let [desc (or (some-> m :kaocha/testable :kaocha.testable/desc)
                   (some-> m :var meta :name))]
      (print (str "\n  " desc))
      (flush))))

(defmethod doc :pass [m]
  (t/with-test-out
    (doc-print-contexts t/*testing-contexts*)))

(defmethod doc :error [m]
  (t/with-test-out
    (doc-print-contexts t/*testing-contexts*)
    (print (output/colored :red " ERROR"))))

(defmethod doc :kaocha/fail-type [m]
  (t/with-test-out
    (doc-print-contexts t/*testing-contexts*)
    (print (output/colored :red " FAIL"))))

(defmethod doc :summary [m]
  (t/with-test-out
    (println)))

(defn debug [m]
  (t/with-test-out
    (prn (cond-> (select-keys m [:type :file :line :var :ns :expected :actual :message :kaocha/testable :debug])
           (:kaocha/testable m)
           (update :kaocha/testable select-keys [:kaocha.testable/id :kaocha.testable/type])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def dots
  "Reporter that prints progress as a sequence of dots and letters."
  [dots* result])

(def documentation
  [doc result])
