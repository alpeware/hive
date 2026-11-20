(ns bitecho.hive.stack
  "Full In-Memory `clj-datachannel` / `bitecho` / `mealy` / `clj-einsum` Stack Wiring (`bitecho.hive.stack`).
   Connects N agents (defaulting to `[:alpha :beta]`) over:
   1. `clj-datachannel` (RFC 4571 stream framing + SCTP DATA packet handling)
   2. `bitecho` (`bitecho.sim.vopr` virtual-time byte link router with active fault-profile packet drop/retransmit
      + `bitecho.light-node.core` E2EE X25519 sessions + Rule 4/5 `bitecho.shell.browser` lifecycle)
   3. `mealy` (`mealy.graph.binder` + `mealy.graph.runtime` declarative agent graphs with `:compute/einsum`,
      `:compute/agy`, `:eval/sci`, and `:net/bitecho` capabilities)
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

(defn agent-slug
  "Returns a hyphenated slug for `agent-id` (e.g., `:arena/worker-0` -> `\"arena-worker-0\"`, `:alpha` -> `\"alpha\"`)."
  [agent-id]
  (if (qualified-keyword? agent-id)
    (str (namespace agent-id) "-" (name agent-id))
    (name agent-id)))

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

(defn resolve-fault-config
  "Resolves a `:fault-profile` keyword (`:reliable`, `:lossy-10pct`, `:custom`) and optional
   `custom-config` map into a VOPR link fault configuration map."
  ([fault-profile]
   (resolve-fault-config fault-profile nil))
  ([fault-profile custom-config]
   (let [base-reliable {:p-good->bad 0.0
                        :p-bad->good 1.0
                        :p-loss-good 0.0
                        :p-loss-bad 0.0
                        :bitflip-prob 0.0
                        :truncate-prob 0.0
                        :mtu 65536}]
     (case (or fault-profile :reliable)
       :lossy-10pct
       (merge base-reliable
              {:p-loss-good 0.10
               :p-loss-bad 0.25
               :p-good->bad 0.05
               :p-bad->good 0.50}
              custom-config)

       :custom
       (merge base-reliable custom-config)

       ;; default :reliable
       (merge base-reliable custom-config)))))

(defn- create-agent-light-node
  "Creates an isolated in-memory Bitecho light node + clj-datachannel WebRTC/SCTP endpoint."
  ([agent-id seed validator-pubkey]
   (create-agent-light-node agent-id seed validator-pubkey {}))
  ([agent-id seed validator-pubkey {:keys [subnet-id initial-balance vopr-light-id]}]
   (let [eff-subnet (str (or subnet-id DEFAULT-SUBNET-ID))
         eff-balance (long (or initial-balance 100000))
         rng (crypto/make-random seed)
         kp (crypto/generate-keypair rng)
         pub-hex (str/lower-case (basalt/bytes->hex (:public kp)))
         priv-bytes (:private kp)
         petname (agent-slug agent-id)
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
                                                        :subnet-id eff-subnet})
                           {:type :dial :peer-id validator-pubkey :subnet-id eff-subnet})
         [conn-open _] (browser/transition-conn conn-dialing {:type :open :peer-id validator-pubkey})
         init-st (light-node/init-light-state
                  {:pubkey pub-hex
                   :fingerprint (:fingerprint dtls-cert)
                   :ice-creds {:ufrag (str "ufrag-" petname)
                               :pwd (str "pwd-" petname "-secret")}}
                  {:subnet-id eff-subnet
                   :network-uri eff-subnet
                   :epoch 1
                   :validator-target validator-pubkey})
         synced-st (:state (light-node/handle-command
                            init-st
                            {"cmd" "sync-account"
                             "balance" eff-balance
                             "spendable" eff-balance
                             "seq" 1
                             "finalized_epoch" 1}
                            priv-bytes))]
     {:agent-id agent-id
      :petname petname
      :vopr-light-id (or vopr-light-id "light1")
      :pubkey pub-hex
      :privkey priv-bytes
      :keypair kp
      :initial-balance eff-balance
      :conn-state conn-open
      :webrtc-conn webrtc-conn
      :light-state synced-st})))

