/**
 * ImageTerrier - The Terabyte Retriever for Images
 * Webpage: http://www.imageterrier.org/
 * Contact: jsh2@ecs.soton.ac.uk
 * Electronics and Computer Science, University of Southampton
 * http://www.ecs.soton.ac.uk/
 *
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Original Code is AbstractRANSACGeomModifier.java
 *
 * The Original Code is Copyright (C) 2011 the University of Southampton
 * and the original contributors.
 * All Rights Reserved.
 *
 * Contributor(s):
 *   Jonathon Hare <jsh2@ecs.soton.ac.uk> (original contributor)
 *   Sina Samangooei <ss@ecs.soton.ac.uk>
 *   David Dupplaw <dpd@ecs.soton.ac.uk>
 */
package org.imageterrier.dsms;

import java.util.ArrayList;
import java.util.List;

import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.imageterrier.basictools.ApplicationSetupUtils;
import org.imageterrier.locfile.PositionSpec;
import org.imageterrier.locfile.QLFDocument;
import org.imageterrier.querying.parser.QLFDocumentQuery;
import org.imageterrier.structures.PositionInvertedIndex;
import org.openimaj.math.geometry.point.Point2d;
import org.openimaj.math.geometry.point.Point2dImpl;
import org.openimaj.math.geometry.transforms.residuals.AlgebraicResidual2d;
import org.openimaj.math.model.EstimatableModel;
import org.openimaj.math.model.fit.RANSAC;
import org.openimaj.util.pair.Pair;
import org.terrier.matching.MatchingQueryTerms;
import org.terrier.matching.ResultSet;
import org.terrier.matching.dsms.DocumentScoreModifier;
import org.terrier.structures.BitIndexPointer;
import org.terrier.structures.Index;
import org.terrier.structures.Lexicon;
import org.terrier.structures.LexiconEntry;
import org.terrier.utility.ApplicationSetup;

/**
 * Abstract base class for {@link DocumentScoreModifier}s that use RANSAC to fit
 * a geometric model to matching pairs of visual terms.
 * 
 * Use of this class (or subclasses) requires that you are using a
 * {@link PositionInvertedIndex} with a {@link PositionSpec} that has encoded
 * spatial (x and y) coordinates for each term posting.
 * 
 * @author Jonathon Hare <jsh2@ecs.soton.ac.uk>
 * 
 */
public abstract class AbstractRANSACGeomModifier implements DocumentScoreModifier {
	public enum ScoringScheme {
		BINARY {
			@Override
			public double score(double originalScore, boolean didMatch, int nInliers, int nOutliers) {
				return (didMatch ? 1.0 : 0.0) * originalScore;
			}
		},
		MULTIPLY_NUM_MATCHES {
			@Override
			public double score(double originalScore, boolean didMatch, int nInliers, int nOutliers) {
				return (didMatch ? nInliers : 0.0) * originalScore;
			}
		},
		NUM_MATCHES {
			@Override
			public double score(double originalScore, boolean didMatch, int nInliers, int nOutliers) {
				return (didMatch ? nInliers : 0.0);
			}
		},
		MULTIPLY_PERCENTAGE_MATCHES {
			@Override
			public double score(double originalScore, boolean didMatch, int nInliers, int nOutliers) {
				return (didMatch ? (double) nInliers / (double) (nInliers + nOutliers) : 0.0) * originalScore;
			}
		},
		PERCENTAGE_MATCHES {
			@Override
			public double score(double originalScore, boolean didMatch, int nInliers, int nOutliers) {
				return (didMatch ? (double) nInliers / (double) (nInliers + nOutliers) : 0.0);
			}
		};

		public abstract double score(double originalScore, boolean didMatch, int nInliers, int nOutliers);
	}

	/** The number of documents to apply re-ranking to */
	public static final String N_DOCS_TO_RERANK = "GeomScoreModifier.num_docs_rerank";

	/**
	 * A threshold for removing matching pairs where one of the terms matches
	 * many terms
	 */
	public static final String FILTERING_THRESHOLD = "GeomScoreModifier.filter_thresh";

	/** The percentage of matches required for a successful match */
	public static final String RANSAC_PER_MATCHES_SUCCESS = "GeomScoreModifier.ransac_per_success_matches";

	/** The percentage of matches required for a successful match */
	public static final String RANSAC_MAX_ITER = "GeomScoreModifier.ransac_max_iter";

	/** The scoring scheme */
	public static final String SCORING_SCHEME = "GeomScoreModifier.scoring";

