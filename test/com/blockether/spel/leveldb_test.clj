(ns com.blockether.spel.leveldb-test
  "Tests for the LevelDB reader — varint parsing, Snappy decompression,
   log file parsing, and LDB file format validation."
  (:require
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.leveldb :as sut])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream File]
   [java.nio ByteBuffer ByteOrder]
   [java.util Arrays]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- encode-varint
  "Encodes an unsigned long as a LEB128 varint byte array."
  ^bytes [^long value]
  (let [baos (ByteArrayOutputStream.)]
    (loop [v value]
      (if (zero? (bit-and v (bit-not 0x7f)))
        (.write baos (int v))
        (do
          (.write baos (int (bit-or (bit-and v 0x7f) 0x80)))
          (recur (unsigned-bit-shift-right v 7)))))
    (.toByteArray baos)))

(defn- write-le-uint16 ^bytes [^long value]
  (let [bb (ByteBuffer/allocate 2)]
    (.order bb ByteOrder/LITTLE_ENDIAN)
    (.putShort bb (short value))
    (.array bb)))

(defn- write-le-uint32 ^bytes [^long value]
  (let [bb (ByteBuffer/allocate 4)]
    (.order bb ByteOrder/LITTLE_ENDIAN)
    (.putInt bb (int value))
    (.array bb)))

(defn- write-le-uint64 ^bytes [^long value]
  (let [bb (ByteBuffer/allocate 8)]
    (.order bb ByteOrder/LITTLE_ENDIAN)
    (.putLong bb value)
    (.array bb)))

;; =============================================================================
;; Varint Tests
;; =============================================================================

(defdescribe varint-test
  "Tests for read-le-varint — LEB128 varint decoding"

  (describe "single-byte varints"
    (it "reads 0"
      (let [in (ByteArrayInputStream. (byte-array [0]))]
        (expect (= 0 (sut/read-le-varint in)))))

    (it "reads 1"
      (let [in (ByteArrayInputStream. (byte-array [1]))]
        (expect (= 1 (sut/read-le-varint in)))))

    (it "reads 127 (max single byte)"
      (let [in (ByteArrayInputStream. (byte-array [127]))]
        (expect (= 127 (sut/read-le-varint in))))))

  (describe "multi-byte varints"
    (it "reads 128 (first two-byte varint)"
      (let [in (ByteArrayInputStream. (byte-array [(unchecked-byte 0x80) 0x01]))]
        (expect (= 128 (sut/read-le-varint in)))))

    (it "reads 300"
      (let [in (ByteArrayInputStream. (encode-varint 300))]
        (expect (= 300 (sut/read-le-varint in)))))

    (it "reads 16384"
      (let [in (ByteArrayInputStream. (encode-varint 16384))]
        (expect (= 16384 (sut/read-le-varint in)))))

    (it "reads large value (1000000)"
      (let [in (ByteArrayInputStream. (encode-varint 1000000))]
        (expect (= 1000000 (sut/read-le-varint in))))))

  (describe "round-trip encoding"
    (it "round-trips various values"
      (doseq [v [0 1 127 128 255 256 16383 16384 100000 2147483647]]
        (let [encoded (encode-varint v)
              decoded (sut/read-le-varint (ByteArrayInputStream. encoded))]
          (expect (= v decoded))))))

  (describe "EOF handling"
    (it "returns nil on empty stream"
      (let [in (ByteArrayInputStream. (byte-array 0))]
        (expect (nil? (sut/read-le-varint in)))))))

;; =============================================================================
;; Snappy Decompression Tests
;; =============================================================================

(defdescribe snappy-test
  "Tests for snappy-decompress — pure Java Snappy decompression"

  (describe "literal-only data"
    (it "decompresses a single literal block"
      ;; Snappy: varint(uncompressed_len) + literal tag + data
      ;; "Hello" = 5 bytes
      ;; Varint(5) = 0x05
      ;; Literal tag: len 5 → (5-1)<<2 | 0x00 = 0x10
      ;; Data: "Hello"
      (let [hello-bytes (.getBytes "Hello" "UTF-8")
            compressed (byte-array (concat [0x05 0x10] (seq hello-bytes)))
            result (sut/snappy-decompress compressed)]
        (expect (= "Hello" (String. result "UTF-8"))))))

  (describe "literal with copy"
    (it "decompresses data with back-reference"
      ;; "AAAA" using literal "A" + copy
      ;; Uncompressed len = 4, varint(4) = 0x04
      ;; Literal "A" (1 byte): tag = (1-1)<<2|0 = 0x00, data = 0x41
      ;; Copy 1-byte: tag = (3-4)... wait, copy1 length = ((tag&0x1C)>>2)+4, min is 4
      ;; So we need at least 4 bytes of copy. Let's do "AAAAA" (5 bytes):
      ;; Literal "A" (1 byte): tag = 0x00, data = 0x41
      ;; Copy 1-byte: length 4, offset 1
      ;; tag: ((4-4)<<2 & 0x1C) | ((1>>3)<<5 & 0xE0) | 0x01 = 0x01
      ;; next byte: offset & 0xFF = 0x01
      (let [compressed (byte-array [0x05 ;; uncompressed length = 5
                                    0x00 0x41 ;; literal "A" (1 byte)
                                    0x01 0x01]) ;; copy1: length=4, offset=1
            result (sut/snappy-decompress compressed)]
        (expect (= "AAAAA" (String. result "UTF-8"))))))

  (describe "empty input"
    (it "decompresses zero-length data"
      (let [compressed (byte-array [0x00]) ;; uncompressed length = 0
            result (sut/snappy-decompress compressed)]
        (expect (zero? (alength result)))))))