(defn- drain-vopr-link-events
  "Steps the VOPR event queue until empty and returns `[updated-world delivered?]`
   indicating whether a `:deliver-packet` event from `vopr-from` to `vopr-to` was popped."
  [world vopr-from vopr-to]
  (loop [w world
         delivered? false
         steps 0]
    (if (or (empty? (:event-queue w)) (>= steps 64))
      [w delivered?]
      (let [[w1 evt] (vopr/pop-event w)
            w2 (update-in w1 [:telemetry :events-executed] (fnil inc 0))
            hit? (and (= :deliver-packet (:type evt))
                      (= vopr-from (:from evt))
                      (= vopr-to (:to evt)))]
        (recur w2 (or delivered? hit?) (inc steps))))))

(defn- record-vopr-packet-drop-diagnose
  "Applies a Rule 4/5 counted `:diagnose {:reason :vopr-packet-drop}` effect to `from-id`'s
   connection state without delivering the dropped frame."
  [net-state from-id to-id wire-cid attempt]
  (let [conn (get-in net-state [:agents from-id :conn-state])
        curr-gen (:generation conn 0)
        new-conn (-> conn
                     (update-in [:diagnostics :dropped] (fnil inc 0))
                     (assoc-in [:diagnostics :last-error] "vopr-packet-drop"))
        diag-effect {:type :diagnose
                     :kind :dropped
                     :reason :vopr-packet-drop
                     :from from-id
                     :to to-id
                     :wire-cid wire-cid
                     :attempt attempt
                     :generation curr-gen}]
    (-> net-state
        (assoc-in [:agents from-id :conn-state] new-conn)
        (update :diagnose-effects (fnil conj []) diag-effect)
        (update-in [:vopr-stats :sent] (fnil inc 0))
        (update-in [:vopr-stats :dropped] (fnil inc 0))
        (update-in [:vopr-stats :diagnostics-dropped-total] (fnil inc 0)))))

