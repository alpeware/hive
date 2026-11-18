(ns bitecho.hive.stack
  "Full In-Memory `clj-datachannel` / `bitecho` / `mealy` / `clj-einsum` Stack Wiring (`bitecho.hive.stack`).
   Connects Agent Alpha and Agent Beta over:
   1. `clj-datachannel` (RFC 4571 stream framing + SCTP DATA packet handling)
   2. `bitecho` (`bitecho.sim.vopr` virtual-time byte link router + `bitecho.light-node.core` E2EE X25519 sessions + Rule 4/5 `bitecho.shell.browser` lifecycle)
   3. `mealy` (`mealy.graph.binder` + `mealy.graph.runtime` declarative agent graphs with `:compute/einsum` and `:net/bitecho` capabilities)
   4. `clj-einsum` (`bitecho.hive.kv` shared-VRAM Gemma 4 E2B weights multiplexer with isolated per-agent KV caches)."
  (:require [bitecho.basalt.core :as basalt]
            [bitecho.core.data :as data]
            [bitecho.crypto :as crypto]
            [bitecho.crypto.sealed :as sealed]
            [bitecho.fressian :as fressian]
            [bitecho.hive.kv :as kv]
            [bitecho.light-node.core :as light-node]
            [bitecho.shell.browser :as browser]
            [bitecho.sim.vopr :as vopr]
            [clojure.string :as str]
            [datachannel.buffer :as dc-buf]
            [datachannel.core :as dc]
            [datachannel.dtls :as dtls]
            [datachannel.tcp :as dtcp]
            [mealy.graph.binder :as binder]
            [mealy.graph.runtime :as runtime]))

(def ^:const DEFAULT-SUBNET-ID
  "Canonical Bitecho subnet for in-memory Hive stack sessions."
  "devnet.bitecho.org")

;; =============================================================================
;; 1. Rule 4 & Rule 5 Shell Connection Lifecycle Boundary
;; =============================================================================

(defn transition-shell-conn
  "Pure Rule 4/5 connection lifecycle reducer delegating to `bitecho.shell.browser/transition-conn`
   with normalization for `:error` -> `:dial-failed` and `:drop-envelope` -> `:send` when not open."
  [conn event]
  (let [norm-event (case (:type event)
                     :error (assoc event :type :dial-failed :error (str (or (:reason event) (:error event) "error")))
                     :drop-envelope {:type :send :envelope {:payload "dropped"} :generation (:generation event)}
                     event)]
    (browser/transition-conn conn norm-event)))

