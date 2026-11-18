(ns bitecho.hive.kv
  "Immutable Append-Only KV Cache Log & Shared-VRAM Weights Multiplexer (`bitecho.hive.kv`).
   Multiplexes N isolated per-agent KV cache states (`:kv-state` atoms + child `DeviceArena`s)
   over a single loaded Gemma 4 E2B VRAM session (`:device-weights` allocated once in VRAM),
   and records every turn's KV state transition as a content-addressed Bitecho `Blob` chain."
  (:require [bitecho.core.data :as data]
            [bitecho.crypto :as crypto]
            [bitecho.fressian :as fressian]
            [clojure.string :as str]
            [einsum.models.gemma4.runtime :as gemma4-rt]
            [einsum.runtime.arena :as arena]
            [einsum.uop.runtime :as uop-rt]))

(def ^:const KV-BLOB-MEDIA-TYPE
  "Canonical media type for Fressian-encoded Hive KV log Blobs."
  "application/x-bitecho-hive-kv+fressian")

(def ^:const DEFAULT-GEMMA4-E2B-MODEL-DIR
  "Default local path to Gemma 4 E2B IT BF16 safetensors model."
  "/home/simonpure/src/alpeware/clj-einsum/.models/gemma-4-E2B-it")

(defn resolve-model-dir
  "Resolves the Gemma 4 E2B model directory from explicit `model-dir`, `HIVE_MODEL_DIR` env var,
   or `DEFAULT-GEMMA4-E2B-MODEL-DIR`."
  [model-dir]
  (or model-dir
      (not-empty (System/getenv "HIVE_MODEL_DIR"))
      DEFAULT-GEMMA4-E2B-MODEL-DIR))

(defn resolve-uop-backend
  "Resolves `:uop-rocm` when ROCm HIP/HIPRTC is available on host, `:uop-intel` when Intel Arc / Xe
   OpenCL GPU runtime is available, or falls back to `:uop-cpu`."
  [requested-backend]
  (let [env-backend (some-> (System/getenv "HIVE_BACKEND") not-empty keyword)
        req (or (when (and requested-backend (not= requested-backend :auto)) requested-backend)
                (when (and env-backend (not= env-backend :auto)) env-backend))]
    (if req
      req
      (cond
        (uop-rt/init-hip-bindings!) :uop-rocm
        (uop-rt/init-opencl-bindings!) :uop-intel
        :else :uop-cpu))))