(defn transmit-single-wire-envelope
  "Transmits a single Bitecho wire envelope from `from-id` to `to-id` across:
   1. Fressian wire serialization (`bitecho.fressian/encode-wire`) + SHA-256 CID calculation
   2. `clj-datachannel` RFC 4571 framing (`datachannel.tcp/encode-rfc4571-frame`)
   3. VOPR stream slicing & reassembly (`vopr/slice-stream-bytes`, `vopr/reassemble-rfc4571-stream`)
   4. VOPR discrete-event link router (`vopr/route-packet`):
      - If VOPR drops the frame, does NOT deliver to `light-node/handle-wire-payload`; emits a
        counted `:diagnose {:reason :vopr-packet-drop}` effect (Rule 5) and deterministically
        retransmits on the next virtual-time tick until delivered.
   5. `clj-datachannel` SCTP DATA packet processing (`datachannel.core/handle-sctp-packet`)
   6. Target Bitecho light-node wire ingestion (`bitecho.light-node.core/handle-wire-payload`).
   Returns `[updated-net-state wire-receipt reply-envelopes]`."
  [net-state from-id to-id envelope]
  (let [raw-wire (fressian/encode-wire envelope)
        wire-cid (data/compute-cid raw-wire)
        rfc4571-frame (dtcp/encode-rfc4571-frame raw-wire)
        from-node (get-in net-state [:agents from-id])
        to-node (get-in net-state [:agents to-id])
        vopr-from (or (:vopr-light-id from-node) "light1")
        vopr-to (or (:vopr-light-id to-node) "light2")]
    (loop [st net-state
           attempt 1]
      (let [world0 (:world st)
            [world1 sliced-chunks] (vopr/slice-stream-bytes world0 rfc4571-frame)
            reassembled-frames (vopr/reassemble-rfc4571-stream sliced-chunks)
            clean-wire (or (first reassembled-frames) raw-wire)
            ;; Force delivery after 12 consecutive drops to guarantee termination under extreme fault injection
            world-routed (if (>= attempt 12)
                           (vopr/schedule-event world1 2000000 {:type :deliver-packet
                                                                :from vopr-from
                                                                :to vopr-to
                                                                :tcp? true
                                                                :bytes clean-wire})
                           (vopr/route-packet world1 vopr-from vopr-to clean-wire {:tcp? true}))
            [world2 delivered?] (drain-vopr-link-events world-routed vopr-from vopr-to)]
        (if-not delivered?
          ;; VOPR dropped the packet: emit counted :diagnose {:reason :vopr-packet-drop} (Rule 5) and retransmit
          (let [st-dropped (-> (record-vopr-packet-drop-diagnose st from-id to-id wire-cid attempt)
                               (assoc :world (update world2 :t-ns (fnil + 0) 5000000)))]
            (recur st-dropped (inc attempt)))
          ;; Packet survived VOPR link: process through clj-datachannel SCTP and target light-node
          (let [retransmissions (dec attempt)
                target-node (get-in st [:agents to-id])
                target-dc (:webrtc-conn target-node)
                tsn (inc (long (:next-tsn st 0)))
                sctp-pkt {:src-port 5000
                          :dst-port 5000
                          :verification-tag (or (:local-ver-tag target-dc) 12345)
                          :chunks [{:type :data
                                    :tsn tsn
                                    :stream-id 0
                                    :ssn (dec tsn)
                                    :payload clean-wire}]}
                dc-res (dc/handle-sctp-packet target-dc sctp-pkt (quot (long (:t-ns world2 0)) 1000000))
                target-dc' (or (:new-state dc-res) target-dc)
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
                         :wire/attempts attempt
                         :wire/retransmissions retransmissions
                         :wire/stdout-events (vec (:stdout-events wire-res))}
                st' (-> st
                        (assoc :world world2
                               :next-tsn tsn)
                        (assoc-in [:agents to-id :webrtc-conn] target-dc')
                        (assoc-in [:agents to-id :light-state] (:state wire-res))
                        (update :wire-log (fnil conj []) receipt)
                        (update-in [:vopr-stats :sent] (fnil inc 0))
                        (update-in [:vopr-stats :delivered] (fnil inc 0))
                        (update-in [:vopr-stats :retransmitted] (fnil + 0) retransmissions))]
            [st' receipt (vec (:net-envelopes wire-res))]))))))

(defn- resolve-target-peer-ids
  "Resolves target peer keyword(s) in `net-state` for an outbound command from `agent-id`."
  [net-state agent-id cmd-map]
  (let [all-other-ids (vec (remove #(= % agent-id) (keys (:agents net-state))))
        cmd (or (get cmd-map "cmd") (some-> (:cmd cmd-map) name))
        explicit-peer (or (get cmd-map "peer") (:peer cmd-map)
                          (get cmd-map "callee") (:callee cmd-map))
        session-id (or (get cmd-map "session_id") (:session_id cmd-map))
        petname->id (:petname->agent-id net-state {})
        pubkey->id (:pubkey->agent-id net-state {})
        resolve-one (fn [p]
                      (cond
                        (nil? p) nil
                        (and (keyword? p) (contains? (:agents net-state) p)) p
                        (contains? petname->id (str p)) (get petname->id (str p))
                        (contains? pubkey->id (str/lower-case (str p))) (get pubkey->id (str/lower-case (str p)))
                        (and (string? p) (str/includes? p "/"))
                        (let [[ns-p nm-p] (str/split p #"/" 2)
                              kw (keyword ns-p nm-p)]
                          (when (contains? (:agents net-state) kw) kw))
                        (string? p)
                        (let [kw (keyword p)]
                          (when (contains? (:agents net-state) kw) kw))
                        :else nil))]
    (cond
      (= cmd "broadcast")
      all-other-ids

      (some? (resolve-one explicit-peer))
      [(resolve-one explicit-peer)]

      (and (string? session-id) (contains? (:session-peers net-state) session-id))
      (let [pair (get-in net-state [:session-peers session-id])]
        (vec (remove #(= % agent-id) pair)))

      (and (string? session-id)
           (some? (get-in net-state [:agents agent-id :light-state :sessions session-id :peer])))
      (let [peer-pub (get-in net-state [:agents agent-id :light-state :sessions session-id :peer])]
        (if-let [pid (resolve-one peer-pub)]
          [pid]
          [(first all-other-ids)]))

      (= 1 (count all-other-ids))
      all-other-ids

      :else
      [(first all-other-ids)])))

(defn execute-light-node-command
  "Executes a Bitecho light-node command map on `agent-id` and routes all emitted wire envelopes
   (plus any resulting `:session-ack` reply envelopes) across the in-memory `clj-datachannel` + VOPR mesh
   to the target peer light node(s).
   Returns `[updated-net-state command-response routed-wire-cids]`."
  [net-state agent-id cmd-map]
  (let [target-ids (resolve-target-peer-ids net-state agent-id cmd-map)
        ;; Normalize :peer / "peer" to the registered petname if a peer is specified
        norm-cmd-map (if-let [raw-p (or (get cmd-map "peer") (:peer cmd-map))]
                       (let [resolved-id (first target-ids)
                             pet (or (get-in net-state [:agents resolved-id :petname]) (str raw-p))]
                         (assoc cmd-map "peer" pet))
                       cmd-map)
        agent-node (get-in net-state [:agents agent-id])
        cmd-res (light-node/handle-command (:light-state agent-node) norm-cmd-map (:privkey agent-node))
        net-state1 (assoc-in net-state [:agents agent-id :light-state] (:state cmd-res))
        out-envs (vec (:net-envelopes cmd-res))
        [net-state2 wire-cids]
        (reduce (fn [[st cids] env]
                  (reduce (fn [[st-t cids-t] peer-id]
                            (if (or (nil? peer-id) (= peer-id agent-id))
                              [st-t cids-t]
                              (let [[st1 receipt reply-envs] (transmit-single-wire-envelope st-t agent-id peer-id env)
                                    cids1 (conj cids-t (:wire/cid receipt))]
                                (reduce (fn [[st-inner cids-inner] rep-env]
                                          (let [[st-rep rep-receipt _] (transmit-single-wire-envelope st-inner peer-id agent-id rep-env)]
                                            [st-rep (conj cids-inner (:wire/cid rep-receipt))]))
                                        [st1 cids1]
                                        reply-envs))))
                          [st cids]
                          target-ids))
                [net-state1 []]
                out-envs)]
    [net-state2
     (assoc (:response cmd-res) "wire_envelope_cids" wire-cids)
     wire-cids]))

(defn establish-e2ee-p2p-session-pair
  "Runs the full Bitecho E2EE X25519 session + P2P DataChannel handshake between `caller-id` and `callee-id`
   over the in-memory `clj-datachannel` + VOPR network:
   1. Mutual petname registration (`caller` <-> `callee`)
   2. `offer` (`caller-id` -> `callee-id`)
   3. `accept` (`callee-id` -> `caller-id`)
   4. `p2p-offer` (`caller-id` -> `callee-id`)
   5. `p2p-answer` (`callee-id` -> `caller-id`)
   6. `p2p-channel-state` (`connected: true`, `zero_burn: true`) on both sides."
  [net-state caller-id callee-id seed]
  (let [pair-salt (if (and (= caller-id :alpha) (= callee-id :beta))
                    0
                    (+ (* 31 (long (count (str caller-id))))
                       (* 17 (long (count (str callee-id))))))
        handshake-rng (crypto/make-random (+ (long (or seed 20261118)) pair-salt 7777))
        det-random-bytes (fn [n]
                           (let [ba (byte-array (long n))]
                             (crypto/next-bytes handshake-rng ba)
                             ba))
        det-x25519-kp (fn []
                        (sealed/x25519-keypair-from-seed (det-random-bytes 32)))]
    (with-redefs [crypto/random-bytes det-random-bytes
                  sealed/generate-x25519-keypair det-x25519-kp]
      (let [caller-node (get-in net-state [:agents caller-id])
            callee-node (get-in net-state [:agents callee-id])
            caller-pub (:pubkey caller-node)
            callee-pub (:pubkey callee-node)
            caller-pet (:petname caller-node)
            callee-pet (:petname callee-node)
            [st1 _ _] (execute-light-node-command
                       net-state caller-id
                       {"cmd" "petname-set" "petname" callee-pet "pubkey" callee-pub "overwrite" true})
            [st2 _ _] (execute-light-node-command
                       st1 callee-id
                       {"cmd" "petname-set" "petname" caller-pet "pubkey" caller-pub "overwrite" true})
            [st3 offer-resp offer-cids] (execute-light-node-command
                                         st2 caller-id
                                         {"cmd" "offer" "callee" callee-pet "price" 0 "burn" 500})
            session-id (get offer-resp "session_id")
            st3b (-> st3
                     (assoc-in [:session-peers session-id] [caller-id callee-id])
                     (assoc-in [:pair-sessions [caller-id callee-id]] session-id)
                     (assoc-in [:pair-sessions [callee-id caller-id]] session-id))
            [st4 accept-resp accept-cids] (execute-light-node-command
                                           st3b callee-id
                                           {"cmd" "accept" "session_id" session-id})
            [st5 _ p2p-off-cids] (execute-light-node-command
                                  st4 caller-id
                                  {"cmd" "p2p-offer"
                                   "session_id" session-id
                                   "transport" "tcp"
                                   "candidates" ["tcp://127.0.0.1:20100"]
                                   "burn" 500})
            [st6 _ p2p-ans-cids] (execute-light-node-command
                                  st5 callee-id
                                  {"cmd" "p2p-answer"
                                   "session_id" session-id
                                   "transport" "tcp"
                                   "candidates" ["tcp://127.0.0.1:20101"]
                                   "burn" 500})
            [st7 _ _] (execute-light-node-command
                       st6 caller-id
                       {"cmd" "p2p-channel-state"
                        "session_id" session-id
                        "connected" true
                        "transport" "tcp"})
            [st8 _ _] (execute-light-node-command
                       st7 callee-id
                       {"cmd" "p2p-channel-state"
                        "session_id" session-id
                        "connected" true
                        "transport" "tcp"})
            pair-cids (vec (concat offer-cids accept-cids p2p-off-cids p2p-ans-cids))
            pair-ok? (and (true? (get offer-resp "ok"))
                          (true? (get accept-resp "ok")))]
        (-> st8
            (assoc :session-id (or (:session-id st8) session-id)
                   :handshake-ok? (and (boolean (:handshake-ok? st8 true)) pair-ok?))
            (update :handshake-wire-cids (fnil into []) pair-cids))))))

;; =============================================================================
;; 3. Declarative Mealy Agent Graph (:compute/einsum, :compute/agy + :net/bitecho)
;; =============================================================================

(defn hive-agent-mealy-step
  "Pure Mealy state reducer for a Hive agent cell orchestrating `:cap/compute` (`:compute/einsum` or `:compute/agy`)
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
   `:cap/compute` (`:compute/einsum` or `:compute/agy`) and `:cap/net` (`:net/bitecho`)."
  [agent-id compute-cap-spec net-cap-spec]
  (let [agent-kw (keyword (str "hive-agent-" (agent-slug agent-id)))]
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
   for arbitrary `:agent-ids` (default `[:alpha :beta]`):
   - `:agent-ids`: vector of all agent IDs in the mesh
   - `:kv-agent-ids`: vector of agent IDs that share the Gemma 4 E2B VRAM multiplexer (defaults to `:agent-ids`)
   - `:session-pairs`: vector of `[caller-id callee-id]` pairs to establish E2EE X25519 sessions for
     (defaults to `[[:alpha :beta]]` when `:agent-ids` is `[:alpha :beta]`, or star from `(first agent-ids)` to all others)
   - `:fault-profile`: `:reliable` (default), `:lossy-10pct`, or `:custom`
   - `:fault-config`: optional VOPR link fault map override
   - `:subnet-id`: Bitecho subnet ID (default `DEFAULT-SUBNET-ID`)
   - `:starting-balances`: optional map `{agent-id balance-ech}`
   - `:min-balance`: optional `--min-balance` floor for `:net/bitecho` (default `1000`)
   - `:agent-graph-builder`: optional `(fn [agent-id compute-cap net-cap opts] ...)` returning `[graph-ast device-ast]`."
  ([] (init-in-memory-hive-stack {}))
  ([opts]
   (let [seed (long (or (:seed opts) 20261118))
         agent-ids (vec (or (:agent-ids opts) [:alpha :beta]))
         kv-agent-ids (vec (or (:kv-agent-ids opts) agent-ids))
         subnet-id (str (or (:subnet-id opts) DEFAULT-SUBNET-ID))
         fault-profile (or (:fault-profile opts) :reliable)
         fault-cfg (resolve-fault-config fault-profile (:fault-config opts))
         kv-mux (kv/init-shared-kv-multiplexer (assoc opts :agent-ids kv-agent-ids :seed seed))
         kv-mux-atom (atom kv-mux)
         n-lights (max 2 (count agent-ids))
         ;; Run both session establishment and episode traffic through the requested fault-cfg VOPR links
         world0 (vopr/create-initial-world seed {:num-validators 4
                                                 :num-light-nodes n-lights
                                                 :fault-config fault-cfg})
         val-pub (get-in world0 [:nodes "node1" :pubkey])
         starting-balances (or (:starting-balances opts) {})
         default-min-balance (long (or (:min-balance opts) 1000))
         agents-map
         (into {}
               (map-indexed
                (fn [idx aid]
                  (let [lid (str "light" (inc idx))
                        bal (long (or (get starting-balances aid) 100000))
                        node (create-agent-light-node
                              aid
                              (+ seed (* 101 (inc idx)))
                              val-pub
                              {:subnet-id subnet-id
                               :initial-balance bal
                               :vopr-light-id lid})]
                    [aid node])))
               agent-ids)
         petname->agent-id (into {} (map (fn [[aid n]] [(:petname n) aid]) agents-map))
         pubkey->agent-id (into {} (map (fn [[aid n]] [(:pubkey n) aid]) agents-map))
         session-pairs (or (:session-pairs opts)
                           (if (= agent-ids [:alpha :beta])
                             [[:alpha :beta]]
                             (let [hub (first agent-ids)]
                               (mapv (fn [peer] [hub peer]) (rest agent-ids)))))
         base-net {:world world0
                   :subnet-id subnet-id
                   :fault-profile fault-profile
                   :fault-config fault-cfg
                   :next-tsn 0
                   :wire-log []
                   :diagnose-effects []
                   :vopr-stats {:sent 0
                                :dropped 0
                                :retransmitted 0
                                :delivered 0
                                :diagnostics-dropped-total 0
                                :silent-failures 0}
                   :agents agents-map
                   :petname->agent-id petname->agent-id
                   :pubkey->agent-id pubkey->agent-id
                   :session-peers {}
                   :pair-sessions {}
                   :handshake-ok? true
                   :handshake-wire-cids []}
         net-after-handshakes
         (reduce (fn [nst [caller callee]]
                   (establish-e2ee-p2p-session-pair nst caller callee seed))
                 base-net
                 session-pairs)
         ;; Keep cumulative VOPR transport telemetry across session handshakes + episode turns
         active-links (into (sorted-map)
                            (map (fn [[k v]] [k (merge v fault-cfg)]))
                            (get-in net-after-handshakes [:world :links]))
         net-ready (assoc-in net-after-handshakes [:world :links] active-links)
         net-atom (atom net-ready)
         kv-agent-set (set kv-agent-ids)
         custom-builder (:agent-graph-builder opts)
         make-agent-runtime
         (fn [aid]
           (let [slug (agent-slug aid)
                 compute-fn (fn cfn
                              ([prompt]
                               (cfn nil prompt {}))
                              ([_sess prompt]
                               (cfn nil prompt {}))
                              ([_sess prompt eff-opts]
                               (if (contains? kv-agent-set aid)
                                 (let [res-box (atom nil)]
                                   (swap! kv-mux-atom
                                          (fn [m]
                                            (let [[m' turn-res] (kv/generate-agent-turn!
                                                                 m aid prompt
                                                                 (assoc (or eff-opts {}) :seed seed))]
                                              (reset! res-box (dissoc turn-res :kv-entry))
                                              m')))
                                   @res-box)
                                 ;; Non-KV agent (e.g. Frontier :compute/agy or Sans-IO observer):
                                 ;; deterministic seeded-mock generation without touching shared Gemma 4 KV arenas
                                 (let [override-fn (:mock-output-fn eff-opts)]
                                   (if (ifn? override-fn)
                                     (override-fn seed aid 1 prompt 24)
                                     (kv/seeded-mock-generate seed aid 1 prompt 24))))))
                 net-handler (fn [cmd-map]
                               (let [resp-box (atom nil)]
                                 (swap! net-atom
                                        (fn [nst]
                                          (let [[nst' resp _] (execute-light-node-command nst aid cmd-map)]
                                            (reset! resp-box resp)
                                            nst')))
                                 @resp-box))
                 agent-bal (long (or (get starting-balances aid) 100000))
                 compute-cap {:id (keyword (str "einsum-" slug))
                              :type :compute/einsum
                              :backend (or (:backend kv-mux) :uop-rocm)
                              :generate-fn compute-fn}
                 net-cap {:id (keyword (str "bitecho-" slug))
                          :type :net/bitecho
                          :min-balance default-min-balance
                          :balance agent-bal
                          :in-memory-handler net-handler}
                 [graph-ast device-ast]
                 (if (ifn? custom-builder)
                   (custom-builder aid compute-cap net-cap {:seed seed
                                                            :subnet-id subnet-id
                                                            :min-balance default-min-balance
                                                            :balance agent-bal
                                                            :compute-fn compute-fn
                                                            :net-handler net-handler
                                                            :opts opts})
                   [(build-hive-mealy-agent-graph aid compute-cap net-cap)
                    [:device {:id (keyword (str "device-" slug))}
                     [:cap compute-cap]
                     [:cap net-cap]]])]
             {:agent-id aid
              :graph-ast graph-ast
              :device-ast device-ast
              :runtime (binder/bind-agent graph-ast device-ast)}))]
     {:seed seed
      :subnet-id subnet-id
      :fault-profile fault-profile
      :agent-ids agent-ids
      :kv-agent-ids kv-agent-ids
      :kv-mux-atom kv-mux-atom
      :net-atom net-atom
      :mealy-agents (into {} (map (fn [aid] [aid (make-agent-runtime aid)]) agent-ids))})))

(defn step-agent-turn!
  "Enqueues `turn-payload` into `agent-id`'s compiled `mealy` agent graph and runs it to quiescence.
   Returns `[updated-stack turn-summary]`."
  [stack agent-id turn-payload]
  (let [compiled (get-in stack [:mealy-agents agent-id])
        rt' (-> (:runtime compiled)
                (runtime/enqueue-input :turn-in turn-payload)
                runtime/run-graph!)
        turn-summary (or (last (get-in rt' [:outbox :turn-out]))
                         (last (get-in rt' [:outbox :blocked-out]))
                         {})
        last-evt (last (:event-log rt'))
        last-event-hash (or (:event/hash last-evt) (:hash last-evt))]
    [(assoc-in stack [:mealy-agents agent-id :runtime] rt')
     (assoc turn-summary
            :mealy-event-root last-event-hash
            :outbox (:outbox rt'))]))

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
