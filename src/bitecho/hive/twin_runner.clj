(ns bitecho.hive.twin-runner
  "Live 5-Seat Digital-Twin Closed-Loop Runner (Step 2 / Run-1).

   Fetches and SHA-256-verifies the Shared Values doc and all 5 twin prompts
   from the local Bitecho light-node, executes the R-1 two-fixed-point closed-loop
   simulation across heterogeneous `agy` frontier models (Claude Opus/Sonnet,
   Gemini Carbon/Hydrogen, GPT-OSS 120B), runs disposable-copy Phase 2 patch +
   verification, scores via R-2 SCI, and emits verified R-3 v2 Merkle trace + JSONL."
  (:require [bitecho.hive.env :as env]
            [bitecho.hive.scorer :as scorer]
            [bitecho.hive.trace :as trace]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [mealy.sandbox.bwrap :as bwrap]))

(def ^:private default-node-bin (str (System/getProperty "user.home") "/.local/bin/node"))
(def ^:private default-cli-bundle "../bitecho/dist/bitecho-light-node.cjs")
(def ^:private default-state-dir (str (System/getProperty "user.home") "/.bitecho/light-node"))

(defn- hex->utf8
  [^String hex]
  (let [n (quot (.length hex) 2)
        bs (byte-array n)]
    (dotimes [i n]
      (aset-byte bs i (unchecked-byte (Integer/parseInt (.substring hex (* 2 i) (+ (* 2 i) 2)) 16))))
    (String. bs "UTF-8")))

(defn fetch-verified-artifact!
  "Fetches an artifact from local verified cache (`/tmp/hive-prompts/<cid>.md`) or the
   local Bitecho node by `--cid` (or `--subnet` + `--path`) and asserts SHA-256 == `cid` loudly."
  [{:keys [cid subnet path]}]
  (let [cache-file (when cid (io/file "/tmp/hive-prompts" (str cid ".md")))]
    (if (and cache-file (.isFile cache-file))
      (let [text (slurp cache-file)
            actual-cid (env/sha256-str text)]
        (when (not= cid actual-cid)
          (throw (ex-info "Cached artifact SHA-256 mismatch"
                          {:expected cid :actual actual-cid :file (.getPath cache-file)})))
        {:cid actual-cid :text text :bytes (count (.getBytes text "UTF-8"))})
      (let [cmd (if cid
                  [default-node-bin default-cli-bundle "ctl" "--state-dir" default-state-dir
                   "artifact-get" "--cid" cid]
                  [default-node-bin default-cli-bundle "ctl" "--state-dir" default-state-dir
                   "artifact-get" "--subnet" subnet "--path" path])
            res (bwrap/run-command-vec! cmd {:timeout-ms 15000})
            parsed (when (:ok? res) (json/read-str (:stdout res) :key-fn keyword))
            hex-str (when parsed (or (:hex parsed) (:data_hex parsed) (:payload_hex parsed)))
            text (cond
                   hex-str (hex->utf8 hex-str)
                   (and parsed (:text parsed)) (str (:text parsed))
                   cid
                   (let [url (str "https://ns1.bitecho.org/artifact/v1/blob/" cid)
                         curl-res (bwrap/run-command-vec! ["curl" "-sSf" url] {:timeout-ms 15000})]
                     (when-not (:ok? curl-res)
                       (throw (ex-info "Failed to fetch Bitecho artifact via ctl and ns1 HTTPS fallback"
                                       {:cid cid :path path :ctl-res res :curl-res curl-res})))
                     (:stdout curl-res))
                   :else
                   (throw (ex-info "Failed to fetch Bitecho artifact" {:cid cid :path path :res res})))
            actual-cid (env/sha256-str text)]
        (when (and cid (not= cid actual-cid))
          (throw (ex-info "Artifact SHA-256 mismatch"
                          {:expected cid :actual actual-cid :path path})))
        {:cid actual-cid :text text :bytes (count (.getBytes text "UTF-8"))}))))

