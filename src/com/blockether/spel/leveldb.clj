(ns com.blockether.spel.leveldb
  "Minimal read-only LevelDB parser for Chrome's localStorage.

   Reads .log (WAL) and .ldb (SSTable) files from a LevelDB directory,
   returning raw key-value records with sequence numbers and state.

   Implements:
   - Log file parsing (32KB blocks with CRC32/length/type headers)
   - SSTable parsing (footer, index, data blocks with prefix compression)
   - Snappy decompression (pure Java, no native dependencies)
   - Varint encoding (protobuf-style little-endian varints)

   Reference: https://github.com/google/leveldb/blob/master/doc/
   Based on: ccl_chromium_reader by CCL Forensics (MIT license)"
  (:require
   [clojure.string :as str])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream
    File FileInputStream InputStream RandomAccessFile]
   [java.nio ByteBuffer ByteOrder]
   [java.util Arrays]))

;; =============================================================================
;; Varint
;; =============================================================================

(defn read-le-varint
  "Reads a little-endian varint from stream. Returns nil at EOF.
   Set `google-32bit?` true to limit to 5 bytes (32-bit varints)."
  ([^InputStream stream]
   (read-le-varint stream false))
  ([^InputStream stream google-32bit?]
   (let [limit (int (if google-32bit? 5 10))]
     (loop [i (int 0), result (long 0)]
       (if (>= i limit)
         result
         (let [b (.read stream)]
           (if (neg? b)
             (when (pos? i) result)
             (let [result (bit-or result (bit-shift-left (long (bit-and b 0x7f)) (* i 7)))]
               (if (zero? (bit-and b 0x80))
                 result
                 (recur (inc i) result))))))))))

;; =============================================================================
;; Snappy Decompression (pure Java)
;; =============================================================================

(defn- snappy-read-uint8 ^long [^InputStream stream]
  (let [b (.read stream)]
    (when (neg? b)
      (throw (ex-info "Snappy: unexpected EOF reading uint8" {})))
    (long b)))

(defn- snappy-read-uint16-le ^long [^InputStream stream]
  (let [b0 (snappy-read-uint8 stream)
        b1 (snappy-read-uint8 stream)]
    (bit-or b0 (bit-shift-left b1 8))))

(defn- snappy-read-uint24-le ^long [^InputStream stream]
  (let [b0 (snappy-read-uint8 stream)
        b1 (snappy-read-uint8 stream)
        b2 (snappy-read-uint8 stream)]
    (bit-or b0 (bit-shift-left b1 8) (bit-shift-left b2 16))))

(defn- snappy-read-uint32-le ^long [^InputStream stream]
  (let [b0 (snappy-read-uint8 stream)
        b1 (snappy-read-uint8 stream)
        b2 (snappy-read-uint8 stream)
        b3 (snappy-read-uint8 stream)]
    (bit-or b0 (bit-shift-left b1 8) (bit-shift-left b2 16) (bit-shift-left b3 24))))

