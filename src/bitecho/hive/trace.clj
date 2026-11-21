(ns bitecho.hive.trace
  "Unified `BIP-005` `Blob` + `Pointer` + RFC 6962 Merkle Trace Format (`H-4`).
   Packages multi-agent scenario turns, KV cache append-only logs, Mealy event roots,
   and Bitecho wire envelope CIDs into content-addressed Fressian Blobs anchored by
   an Ed25519-signed `BIP-005` `Pointer` in subnet `\"hive/scenarios\"`."
  (:require [bitecho.basalt.core :as basalt]
            [bitecho.core.data :as data]
            [bitecho.crypto :as crypto]
            [bitecho.fressian :as fressian]
            [bitecho.hive.env :as env]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def ^:const HIVE-SCENARIOS-SUBNET
  "Canonical Bitecho subnet for Hive scenario traces."
  "hive/scenarios")

(def ^:const TURN-BLOB-MEDIA-TYPE
  "Canonical media type for Fressian-encoded Hive turn trace Blobs."
  "application/x-bitecho-hive-turn+fressian")

(defn canonical-turn-record
  "Constructs a canonical turn trace map (without `:turn/cid`) ready for deterministic Fressian encoding."
  [{:keys [seq actor-id actor-pubkey prev-turn-cid kv-log-cid prompt-tokens
           output-tokens output-text mealy-event-root bitecho-session-id wire-envelope-cids]}]
  {:trace/version 1
   :turn/seq (long seq)
   :turn/actor-id (keyword actor-id)
   :turn/actor-pubkey (str actor-pubkey)
   :turn/prev-turn-cid (when prev-turn-cid (str prev-turn-cid))
   :turn/kv-log-cid (str kv-log-cid)
   :turn/prompt-tokens (vec prompt-tokens)
   :turn/output-tokens (vec output-tokens)
   :turn/output-text (str output-text)
   :turn/mealy-event-root (str mealy-event-root)
   :turn/bitecho-session-id (str bitecho-session-id)
   :turn/wire-envelope-cids (mapv str (or wire-envelope-cids []))})

(defn make-turn-blob
  "Encodes a canonical turn record into a `bitecho.core.data/make-blob` Fressian `Blob`.
   Returns `[turn-with-cid blob]`."
  [turn-input]
  (let [canon (if (:trace/version turn-input)
                (dissoc turn-input :turn/cid)
                (canonical-turn-record turn-input))
        bs (fressian/encode-canonical canon)
        blob (data/make-blob bs TURN-BLOB-MEDIA-TYPE)
        cid (:blob/cid blob)]
    [(assoc canon :turn/cid cid) blob]))

(defn build-episode-trace
  "Packages an ordered sequence of turn inputs, KV logs, and authority keypair into a
   content-addressed `BIP-005` `Blob` + `Pointer` + RFC 6962 Merkle episode trace."
  [{:keys [scenario-id run-id turns kv-blob-store kv-logs authority-keypair seed]}]
  (let [kp (or authority-keypair (crypto/generate-keypair (crypto/make-random (long (or seed 20261118)))))
        auth-pub (str/lower-case (basalt/bytes->hex (:public kp)))
        auth-priv (:private kp)
        scen-id (str (or scenario-id "hello-gemma4-e2b"))
        r-id (str (or run-id (str "seed-" (or seed 20261118))))
        pointer-path (str "runs/" scen-id "/" r-id)
        init-data (cond-> (data/init-data-state)
                    kv-blob-store (assoc :blobs kv-blob-store))
        {:keys [data-state built-turns turn-cids]}
        (reduce (fn [{:keys [data-state built-turns turn-cids prev-cid]} raw-turn]
                  (let [turn-in (if (contains? raw-turn :turn/seq)
                                  (assoc raw-turn :turn/prev-turn-cid prev-cid)
                                  (assoc raw-turn :prev-turn-cid prev-cid))
                        [turn-record blob] (make-turn-blob turn-in)
                        put-res (data/put-data-blob data-state blob)
                        _ (when-not (:ok? put-res)
                            (throw (ex-info "Failed to insert turn Blob into data-state"
                                            {:error (:error put-res)})))
                        cid (:turn/cid turn-record)]
                    {:data-state (:state put-res)
                     :built-turns (conj built-turns turn-record)
                     :turn-cids (conj turn-cids cid)
                     :prev-cid cid}))
                {:data-state init-data
                 :built-turns []
                 :turn-cids []
                 :prev-cid nil}
                turns)
        merkle-root (data/compute-rfc6962-merkle-root turn-cids)
        pointer (data/sign-pointer auth-priv
                                   {:pointer/authority auth-pub
                                    :pointer/subnet HIVE-SCENARIOS-SUBNET
                                    :pointer/path pointer-path
                                    :pointer/seq 1
                                    :pointer/leader-term 1
                                    :pointer/target merkle-root
                                    :pointer/proof {:proof/type :self}})
        ptr-ctx {:subnet HIVE-SCENARIOS-SUBNET
                 :path-acl [{:pattern "runs/*" :principal {:type :allowlist}}]
                 :trust-anchors #{auth-pub}}
        app-res (data/apply-pointer data-state pointer ptr-ctx)
        _ (when-not (:applied? app-res)
            (throw (ex-info "Failed to apply episode trace Pointer" {:result app-res})))
        data-state' (:state app-res)
        subnet-merkle-root (data/compute-merkle-root data-state' HIVE-SCENARIOS-SUBNET)
        merkle-proof (data/generate-merkle-proof data-state' HIVE-SCENARIOS-SUBNET pointer-path)]
    {:trace/version 1
     :scenario-id scen-id
     :run-id r-id
     :pointer-subnet HIVE-SCENARIOS-SUBNET
     :pointer-path pointer-path
     :authority-pubkey auth-pub
     :turns built-turns
     :turn-cids turn-cids
     :merkle-root merkle-root
     :subnet-merkle-root subnet-merkle-root
     :pointer (:pointer app-res)
     :merkle-proof merkle-proof
     :kv-logs (or kv-logs {})
     :data-state data-state'}))

(defn verify-episode-trace
  "Purely verifies an episode trace bundle:
   1. Every turn `Blob` exists in `:data-state`, passes `data/verify-blob`, and its Fressian-decoded map
      matches the turn record in `:turns`.
   2. `:turn/prev-turn-cid` forms an unbroken hash chain (`nil` at index 0, previous `:turn/cid` thereafter),
      and `:turn/seq` increments strictly `1..N`.
   3. Every turn's `:turn/kv-log-cid` exists in the `Blob` store, passes `data/verify-blob`, and matches
      the turn's prompt and output token sequence.
   4. The RFC 6962 Merkle root over `:turn-cids` equals `:merkle-root` and `(:pointer/target pointer)`.
   5. The `BIP-005` `Pointer` signature and subnet/path ACL pass `data/verify-pointer`, and the RFC 6962
      subnet Merkle inclusion proof passes `data/verify-merkle-proof`."
  [episode-trace]
  (if-not (and (map? episode-trace)
               (seq (:turns episode-trace))
               (seq (:turn-cids episode-trace))
               (= (count (:turns episode-trace)) (count (:turn-cids episode-trace)))
               (map? (:pointer episode-trace))
               (map? (:data-state episode-trace)))
    {:ok? false :error :malformed-episode-trace}
    (let [turns (vec (:turns episode-trace))
          turn-cids (vec (:turn-cids episode-trace))
          blob-store (get-in episode-trace [:data-state :blobs])
          ptr (:pointer episode-trace)
          auth-pub (:authority-pubkey episode-trace)
          ptr-ctx {:subnet HIVE-SCENARIOS-SUBNET
                   :path-acl [{:pattern "runs/*" :principal {:type :allowlist}}]
                   :trust-anchors #{auth-pub}}
          ptr-ver (data/verify-pointer ptr ptr-ctx)
          computed-root (data/compute-rfc6962-merkle-root turn-cids)
          proof-ver (data/verify-merkle-proof (:merkle-proof episode-trace)
                                              (:subnet-merkle-root episode-trace))]
      (cond
        (not (:ok? ptr-ver))
        {:ok? false :error :invalid-trace-pointer :details ptr-ver}

        (not= computed-root (:merkle-root episode-trace))
        {:ok? false :error :turn-merkle-root-mismatch :expected (:merkle-root episode-trace) :actual computed-root}

        (not= computed-root (:pointer/target ptr))
        {:ok? false :error :pointer-target-mismatch :expected computed-root :actual (:pointer/target ptr)}

        (not (:ok? proof-ver))
        {:ok? false :error :invalid-merkle-inclusion-proof :details proof-ver}

        :else
        (loop [idx 0
               expected-prev nil]
          (if (>= idx (count turns))
            {:ok? true
             :turn-count (count turns)
             :merkle-root computed-root
             :subnet-merkle-root (:subnet-merkle-root episode-trace)
             :pointer-path (:pointer/path ptr)}
            (let [turn (nth turns idx)
                  cid (nth turn-cids idx)
                  blob-res (data/get-blob blob-store cid)
                  blob (:blob blob-res)
                  decoded (when (and (:hit? blob-res) (data/verify-blob blob))
                            (fressian/decode-wire (:blob/bytes blob)))
                  kv-cid (:turn/kv-log-cid turn)
                  kv-blob-res (data/get-blob blob-store kv-cid)
                  kv-blob (:blob kv-blob-res)
                  kv-decoded (when (and (:hit? kv-blob-res) (data/verify-blob kv-blob))
                               (fressian/decode-wire (:blob/bytes kv-blob)))]
              (cond
                (not= cid (:turn/cid turn))
                {:ok? false :error :turn-cid-mismatch :index idx}

                (not (:hit? blob-res))
                {:ok? false :error :missing-turn-blob :cid cid :index idx}

                (not (data/verify-blob blob))
                {:ok? false :error :corrupted-turn-blob :cid cid :index idx}

                (not= (dissoc turn :turn/cid) decoded)
                {:ok? false :error :turn-blob-payload-tampered :cid cid :index idx}

                (not= (inc idx) (long (:turn/seq turn)))
                {:ok? false :error :non-monotonic-turn-seq :index idx :seq (:turn/seq turn)}

                (not= expected-prev (:turn/prev-turn-cid turn))
                {:ok? false :error :broken-turn-hash-chain :index idx :expected expected-prev :actual (:turn/prev-turn-cid turn)}

                (not (:hit? kv-blob-res))
                {:ok? false :error :missing-turn-kv-blob :kv-cid kv-cid :index idx}

                (not= (:turn/prompt-tokens turn) (:kv/prompt-tokens kv-decoded))
                {:ok? false :error :turn-kv-prompt-tokens-mismatch :index idx}

                (not= (:turn/output-tokens turn) (:kv/new-tokens kv-decoded))
                {:ok? false :error :turn-kv-output-tokens-mismatch :index idx}

                :else
                (recur (inc idx) cid)))))))))

;; ---------------------------------------------------------------------------
;; R-3 Training Trace Schema (`:trace/version 2`) + JSONL Export Projection
;; ---------------------------------------------------------------------------

(def ^:const HEADER-BLOB-MEDIA-TYPE
  "Canonical media type for Fressian-encoded Hive v2 episode header Blobs."
  "application/x-bitecho-hive-header-v2+fressian")

(def ^:const OBS-BLOB-MEDIA-TYPE
  "Canonical media type for Fressian-encoded Hive v2 observation Blobs."
  "application/x-bitecho-hive-obs-v2+fressian")

(defn- put-blob-or-throw!
  [data-state payload media-type]
  (let [bs (fressian/encode-canonical payload)
        blob (data/make-blob bs media-type)
        put-res (data/put-data-blob data-state blob)]
    (when-not (:ok? put-res)
      (throw (ex-info "Failed to insert v2 Blob into data-state"
                      {:media-type media-type :error (:error put-res)})))
    [(:blob/cid blob) (:state put-res)]))

(defn build-episode-trace-v2
  "Packages an R-1 `env-state` and R-2 `rewards` into a Merkle-verified `:trace/version 2`
   training trace bundle (`BIP-005` `Blob` + `Pointer` + RFC 6962 Merkle root over `[header-cid & turn-cids]`)."
  [{:keys [env-state rewards authority-keypair seed run-id]}]
  (let [seed-l (long (or seed (:seed env-state) 20261120))
        kp (or authority-keypair (crypto/generate-keypair (crypto/make-random seed-l)))
        auth-pub (str/lower-case (basalt/bytes->hex (:public kp)))
        auth-priv (:private kp)
        scen-id (str (or (get-in env-state [:spec :scenario/id]) "governance-closed-loop"))
        r-id (str (or run-id (str "seed-" seed-l)))
        pointer-path (str "runs/" scen-id "/" r-id)
        header {:trace/version 2
                :episode/id r-id
                :episode/scenario-id scen-id
                :episode/phase (:phase env-state)
                :episode/simulation? (boolean (:simulation? env-state))
                :episode/advisory-only (boolean (:advisory-only env-state))
                :episode/seed seed-l
                :episode/values-cid (:values-cid env-state)
                :episode/formatter-cid (:formatter-cid env-state)
                :episode/env-snapshot (get-in env-state [:spec :scenario/env-snapshot])
                :episode/policy-provenance (:policy-provenance env-state)
                :episode/chain-links {:proposal-cid (:proposal-cid env-state)
                                      :sim-consent-receipt-cid (get-in env-state [:sim-consent-receipt :receipt-cid])
                                      :base-commit-sha (get-in env-state [:spec :scenario/env-snapshot :bitecho-commit])
                                      :patch-cid (:patch-cid env-state)
                                      :chaos-results (:chaos-results env-state)}
                :episode/outcome {:status (:status env-state)
                                  :rounds-executed (:round env-state)
                                  :rewards rewards}}
        [header-cid ds-after-hdr] (put-blob-or-throw! (data/init-data-state) header HEADER-BLOB-MEDIA-TYPE)
        {:keys [data-state built-turns turn-cids]}
        (reduce (fn [{:keys [data-state built-turns turn-cids prev-cid]} raw-turn]
                  (let [obs (:observation raw-turn)
                        [obs-cid ds1] (put-blob-or-throw! data-state obs OBS-BLOB-MEDIA-TYPE)
                        turn-rec {:trace/version 2
                                  :turn/seq (long (:seq raw-turn))
                                  :turn/actor-id (keyword (:actor-id raw-turn))
                                  :turn/prev-turn-cid prev-cid
                                  :turn/phase (:phase raw-turn)
                                  :turn/round (long (:round raw-turn))
                                  :turn/stage (:stage raw-turn)
                                  :turn/observation-cid obs-cid
                                  :turn/action (:action raw-turn)
                                  :turn/step-reward (double (or (:step-reward raw-turn) 0.0))
                                  :turn/latency-ms (long (or (:latency-ms raw-turn) 0))}
                        [t-cid ds2] (put-blob-or-throw! ds1 turn-rec TURN-BLOB-MEDIA-TYPE)]
                    {:data-state ds2
                     :built-turns (conj built-turns (assoc turn-rec :turn/cid t-cid))
                     :turn-cids (conj turn-cids t-cid)
                     :prev-cid t-cid}))
                {:data-state ds-after-hdr
                 :built-turns []
                 :turn-cids []
                 :prev-cid nil}
                (:turns env-state))
        leaf-cids (into [header-cid] turn-cids)
        merkle-root (data/compute-rfc6962-merkle-root leaf-cids)
        pointer (data/sign-pointer auth-priv
                                   {:pointer/authority auth-pub
                                    :pointer/subnet HIVE-SCENARIOS-SUBNET
                                    :pointer/path pointer-path
                                    :pointer/seq 1
                                    :pointer/leader-term 1
                                    :pointer/target merkle-root
                                    :pointer/proof {:proof/type :self}})
        ptr-ctx {:subnet HIVE-SCENARIOS-SUBNET
                 :path-acl [{:pattern "runs/*" :principal {:type :allowlist}}]
                 :trust-anchors #{auth-pub}}
        app-res (data/apply-pointer data-state pointer ptr-ctx)
        _ (when-not (:applied? app-res)
            (throw (ex-info "Failed to apply v2 episode trace Pointer" {:result app-res})))
        ds-final (:state app-res)
        subnet-merkle-root (data/compute-merkle-root ds-final HIVE-SCENARIOS-SUBNET)
        merkle-proof (data/generate-merkle-proof ds-final HIVE-SCENARIOS-SUBNET pointer-path)]
    {:trace/version 2
     :scenario-id scen-id
     :run-id r-id
     :pointer-subnet HIVE-SCENARIOS-SUBNET
     :pointer-path pointer-path
     :authority-pubkey auth-pub
     :header (assoc header :header/cid header-cid)
     :header-cid header-cid
     :turns built-turns
     :turn-cids turn-cids
     :merkle-root merkle-root
     :subnet-merkle-root subnet-merkle-root
     :pointer (:pointer app-res)
     :merkle-proof merkle-proof
     :data-state ds-final}))

(defn verify-episode-trace-v2
  "Purely verifies a `:trace/version 2` episode trace bundle:
   header Blob, every turn Blob + observation Blob, unbroken `:turn/prev-turn-cid`
   hash chain, RFC 6962 Merkle root over `[header-cid & turn-cids]`, Ed25519
   `BIP-005` `Pointer` signature, and subnet Merkle inclusion proof."
  [ep-trace]
  (if-not (and (map? ep-trace)
               (= 2 (:trace/version ep-trace))
               (string? (:header-cid ep-trace))
               (vector? (:turns ep-trace))
               (= (count (:turns ep-trace)) (count (:turn-cids ep-trace)))
               (map? (:pointer ep-trace))
               (map? (:data-state ep-trace)))
    {:ok? false :error :malformed-v2-trace}
    (let [blob-store (get-in ep-trace [:data-state :blobs])
          hdr-cid (:header-cid ep-trace)
          hdr-res (data/get-blob blob-store hdr-cid)
          hdr-blob (:blob hdr-res)
          hdr-decoded (when (and (:hit? hdr-res) (data/verify-blob hdr-blob))
                        (fressian/decode-wire (:blob/bytes hdr-blob)))
          turns (:turns ep-trace)
          turn-cids (:turn-cids ep-trace)
          leaf-cids (into [hdr-cid] turn-cids)
          computed-root (data/compute-rfc6962-merkle-root leaf-cids)
          ptr (:pointer ep-trace)
          auth-pub (:authority-pubkey ep-trace)
          ptr-ctx {:subnet HIVE-SCENARIOS-SUBNET
                   :path-acl [{:pattern "runs/*" :principal {:type :allowlist}}]
                   :trust-anchors #{auth-pub}}
          ptr-ver (data/verify-pointer ptr ptr-ctx)
          proof-ver (data/verify-merkle-proof (:merkle-proof ep-trace)
                                              (:subnet-merkle-root ep-trace))]
      (cond
        (not (:hit? hdr-res))
        {:ok? false :error :missing-header-blob :cid hdr-cid}

        (not= (dissoc (:header ep-trace) :header/cid) hdr-decoded)
        {:ok? false :error :header-blob-tampered}

        (not (:ok? ptr-ver))
        {:ok? false :error :invalid-trace-pointer :details ptr-ver}

        (not= computed-root (:merkle-root ep-trace))
        {:ok? false :error :merkle-root-mismatch}

        (not= computed-root (:pointer/target ptr))
        {:ok? false :error :pointer-target-mismatch}

        (not (:ok? proof-ver))
        {:ok? false :error :invalid-merkle-inclusion-proof :details proof-ver}

        :else
        (loop [idx 0
               expected-prev nil]
          (if (>= idx (count turns))
            {:ok? true
             :trace-version 2
             :turn-count (count turns)
             :header-cid hdr-cid
             :merkle-root computed-root
             :pointer-path (:pointer/path ptr)}
            (let [turn (nth turns idx)
                  cid (nth turn-cids idx)
                  b-res (data/get-blob blob-store cid)
                  blob (:blob b-res)
                  decoded (when (and (:hit? b-res) (data/verify-blob blob))
                            (fressian/decode-wire (:blob/bytes blob)))
                  obs-cid (:turn/observation-cid turn)
                  obs-res (data/get-blob blob-store obs-cid)]
              (cond
                (not= cid (:turn/cid turn))
                {:ok? false :error :turn-cid-mismatch :index idx}

                (not (:hit? b-res))
                {:ok? false :error :missing-turn-blob :index idx}

                (not= (dissoc turn :turn/cid) decoded)
                {:ok? false :error :turn-blob-tampered :index idx}

                (not= (inc idx) (long (:turn/seq turn)))
                {:ok? false :error :non-monotonic-turn-seq :index idx}

                (not= expected-prev (:turn/prev-turn-cid turn))
                {:ok? false :error :broken-turn-hash-chain :index idx}

                (not (and (:hit? obs-res) (data/verify-blob (:blob obs-res))))
                {:ok? false :error :missing-or-corrupted-obs-blob :index idx}

                :else
                (recur (inc idx) cid)))))))))

(defn export-trace-v2->jsonl
  "Projects a verified `:trace/version 2` episode trace into lab-ready JSONL
   (one flat JSON object per turn; zero bitecho-specific tooling needed to ingest)."
  [ep-trace]
  (let [hdr (:header ep-trace)
        blob-store (get-in ep-trace [:data-state :blobs])
        prov (:episode/policy-provenance hdr)
        rewards (get-in hdr [:episode/outcome :rewards])
        team-r (double (or (:team-reward rewards) 0.0))
        agent-rs (or (:agent-rewards rewards) {})]
    (->> (:turns ep-trace)
         (map (fn [t]
                (let [actor (:turn/actor-id t)
                      seat (get prov actor)
                      obs-blob (:blob (data/get-blob blob-store (:turn/observation-cid t)))
                      obs (when obs-blob (fressian/decode-wire (:blob/bytes obs-blob)))
                      act (:turn/action t)]
                  (json/write-str
                   {:episode_id (:episode/id hdr)
                    :merkle_root (:merkle-root ep-trace)
                    :simulation (boolean (:episode/simulation? hdr))
                    :phase (name (:turn/phase t))
                    :round (:turn/round t)
                    :stage (name (:turn/stage t))
                    :turn_seq (:turn/seq t)
                    :agent_id (name actor)
                    :twin_of (when (:twin-of seat) (name (:twin-of seat)))
                    :harness (when (:harness seat) (name (:harness seat)))
                    :model_selector (:model-selector seat)
                    :prompt_cid (:prompt-cid seat)
                    :twin_config_cid (:twin-config-cid seat)
                    :values_cid (:episode/values-cid hdr)
                    :formatter_cid (:episode/formatter-cid hdr)
                    :messages (when obs (env/obs->messages obs))
                    :action_type (name (:action/type act))
                    :action_structured (or (:action/protocol act) (:action/tool act))
                    :raw_output (:action/raw-output act)
                    :token_ids (:action/token-ids act)
                    :logprobs (:action/logprobs act)
                    :step_reward (:turn/step-reward t)
                    :terminal_agent_reward (double (get agent-rs actor team-r))
                    :terminal_team_reward team-r
                    :latency_ms (:turn/latency-ms t)}))))
         (str/join "\n"))))
