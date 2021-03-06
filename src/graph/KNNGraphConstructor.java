package graph;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;

import feature.DynamicSparseVector;
import feature.SparseVector;

public class KNNGraphConstructor {
	SparseVector[] features;
	EdgeBuilderThread[] threads;
	EdgeList[] edges;
	int numNodes;
	int numNeighbors;
	boolean mutualKNN;
	int numThreads;
	double similarityThreshold;
	
	public KNNGraphConstructor() { }
	
	public KNNGraphConstructor(SparseVector[] features, int numNeighbors,
			boolean mutualKNN, double similarityThreshold, int numThreads) {
		this.features = features;
		this.numNodes = features.length;
		this.numNeighbors = numNeighbors;
		this.mutualKNN = mutualKNN;
		this.similarityThreshold = similarityThreshold;
		this.numThreads = numThreads;
	}
	
	public void run() {
		System.out.println(String.format("Starting to build graph with " +
				"%d nodes, and %d neighbors with %s KNN method. ",
				numNodes, numNeighbors, (mutualKNN ? "mutual" : "symmetric")));
		edges = new EdgeList[numNodes];
		for(int i = 0; i < numNodes; i++) {
			edges[i] = new EdgeList(numNeighbors);
		}
		int batchSize = numNodes / numThreads;
		EdgeBuilderThread[] threads = new EdgeBuilderThread[numThreads];
		for (int i = 0; i < numThreads; i++) {
			int start = i * batchSize;
			int end = (i == numThreads - 1 ? numNodes : start + batchSize);
			threads[i] = new EdgeBuilderThread(i, start, end);
			threads[i].start();
		}
		for (int i = 0; i < numThreads; i++) {
			try {
				threads[i].join();
			} catch (InterruptedException e) { }
		}
		symmetrifyGraph();
	}

	public SparseVector[] getEdgeList() {
		SparseVector[] tEdges = new SparseVector[numNodes];
		for (int i = 0; i < numNodes; i++) {
			DynamicSparseVector tEdge = new DynamicSparseVector();
			for (Iterator<Edge> itr = edges[i].iterator(); itr.hasNext(); ) {
				Edge e = itr.next();
				if (!edges[e.neighbor].contains(i) || e.weight <= 0) {
					continue;
				}
				tEdge.add(e.neighbor, e.weight);
			}
			tEdges[i] = new SparseVector(tEdge);
		}
		return tEdges;
	}
	
	protected synchronized void print(String string) {
		System.out.print(string);
	}

	protected class EdgeBuilderThread extends Thread {
		int start, end, id;
		boolean finished;

		EdgeBuilderThread(int id, int start, int end) {
			this.id = id;
			this.start = start;
			this.end = end;
			this.finished = false;
			print("Thread " + id + " processing nodes " + start + " to "
					+ end + "\n");
		}

		@Override
		public void run() {
			long startTime = System.currentTimeMillis();
			for (int i = start; i < end; i++) {
				for (int j = 0; j < numNodes; j++) {
					if (i != j) {
						double sim = features[i].dotProduct(features[j]);
						if (sim >= similarityThreshold) {
							edges[i].add(new Edge(j, sim));
						}
					}
				}
				if (i > start && (i - start) % 1000 == 0) {
					int offset = (i - start);
					long timeElapsedMin = 1 + (System.currentTimeMillis()
							- startTime) / 60000;
					long nodesPerMin = offset / timeElapsedMin;
					print("Thread" + id + "\t::" + offset
							+ " nodes finished, " + (end -start - offset)
							+ " nodes to go. " + nodesPerMin
							+ " nodes per minute\n");
				}
			}
		}
	}
	
	protected void symmetrifyGraph() {
		for (int i = 0; i < numNodes; i++) {
			edges[i].freeze();
		}
		if (!mutualKNN) {
			for (int i = 0; i < numNodes; i++) {
				for (Iterator<Edge> itr = edges[i].iterator();
						itr.hasNext(); ) {
					Edge e = itr.next();
					edges[e.neighbor].symAdd(new Edge(i, e.weight));
				}
			}
		}
		// Print Graph info
		int nrEdges = 0, nrEmptyNodes = 0;
		double weightNorm = 0;
		double avgDegree = 0, maxDegree = -1, minDegree = Double.MAX_VALUE;
		double avgFreqEmpty = 0, avgFreqNonEmpty = 0; 
		for (int i = 0; i < numNodes; i++) {
			int degree = 0;
			for (Iterator<Edge> itr = edges[i].iterator(); itr.hasNext(); ) {
				Edge e = itr.next();
				if (!edges[e.neighbor].contains(i) || e.weight <= 0) {
					continue;
				}
				degree ++;
				nrEdges ++;
				weightNorm += e.weight;
			}
			if (degree == 0) {
				++ nrEmptyNodes;
			}
			avgDegree += degree;
			maxDegree = Math.max(maxDegree, degree);
			minDegree = Math.min(minDegree, degree);
		}
		System.out.println("graph weight norm: " + weightNorm +
				"\t num edges:\t" + nrEdges);
		System.out.println("Averaged node degree: " + avgDegree / numNodes + 
				"\n Maximum node degree: " + maxDegree + 
				"\n Minimum node degree: " + minDegree + 
				"\n Number of nodes: " + numNodes + 
				String.format("\n Number of empty nodes: %d (%.2f%%)", 
						nrEmptyNodes, 100.0 * nrEmptyNodes / numNodes));
		System.out.println(String.format(
				"Averaged empty node frequency: %f\n" + 
				"Averaged non-empty node frequency: %f\n",
				avgFreqEmpty / nrEmptyNodes, 
				avgFreqNonEmpty / (numNodes - nrEmptyNodes)));
	}
	
	public void saveGraph(String graphPath, String ngramPath)
			throws UnsupportedEncodingException, FileNotFoundException,
				IOException {
		System.out.println("Saving graph to file: " + graphPath);
		BufferedWriter fout = new BufferedWriter(new FileWriter(graphPath));
		for (int i = 0; i < numNodes; i++) {
			for (Iterator<Edge> itr = edges[i].iterator(); itr.hasNext(); ) {
				Edge e = itr.next();
				if (!edges[e.neighbor].contains(i) || e.weight <= 0) {
					continue;
				}
				fout.write(String.format("%d\t%d\t%.12f\n", i + 1,
						e.neighbor + 1, e.weight));
			}
		}
		fout.close();
		/*
		System.out.println("Saving ngram index to file: " + ngramPath);
		fout = new BufferedWriter(new FileWriter(ngramPath));
		for (int nid= 0; nid < numNodes; nid++ ) {
			String ngram = ngramCounts.index2str.get(nid);
			fout.write(String.format("%d\t%s\n", nid + 1, ngram));
		}
		fout.close();
		*/
	}
}