(defn snappy-decompress
  "Decompresses a raw Snappy-compressed byte array (NOT framed format).
   Returns decompressed byte array."
  ^bytes [^bytes compressed]
  (with-open [in (ByteArrayInputStream. compressed)]
    (let [uncompressed-len (long (read-le-varint in))
          out (ByteArrayOutputStream. (int uncompressed-len))]
      (loop []
        (let [tag-byte (.read in)]
          (when-not (neg? tag-byte)
            (let [tag (bit-and tag-byte 0x03)]
              (cond
                ;; Literal
                (zero? tag)
                (let [len-code (bit-shift-right (bit-and tag-byte 0xFC) 2)
                      length (long (cond
                                     (< len-code 60) (inc len-code)
                                     (= len-code 60) (inc (snappy-read-uint8 in))
                                     (= len-code 61) (inc (snappy-read-uint16-le in))
                                     (= len-code 62) (inc (snappy-read-uint24-le in))
                                     :else (inc (snappy-read-uint32-le in))))
                      buf (byte-array length)
                      nread (.read in buf)]
                  (when (not= (long nread) length)
                    (throw (ex-info "Snappy: couldn't read literal data"
                             {:expected length :got nread})))
                  (.write out buf 0 (int length)))

                ;; Copy 1-byte offset
                (= tag 1)
                (let [length (long (+ (bit-shift-right (bit-and tag-byte 0x1C) 2) 4))
                      offset (long (bit-or (bit-shift-left (bit-and tag-byte 0xE0) 3)
                                     (snappy-read-uint8 in)))
                      buf (.toByteArray out)
                      src-start (- (long (alength buf)) offset)]
                  (when (zero? offset)
                    (throw (ex-info "Snappy: offset cannot be 0" {})))
                  (dotimes [i (int length)]
                    (.write out (int (aget buf (int (+ src-start (long (mod (long i) offset)))))))))

                ;; Copy 2-byte offset
                (= tag 2)
                (let [length (long (inc (bit-shift-right (bit-and tag-byte 0xFC) 2)))
                      offset (snappy-read-uint16-le in)
                      buf (.toByteArray out)
                      src-start (- (long (alength buf)) offset)]
                  (when (zero? offset)
                    (throw (ex-info "Snappy: offset cannot be 0" {})))
                  (dotimes [i (int length)]
                    (.write out (int (aget buf (int (+ src-start (long (mod (long i) offset)))))))))

                ;; Copy 4-byte offset
                (= tag 3)
                (let [length (long (inc (bit-shift-right (bit-and tag-byte 0xFC) 2)))
                      offset (snappy-read-uint32-le in)
                      buf (.toByteArray out)
                      src-start (- (long (alength buf)) offset)]
                  (when (zero? offset)
                    (throw (ex-info "Snappy: offset cannot be 0" {})))
                  (dotimes [i (int length)]
                    (.write out (int (aget buf (int (+ src-start (long (mod (long i) offset)))))))))))
            (recur))))
      (let [result (.toByteArray out)]
        (when (not= (long (alength result)) uncompressed-len)
          (throw (ex-info "Snappy: wrong decompressed length"
                   {:expected uncompressed-len :got (alength result)})))
        result))))

;; =============================================================================
;; Binary helpers
;; =============================================================================

(defn- read-uint16-le ^long [^bytes buf ^long offset]
  (bit-or (bit-and (long (aget buf (int offset))) 0xFF)
    (bit-shift-left (bit-and (long (aget buf (int (inc offset)))) 0xFF) 8)))

(defn- read-uint32-le ^long [^bytes buf ^long offset]
  (bit-or (bit-and (long (aget buf (int offset))) 0xFF)
    (bit-shift-left (bit-and (long (aget buf (int (+ offset 1)))) 0xFF) 8)
    (bit-shift-left (bit-and (long (aget buf (int (+ offset 2)))) 0xFF) 16)
    (bit-shift-left (bit-and (long (aget buf (int (+ offset 3)))) 0xFF) 24)))

(defn- read-uint64-le ^long [^bytes buf ^long offset]
  (let [bb (ByteBuffer/wrap buf (int offset) 8)]
    (.order bb ByteOrder/LITTLE_ENDIAN)
    (.getLong bb)))

(defn- varint-from-bytes
  "Reads a varint from byte array starting at offset.
   Returns [value bytes-consumed]."
  [^bytes buf ^long offset ^long limit]
  (let [buf-len (long (alength buf))
        max-bytes (long (min 10 (- buf-len offset)))]
    (loop [i (int 0), result (long 0)]
      (if (or (>= i (int max-bytes)) (>= i (int limit)))
        [result (long i)]
        (let [b (bit-and (long (aget buf (int (+ offset (long i))))) 0xFF)
              result (bit-or result (bit-shift-left (long (bit-and b 0x7f)) (* (long i) 7)))]
          (if (zero? (bit-and b 0x80))
            [result (long (inc i))]
            (recur (inc i) result)))))))