	@Override
	public boolean modifyScores(Index index, MatchingQueryTerms queryTerms, ResultSet resultSet) {
		final Lexicon<String> lexicon = index.getLexicon();
		final PositionInvertedIndex invertedIndex = (PositionInvertedIndex) index.getInvertedIndex();
		final PositionSpec spec = invertedIndex.getPositionSpec();

		final double[] scores = resultSet.getScores();
		final QLFDocumentQuery<?> query = (QLFDocumentQuery<?>) queryTerms.getQuery();
		final QLFDocument<?> queryDoc = query.getDocument();

		final int nd2r = ApplicationSetupUtils.getProperty(N_DOCS_TO_RERANK, resultSet.getResultSize());
		final int nRerankDocs = (nd2r <= 0 ? resultSet.getResultSize() : (nd2r > resultSet.getResultSize() ? resultSet
				.getResultSize() : nd2r));

		queryDoc.reset();

		// record matches per doc:
		final TIntObjectHashMap<List<Pair<Point2d>>> allMatchingPoints = new TIntObjectHashMap<List<Pair<Point2d>>>();
		String queryTerm;
		while (!queryDoc.endOfDocument()) {
			queryTerm = queryDoc.getNextTerm();
			final LexiconEntry le = lexicon.getLexiconEntry(queryTerm);
			if (le != null) {
				final int[] queryPos = spec.getPosition(queryDoc);

				final TIntObjectHashMap<int[][]> matchDocs = invertedIndex.getPositions((BitIndexPointer) le);

				for (int i = 0; i < nRerankDocs; i++) {
					final int docid = resultSet.getDocids()[i];
					final int[][] matchingPoints = matchDocs.get(docid);

					if (matchingPoints != null) {
						List<Pair<Point2d>> ml = allMatchingPoints.get(docid);
						if (ml == null)
							allMatchingPoints.put(docid, ml = new ArrayList<Pair<Point2d>>(1));

						addMatches(queryPos, matchingPoints, ml);
					}
				}
			}
		}

		// now to do homography stuff
		final EstimatableModel<Point2d, Point2d> hm = makeModel();

		final int nIter = ApplicationSetupUtils.getProperty(RANSAC_MAX_ITER, 100);
		final double perItemsSuccess = ApplicationSetupUtils.getProperty(RANSAC_PER_MATCHES_SUCCESS, 0.5);

		final ScoringScheme scoringScheme = ScoringScheme.valueOf(ApplicationSetup.getProperty(SCORING_SCHEME,
				ScoringScheme.NUM_MATCHES.name()));

		RANSAC.StoppingCondition stoppingCondition = null;

		if (perItemsSuccess > 1) {
			stoppingCondition = new RANSAC.NumberInliersStoppingCondition((int) perItemsSuccess);
		} else if (perItemsSuccess > 0 && perItemsSuccess <= 1) {
			stoppingCondition = new RANSAC.PercentageInliersStoppingCondition(perItemsSuccess);
		} else if (perItemsSuccess < 0 && perItemsSuccess > -1) {
			stoppingCondition = new RANSAC.ProbabilisticMinInliersStoppingCondition(Math.abs(perItemsSuccess));
		} else {
			stoppingCondition = new RANSAC.BestFitStoppingCondition();
		}

		@SuppressWarnings({ "rawtypes", "unchecked" })
		final RANSAC<Point2d, Point2d, ?> ransac = new RANSAC<Point2d, Point2d, EstimatableModel<Point2d, Point2d>>(hm,
				new AlgebraicResidual2d(), getTolerance(),
				nIter, stoppingCondition, false);

		for (int i = 0; i < nRerankDocs; i++) {
			final int docid = resultSet.getDocids()[i];
			final List<Pair<Point2d>> data = allMatchingPoints.get(docid);
			filter(data);

			final boolean didFit = ransac.fitData(data);
			final int nInliers = ransac.getInliers().size();
			final int nOutliers = ransac.getOutliers().size();
			scores[i] = scoringScheme.score(scores[i], didFit, nInliers, nOutliers);
		}

		return true;
	}

	/**
	 * Make a new model instance.
	 * 
	 * @return the model
	 */
	public abstract EstimatableModel<Point2d, Point2d> makeModel();

	protected void filter(List<Pair<Point2d>> in) {
		final float thresh = ApplicationSetupUtils.getProperty(FILTERING_THRESHOLD, 0.5f);

		if (thresh <= 0)
			return;

		// remove matches where there is a big inbalance
		final TObjectIntHashMap<Point2d> forwardMatches = new TObjectIntHashMap<Point2d>();
		final TObjectIntHashMap<Point2d> reverseMatches = new TObjectIntHashMap<Point2d>();

		for (final Pair<Point2d> m : in) {
			forwardMatches.adjustOrPutValue(m.firstObject(), 1, 1);
			reverseMatches.adjustOrPutValue(m.secondObject(), 1, 1);
		}

		final List<Pair<Point2d>> remove = new ArrayList<Pair<Point2d>>();
		for (final Pair<Point2d> m : in) {
			final float score = (float) Math.min(forwardMatches.get(m.firstObject()),
					reverseMatches.get(m.secondObject()))
					/ (float) Math.max(forwardMatches.get(m.firstObject()), reverseMatches.get(m.secondObject()));

			if (score < thresh)
				remove.add(m);
		}

		in.removeAll(remove);
	}

	@Override
	public abstract AbstractRANSACGeomModifier clone();

	protected void addMatches(int[] qp, int[][] tps, List<Pair<Point2d>> matches) {
		final Point2d qpt = new Point2dImpl(qp[0], qp[1]);

		for (final int[] tp : tps) {
			final Point2d tpt = new Point2dImpl(tp[0], tp[1]);
			matches.add(new Pair<Point2d>(qpt, tpt));
		}
	}

	protected abstract double getTolerance();
}