;; =============================================================================
;; Log File Parsing Tests
;; =============================================================================

(defn- build-log-batch
  "Builds a raw log batch with the given key-value entries.
   Returns byte array: seq(8) + count(4) + entries..."
  [^long seq-num entries]
  (let [baos (ByteArrayOutputStream.)]
    ;; Sequence number (8 bytes LE)
    (.write baos (write-le-uint64 seq-num) 0 8)
    ;; Count (4 bytes LE)
    (.write baos (write-le-uint32 (count entries)) 0 4)
    ;; Entries
    (doseq [{:keys [state key value]} entries]
      ;; State byte: 0=deleted, 1=live
      (.write baos (int (if (= state :deleted) 0 1)))
      ;; Key length (varint) + key data
      (let [key-bytes (.getBytes ^String key "UTF-8")
            val-bytes (if value (.getBytes ^String value "UTF-8") (byte-array 0))]
        (.write baos (encode-varint (alength key-bytes)) 0 (alength (encode-varint (alength key-bytes))))
        (.write baos key-bytes 0 (alength key-bytes))
        (when (not= state :deleted)
          (.write baos (encode-varint (alength val-bytes)) 0 (alength (encode-varint (alength val-bytes))))
          (.write baos val-bytes 0 (alength val-bytes)))))
    (.toByteArray baos)))

(defn- build-log-record
  "Wraps a batch in a Full log record (type=1) with CRC + length + type header."
  ^bytes [^bytes batch]
  (let [baos (ByteArrayOutputStream.)
        len (alength batch)]
    ;; CRC32 placeholder (4 bytes)
    (.write baos (byte-array 4) 0 4)
    ;; Length (2 bytes LE)
    (.write baos (write-le-uint16 len) 0 2)
    ;; Type = Full (1)
    (.write baos (int 1))
    ;; Batch data
    (.write baos batch 0 len)
    (.toByteArray baos)))

(defdescribe log-file-test
  "Tests for log file parsing"

  (describe "single batch with entries"
    (it "parses a simple log file with one batch"
      (let [batch (build-log-batch 100
                    [{:state :live :key "testkey" :value "testval"}
                     {:state :live :key "key2" :value "val2"}])
            record (build-log-record batch)
            ;; Create temp dir with this log file
            dir (File/createTempFile "ldb_test" "")
            _ (.delete dir)
            _ (.mkdirs dir)
            log-file (File. dir "000001.log")]
        (.deleteOnExit dir)
        (.deleteOnExit log-file)
        (with-open [out (java.io.FileOutputStream. log-file)]
          (.write out record 0 (alength record)))
        (let [records (sut/read-records (.getPath dir))]
          (expect (= 2 (count records)))
          (expect (= "testkey" (String. ^bytes (:key (first records)) "UTF-8")))
          (expect (= "testval" (String. ^bytes (:value (first records)) "UTF-8")))
          (expect (= :live (:state (first records))))
          (expect (= 100 (:seq (first records))))
          (expect (= "key2" (String. ^bytes (:key (second records)) "UTF-8")))
          (expect (= 101 (:seq (second records))))))))

  (describe "batch with deletion"
    (it "correctly marks deleted entries"
      (let [batch (build-log-batch 200
                    [{:state :deleted :key "deleted-key"}
                     {:state :live :key "live-key" :value "live-val"}])
            record (build-log-record batch)
            dir (File/createTempFile "ldb_del" "")
            _ (.delete dir)
            _ (.mkdirs dir)
            log-file (File. dir "000001.log")]
        (.deleteOnExit dir)
        (.deleteOnExit log-file)
        (with-open [out (java.io.FileOutputStream. log-file)]
          (.write out record 0 (alength record)))
        (let [records (sut/read-records (.getPath dir))]
          (expect (= 2 (count records)))
          (expect (= :deleted (:state (first records))))
          (expect (= :live (:state (second records)))))))))

;; =============================================================================
;; read-records with nonexistent directory
;; =============================================================================

(defdescribe read-records-edge-cases-test
  "Edge case tests for read-records"

  (describe "nonexistent directory"
    (it "returns nil for missing directory"
      (expect (nil? (sut/read-records "/nonexistent/path/to/leveldb")))))

  (describe "empty directory"
    (it "returns empty seq for empty directory"
      (let [dir (File/createTempFile "empty_ldb" "")]
        (.delete dir)
        (.mkdirs dir)
        (.deleteOnExit dir)
        (expect (empty? (sut/read-records (.getPath dir))))))))

;; =============================================================================
;; Integration test — real Chrome localStorage (macOS only)
;; =============================================================================

(defdescribe chrome-localstorage-integration-test
  "Integration test — reads real Chrome localStorage on macOS"

  (describe "reads real Chrome localStorage"
    (it "returns records from a real Chrome profile"
      (let [ls-dir (str (System/getProperty "user.home")
                     "/Library/Application Support/Google/Chrome/Profile 1/Local Storage/leveldb")]
        (when (.isDirectory (File. ls-dir))
          (let [records (sut/read-records ls-dir)]
            (expect (pos? (count records)))
            ;; Every record should have :key, :value, :seq, :state
            (doseq [r (take 10 records)]
              (expect (some? (:key r)))
              (expect (some? (:value r)))
              (expect (some? (:seq r)))
              (expect (contains? #{:live :deleted} (:state r))))))))))
