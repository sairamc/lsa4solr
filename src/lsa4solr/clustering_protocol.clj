(ns lsa4solr.clustering-protocol
  (:use [clojure.contrib.seq-utils :only [indexed]]
	[lsa4solr core hadoop-utils lucene-utils]
	[incanter.core]
	[incanter.stats])
  (:import (cern.colt.matrix.tdouble.algo.decomposition DoubleSingularValueDecomposition)
	   (incanter Matrix)))

(defprotocol LSAClusteringEngineProtocol
  (get-mapper [self terms vec-ref ndocs])
  (init-frequency-vector [self n])
  (get-frequency-matrix [self reader field terms hits])
  (svd [self k m])
  (cluster-docs [self reader doc-seq svd-factorization k num-clusters id-field]))

(defn get-mapper-common [terms vec-ref ndocs update-ref]
  (proxy [org.apache.lucene.index.TermVectorMapper]
      []
    (map [term frequency offsets positions]
	 (let [term-entry ((keyword term) terms)]
	   (dosync 
	    (update-ref vec-ref (- (:idx term-entry) 1)  (* frequency (:idf term-entry))))))
    (setExpectations [field numTerms storeOffsets storePositions]
		     nil)))


(deftype LocalLSAClusteringEngine 
  []
  LSAClusteringEngineProtocol
  (get-mapper [self terms vec-ref ndocs]
	      (get-mapper-common terms vec-ref ndocs
				 (fn [vec-ref idx weight]
				   (alter vec-ref assoc idx weight))))
  
  (init-frequency-vector [self n]
			 (ref (vec (repeat n 0))))
  
  (get-frequency-matrix [self reader field terms hits]
			(trans (matrix (extract-frequency-vectors
					reader
					(fn [n] (init-frequency-vector self n))
					(fn [terms vec-ref ndocs] 
					  (get-mapper self
						      terms
						      vec-ref
						      ndocs))
					field
					terms
					hits))))
  (svd [self k m] 
       (let [svd-result (DoubleSingularValueDecomposition. m)]
	 {:U (Matrix. (.getU svd-result))
	  :S (Matrix. (.getS svd-result))
	  :V (Matrix. (.getV svd-result))}))
  
  (cluster-docs [self reader doc-seq svd-factorization k num-clusters id-field]
		(let [U (:U svd-factorization)
		      S (:S svd-factorization)
		      V (:V svd-factorization)
		      VS (mmult (sel V :cols (range 0 k)) 
				(sel (sel S :cols (range 0 k)) :rows (range 0 k)))
		      pca (principal-components VS)
		      pcs (sel (:rotation pca) :cols (range 0 num-clusters))
		      sims (map (fn [docvec] 
				  (sort-by #(second %) 
					   (map (fn [pc] 
						  [(first pc) (cosine-similarity docvec (second pc))]) 
						(indexed (trans pcs))))) 
				VS)
		      labels (clojure.contrib.seq-utils/indexed (map #(first (last %)) sims))
		      clusters (reduce #(merge %1 %2) 
				       {} 
				       (map (fn [x] {(keyword (str x)) 
						     (map #(get-docid reader
								      id-field
								      (nth doc-seq %)) 
							  (map first
							       (filter #(= (second %) x) 
								       labels)))})
					    (range 0 num-clusters)))]
		  clusters))
  )

(deftype DistributedLSAClusteringEngine 
  [] 
  LSAClusteringEngineProtocol
  (get-mapper [self terms vec-ref ndocs]
	      (get-mapper-common terms vec-ref ndocs
				 (fn [vec-ref idx weight]
				   (.setQuick @vec-ref idx weight))))

  (init-frequency-vector [self n]
			 (ref (new org.apache.mahout.math.RandomAccessSparseVector n)))
  
  (get-frequency-matrix [self reader field terms hits]
			(let [rows (to-array-of org.apache.mahout.math.Vector
						(extract-frequency-vectors
						 reader
						 (fn [n] (init-frequency-vector self n))
						 (fn [terms vec-ref ndocs] 
						   (get-mapper self
							       terms
							       vec-ref
							       ndocs))
						 field
						 terms
						 hits))]
			  (.transpose (new org.apache.mahout.math.SparseRowMatrix 
					   (int-array [(count rows) (count terms)]) 
					   rows))))

  (svd [self k m]
       (let [hadoop-conf (new org.apache.hadoop.conf.Configuration)
	     writer (write-matrix hadoop-conf m)
	     dm (doto (new org.apache.mahout.math.hadoop.DistributedRowMatrix 
			   "/tmp/distMatrix" 
			   "/tmp/hadoopOut"
			   (.numRows m)
			   (.numCols m))
		  (.configure (new org.apache.hadoop.mapred.JobConf hadoop-conf)))
	     eigenvalues (new java.util.ArrayList)
	     eigenvectors (new org.apache.mahout.math.DenseMatrix (+ k 2) (.numCols m))
	     decomposer (doto (new org.apache.mahout.math.hadoop.decomposer.DistributedLanczosSolver)
			  (.solve dm (+ k 2) eigenvectors eigenvalues false))]
	 {:eigenvectors eigenvectors
	  :eigenvalues eigenvalues
	  :U nil
	  :S (diag (map #(sqrt %) (reverse (take-last k eigenvalues))))
	  :V (trans 
	      (matrix (to-array (map (fn [vec] (map #(.get %1) 
						    (iterator-seq (.iterateAll (.vector vec)))))
				     (take k eigenvectors)))))}))

  (cluster-docs [self reader doc-seq svd-factorization k num-clusters id-field]
		(let [U (:U svd-factorization)
		      S (:S svd-factorization)
		      V (:V svd-factorization)
		      VS (mmult (sel V :cols (range 0 k)) 
				(sel (sel S :cols (range 0 k)) :rows (range 0 k)))
		      pca (principal-components VS)
		      pcs (sel (:rotation pca) :cols (range 0 num-clusters))
		      sims (map (fn [docvec] 
				  (sort-by #(second %) 
					   (map (fn [pc] 
						  [(first pc) (cosine-similarity docvec (second pc))]) 
						(indexed (trans pcs))))) 
				VS)
		      labels (clojure.contrib.seq-utils/indexed (map #(first (last %)) sims))
		      clusters (reduce #(merge %1 %2) 
				       {} 
				       (map (fn [x] {(keyword (str x)) 
						     (map #(get-docid reader
								      id-field
								      (nth doc-seq %)) 
							  (map first
							       (filter #(= (second %) x) 
								       labels)))})
					    (range 0 num-clusters)))]
		  clusters))
  )