;; =============================================================================
;; Log file parsing
;; =============================================================================

(def ^:private ^:const LOG_BLOCK_SIZE 32768)
(def ^:private ^:const LOG_HEADER_SIZE 7)

(defn- concat-bytes
  "Concatenates two byte arrays."
  ^bytes [^bytes a ^bytes b]
  (let [baos (ByteArrayOutputStream. (+ (alength a) (alength b)))]
    (.write baos a 0 (alength a))
    (.write baos b 0 (alength b))
    (.toByteArray baos)))

(defn- parse-log-batches
  "Parses a .log file into raw batch byte arrays.
   Returns vec of [block-offset batch-bytes].
   Single flat loop — block boundaries are computed from position."
  [^bytes file-data]
  (let [file-len (long (alength file-data))]
    (loop [pos (long 0)
           in-record false
           block-acc nil
           start-offset (long 0)
           results (transient [])]
      (let [block-idx (quot pos (long LOG_BLOCK_SIZE))
            block-end (min (long (* (inc block-idx) (long LOG_BLOCK_SIZE))) file-len)]
        (cond
          ;; Past end of file
          (>= pos file-len)
          (persistent! results)

          ;; Not enough room for a header in this block → advance to next block
          (> (+ pos (long LOG_HEADER_SIZE)) block-end)
          (recur (long (* (inc block-idx) (long LOG_BLOCK_SIZE)))
            in-record block-acc start-offset results)

          :else
          (let [length (read-uint16-le file-data (+ pos 4))
                block-type (long (bit-and (long (aget file-data (int (+ pos 6)))) 0xFF))
                data-start (long (+ pos (long LOG_HEADER_SIZE)))
                data-end (long (+ data-start length))]
            (if (> data-end block-end)
              ;; Record extends past block → skip to next block
              (recur (long (* (inc block-idx) (long LOG_BLOCK_SIZE)))
                in-record block-acc start-offset results)
              (let [part (Arrays/copyOfRange file-data (int data-start) (int data-end))]
                (cond
                  ;; Full record (type 1)
                  (= block-type 1)
                  (recur data-end false nil 0
                    (conj! results [data-start part]))

                  ;; First of multi-part (type 2)
                  (= block-type 2)
                  (recur data-end true part data-start results)

                  ;; Middle of multi-part (type 3)
                  (= block-type 3)
                  (if in-record
                    (recur data-end true (concat-bytes ^bytes block-acc part)
                      start-offset results)
                    (recur data-end false nil 0 results))

                  ;; Last of multi-part (type 4)
                  (= block-type 4)
                  (if in-record
                    (recur data-end false nil 0
                      (conj! results [start-offset (concat-bytes ^bytes block-acc part)]))
                    (recur data-end false nil 0 results))

                  ;; Zero or unknown — skip
                  :else
                  (recur data-end in-record block-acc start-offset results))))))))))

(defn- parse-log-entries
  "Parses batch data into individual key-value records."
  [^bytes batch ^long batch-offset ^String origin-file]
  (when (>= (alength batch) 12)
    (let [seq-num (read-uint64-le batch 0)
          count-val (read-uint32-le batch 8)]
      (loop [i (long 0), pos (long 12), results (transient [])]
        (if (or (>= i count-val) (>= pos (long (alength batch))))
          (persistent! results)
          (let [state-byte (bit-and (long (aget batch (int pos))) 0xFF)
                state (if (zero? state-byte) :deleted :live)
                pos (inc pos)
                [key-len kl-bytes] (varint-from-bytes batch pos 5)
                pos (+ pos (long kl-bytes))
                key-data (Arrays/copyOfRange batch (int pos) (int (+ pos (long key-len))))
                pos (+ pos (long key-len))]
            (if (= state :deleted)
              (recur (inc i) pos
                (conj! results {:key key-data
                                :value (byte-array 0)
                                :seq (+ seq-num i)
                                :state state
                                :file-type :log
                                :origin origin-file
                                :offset batch-offset}))
              (let [[val-len vl-bytes] (varint-from-bytes batch pos 5)
                    pos (+ pos (long vl-bytes))
                    val-data (Arrays/copyOfRange batch (int pos) (int (+ pos (long val-len))))
                    pos (+ pos (long val-len))]
                (recur (inc i) pos
                  (conj! results {:key key-data
                                  :value val-data
                                  :seq (+ seq-num i)
                                  :state state
                                  :file-type :log
                                  :origin origin-file
                                  :offset batch-offset}))))))))))

