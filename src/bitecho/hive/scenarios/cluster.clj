(ns bitecho.hive.scenarios.cluster
  "Phase 2 Configurable `N` Machines x `M` Agents-Per-Machine Multi-Machine Hive Swarm (`bitecho.hive.scenarios.cluster`).
   Supports:
   1. Pure Sans-IO topology & ring schedule construction (`build-cluster-topology`) for any `N` machines
      and uniform (`--agents-per-machine M`) or heterogeneous (`--machine-agents nexus=12,vector=10`) agent slices.
   2. Pure Sans-IO in-memory multi-machine simulation (`run-in-memory-cluster-scenario`) where each machine has
      its own isolated VRAM weight multiplexer (`weight-allocations = 1` per machine, `M_i` isolated KV caches),
      home-node Bitecho bridge (intra-machine 0-burn in-memory turns + cross-machine P2P wire envelopes),
      and deterministic RFC 6962 Episode Merkle Root convergence across all `N` machines.
   3. Live distributed P2P execution across LAN hosts (`run-live-machine-cluster-node!`, e.g., `nexus` at
      `192.168.86.36:5610` and `vector` at `192.168.86.26:5610`) using length-prefixed RFC 4571 / Fressian
      wire packets with an explicit startup handshake barrier and turn/KV-Blob replication."
  (:require [bitecho.basalt.core :as basalt]
            [bitecho.core.data :as data]
            [bitecho.crypto :as crypto]
            [bitecho.fressian :as fressian]
            [bitecho.hive.kv :as kv]
            [bitecho.hive.stack :as stack]
            [bitecho.hive.trace :as trace]
            [bitecho.shell.browser :as browser]
            [clojure.string :as str]
            [mealy.graph.binder :as binder])
  (:import (java.io DataInputStream DataOutputStream EOFException)
           (java.net InetSocketAddress ServerSocket Socket)))

(def ^:const DEFAULT-CLUSTER-SUBNET-ID
  "Default shared Bitecho subnet for Phase 2 multi-machine Hive swarms."
  "hive/swarm-01")

;; =============================================================================
;; 1. Pure Sans-IO N x M Topology & Ring Schedule Construction
;; =============================================================================

(defn- normalize-machine-id
  "Normalizes a machine identifier into a simple keyword (e.g. `:nexus`, `:vector`, `:m0`)."
  [m]
  (cond
    (keyword? m) (keyword (name m))
    (string? m) (keyword (str/trim m))
    :else (keyword (str "m" m))))

(defn agent-id-for
  "Constructs the canonical namespace-qualified agent keyword for `machine-id` and local slot `slot-idx`
   (e.g., `(:nexus, 0) -> :nexus/agent-0`)."
  [machine-id slot-idx]
  (keyword (name (normalize-machine-id machine-id))
           (str "agent-" (long slot-idx))))

(defn agent-display-name
  "Returns the canonical string identifier for a qualified agent keyword (e.g. `\"nexus/agent-0\"`)."
  [agent-id]
  (if (qualified-keyword? agent-id)
    (str (namespace agent-id) "/" (name agent-id))
    (name agent-id)))

(defn machine-of-agent
  "Returns the home machine keyword for `agent-id` (e.g. `:nexus/agent-0 -> :nexus`)."
  [agent-id]
  (if (qualified-keyword? agent-id)
    (keyword (namespace agent-id))
    :default))