(defn simulate-shell-lifecycle-commands
  "Executes a sequence of model-based lifecycle commands against a Rule 4 single-authority
   connection state map, verifying Rule 4 generation fencing and Rule 5 zero silent failures."
  [commands]
  (let [init-conn (browser/initial-conn-state {:peer-id "node1" :subnet-id DEFAULT-SUBNET-ID})
        {:keys [conn all-effects silent-failures]}
        (reduce (fn [{:keys [conn all-effects silent-failures]} cmd]
                  (let [curr-gen (:generation conn 0)
                        cmd-gen (:generation cmd)
                        stale? (and (some? cmd-gen) (< (long cmd-gen) (long curr-gen)))
                        fail-cmd? (or stale?
                                      (= :error (:type cmd))
                                      (= :dial-failed (:type cmd))
                                      (and (= :drop-envelope (:type cmd)) (not= :open (:phase conn))))
                        [next-conn effs] (transition-shell-conn conn cmd)
                        has-diag? (boolean (some #(= :diagnose (:type %)) effs))
                        silent? (and fail-cmd? (not has-diag?))]
                    {:conn next-conn
                     :all-effects (into all-effects effs)
                     :silent-failures (if silent? (inc silent-failures) silent-failures)}))
                {:conn init-conn
                 :all-effects []
                 :silent-failures 0}
                commands)
        diag (:diagnostics conn)]
    {:ok? (and (zero? silent-failures)
               (= (:phase conn) (:phase diag))
               (= (:generation conn) (:generation diag)))
     :zero-silent-failures? (zero? silent-failures)
     :final-conn conn
     :diagnostics diag
     :effect-count (count all-effects)}))

;; =============================================================================
;; 2. In-Memory clj-datachannel + VOPR + Bitecho Light-Node Wire Transport
;; =============================================================================

(defn- create-agent-light-node
  "Creates an isolated in-memory Bitecho light node + clj-datachannel WebRTC/SCTP endpoint."
  [agent-id seed validator-pubkey]
  (let [rng (crypto/make-random seed)
        kp (crypto/generate-keypair rng)
        pub-hex (str/lower-case (basalt/bytes->hex (:public kp)))
        priv-bytes (:private kp)
        agent-name (name agent-id)
        dtls-cert (dtls/generate-simulated-cert (dc-buf/make-rng seed))
        webrtc-conn (assoc (dc/create-connection {:simulated-dtls? true
                                                  :seed seed
                                                  :cert-data dtls-cert
                                                  :local-ver-tag (+ 10000 (mod (Math/abs (long seed)) 50000))
                                                  :remote-ver-tag (+ 60000 (mod (Math/abs (long seed)) 50000))
                                                  :now-ms 0}
                                                 (= agent-id :alpha))
                           :state :established)
        [conn-dialing _] (browser/transition-conn
                          (browser/initial-conn-state {:peer-id validator-pubkey
                                                       :subnet-id DEFAULT-SUBNET-ID})
                          {:type :dial :peer-id validator-pubkey :subnet-id DEFAULT-SUBNET-ID})
        [conn-open _] (browser/transition-conn conn-dialing {:type :open :peer-id validator-pubkey})
        init-st (light-node/init-light-state
                 {:pubkey pub-hex
                  :fingerprint (:fingerprint dtls-cert)
                  :ice-creds {:ufrag (str "ufrag-" agent-name)
                              :pwd (str "pwd-" agent-name "-secret")}}
                 {:subnet-id DEFAULT-SUBNET-ID
                  :network-uri DEFAULT-SUBNET-ID
                  :epoch 1
                  :validator-target validator-pubkey})
        synced-st (:state (light-node/handle-command
                           init-st
                           {"cmd" "sync-account"
                            "balance" 100000
                            "spendable" 100000
                            "seq" 1
                            "finalized_epoch" 1}
                           priv-bytes))]
    {:agent-id agent-id
     :petname agent-name
     :pubkey pub-hex
     :privkey priv-bytes
     :keypair kp
     :conn-state conn-open
     :webrtc-conn webrtc-conn
     :light-state synced-st}))

(defn- transmit-single-wire-envelope
  "Transmits a single Bitecho wire envelope from `from-id` to `to-id` across:
   1. Fressian wire serialization (`bitecho.fressian/encode-wire`) + SHA-256 CID calculation
   2. `clj-datachannel` RFC 4571 framing (`datachannel.tcp/encode-rfc4571-frame`)
   3. VOPR stream slicing & reassembly (`vopr/slice-stream-bytes`, `vopr/reassemble-rfc4571-stream`)
   4. `clj-datachannel` SCTP DATA packet processing (`datachannel.core/handle-sctp-packet`)
   5. VOPR discrete-event packet routing (`vopr/route-packet` + `vopr/run-simulation`)
   6. Target Bitecho light-node wire ingestion (`bitecho.light-node.core/handle-wire-payload`).
   Returns `[updated-net-state wire-receipt reply-envelopes]`."
  [net-state from-id to-id envelope]
  (let [world (:world net-state)
        raw-wire (fressian/encode-wire envelope)
        wire-cid (data/compute-cid raw-wire)
        rfc4571-frame (dtcp/encode-rfc4571-frame raw-wire)
        [world1 sliced-chunks] (vopr/slice-stream-bytes world rfc4571-frame)
        reassembled-frames (vopr/reassemble-rfc4571-stream sliced-chunks)
        clean-wire (or (first reassembled-frames) raw-wire)
        target-node (get-in net-state [:agents to-id])
        target-dc (:webrtc-conn target-node)
        tsn (inc (long (:next-tsn net-state 0)))
        sctp-pkt {:src-port 5000
                  :dst-port 5000
                  :verification-tag (or (:local-ver-tag target-dc) 12345)
                  :chunks [{:type :data
                            :tsn tsn
                            :stream-id 0
                            :ssn (dec tsn)
                            :payload clean-wire}]}
        dc-res (dc/handle-sctp-packet target-dc sctp-pkt (quot (long (:t-ns world1 0)) 1000000))
        target-dc' (or (:new-state dc-res) target-dc)
        ;; Also route through VOPR virtual-time link router between light1 and light2
        vopr-from (if (= from-id :alpha) "light1" "light2")
        vopr-to (if (= to-id :alpha) "light1" "light2")
        world2 (-> world1
                   (vopr/route-packet vopr-from vopr-to clean-wire {:tcp? true})
                   (vopr/run-simulation 8))
        wire-res (light-node/handle-wire-payload
                  (:light-state target-node)
                  clean-wire
                  (:privkey target-node))
        receipt {:wire/cid wire-cid
                 :wire/from from-id
                 :wire/to to-id
                 :wire/byte-count (alength ^bytes raw-wire)
                 :wire/rfc4571-chunks (count sliced-chunks)
                 :wire/sctp-tsn tsn
                 :wire/stdout-events (vec (:stdout-events wire-res))}
        net-state' (-> net-state
                       (assoc :world world2
                              :next-tsn tsn)
                       (assoc-in [:agents to-id :webrtc-conn] target-dc')
                       (assoc-in [:agents to-id :light-state] (:state wire-res))
                       (update :wire-log (fnil conj []) receipt))]
    [net-state' receipt (vec (:net-envelopes wire-res))]))

(defn execute-light-node-command
  "Executes a Bitecho light-node command map on `agent-id` and routes all emitted wire envelopes
   (plus any resulting `:session-ack` reply envelopes) across the in-memory `clj-datachannel` + VOPR mesh
   to the peer light node.
   Returns `[updated-net-state command-response routed-wire-cids]`."
  [net-state agent-id cmd-map]
  (let [peer-id (if (= agent-id :alpha) :beta :alpha)
        agent-node (get-in net-state [:agents agent-id])
        cmd-res (light-node/handle-command (:light-state agent-node) cmd-map (:privkey agent-node))
        net-state1 (assoc-in net-state [:agents agent-id :light-state] (:state cmd-res))
        out-envs (vec (:net-envelopes cmd-res))
        [net-state2 wire-cids]
        (reduce (fn [[st cids] env]
                  (let [[st1 receipt reply-envs] (transmit-single-wire-envelope st agent-id peer-id env)
                        cids1 (conj cids (:wire/cid receipt))]
                    ;; Route any immediate protocol reply envelopes (e.g. :session-ack) back to sender
                    (reduce (fn [[st-inner cids-inner] rep-env]
                              (let [[st-rep rep-receipt _] (transmit-single-wire-envelope st-inner peer-id agent-id rep-env)]
                                [st-rep (conj cids-inner (:wire/cid rep-receipt))]))
                            [st1 cids1]
                            reply-envs)))
                [net-state1 []]
                out-envs)]
    [net-state2
     (assoc (:response cmd-res) "wire_envelope_cids" wire-cids)
     wire-cids]))

(defn- establish-e2ee-p2p-session
  "Runs the full Bitecho E2EE X25519 session + P2P DataChannel handshake between `:alpha` and `:beta`
   over the in-memory `clj-datachannel` + VOPR network:
   1. Mutual petname registration (`alpha` <-> `beta`)
   2. `offer` (:alpha -> :beta)
   3. `accept` (:beta -> :alpha)
   4. `p2p-offer` (:alpha -> :beta)
   5. `p2p-answer` (:beta -> :alpha)
   6. `p2p-channel-state` (`connected: true`, `zero_burn: true`) on both sides."
  [net-state seed]
  (let [handshake-rng (crypto/make-random (+ (long (or seed 20261118)) 7777))
        det-random-bytes (fn [n]
                           (let [ba (byte-array (long n))]
                             (crypto/next-bytes handshake-rng ba)
                             ba))
        det-x25519-kp (fn []
                        (sealed/x25519-keypair-from-seed (det-random-bytes 32)))]
    (with-redefs [crypto/random-bytes det-random-bytes
                  sealed/generate-x25519-keypair det-x25519-kp]
      (let [alpha-pub (get-in net-state [:agents :alpha :pubkey])
            beta-pub (get-in net-state [:agents :beta :pubkey])
            [st1 _ _] (execute-light-node-command
                       net-state :alpha
                       {"cmd" "petname-set" "petname" "beta" "pubkey" beta-pub "overwrite" true})
            [st2 _ _] (execute-light-node-command
                       st1 :beta
                       {"cmd" "petname-set" "petname" "alpha" "pubkey" alpha-pub "overwrite" true})
            [st3 offer-resp offer-cids] (execute-light-node-command
                                         st2 :alpha
                                         {"cmd" "offer" "callee" "beta" "price" 0 "burn" 500})
            session-id (get offer-resp "session_id")
            [st4 accept-resp accept-cids] (execute-light-node-command
                                           st3 :beta
                                           {"cmd" "accept" "session_id" session-id})
            [st5 _ p2p-off-cids] (execute-light-node-command
                                  st4 :alpha
                                  {"cmd" "p2p-offer"
                                   "session_id" session-id
                                   "transport" "tcp"
                                   "candidates" ["tcp://127.0.0.1:20100"]
                                   "burn" 500})
            [st6 _ p2p-ans-cids] (execute-light-node-command
                                  st5 :beta
                                  {"cmd" "p2p-answer"
                                   "session_id" session-id
                                   "transport" "tcp"
                                   "candidates" ["tcp://127.0.0.1:20101"]
                                   "burn" 500})
            [st7 _ _] (execute-light-node-command
                       st6 :alpha
                       {"cmd" "p2p-channel-state"
                        "session_id" session-id
                        "connected" true
                        "transport" "tcp"})
            [st8 _ _] (execute-light-node-command
                       st7 :beta
                       {"cmd" "p2p-channel-state"
                        "session_id" session-id
                        "connected" true
                        "transport" "tcp"})]
        (assoc st8
               :session-id session-id
               :handshake-ok? (and (true? (get offer-resp "ok"))
                                   (true? (get accept-resp "ok")))
               :handshake-wire-cids (vec (concat offer-cids accept-cids p2p-off-cids p2p-ans-cids)))))))

;; =============================================================================
;; 3. Declarative Mealy Agent Graph (:compute/einsum + :net/bitecho)
;; =============================================================================

(defn hive-agent-mealy-step
  "Pure Mealy state reducer for a Hive agent cell orchestrating `:cap/compute` (`:compute/einsum`)
   and `:cap/net` (`:net/bitecho`) over a single turn:
   - `:turn-in`:
     - If `:poll-inbox?` is true, emits `{:inbox [{:peek false}]}` to `:cap/net` first.
     - Otherwise emits `{:generate [{:prompt (:prompt payload)}]}` directly to `:cap/compute`.
   - `:net-reply`:
     - When phase is `:awaiting-inbox`, extracts the incoming peer message text from `:messages`,
       synthesizes the reply prompt, and emits `{:generate [{:prompt reply-prompt}]}` to `:cap/compute`.
     - When phase is `:awaiting-send-ack`, records the Bitecho send receipt and emits the completed
       turn summary to `:turn-out`.
   - `:compute-reply`:
     - Records the generated text and token telemetry from `:cap/compute`, and emits
       `{:send [{:peer (:peer state) :message (:text payload)}]}` to `:cap/net`."
  [state in-port payload]
  (case in-port
    :turn-in
    (let [peer (str (or (:peer payload) "beta"))
          poll? (boolean (:poll-inbox? payload))
          prompt (str (or (:prompt payload) "Say hello in one short sentence."))
          reply-template (or (:reply-prompt-template payload)
                             "Peer said: \"%s\"\nReply with a friendly hello in one short sentence.")]
      (if poll?
        [(assoc state
                :phase :awaiting-inbox
                :peer peer
                :fallback-prompt prompt
                :reply-template reply-template)
         {:inbox [{:peek false}]}]
        [(assoc state
                :phase :awaiting-compute
                :peer peer
                :active-prompt prompt)
         {:generate [{:prompt prompt}]}]))

    :net-reply
    (case (:phase state)
      :awaiting-inbox
      (let [msgs (or (:messages payload) (get payload "messages") [])
            first-msg (first msgs)
            incoming-text (or (:text first-msg) (get first-msg "text")
                              (:message first-msg) (get first-msg "message")
                              "")
            synthesized-prompt (if (not (str/blank? incoming-text))
                                 (format (:reply-template state) incoming-text)
                                 (:fallback-prompt state))]
        [(assoc state
                :phase :awaiting-compute
                :inbox-messages msgs
                :received-text incoming-text
                :active-prompt synthesized-prompt)
         {:generate [{:prompt synthesized-prompt}]}])

      :awaiting-send-ack
      (let [gen-res (:last-compute state)
            summary {:agent-id (:agent-id state)
                     :peer (:peer state)
                     :received-text (:received-text state)
                     :prompt (:active-prompt state)
                     :output-text (:text gen-res)
                     :prompt-ids (:prompt-ids gen-res)
                     :new-token-ids (:new-token-ids gen-res)
                     :prompt-tokens (:prompt-tokens gen-res)
                     :new-tokens (:new-tokens gen-res)
                     :prefill-ms (:prefill-ms gen-res)
                     :decode-ms (:decode-ms gen-res)
                     :decode-tok-s (:decode-tok-s gen-res)
                     :decode-ms-tok (:decode-ms-tok gen-res)
                     :total-ms (:total-ms gen-res)
                     :kv-log-cid (:kv-log-cid gen-res)
                     :send-receipt payload}]
        [(-> state
             (assoc :phase :completed
                    :last-send-receipt payload)
             (update :completed-turns (fnil conj []) summary))
         {:turn-out [summary]}])

      [state {}])

    :compute-reply
    (let [text (str/trim (str (or (:text payload) "")))
          peer (:peer state)]
      [(assoc state
              :phase :awaiting-send-ack
              :last-compute (assoc payload :text text))
       {:send [{:peer peer
                :message text}]}])))

(defn build-hive-mealy-agent-graph
  "Constructs the declarative `[:agent ...]` Hiccup AST for a Hive agent binding
   `:cap/compute` (`:compute/einsum`) and `:cap/net` (`:net/bitecho`)."
  [agent-id compute-cap-spec net-cap-spec]
  (let [agent-kw (keyword (str "hive-agent-" (name agent-id)))]
    [:agent {:id agent-kw
             :ins [:turn-in]
             :outs [:turn-out]
             :requires {:compute compute-cap-spec
                        :net net-cap-spec}}
     [:mealy {:id :turn-cell
              :init-state {:agent-id agent-id
                           :phase :idle
                           :completed-turns []}
              :ins [:turn-in :compute-reply :net-reply]
              :outs [:generate :inbox :send :turn-out]
              :step hive-agent-mealy-step}]
     [:conn [:agent/in :turn-in] [:turn-cell :turn-in]]
     [:conn [:turn-cell :generate] [:cap/compute :generate]]
     [:conn [:cap/compute :reply] [:turn-cell :compute-reply]]
     [:conn [:turn-cell :inbox] [:cap/net :inbox]]
     [:conn [:turn-cell :send] [:cap/net :send]]
     [:conn [:cap/net :reply] [:turn-cell :net-reply]]
     [:conn [:turn-cell :turn-out] [:agent/out :turn-out]]]))

(defn init-in-memory-hive-stack
  "Initializes the complete in-memory `clj-datachannel` / `bitecho` / `mealy` / `clj-einsum` stack
   for `:alpha` and `:beta` sharing a single Gemma 4 E2B VRAM weight allocation."
  ([] (init-in-memory-hive-stack {}))
  ([opts]
   (let [seed (long (or (:seed opts) 20261118))
         kv-mux (kv/init-shared-kv-multiplexer (assoc opts :agent-ids [:alpha :beta] :seed seed))
         kv-mux-atom (atom kv-mux)
         ;; Initialize VOPR world with zero packet loss for deterministic E2E roundtrip
         reliable-link {:p-good->bad 0.0
                        :p-bad->good 1.0
                        :p-loss-good 0.0
                        :p-loss-bad 0.0
                        :bitflip-prob 0.0
                        :truncate-prob 0.0}
         world (vopr/create-initial-world seed {:num-validators 4
                                                :num-light-nodes 2
                                                :fault-config reliable-link})
         val-pub (get-in world [:nodes "node1" :pubkey])
         alpha-node (create-agent-light-node :alpha (+ seed 101) val-pub)
         beta-node (create-agent-light-node :beta (+ seed 202) val-pub)
         net-atom (atom (establish-e2ee-p2p-session
                         {:world world
                          :next-tsn 0
                          :wire-log []
                          :agents {:alpha alpha-node
                                   :beta beta-node}}
                         seed))
         make-agent-runtime
         (fn [aid]
           (let [compute-fn (fn cfn
                              ([prompt]
                               (cfn nil prompt {}))
                              ([_sess prompt]
                               (cfn nil prompt {}))
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
                               (let [resp-box (atom nil)]
                                 (swap! net-atom
                                        (fn [nst]
                                          (let [[nst' resp _] (execute-light-node-command nst aid cmd-map)]
                                            (reset! resp-box resp)
                                            nst')))
                                 @resp-box))
                 compute-cap {:id (keyword (str "einsum-" (name aid)))
                              :type :compute/einsum
                              :backend (or (:backend kv-mux) :uop-rocm)
                              :generate-fn compute-fn}
                 net-cap {:id (keyword (str "bitecho-" (name aid)))
                          :type :net/bitecho
                          :min-balance 1000
                          :balance 100000
                          :in-memory-handler net-handler}
                 graph-ast (build-hive-mealy-agent-graph aid compute-cap net-cap)
                 device-ast [:device {:id (keyword (str "device-" (name aid)))}
                             [:cap compute-cap]
                             [:cap net-cap]]]
             {:agent-id aid
              :graph-ast graph-ast
              :device-ast device-ast
              :runtime (binder/bind-agent graph-ast device-ast)}))]
     {:seed seed
      :kv-mux-atom kv-mux-atom
      :net-atom net-atom
      :mealy-agents {:alpha (make-agent-runtime :alpha)
                     :beta (make-agent-runtime :beta)}})))

(defn step-agent-turn!
  "Enqueues `turn-payload` into `agent-id`'s compiled `mealy` agent graph and runs it to quiescence.
   Returns `[updated-stack turn-summary]`."
  [stack agent-id turn-payload]
  (let [compiled (get-in stack [:mealy-agents agent-id])
        rt' (-> (:runtime compiled)
                (runtime/enqueue-input :turn-in turn-payload)
                runtime/run-graph!)
        turn-summary (last (get-in rt' [:outbox :turn-out]))
        last-evt (last (:event-log rt'))
        last-event-hash (or (:event/hash last-evt) (:hash last-evt))]
    [(assoc-in stack [:mealy-agents agent-id :runtime] rt')
     (assoc turn-summary :mealy-event-root last-event-hash)]))

(defn verify-mealy-agent-replay
  "Verifies that `agent-id`'s `mealy` graph has an intact SHA-256 event log chain
   and replays deterministically with zero capabilities via `mealy.graph.runtime/replay-graph`."
  [stack agent-id]
  (let [compiled (get-in stack [:mealy-agents agent-id])
        rt (:runtime compiled)
        graph-ast (:graph-ast compiled)
        event-log (:event-log rt)
        chain-valid? (boolean (runtime/verify-event-log-chain event-log))
        replayed (runtime/replay-graph graph-ast event-log)
        outbox-match? (= (:outbox rt) (:outbox replayed))
        states-match? (= (:node-states rt) (:node-states replayed))
        last-evt (last event-log)]
    {:ok? (and chain-valid? outbox-match? states-match?)
     :chain-valid? chain-valid?
     :outbox-match? outbox-match?
     :states-match? states-match?
     :event-count (count event-log)
     :head-hash (or (:event/hash last-evt) (:hash last-evt))}))

(defn close-hive-stack!
  "Cleanly releases the shared VRAM multiplexer and all child KV cache arenas."
  [stack]
  (when-let [mux-atom (:kv-mux-atom stack)]
    (kv/close-shared-kv-multiplexer! @mux-atom))
  nil)