(defn- read-file-bytes
  "Reads entire file into byte array."
  ^bytes [^File file]
  (let [len (int (.length file))
        data (byte-array len)]
    (with-open [fis (FileInputStream. file)]
      (loop [off (int 0)]
        (when (< off len)
          (let [n (.read fis data off (- len off))]
            (when (pos? n)
              (recur (+ off n)))))))
    data))

(defn- read-log-file
  "Reads all records from a .log file."
  [^File file]
  (let [file-data (read-file-bytes file)
        path (.getPath file)
        batches (parse-log-batches file-data)]
    (mapcat (fn [[offset batch]]
              (parse-log-entries batch (long offset) path))
      batches)))

;; =============================================================================
;; LDB (SSTable) file parsing
;; =============================================================================

(def ^:private ^:const LDB_FOOTER_SIZE 48)
(def ^:private ^:const LDB_BLOCK_TRAILER_SIZE 5)
(def ^:private ^:const LDB_MAGIC (unchecked-long 0xdb4775248b80fb57))

(defn- read-block-handle
  "Reads a BlockHandle (offset + length) from byte array.
   Returns {:offset long :length long :bytes-consumed long}."
  [^bytes buf ^long offset]
  (let [[bh-offset off-bytes] (varint-from-bytes buf offset 10)
        [bh-length len-bytes] (varint-from-bytes buf (+ offset (long off-bytes)) 10)]
    {:offset (long bh-offset)
     :length (long bh-length)
     :bytes-consumed (+ (long off-bytes) (long len-bytes))}))

(defn- read-ldb-block
  "Reads and optionally decompresses a block from an LDB file.
   Returns [block-bytes was-compressed?]."
  [^RandomAccessFile raf ^long offset ^long length]
  (.seek raf offset)
  (let [raw (byte-array length)
        trailer (byte-array LDB_BLOCK_TRAILER_SIZE)]
    (loop [off (int 0)]
      (when (< off (int length))
        (let [n (.read raf raw off (- (int length) off))]
          (when (pos? n)
            (recur (+ off n))))))
    (.readFully raf trailer)
    (let [compressed? (not (zero? (aget trailer 0)))]
      (if compressed?
        [(snappy-decompress raw) true]
        [raw false]))))

(defn- parse-block-entries
  "Parses entries from a data block using prefix compression.
   Returns vec of [key-bytes value-bytes entry-offset]."
  [^bytes block-data]
  (let [block-len (long (alength block-data))
        restart-cnt (read-uint32-le block-data (- block-len 4))
        restart-off (long (- block-len (* (inc restart-cnt) 4)))]
    (loop [pos (long 0), prev-key (byte-array 0), results (transient [])]
      (if (>= pos restart-off)
        (persistent! results)
        (let [[shared-len sl-bytes] (varint-from-bytes block-data pos 5)
              pos (+ pos (long sl-bytes))
              [non-shared-len nsl-bytes] (varint-from-bytes block-data pos 5)
              pos (+ pos (long nsl-bytes))
              [value-len vl-bytes] (varint-from-bytes block-data pos 5)
              pos (+ pos (long vl-bytes))
              key-data (let [key-buf (byte-array (+ (long shared-len) (long non-shared-len)))]
                         (when (pos? (long shared-len))
                           (System/arraycopy prev-key 0 key-buf 0 (int shared-len)))
                         (System/arraycopy block-data (int pos) key-buf (int shared-len) (int non-shared-len))
                         key-buf)
              pos (+ pos (long non-shared-len))
              val-data (Arrays/copyOfRange block-data (int pos) (int (+ pos (long value-len))))
              pos (+ pos (long value-len))]
          (recur pos key-data (conj! results [key-data val-data pos])))))))