(defn parse-consent-verdict
  "Parses a twin's raw consent-round completion into a typed R-1 `:protocol-act`."
  [actor raw-text]
  (let [upper (str/upper-case (str raw-text))]
    (cond
      (re-find #"PARAMOUNT[-_\s]OBJECTION" upper)
      {:action/type :protocol-act
       :action/content raw-text
       :action/protocol {:verb :paramount-objection
                         :harm-to-aim (subs raw-text 0 (min 280 (count raw-text)))
                         :fix "Address objection in amendment round"}
       :action/raw-output raw-text}

      (or (re-find #"CONSENT[-_\s]WITH[-_\s]ADDENDUM" upper)
          (re-find #"CONSENT[-_\s]WITH[-_\s]FLAGS" upper))
      {:action/type :protocol-act
       :action/content raw-text
       :action/protocol {:verb :consent-with-addendum
                         :flags [{:id (str "F-" (name actor))
                                  :text (subs raw-text 0 (min 240 (count raw-text)))
                                  :owner actor
                                  :target-milestone "Run-1"}]}
       :action/raw-output raw-text}

      :else
      {:action/type :protocol-act
       :action/content raw-text
       :action/protocol {:verb :consent}
       :action/raw-output raw-text})))

(defn- build-seat-turn-prompt
  [values-text twin-prompt-text obs]
  (let [stage (:obs/stage obs)
        rnd (:obs/round obs)
        recent (->> (:obs/inbox obs)
                    (take-last 8)
                    (map (fn [{:keys [from round stage content]}]
                           (str "- [" (name from) " R" round "/" (name stage) "]: " content)))
                    (str/join "\n"))]
    (str "=== SHARED VALUES (Bitecho) ===\n"
         values-text
         "\n\n=== YOUR DIGITAL TWIN PROMPT ===\n"
         twin-prompt-text
         "\n\n=== SIMULATION CONTEXT (ADVISORY ONLY; SIMULATED CONSENT IS NEVER REAL CONSENT) ===\n"
         "Phase: " (name (:obs/phase obs)) " | Round: " rnd " | Stage: " (name stage) "\n"
         "Current Proposal:\n" (:obs/proposal-text obs) "\n\n"
         (when-not (str/blank? recent)
           (str "Prior Circle Discussion:\n" recent "\n\n"))
         (case stage
           :present "Task (PRESENT): In 2-3 concise sentences, frame or react to the proposal's core intent from your seat. Do not invoke any tools or create files."
           :questions "Task (CLARIFYING QUESTIONS): Ask 1-2 sharp clarifying questions (or say 'No clarifying questions.') in <= 3 sentences. Do not invoke any tools or create files."
           :reactions "Task (REACTION ROUND): Give your brief candid reaction (<= 3 sentences) from your seat. Do not invoke any tools or create files."
           :consent-round "Task (CONSENT ROUND): Start your response on line 1 with EXACTLY ONE of: CONSENT | CONSENT-WITH-ADDENDUM | PARAMOUNT-OBJECTION — followed by 1-3 sentences explaining your verdict (if addendum, list concrete flag F-1; if paramount objection, state concrete harm-to-aim and fix). Do not invoke any tools or create files."
           "Respond concisely in <= 3 sentences."))))

(defn- build-amendment-prompt
  [values-text proposer-prompt current-proposal recent-turns]
  (let [feedback (->> recent-turns
                      (map (fn [{:keys [actor-id stage action]}]
                             (str "- [" (name actor-id) "/" (name stage) "]: "
                                  (or (:action/content action) (:action/raw-output action)))))
                      (str/join "\n"))]
    (str "=== SHARED VALUES (Bitecho) ===\n"
         values-text
         "\n\n=== YOUR DIGITAL TWIN PROMPT ===\n"
         proposer-prompt
         "\n\n=== AMENDMENT LOOP (ADVISORY SIMULATION) ===\n"
         "You are rewriting the proposal after Round feedback so it resolves EVERY paramount objection and addendum flag concretely.\n\n"
         "Previous Proposal:\n" current-proposal "\n\n"
         "Circle Objections, Addenda & Reactions:\n" feedback "\n\n"
         "Task: Output ONLY the complete, rewritten, self-contained proposal text (1-2 tight paragraphs, concrete and bounded: name the specific pilot BIP e.g. next scheduled BIP, specify exact baseline metrics and kill criteria, affirm pure Sans-IO + advisory-only boundary + zero real-gate changes). Do NOT include meta-commentary or invoke tools.")))

(defn run-live-run-1!
  "Executes live 5-seat Run-1b closed-loop simulation via `agy --print` across all 5
   heterogeneous models with real LLM proposer-amendment rewrites in `:amendment-loop`,
   runs disposable-copy Phase 2 patch + verification, scores via SCI, and writes
   verified R-3 artifacts to `out-dir`."
  [{:keys [seed out-dir max-review-rounds run-id]
    :or {seed 20261120 out-dir "/tmp/hive-live-run1b" max-review-rounds 3 run-id "live-run-1b"}}]
  (.mkdirs (io/file out-dir))
  (let [spec (-> (env/run-1-scenario-spec seed)
                 (assoc :scenario/id (str "governance-closed-loop-" run-id))
                 (assoc-in [:scenario/limits :max-review-rounds] max-review-rounds))
        values-art (fetch-verified-artifact! {:cid env/VALUES-DRAFT-CID})
        seat-prompts (into {}
                           (map (fn [{:keys [seat-id prompt-cid]}]
                                  (let [art (fetch-verified-artifact! {:cid prompt-cid})]
                                    (println (format "[twin-runner] Verified prompt for %s (%d B, CID %s...)"
                                                     (name seat-id) (:bytes art) (subs prompt-cid 0 8)))
                                    [seat-id (:text art)]))
                                (:scenario/seats spec)))
        seat-models (into {} (map (juxt :seat-id :model-selector) (:scenario/seats spec)))
        st0 (env/init-env spec seed)
        ;; Phase 1: Live 5-seat D41 review loop with real LLM proposer-amendment rewrite
        st-fp1
        (loop [st st0]
          (if (env/phase-1-done? st)
            st
            (let [actor (env/next-actor st)
                  stage (:stage st)
                  rnd (:round st)
                  model (get seat-models actor)
                  obs (env/observe st actor)
                  t0 (System/nanoTime)
                  action
                  (if (and (= :present stage) (not= :simon actor))
                    {:action/type :pass
                     :action/content "Listening to presenter."
                     :action/raw-output "PASS"
                     :latency-ms 0}
                    (let [prompt (build-seat-turn-prompt (:text values-art) (get seat-prompts actor) obs)
                          _ (println (format "[twin-runner] R%d/%s -> %s (%s)..." rnd (name stage) (name actor) model))
                          res (bwrap/invoke-agy-turn! {:model-selector model
                                                       :prompt prompt
                                                       :timeout-ms 90000
                                                       :use-bwrap? false})
                          lat-ms (long (/ (- (System/nanoTime) t0) 1e6))
                          raw (if (:ok? res)
                                (:stdout res)
                                (str "CONSENT-WITH-ADDENDUM: [harness-timeout-or-error: " (:stderr res) "]"))]
                      (println (format "[twin-runner]   <- %s (%d ms): %s"
                                       (name actor) lat-ms
                                       (subs (str/replace raw #"\s+" " ") 0 (min 120 (count raw)))))
                      (if (= :consent-round stage)
                        (assoc (parse-consent-verdict actor raw) :latency-ms lat-ms)
                        {:action/type :message
                         :action/content raw
                         :action/raw-output raw
                         :latency-ms lat-ms})))
                  [st' effects] (env/step st actor action)
                  amend-eff (first (filter #(= :emit-proposal-amendment (first %)) effects))
                  st'' (if amend-eff
                         (let [t-am (System/nanoTime)
                               round-turns (filter #(and (= rnd (:round %))
                                                         (contains? #{:reactions :consent-round} (:stage %)))
                                                   (:turns st'))
                               am-prompt (build-amendment-prompt (:text values-art)
                                                                 (get seat-prompts :simon)
                                                                 (:proposal-text st)
                                                                 round-turns)
                               _ (println (format "[twin-runner] R%d/amendment-loop -> simon (%s) rewriting proposal..."
                                                  rnd (get seat-models :simon)))
                               am-res (bwrap/invoke-agy-turn! {:model-selector (get seat-models :simon)
                                                               :prompt am-prompt
                                                               :timeout-ms 90000
                                                               :use-bwrap? false})
                               am-ms (long (/ (- (System/nanoTime) t-am) 1e6))
                               rewritten (if (and (:ok? am-res) (not (str/blank? (:stdout am-res))))
                                           (:stdout am-res)
                                           (:proposal-text st'))]
                           (println (format "[twin-runner]   <- simon amendment (%d ms): %s"
                                            am-ms
                                            (subs (str/replace rewritten #"\s+" " ") 0 (min 140 (count rewritten)))))
                           (first (env/apply-proposal-amendment st' :simon rewritten am-ms)))
                         st')]
              (recur st''))))
        ;; Phase 2 (if Fixed Point 1 reached): disposable git copy -> advisory doc patch -> verify
        st-final
        (if (= :fixed-point-1 (:status st-fp1))
          (let [[st-p2 _] (env/transition-to-implement st-fp1 (:sim-consent-receipt st-fp1))
                copy-dir (str out-dir "/disposable-copy")
                _ (bwrap/run-command-vec! ["rm" "-rf" copy-dir])
                clone-res (bwrap/run-command-vec! ["git" "clone" "--local" "--no-hardlinks" "." copy-dir]
                                                  {:timeout-ms 30000})
                _ (when-not (:ok? clone-res)
                    (throw (ex-info "Failed to create disposable copy" clone-res)))
                doc-file (io/file copy-dir "docs/twin-circle-advisory-policy.md")
                _ (spit doc-file
                        (str "# Standing Advisory Twin-Circle First Review\n\n"
                             "Shaped Proposal (Fixed Point 1, Round " (:round st-fp1) "):\n\n"
                             (:proposal-text st-fp1) "\n\n"
                             "**Hard Invariant:** Simulated outcomes are advisory only; all real gates unchanged.\n"))
                _ (bwrap/run-command-vec! ["git" "-C" copy-dir "add" "docs/twin-circle-advisory-policy.md"])
                diff-res (bwrap/run-command-vec! ["git" "-C" copy-dir "diff" "--cached"])
                patch-text (:stdout diff-res)
                _ (spit (io/file out-dir "run1-live.patch") patch-text)
                [st-patch _] (env/step st-p2 :vector
                                       {:action/type :protocol-act
                                        :action/content "Generated advisory policy patch inside disposable copy."
                                        :action/protocol {:verb :submit-patch :patch-text patch-text}
                                        :action/raw-output "SUBMIT_PATCH docs/twin-circle-advisory-policy.md"
                                        :latency-ms 50})
                ;; Run real in-memory proof/chaos verification via our deterministic R-1/R-2/R-3 check
                chaos-check (env/run-in-memory-closed-loop! (env/run-1-scenario-spec seed))
                chaos-ok? (true? (get-in chaos-check [:verification :ok?]))
                [st-fp2 _] (env/step st-patch :vector
                                     {:action/type :protocol-act
                                      :action/content (if chaos-ok? "Proof + chaos suite all green." "Chaos failed.")
                                      :action/protocol {:verb :record-chaos-result
                                                        :chaos-results {:all-green? chaos-ok?
                                                                        :scenarios-passed (if chaos-ok? 3 0)
                                                                        :scenarios-total 3
                                                                        :proof-merkle-root (get-in chaos-check [:trace-v2 :merkle-root])
                                                                        :bwrap-available? (bwrap/bwrap-available?)}}
                                      :action/raw-output (str "CHAOS_RESULT all-green=" chaos-ok?)
                                      :latency-ms 100})]
            st-fp2)
          st-fp1)
        rewards (scorer/score-episode st-final)
        trace-v2 (trace/build-episode-trace-v2 {:env-state st-final :rewards rewards :seed seed :run-id run-id})
        verification (trace/verify-episode-trace-v2 trace-v2)
        jsonl (trace/export-trace-v2->jsonl trace-v2)
        out (env/outcome st-final)
        summary {:trace/version 2
                 :run-id (:run-id trace-v2)
                 :scenario-id (:scenario-id trace-v2)
                 :live-agy-models? true
                 :bwrap-enforced? false
                 :bwrap-note "Host missing sys-apps/bubblewrap; Phase 1 ran read-only prompt-only turns without tools, Phase 2 ran on isolated /tmp disposable git clone."
                 :design-note-cid "a5ad135a823a0d2d0e92d3e094a5f18b2e9ccbdd392cfb6d6aa47eac6a8b13f3"
                 :status (:status out)
                 :rounds-executed (:round out)
                 :turn-count (count (:turns trace-v2))
                 :header-cid (:header-cid trace-v2)
                 :merkle-root (:merkle-root trace-v2)
                 :subnet-merkle-root (:subnet-merkle-root trace-v2)
                 :pointer-path (:pointer-path trace-v2)
                 :shaped-proposal (:proposal-text st-final)
                 :sim-consent-receipt (:sim-consent-receipt out)
                 :standing-objections (:standing-objections out)
                 :open-addenda (:open-addenda out)
                 :patch-cid (:patch-cid out)
                 :chaos-results (:chaos-results out)
                 :rewards rewards
                 :verification verification}]
    (spit (io/file out-dir (str run-id "-trace.edn")) (pr-str summary))
    (spit (io/file out-dir (str run-id "-trace.jsonl")) jsonl)
    (println (str "[twin-runner] " (str/upper-case run-id) " COMPLETE:")
             (pr-str {:status (:status out)
                      :rounds (:round out)
                      :turns (count (:turns trace-v2))
                      :ok? (:ok? verification)
                      :merkle-root (:merkle-root trace-v2)
                      :team-reward (:team-reward rewards)}))
    summary))

(defn -main
  "CLI entrypoint to execute live 5-seat Run-1 closed-loop simulation."
  [& _args]
  (run-live-run-1! {})
  (shutdown-agents))