(defn- seeded-mock-generate
  "Deterministic Sans-IO seeded token generator for fast generative property testing."
  [seed agent-id turn-seq prompt max-new-tokens]
  (let [prompt-str (str (or prompt ""))
        rng-seed (+ (long (or seed 0))
                    (* 31337 (long (hash agent-id)))
                    (* 997 (long (or turn-seq 1)))
                    (long (hash prompt-str)))
        rng (crypto/make-random rng-seed)
        prompt-bytes (fressian/utf8->bytes prompt-str)
        prompt-tokens (into [2 105 2364 107]
                            (map #(long (+ 200 (bit-and (int %) 0xff)))
                                 (take 24 prompt-bytes)))
        vocab ["Hello" "Beta" "Alpha" "from" "across" "the" "Bitecho" "Hive"
               "in-memory" "mesh" "stack" "greetings" "friend" "acknowledged" "ready" "over"]
        n-toks (max 4 (min (long (or max-new-tokens 12)) 12))
        chosen-indices (vec (repeatedly n-toks #(crypto/next-int rng (count vocab))))
        new-tokens (mapv #(+ 1000 (long %)) chosen-indices)
        words (mapv #(nth vocab %) chosen-indices)
        peer-name (if (= agent-id :alpha) "Beta" "Alpha")
        self-name (if (= agent-id :alpha) "Alpha" "Beta")
        text (str "Hello " peer-name "! This is " self-name " (" (str/join " " (take 4 words)) ").")]
    {:text text
     :prompt-ids prompt-tokens
     :prompt-tokens (count prompt-tokens)
     :new-tokens (count new-tokens)
     :new-token-ids new-tokens
     :prefill-ms 0.25
     :decode-ms 0.50
     :decode-tok-s 1000.0
     :decode-ms-tok 0.05
     :total-ms 0.75}))

(defn init-shared-kv-multiplexer
  "Initializes a shared-weights VRAM multiplexer holding ONE loaded model weight allocation
   (`:device-weights`) and `N` isolated agent KV cache sessions (`:agent-sessions`).
   Options:
   - `:mode`: `:live-gemma4` (default) or `:seeded-mock`
   - `:model-dir`: path to Gemma 4 E2B IT safetensors
   - `:precision`: `:bf16` (default)
   - `:backend`: `:uop-rocm`, `:uop-cpu`, or `:auto` (default `:auto`)
   - `:max-seq-len`: maximum KV cache sequence length (default `256`)
   - `:max-new-tokens`: default max new tokens per turn (default `24`)
   - `:temperature`: sampling temperature (default `0.0`)
   - `:agent-ids`: vector of agent keywords (default `[:alpha :beta]`)
   - `:seed`: deterministic PRNG seed (default `20261118`)"
  ([] (init-shared-kv-multiplexer {}))
  ([opts]
   (let [mode (or (:mode opts) :live-gemma4)
         agent-ids (vec (or (:agent-ids opts) [:alpha :beta]))
         max-seq-len (long (or (:max-seq-len opts) 256))
         max-new-tokens (long (or (:max-new-tokens opts) 24))
         temperature (double (or (:temperature opts) 0.0))
         precision (or (:precision opts) :bf16)
         seed (long (or (:seed opts) 20261118))
         quiet (if (contains? opts :quiet) (boolean (:quiet opts)) true)
         model-dir (resolve-model-dir (:model-dir opts))
         base-session
         (if (= mode :seeded-mock)
           {:mode :seeded-mock
            :ctx {:backend :seeded-mock}
            :model-dir model-dir
            :config {:weight-dtype precision
                     :max-seq-len max-seq-len
                     :num-layers 35
                     :num-kv-shared-layers 0}
            :opts {:max-seq-len max-seq-len
                   :max-new-tokens max-new-tokens
                   :temperature temperature
                   :quiet quiet}
            :session-arena (arena/create-arena {:backend :seeded-mock})
            :device-weights [(Object.) (Object.)]
            :kv-state (atom nil)
            :max-seq-len max-seq-len}
           (let [backend (resolve-uop-backend (:backend opts))
                 session-opts {:model-dir model-dir
                               :precision precision
                               :backend backend
                               :max-seq-len max-seq-len
                               :max-new-tokens max-new-tokens
                               :temperature temperature
                               :method :kv-cache
                               :vram-loop? false
                               :quiet quiet}]
             (gemma4-rt/init-agent-vram-session session-opts max-seq-len)))
         agent-sessions
         (into {}
               (map (fn [aid]
                      (let [child-arena (arena/create-arena (:ctx base-session)
                                                            (:session-arena base-session))
                            agent-sess (assoc base-session
                                              :agent-id aid
                                              :kv-state (atom nil)
                                              :session-arena child-arena)]
                        [aid agent-sess])))
               agent-ids)]
     {:mode mode
      :seed seed
      :model-dir (:model-dir base-session)
      :precision precision
      :backend (get-in base-session [:ctx :backend])
      :uop-target (get-in base-session [:ctx :uop-target])
      :max-seq-len max-seq-len
      :max-new-tokens max-new-tokens
      :temperature temperature
      :weight-allocations 1
      :base-session base-session
      :agent-ids agent-ids
      :agent-sessions agent-sessions
      :kv-logs (into {} (map (fn [aid] [aid []]) agent-ids))
      :blob-store (data/init-blob-store)})))

(defn get-agent-session
  "Returns the isolated agent VRAM session view for `agent-id`."
  [mux agent-id]
  (get-in mux [:agent-sessions agent-id]))

(defn multiplexer-stats
  "Returns verification statistics proving single VRAM weight allocation and isolated per-agent KV caches."
  [mux]
  (let [sessions (vals (:agent-sessions mux))
        weight-refs (mapv :device-weights sessions)
        kv-atoms (mapv :kv-state sessions)
        arenas (mapv :session-arena sessions)
        base-weights (:device-weights (:base-session mux))
        shared-weights? (and (some? base-weights)
                             (every? #(identical? base-weights %) weight-refs))
        distinct-kv-atoms? (= (count kv-atoms)
                              (count (distinct (map #(System/identityHashCode %) kv-atoms))))
        distinct-arenas? (= (count arenas)
                            (count (distinct (map #(System/identityHashCode %) arenas))))]
    {:weight-allocations (:weight-allocations mux 1)
     :kv-cache-count (count sessions)
     :shared-device-weights? shared-weights?
     :isolated-kv-atoms? distinct-kv-atoms?
     :isolated-kv-arenas? distinct-arenas?
     :device-weights-identity (System/identityHashCode base-weights)
     :kv-atom-identities (into {}
                               (map (fn [[aid s]]
                                      [aid (System/identityHashCode (:kv-state s))]))
                               (:agent-sessions mux))
     :kv-log-counts (into {}
                          (map (fn [[aid entries]]
                                 [aid (count entries)]))
                          (:kv-logs mux))}))

(defn record-agent-turn-kv!
  "Records an immutable content-addressed KV cache log `Blob` for `agent-id`'s turn.
   Updates `@(:kv-state agent-session)` if not already populated by live UOp execution.
   Returns `[updated-mux kv-log-entry]`."
  [mux agent-id {:keys [turn-seq prompt-tokens new-tokens prefill-ms decode-ms total-ms prior-cached-tokens]}]
  (let [agent-sess (get-agent-session mux agent-id)
        _ (when-not agent-sess
            (throw (ex-info "Unknown agent-id in KV multiplexer" {:agent-id agent-id})))
        prior-entries (get-in mux [:kv-logs agent-id] [])
        parent-entry (last prior-entries)
        parent-cid (:kv/cid parent-entry)
        p-toks (vec prompt-tokens)
        n-toks (vec new-tokens)
        baseline-cached (or prior-cached-tokens
                            (:kv/cached-tokens parent-entry)
                            [])
        p-match (long (gemma4-rt/common-prefix-len baseline-cached p-toks))
        all-cached (into p-toks n-toks)
        kv-atom (:kv-state agent-sess)
        cur-kv @kv-atom
        _ (when (or (nil? cur-kv) (not= (:cached-tokens cur-kv) all-cached))
            (reset! kv-atom {:cached-tokens all-cached
                             :kv-buffers (or (:kv-buffers cur-kv) [:simulated-kv-buffer agent-id turn-seq])
                             :prefill-ms (double (or prefill-ms 0.0))
                             :decode-ms (double (or decode-ms 0.0))
                             :total-ms (double (or total-ms 0.0))}))
        updated-kv @kv-atom
        weights-id (long (System/identityHashCode (:device-weights agent-sess)))
        weights-digest (str (:model-dir mux) ":" (name (or (:precision mux) :bf16)))
        canonical-entry {:kv/version 1
                         :kv/agent-id agent-id
                         :kv/turn-seq (long (or turn-seq (inc (count prior-entries))))
                         :kv/parent-cid parent-cid
                         :kv/prompt-tokens p-toks
                         :kv/new-tokens n-toks
                         :kv/cached-tokens (vec (:cached-tokens updated-kv))
                         :kv/prefix-reused-tokens p-match
                         :kv/kv-buffer-count (count (:kv-buffers updated-kv))
                         :kv/weights-digest weights-digest}
        encoded-bytes (fressian/encode-canonical canonical-entry)
        blob (data/make-blob encoded-bytes KV-BLOB-MEDIA-TYPE)
        put-res (data/put-blob (:blob-store mux) blob)
        _ (when-not (:ok? put-res)
            (throw (ex-info "Failed to store KV log Blob" {:error (:error put-res)})))
        cid (:cid put-res)
        full-entry (assoc canonical-entry
                          :kv/cid cid
                          :kv/weights-identity-hash weights-id)
        mux' (-> mux
                 (assoc :blob-store (:store put-res))
                 (update-in [:kv-logs agent-id] (fnil conj []) full-entry))]
    [mux' full-entry]))

(defn generate-agent-turn!
  "Executes 1 turn of inference for `agent-id` using its isolated KV cache and the shared VRAM weights,
   appending a new immutable KV log `Blob` entry.
   Returns `[updated-mux turn-result]`."
  ([mux agent-id prompt]
   (generate-agent-turn! mux agent-id prompt {}))
  ([mux agent-id prompt call-opts]
   (let [agent-sess (get-agent-session mux agent-id)
         _ (when-not agent-sess
             (throw (ex-info "Unknown agent-id in generate-agent-turn!" {:agent-id agent-id})))
         prior-entries (get-in mux [:kv-logs agent-id] [])
         turn-seq (long (or (:turn-seq call-opts) (inc (count prior-entries))))
         prior-cached (or (:kv/cached-tokens (last prior-entries))
                          (when-let [ks @(:kv-state agent-sess)]
                            (:cached-tokens ks))
                          [])
         eff-opts (merge {:max-new-tokens (:max-new-tokens mux)
                          :max-seq-len (:max-seq-len mux)
                          :temperature (:temperature mux)
                          :quiet true}
                         call-opts)
         gen-res (if (= :seeded-mock (:mode mux))
                   (seeded-mock-generate (or (:seed call-opts) (:seed mux))
                                         agent-id
                                         turn-seq
                                         prompt
                                         (:max-new-tokens eff-opts))
                   (let [prep (gemma4-rt/prepare-prompt-ids agent-sess prompt eff-opts)
                         out (gemma4-rt/generate-new-tokens-and-text agent-sess prompt eff-opts)]
                     (assoc out :prompt-ids (:clamped-prompt-ids prep))))
         prompt-ids (vec (or (:prompt-ids gen-res) []))
         new-ids (vec (or (:new-token-ids gen-res) []))
         [mux' kv-entry] (record-agent-turn-kv!
                          mux
                          agent-id
                          {:turn-seq turn-seq
                           :prompt-tokens prompt-ids
                           :new-tokens new-ids
                           :prior-cached-tokens prior-cached
                           :prefill-ms (:prefill-ms gen-res)
                           :decode-ms (:decode-ms gen-res)
                           :total-ms (:total-ms gen-res)})]
     [mux' (assoc gen-res
                  :agent-id agent-id
                  :turn-seq turn-seq
                  :prompt prompt
                  :prompt-ids prompt-ids
                  :kv-log-cid (:kv/cid kv-entry)
                  :kv-entry kv-entry)])))

(defn verify-agent-kv-chain
  "Purely verifies the integrity of `agent-id`'s append-only KV log chain in `mux`:
   - Every entry's `:kv/cid` matches a valid `Blob` in `(:blob-store mux)` whose Fressian-decoded map equals the entry.
   - `:kv/parent-cid` links strictly to the preceding entry's `:kv/cid` (`nil` on turn 1).
   - `:kv/cached-tokens` equals `(into (:kv/prompt-tokens entry) (:kv/new-tokens entry))`.
   - `:kv/weights-identity-hash` matches the shared `:device-weights` identity hash."
  [mux agent-id]
  (let [entries (vec (get-in mux [:kv-logs agent-id] []))
        store (:blob-store mux)
        expected-weights-id (long (System/identityHashCode (:device-weights (:base-session mux))))]
    (loop [idx 0
           expected-parent nil]
      (if (>= idx (count entries))
        {:ok? true
         :agent-id agent-id
         :entry-count (count entries)
         :head-cid (:kv/cid (last entries))}
        (let [entry (nth entries idx)
              cid (:kv/cid entry)
              blob-res (data/get-blob store cid)
              blob (:blob blob-res)
              decoded (when (and (:hit? blob-res) (data/verify-blob blob))
                        (fressian/decode-wire (:blob/bytes blob)))]
          (cond
            (not (:hit? blob-res))
            {:ok? false :error :missing-kv-blob :cid cid :index idx}

            (not (data/verify-blob blob))
            {:ok? false :error :invalid-kv-blob-cid :cid cid :index idx}

            (not= (dissoc entry :kv/cid :kv/weights-identity-hash) decoded)
            {:ok? false :error :kv-entry-payload-mismatch :cid cid :index idx}

            (not= expected-parent (:kv/parent-cid entry))
            {:ok? false :error :broken-kv-parent-chain :expected expected-parent :actual (:kv/parent-cid entry) :index idx}

            (not= (into (vec (:kv/prompt-tokens entry)) (:kv/new-tokens entry))
                  (:kv/cached-tokens entry))
            {:ok? false :error :cached-tokens-mismatch :cid cid :index idx}

            (not= expected-weights-id (long (:kv/weights-identity-hash entry)))
            {:ok? false :error :weights-identity-mismatch :cid cid :index idx}

            :else
            (recur (inc idx) cid)))))))

(defn close-shared-kv-multiplexer!
  "Releases all per-agent KV cache child arenas and closes the shared VRAM base session."
  [mux]
  (doseq [[_ agent-sess] (:agent-sessions mux)]
    (when-let [ks (:kv-state agent-sess)]
      (reset! ks nil))
    (when-let [sa (:session-arena agent-sess)]
      (arena/close-arena! sa)))
  (when (= :live-gemma4 (:mode mux))
    (when-let [base (:base-session mux)]
      (gemma4-rt/close-agent-session! base)))
  nil)