(defn- read-ldb-file
  "Reads all records from an .ldb (SSTable) file."
  [^File file]
  (with-open [raf (RandomAccessFile. file "r")]
    (let [file-len (.length raf)
          path (.getPath file)]
      (.seek raf (- file-len (long LDB_FOOTER_SIZE)))
      (let [footer (byte-array LDB_FOOTER_SIZE)]
        (.readFully raf footer)
        (let [magic (read-uint64-le footer (- (long LDB_FOOTER_SIZE) 8))]
          (when (not= magic LDB_MAGIC)
            (throw (ex-info "Invalid LDB magic number"
                     {:file path
                      :expected (format "%016x" LDB_MAGIC)
                      :got (format "%016x" magic)}))))
        (let [meta-handle (read-block-handle footer 0)
              idx-handle (read-block-handle footer (:bytes-consumed meta-handle))
              [idx-block _] (read-ldb-block raf (:offset idx-handle) (:length idx-handle))
              idx-entries (parse-block-entries idx-block)]
          (doall
            (mapcat
              (fn [[_idx-key idx-val _]]
                (let [handle (read-block-handle ^bytes idx-val 0)
                      [data-block was-compressed?] (read-ldb-block raf
                                                     (:offset handle)
                                                     (:length handle))
                      entries (parse-block-entries data-block)]
                  (map (fn [[key-data val-data entry-offset]]
                         (let [key-len (long (alength ^bytes key-data))
                               user-key (if (>= key-len 8)
                                          (Arrays/copyOfRange ^bytes key-data 0 (int (- key-len 8)))
                                          key-data)
                               seq-num (if (>= key-len 8)
                                         (unsigned-bit-shift-right
                                           (read-uint64-le ^bytes key-data (- key-len 8))
                                           8)
                                         0)
                               state (if (>= key-len 8)
                                       (if (zero? (aget ^bytes key-data (int (- key-len 8))))
                                         :deleted :live)
                                       :live)]
                           {:key user-key
                            :value val-data
                            :seq seq-num
                            :state state
                            :file-type :ldb
                            :origin path
                            :offset entry-offset
                            :compressed was-compressed?}))
                    entries)))
              idx-entries)))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn- file-number
  "Extracts the numeric file number from a LevelDB filename (e.g. '000005.ldb' → 5)."
  ^long [^File file]
  (let [name (.getName file)]
    (try
      (Long/parseLong (.substring name 0 6) 16)
      (catch Exception _
        (try
          (Long/parseLong (first (str/split name #"\.")) 10)
          (catch Exception _ 0))))))

(defn read-records
  "Reads all records from a LevelDB directory.

   Returns a sequence of maps:
   {:key bytes, :value bytes, :seq long, :state :live/:deleted,
    :file-type :log/:ldb, :origin String, :offset long}

   Records are yielded from all .log and .ldb files, sorted by file number."
  [^String dir-path]
  (let [dir (File. dir-path)]
    (when (.isDirectory dir)
      (let [files (->> (.listFiles dir)
                    (filter (fn [^File f]
                              (and (.isFile f)
                                (let [name (.getName f)]
                                  (or (.endsWith name ".log")
                                    (.endsWith name ".ldb")
                                    (.endsWith name ".sst"))))))
                    (sort-by file-number))]
        (mapcat (fn [^File f]
                  (try
                    (let [name (.getName f)]
                      (if (.endsWith name ".log")
                        (read-log-file f)
                        (read-ldb-file f)))
                    (catch Exception e
                      (binding [*out* *err*]
                        (println (str "Warning: skipping " (.getPath f) ": " (.getMessage e))))
                      nil)))
          files)))))