(defn build-cluster-topology
  "Purely builds the `N` machines x `M_i` agents-per-machine Hive cluster topology and ring schedule.
   Options:
   - `:machines`: number of machines `N` (default `2`)
   - `:agents-per-machine`: default number of agents `M` per machine (default `2`)
   - `:machine-ids`: optional explicit vector of machine keywords (defaults to `[:nexus :vector]` when `N=2`, else `[:m0 .. :m{N-1}]`)
   - `:machine-agents`: optional map of `{machine-id agent-count}` for heterogeneous swarms (e.g. `{:nexus 12 :vector 10}`)
   - `:peers`: optional map of `{machine-id {:host \"...\" :port 5610}}`
   - `:turns`: optional number of turns in the episode schedule (defaults to total agents across all machines)
   - `:subnet-id`: shared Bitecho subnet ID (default `\"hive/swarm-01\"`)"
  ([] (build-cluster-topology {}))
  ([opts]
   (let [peer-keys (when (and (map? (:peers opts)) (seq (:peers opts)))
                     (vec (keys (:peers opts))))
         explicit-ids (when (seq (:machine-ids opts))
                        (mapv normalize-machine-id (:machine-ids opts)))
         override-map (when (map? (:machine-agents opts))
                        (into {}
                              (map (fn [[k v]] [(normalize-machine-id k) (long v)]))
                              (:machine-agents opts)))
         n-machines (long (or (:machines opts)
                              (when explicit-ids (count explicit-ids))
                              (when peer-keys (count peer-keys))
                              (when (seq override-map) (count override-map))
                              2))
         default-m (long (or (:agents-per-machine opts) 2))
         machine-ids (cond
                       (seq explicit-ids) explicit-ids
                       (and (= n-machines 2) (empty? peer-keys) (empty? override-map)) [:nexus :vector]
                       (seq peer-keys) peer-keys
                       (seq override-map) (vec (keys override-map))
                       :else (mapv #(keyword (str "m" %)) (range n-machines)))
         subnet-id (str (or (:subnet-id opts) DEFAULT-CLUSTER-SUBNET-ID))
         machines-by-id
         (into {}
               (map-indexed
                (fn [m-idx mid]
                  (let [m-count (long (or (get override-map mid) default-m))
                        aids (mapv #(agent-id-for mid %) (range m-count))
                        endpoint (get-in opts [:peers mid])]
                    [mid {:machine-id mid
                          :machine-index m-idx
                          :agent-count m-count
                          :agent-ids aids
                          :endpoint endpoint}])))
               machine-ids)
         ;; Interleave turn order so Turn 1 is cross-machine (m0/agent-0 -> m1/agent-0),
         ;; Turn 2 is intra-machine on m1 (m1/agent-0 -> m1/agent-1),
         ;; Turn 3 is cross-machine (m1/agent-1 -> m0/agent-1),
         ;; Turn 4 is intra-machine on m0 (m0/agent-1 -> m0/agent-0), etc.
         ;; General formula for ordering all agents across N machines to alternate cross-machine & intra-machine legs:
         ;; Wait: let's construct the canonical ring order of all agents!
         ;; For 2x2 (:nexus, :vector):
         ;; [:nexus/agent-0, :vector/agent-0, :vector/agent-1, :nexus/agent-1]
         ;; In general across N machines, we snake through slot blocks or machine blocks so we alternate cross-machine and intra-machine hops!
         all-agents
         (vec
          (if (and (>= (count machine-ids) 2)
                   (some #(>= (:agent-count (get machines-by-id %)) 2) machine-ids))
            ;; Snake ordering:
            ;; Machine 0 slot 0 -> Machine 1 slot 0..M_1-1 -> Machine 2 slot 0..M_2-1 ... -> Machine 0 slot 1..M_0-1
            (let [m0 (first machine-ids)
                  m0-agents (get-in machines-by-id [m0 :agent-ids])
                  rest-machines (rest machine-ids)
                  rest-agents (mapcat #(get-in machines-by-id [% :agent-ids]) rest-machines)]
              (vec (concat [(first m0-agents)]
                           rest-agents
                           (rest m0-agents))))
            (mapcat #(get-in machines-by-id [% :agent-ids]) machine-ids)))
         total-agents (count all-agents)
         num-turns (long (or (:turns opts) total-agents))
         ring-schedule
         (mapv (fn [turn-idx]
                 (let [seq-num (inc turn-idx)
                       actor-id (nth all-agents (mod turn-idx total-agents))
                       target-id (nth all-agents (mod (inc turn-idx) total-agents))
                       from-machine (machine-of-agent actor-id)
                       to-machine (machine-of-agent target-id)
                       leg-type (if (= from-machine to-machine)
                                  :intra-machine
                                  :cross-machine-p2p)]
                   {:turn/seq seq-num
                    :actor-id actor-id
                    :target-id target-id
                    :from-machine from-machine
                    :to-machine to-machine
                    :leg-type leg-type
                    :poll-inbox? (> seq-num 1)}))
               (range num-turns))]
     {:subnet-id subnet-id
      :machine-count (count machine-ids)
      :machine-ids machine-ids
      :machines machines-by-id
      :all-agents all-agents
      :total-agents total-agents
      :turn-count num-turns
      :ring-schedule ring-schedule})))

;; =============================================================================
;; 2. Deterministic Cluster Identity & Home-Node State Initialization
;; =============================================================================

(defn- deterministic-agent-keypair
  "Derives a deterministic Ed25519 keypair for `agent-id` from `seed` so all `N` machines
   agree on every agent's public key and the scenario trace authority keypair."
  [seed agent-id]
  (let [agent-str (agent-display-name agent-id)
        h (reduce (fn [acc ch]
                    (unchecked-add (unchecked-multiply 31 (long acc)) (long (int ch))))
                  0
                  agent-str)
        rng (crypto/make-random (unchecked-add (long seed) (unchecked-multiply 100003 (long h))))]
    (crypto/generate-keypair rng)))

(defn- deterministic-session-id
  "Computes a deterministic 64-hex Bitecho subnet session ID for `subnet-id` and `seed`."
  [subnet-id seed]
  (data/compute-cid (fressian/utf8->bytes (str "hive-cluster-session:" subnet-id ":" seed))))

(defn init-machine-home-node
  "Initializes a single machine's home-node state for `machine-id` within `topology`:
   - Loads Gemma 4 E2B weights ONCE in VRAM (`weight-allocations = 1`) via `kv/init-shared-kv-multiplexer`
   - Allocates `M_i` isolated KV cache arenas for `machine-id`'s local agents
   - Binds `M_i` declarative `mealy` agent graphs (`:compute/einsum` + `:net/bitecho`)
   - Initializes a Rule 4/5 shell connection state map for each remote peer machine."
  [topology machine-id opts]
  (let [seed (long (or (:seed opts) 20261118))
        subnet-id (:subnet-id topology)
        session-id (deterministic-session-id subnet-id seed)
        m-spec (get-in topology [:machines machine-id])
        _ (when-not m-spec
            (throw (ex-info "Unknown machine-id in topology"
                            {:machine-id machine-id
                             :known-machines (:machine-ids topology)})))
        local-agents (:agent-ids m-spec)
        kv-mux (kv/init-shared-kv-multiplexer
                (assoc opts
                       :agent-ids local-agents
                       :seed seed))
        kv-mux-atom (atom kv-mux)
        agent-identities
        (into {}
              (map (fn [aid]
                     (let [kp (deterministic-agent-keypair seed aid)]
                       [aid {:agent-id aid
                             :keypair kp
                             :pubkey (str/lower-case (basalt/bytes->hex (:public kp)))}])))
              (:all-agents topology))
        ;; Local inbox queues + wire logs + replicated turn/KV blobs on this home node
        home-net-atom
        (atom {:machine-id machine-id
               :subnet-id subnet-id
               :session-id session-id
               :inboxes (into {} (map (fn [aid] [aid []]) local-agents))
               :wire-log []
               :replicated-turns []
               :replicated-kv-blobs {}
               :peer-conns (into {}
                                 (keep (fn [peer-mid]
                                         (when (not= peer-mid machine-id)
                                           (let [init-c (browser/initial-conn-state
                                                         {:peer-id (name peer-mid)
                                                          :subnet-id subnet-id})
                                                 [c1 _] (browser/transition-conn
                                                         init-c
                                                         {:type :dial :peer-id (name peer-mid) :subnet-id subnet-id})
                                                 [c2 _] (browser/transition-conn
                                                         c1
                                                         {:type :open :peer-id (name peer-mid)})]
                                             [peer-mid c2]))))
                                 (:machine-ids topology))})
        outbox-atom (atom [])
        make-local-mealy-agent
        (fn [aid]
          (let [compute-fn (fn cfn
                             ([prompt] (cfn nil prompt {}))
                             ([_sess prompt] (cfn nil prompt {}))
                             ([_sess prompt eff-opts]
                              (let [res-box (atom nil)]
                                (swap! kv-mux-atom
                                       (fn [m]
                                         (let [[m' turn-res] (kv/generate-agent-turn!
                                                              m aid prompt
                                                              (assoc (or eff-opts {}) :seed seed))]
                                           (reset! res-box (dissoc turn-res :kv-entry))
                                           m')))
                                @res-box)))
                net-handler (fn [cmd-map]
                              (let [cmd (or (get cmd-map "cmd") (some-> (:cmd cmd-map) name))]
                                (case cmd
                                  "inbox"
                                  (let [msgs-box (atom [])]
                                    (swap! home-net-atom
                                           (fn [st]
                                             (let [msgs (get-in st [:inboxes aid] [])]
                                               (reset! msgs-box msgs)
                                               (if (false? (get cmd-map "peek" false))
                                                 (assoc-in st [:inboxes aid] [])
                                                 st))))
                                    {"ok" true
                                     "messages" @msgs-box})

                                  "send"
                                  (let [target-str (str (or (get cmd-map "peer") (:peer cmd-map)))
                                        target-kw (if (str/includes? target-str "/")
                                                    (let [[ns-part nm-part] (str/split target-str #"/" 2)]
                                                      (keyword ns-part nm-part))
                                                    (keyword target-str))
                                        text (str (or (get cmd-map "message") (:message cmd-map) ""))
                                        target-machine (machine-of-agent target-kw)
                                        leg-type (if (= target-machine machine-id)
                                                   :intra-machine
                                                   :cross-machine-p2p)
                                        wire-env {:hive/version 1
                                                  :hive/subnet-id subnet-id
                                                  :hive/session-id session-id
                                                  :hive/from-agent aid
                                                  :hive/to-agent target-kw
                                                  :hive/from-machine machine-id
                                                  :hive/to-machine target-machine
                                                  :hive/leg-type leg-type
                                                  :hive/text text}
                                        wire-bytes (fressian/encode-canonical wire-env)
                                        wire-cid (data/compute-cid wire-bytes)
                                        msg-entry {"from" (agent-display-name aid)
                                                   "to" (agent-display-name target-kw)
                                                   "text" text
                                                   "wire_cid" wire-cid
                                                   "leg_type" (name leg-type)}]
                                    (swap! home-net-atom
                                           (fn [st]
                                             (cond-> (update st :wire-log conj (assoc wire-env :wire/cid wire-cid))
                                               (= target-machine machine-id)
                                               (update-in [:inboxes target-kw] (fnil conj []) msg-entry))))
                                    (when (not= target-machine machine-id)
                                      (swap! outbox-atom conj {:wire-cid wire-cid
                                                               :wire-envelope wire-env
                                                               :wire-bytes wire-bytes
                                                               :msg-entry msg-entry
                                                               :to-machine target-machine
                                                               :to-agent target-kw}))
                                    {"ok" true
                                     "session_id" session-id
                                     "leg_type" (name leg-type)
                                     "wire_envelope_cids" [wire-cid]})

                                  {"ok" true})))
                compute-cap {:id (keyword (str "einsum-" (namespace aid) "-" (name aid)))
                             :type :compute/einsum
                             :backend (or (:backend kv-mux) :uop-rocm)
                             :generate-fn compute-fn}
                net-cap {:id (keyword (str "bitecho-" (namespace aid) "-" (name aid)))
                         :type :net/bitecho
                         :min-balance 1000
                         :balance 100000
                         :in-memory-handler net-handler}
                graph-ast (stack/build-hive-mealy-agent-graph aid compute-cap net-cap)
                device-ast [:device {:id (keyword (str "device-" (namespace aid) "-" (name aid)))}
                            [:cap compute-cap]
                            [:cap net-cap]]]
            {:agent-id aid
             :graph-ast graph-ast
             :device-ast device-ast
             :runtime (binder/bind-agent graph-ast device-ast)}))]
    {:machine-id machine-id
     :seed seed
     :subnet-id subnet-id
     :session-id session-id
     :topology topology
     :local-agents local-agents
     :agent-identities agent-identities
     :kv-mux-atom kv-mux-atom
     :home-net-atom home-net-atom
     :outbox-atom outbox-atom
     :mealy-agents (into {} (map (fn [aid] [aid (make-local-mealy-agent aid)]) local-agents))}))

(defn close-machine-home-node!
  "Releases VRAM weights and child KV arenas for a machine home node."
  [home-node]
  (when-let [mux-atom (:kv-mux-atom home-node)]
    (kv/close-shared-kv-multiplexer! @mux-atom))
  nil)

(defn execute-local-scheduled-turn!
  "Executes a scheduled turn on its local home node, records the turn & KV-log Blob,
   and returns `[updated-home-node turn-packet]` ready for cluster replication."
  [home-node sched-step]
  (let [turn-seq (:turn/seq sched-step)
        actor-id (:actor-id sched-step)
        target-id (:target-id sched-step)
        leg-type (:leg-type sched-step)
        poll? (boolean (:poll-inbox? sched-step))
        actor-str (agent-display-name actor-id)
        target-str (agent-display-name target-id)
        prompt (if poll?
                 (format "You are %s. Greet %s in one short sentence." actor-str target-str)
                 (format "You are %s on machine %s. Say hello to %s in one short sentence."
                         actor-str (name (:machine-id home-node)) target-str))
        reply-template (str "Peer message received: \"%s\"\nYou are " actor-str
                            ". Reply and pass the greeting to " target-str " in one short sentence.")
        _ (reset! (:outbox-atom home-node) [])
        [home-node' turn-summary] (stack/step-agent-turn!
                                   home-node
                                   actor-id
                                   {:peer target-str
                                    :poll-inbox? poll?
                                    :prompt prompt
                                    :reply-prompt-template reply-template})
        kv-mux @(:kv-mux-atom home-node')
        kv-cid (:kv-log-cid turn-summary)
        kv-blob-res (data/get-blob (:blob-store kv-mux) kv-cid)
        kv-blob (:blob kv-blob-res)
        actor-pub (get-in home-node' [:agent-identities actor-id :pubkey])
        wire-cids (vec (or (get-in turn-summary [:send-receipt "wire_envelope_cids"])
                           (get-in turn-summary [:send-receipt :wire_envelope_cids])
                           []))
        outbox-msgs @(:outbox-atom home-node')
        turn-record {:seq turn-seq
                     :actor-id actor-id
                     :actor-pubkey actor-pub
                     :kv-log-cid kv-cid
                     :prompt-tokens (:prompt-ids turn-summary)
                     :output-tokens (:new-token-ids turn-summary)
                     :output-text (:output-text turn-summary)
                     :mealy-event-root (:mealy-event-root turn-summary)
                     :bitecho-session-id (:session-id home-node')
                     :wire-envelope-cids wire-cids}
        turn-packet {:packet/type :hive-cluster-turn
                     :turn/seq turn-seq
                     :turn/from-machine (:from-machine sched-step)
                     :turn/to-machine (:to-machine sched-step)
                     :turn/actor-id actor-id
                     :turn/target-id target-id
                     :turn/leg-type leg-type
                     :turn/record turn-record
                     :turn/telemetry (select-keys turn-summary
                                                  [:prefill-ms :decode-ms :decode-tok-s :decode-ms-tok
                                                   :total-ms :prompt-tokens :new-tokens :received-text :output-text])
                     :turn/kv-blob-bytes (:blob/bytes kv-blob)
                     :turn/kv-blob-cid kv-cid
                     :turn/outbox-messages outbox-msgs}]
    ;; Record into our own home-net-atom as well
    (swap! (:home-net-atom home-node')
           (fn [st]
             (-> st
                 (update :replicated-turns conj turn-packet)
                 (assoc-in [:replicated-kv-blobs kv-cid] kv-blob))))
    [home-node' turn-packet]))

(defn apply-replicated-turn-packet!
  "Applies an incoming replicated `turn-packet` from a remote machine to `home-node`:
   - Stores the remote turn's KV-log `Blob` into `(:blob-store @(:kv-mux-atom home-node))`
     so `trace/verify-episode-trace` can verify every turn's KV-log Blob locally on every machine.
   - Delivers any cross-machine P2P message addressed to a local agent on `home-node` into its inbox.
   - Appends the turn packet to `:replicated-turns`."
  [home-node turn-packet]
  (let [kv-bytes (:turn/kv-blob-bytes turn-packet)
        kv-blob (data/make-blob kv-bytes kv/KV-BLOB-MEDIA-TYPE)
        _ (swap! (:kv-mux-atom home-node)
                 (fn [m]
                   (let [put-res (data/put-blob (:blob-store m) kv-blob)]
                     (if (:ok? put-res)
                       (assoc m :blob-store (:store put-res))
                       m))))
        my-mid (:machine-id home-node)]
    (swap! (:home-net-atom home-node)
           (fn [st]
             (let [st1 (-> st
                           (update :replicated-turns conj turn-packet)
                           (assoc-in [:replicated-kv-blobs (:blob/cid kv-blob)] kv-blob))]
               (reduce (fn [acc out-msg]
                         (if (= my-mid (:to-machine out-msg))
                           (-> acc
                               (update :wire-log conj (assoc (:wire-envelope out-msg)
                                                             :wire/cid (:wire-cid out-msg)))
                               (update-in [:inboxes (:to-agent out-msg)]
                                          (fnil conj [])
                                          (:msg-entry out-msg)))
                           acc))
                       st1
                       (:turn/outbox-messages turn-packet)))))
    home-node))

(defn finalize-machine-episode-trace
  "Builds and verifies the deterministic `BIP-005` `Blob` + `Pointer` + RFC 6962 Merkle
   Episode Trace on `home-node` from all replicated turns (`1..K`)."
  [home-node]
  (let [topology (:topology home-node)
        seed (:seed home-node)
        first-agent (first (:all-agents topology))
        auth-kp (get-in home-node [:agent-identities first-agent :keypair])
        kv-mux @(:kv-mux-atom home-node)
        home-net @(:home-net-atom home-node)
        ordered-packets (vec (sort-by :turn/seq (:replicated-turns home-net)))
        ordered-turns (mapv :turn/record ordered-packets)
        scen-id (format "cluster-%dx%d-gemma4-e2b"
                        (:machine-count topology)
                        (:total-agents topology))
        ep-trace (trace/build-episode-trace
                  {:scenario-id scen-id
                   :run-id (str "seed-" seed)
                   :seed seed
                   :authority-keypair auth-kp
                   :kv-blob-store (:blob-store kv-mux)
                   :kv-logs (:kv-logs kv-mux)
                   :turns ordered-turns})
        trace-ver (trace/verify-episode-trace ep-trace)
        vram-stats (kv/multiplexer-stats kv-mux)
        local-kv-checks (into {}
                              (map (fn [aid] [aid (kv/verify-agent-kv-chain kv-mux aid)]))
                              (:local-agents home-node))
        local-replay-checks (into {}
                                  (map (fn [aid] [aid (stack/verify-mealy-agent-replay home-node aid)]))
                                  (:local-agents home-node))
        all-ok? (and (:ok? trace-ver)
                     (= 1 (:weight-allocations vram-stats))
                     (= (count (:local-agents home-node)) (:kv-cache-count vram-stats))
                     (:shared-device-weights? vram-stats)
                     (:isolated-kv-atoms? vram-stats)
                     (every? :ok? (vals local-kv-checks))
                     (every? :ok? (vals local-replay-checks)))]
    {:ok? all-ok?
     :machine-id (:machine-id home-node)
     :subnet-id (:subnet-id home-node)
     :bitecho-session-id (:session-id home-node)
     :backend (:backend kv-mux)
     :uop-target (:uop-target kv-mux)
     :platform (get-in kv-mux [:base-session :ctx :platform])
     :model-dir (:model-dir kv-mux)
     :precision (:precision kv-mux)
     :agent-count (count (:local-agents home-node))
     :local-agents (:local-agents home-node)
     :vram-proof vram-stats
     :kv-chain-checks local-kv-checks
     :mealy-replay-checks local-replay-checks
     :episode-merkle-root (:merkle-root ep-trace)
     :subnet-merkle-root (:subnet-merkle-root ep-trace)
     :trace-verification trace-ver
     :episode-trace ep-trace
     :turns-telemetry (mapv (fn [pkt]
                              (merge {:turn/seq (:turn/seq pkt)
                                      :actor-id (:turn/actor-id pkt)
                                      :target-id (:turn/target-id pkt)
                                      :from-machine (:turn/from-machine pkt)
                                      :to-machine (:turn/to-machine pkt)
                                      :leg-type (:turn/leg-type pkt)
                                      :kv-log-cid (:turn/kv-blob-cid pkt)}
                                     (:turn/telemetry pkt)))
                            ordered-packets)}))

;; =============================================================================
;; 3. In-Memory N x M Multi-Machine Simulation Runner (Sans-IO / VOPR / test.check)
;; =============================================================================

(defn run-in-memory-cluster-scenario
  "Runs an entire `N` machines x `M_i` agents-per-machine Hive swarm in-memory:
   - Instantiates `N` isolated machine home-nodes (each with `weight-allocations = 1` and `M_i` KV caches).
   - Steps through the topology's `ring-schedule` (both `:cross-machine-p2p` and `:intra-machine` legs).
   - Replicates each turn's wire envelope and KV-log Blob across all `N` machine home-nodes.
   - Verifies that all `N` machines independently compute the EXACT same RFC 6962 Episode Merkle Root."
  ([] (run-in-memory-cluster-scenario {}))
  ([opts]
   (let [topology (build-cluster-topology opts)
         machine-ids (:machine-ids topology)
         home-nodes0 (into {}
                           (map (fn [mid]
                                  [mid (init-machine-home-node topology mid opts)]))
                           machine-ids)]
     (try
       (let [final-nodes
             (reduce (fn [nodes sched-step]
                       (let [active-mid (:from-machine sched-step)
                             active-node (get nodes active-mid)
                             [active-node' turn-pkt] (execute-local-scheduled-turn! active-node sched-step)]
                         (into {}
                               (map (fn [[mid node]]
                                      (if (= mid active-mid)
                                        [mid active-node']
                                        [mid (apply-replicated-turn-packet! node turn-pkt)])))
                               nodes)))
                     home-nodes0
                     (:ring-schedule topology))
             machines-summary
             (into {}
                   (map (fn [[mid node]]
                          [mid (finalize-machine-episode-trace node)]))
                   final-nodes)
             merkle-roots (mapv :episode-merkle-root (vals machines-summary))
             converged? (and (seq merkle-roots) (= 1 (count (distinct merkle-roots))))
             first-summary (get machines-summary (first machine-ids))
             all-ok? (and converged?
                          (every? :ok? (vals machines-summary)))]
         {:ok? all-ok?
          :scenario :cluster-hello
          :topology topology
          :converged? converged?
          :converged-merkle-root (first merkle-roots)
          :converged-subnet-merkle-root (:subnet-merkle-root first-summary)
          :machines-summary machines-summary
          :turns-telemetry (:turns-telemetry first-summary)})
       (finally
         (doseq [[_ node] home-nodes0]
           (close-machine-home-node! node)))))))

;; =============================================================================
;; 4. Live Distributed Cross-Machine LAN P2P Runner (RFC 4571 + Fressian over TCP)
;; =============================================================================

(defn- send-fressian-frame!
  "Sends a single RFC 4571 length-prefixed Fressian frame over `Socket`."
  [^Socket sock payload-map]
  (let [raw-bytes (fressian/encode-canonical payload-map)
        out (DataOutputStream. (.getOutputStream sock))]
    (.writeInt out (alength ^bytes raw-bytes))
    (.write out ^bytes raw-bytes)
    (.flush out)))

(defn- recv-fressian-frame!
  "Reads a single length-prefixed Fressian frame from `Socket`."
  [^Socket sock]
  (let [in (DataInputStream. (.getInputStream sock))
        len (.readInt in)]
    (when (or (neg? len) (> len (* 64 1024 1024)))
      (throw (ex-info "Invalid Fressian frame length on P2P socket" {:length len})))
    (let [buf (byte-array len)]
      (.readFully in buf)
      (fressian/decode-wire buf))))

(defn- send-packet-to-peer-with-retry!
  "Connects to `peer-endpoint` (`{:host ... :port ...}`) with retry backoff (up to `timeout-ms`),
   transmits `packet-map`, and waits for `{:ok true}` ACK."
  [peer-mid {:keys [host port]} packet-map timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long (or timeout-ms 180000)))]
    (loop [attempt 1]
      (let [res (try
                  (with-open [sock (Socket.)]
                    (.connect sock (InetSocketAddress. (str host) (int port)) 3000)
                    (.setSoTimeout sock 30000)
                    (send-fressian-frame! sock packet-map)
                    (let [ack (recv-fressian-frame! sock)]
                      {:ok? true :ack ack}))
                  (catch Exception e
                    {:ok? false :error (.getMessage e)}))]
        (if (:ok? res)
          (:ack res)
          (if (< (System/currentTimeMillis) deadline)
            (do
              (Thread/sleep (long (min 1000 (* 150 attempt))))
              (recur (inc attempt)))
            (throw (ex-info (str "Timed out sending P2P packet to peer " (name peer-mid)
                                 " at " host ":" port)
                            {:peer peer-mid
                             :host host
                             :port port
                             :packet-type (:packet/type packet-map)
                             :last-error (:error res)}))))))))

(defn run-live-machine-cluster-node!
  "Runs a live multi-machine Hive node for `(:machine-id opts)` binding a TCP server on
   `(:bind-host opts):(:bind-port opts)` (default port `5610`), synchronizing with all remote peers
   in `(:peers opts)` via an explicit ready barrier after loading local Gemma 4 E2B weights into VRAM,
   stepping through the `N x M` ring schedule across intra-machine and cross-machine P2P legs,
   and verifying RFC 6962 Episode Merkle Root convergence across all `N` machines."
  [opts]
  (let [topology (build-cluster-topology opts)
        my-mid (normalize-machine-id (:machine-id opts))
        bind-host (str (or (:bind-host opts)
                           (get-in topology [:machines my-mid :endpoint :host])
                           "0.0.0.0"))
        bind-port (int (or (:bind-port opts)
                           (get-in topology [:machines my-mid :endpoint :port])
                           5610))
        timeout-ms (long (or (:timeout-ms opts) 240000))
        remote-peers (into {}
                           (keep (fn [[mid m-info]]
                                   (when (not= mid my-mid)
                                     [mid (or (:endpoint m-info)
                                              (get-in opts [:peers mid]))])))
                           (:machines topology))
        ready-peers-atom (atom #{my-mid})
        turn-inbox-atom (atom {})
        merkle-peers-atom (atom {})
        server-sock (ServerSocket.)]
    (.setReuseAddress server-sock true)
    (.bind server-sock (InetSocketAddress. bind-host bind-port))
    (let [running-atom (atom true)
          accept-Future
          (future
            (while @running-atom
              (try
                (let [client (.accept server-sock)]
                  (future
                    (try
                      (with-open [c client]
                        (.setSoTimeout c 30000)
                        (let [pkt (recv-fressian-frame! c)
                              ptype (:packet/type pkt)]
                          (case ptype
                            :hive-cluster-ready
                            (do
                              (swap! ready-peers-atom conj (normalize-machine-id (:machine-id pkt)))
                              (send-fressian-frame! c {:ok true :machine-id my-mid :event :ready-ack}))

                            :hive-cluster-turn
                            (do
                              (swap! turn-inbox-atom assoc (long (:turn/seq pkt)) pkt)
                              (send-fressian-frame! c {:ok true :machine-id my-mid :turn/seq (:turn/seq pkt)}))

                            :hive-cluster-merkle
                            (do
                              (swap! merkle-peers-atom assoc (normalize-machine-id (:machine-id pkt)) pkt)
                              (send-fressian-frame! c {:ok true :machine-id my-mid :event :merkle-ack}))

                            (send-fressian-frame! c {:ok false :error :unknown-packet-type}))))
                      (catch EOFException _)
                      (catch Exception _))))
                (catch Exception _))))
          home-node0 (init-machine-home-node topology my-mid opts)]
      (try
        ;; 1. Broadcast :hive-cluster-ready to all remote peers once VRAM weights & KV arenas are initialized
        (doseq [[peer-mid endpoint] remote-peers]
          (when-not (and (:host endpoint) (:port endpoint))
            (throw (ex-info "Missing host:port endpoint for remote peer machine"
                            {:peer-machine peer-mid :peers (:peers opts)})))
          (send-packet-to-peer-with-retry!
           peer-mid endpoint
           {:packet/type :hive-cluster-ready
            :machine-id my-mid
            :subnet-id (:subnet-id topology)}
           timeout-ms))
        ;; Wait until all N machines have signalled ready
        (let [deadline (+ (System/currentTimeMillis) timeout-ms)
              all-mids (set (:machine-ids topology))]
          (loop []
            (when (not= all-mids @ready-peers-atom)
              (when (>= (System/currentTimeMillis) deadline)
                (throw (ex-info "Timed out waiting for all cluster peer machines to reach ready barrier"
                                {:expected all-mids :ready @ready-peers-atom})))
              (Thread/sleep 100)
              (recur))))
        ;; 2. Execute the N x M ring schedule turn by turn (1..K)
        (let [final-home-node
              (reduce (fn [hnode sched-step]
                        (let [t-seq (long (:turn/seq sched-step))
                              active-mid (:from-machine sched-step)]
                          (if (= active-mid my-mid)
                            (let [[hnode' turn-pkt] (execute-local-scheduled-turn! hnode sched-step)]
                              (doseq [[peer-mid endpoint] remote-peers]
                                (send-packet-to-peer-with-retry! peer-mid endpoint turn-pkt timeout-ms))
                              hnode')
                            ;; Wait for remote active-mid to broadcast turn-pkt for t-seq
                            (let [deadline (+ (System/currentTimeMillis) timeout-ms)
                                  turn-pkt (loop []
                                             (if-let [pkt (get @turn-inbox-atom t-seq)]
                                               pkt
                                               (do
                                                 (when (>= (System/currentTimeMillis) deadline)
                                                   (throw (ex-info "Timed out waiting for remote turn packet"
                                                                   {:turn/seq t-seq
                                                                    :from-machine active-mid
                                                                    :local-machine my-mid})))
                                                 (Thread/sleep 50)
                                                 (recur))))]
                              (apply-replicated-turn-packet! hnode turn-pkt)))))
                      home-node0
                      (:ring-schedule topology))
              local-summary (finalize-machine-episode-trace final-home-node)
              my-merkle (:episode-merkle-root local-summary)
              merkle-pkt {:packet/type :hive-cluster-merkle
                          :machine-id my-mid
                          :episode-merkle-root my-merkle
                          :subnet-merkle-root (:subnet-merkle-root local-summary)
                          :backend (:backend local-summary)
                          :uop-target (:uop-target local-summary)
                          :vram-proof (:vram-proof local-summary)}]
          (swap! merkle-peers-atom assoc my-mid merkle-pkt)
          ;; 3. Exchange final Episode Merkle Roots across all peers to prove convergence
          (doseq [[peer-mid endpoint] remote-peers]
            (send-packet-to-peer-with-retry! peer-mid endpoint merkle-pkt timeout-ms))
          (let [deadline (+ (System/currentTimeMillis) timeout-ms)
                all-mids (set (:machine-ids topology))]
            (loop []
              (when (not= all-mids (set (keys @merkle-peers-atom)))
                (when (>= (System/currentTimeMillis) deadline)
                  (throw (ex-info "Timed out waiting for peer Merkle root confirmations"
                                  {:expected all-mids :received (keys @merkle-peers-atom)})))
                (Thread/sleep 50)
                (recur))))
          ;; Grace sleep so remote peers finish reading their last ACK before socket close
          (Thread/sleep 300)
          (let [peer-merkle-map @merkle-peers-atom
                all-roots (mapv :episode-merkle-root (vals peer-merkle-map))
                converged? (and (seq all-roots) (= 1 (count (distinct all-roots))))]
            (assoc local-summary
                   :ok? (and (:ok? local-summary) converged?)
                   :scenario :cluster-hello
                   :topology topology
                   :bind-host bind-host
                   :bind-port bind-port
                   :converged? converged?
                   :converged-merkle-root my-merkle
                   :peer-merkle-proofs peer-merkle-map)))
        (finally
          (reset! running-atom false)
          (try (.close server-sock) (catch Exception _))
          (future-cancel accept-Future)
          (close-machine-home-node! home-node0))))))
