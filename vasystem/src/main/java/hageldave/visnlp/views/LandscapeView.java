package hageldave.visnlp.views;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.ejml.simple.SimpleMatrix;
import org.ejml.simple.SimpleSVD;
import org.ejml.simple.ops.SimpleOperations_DDRM;

import hageldave.imagingkit.core.Pixel;
import hageldave.jplotter.canvas.BlankCanvas;
import hageldave.jplotter.color.ColorMap;
import hageldave.jplotter.color.DefaultColorMap;
import hageldave.jplotter.color.SimpleColorMap;
import hageldave.jplotter.coordsys.TickMarkGenerator;
import hageldave.jplotter.interaction.CoordSysPanning;
import hageldave.jplotter.interaction.CoordSysScrollZoom;
import hageldave.jplotter.interaction.CoordSysViewSelector;
import hageldave.jplotter.misc.Contours;
import hageldave.jplotter.misc.DefaultGlyph;
import hageldave.jplotter.renderables.Lines;
import hageldave.jplotter.renderables.Lines.SegmentDetails;
import hageldave.jplotter.renderables.Points;
import hageldave.jplotter.renderables.Triangles;
import hageldave.jplotter.renderables.Triangles.TriangleDetails;
import hageldave.jplotter.renderers.CoordSysRenderer;
import hageldave.jplotter.renderers.LinesRenderer;
import hageldave.jplotter.renderers.PointsRenderer;
import hageldave.jplotter.renderers.TrianglesRenderer;
import hageldave.jplotter.util.Pair;
import hageldave.jplotter.util.Utils;
import hageldave.visnlp.data.KomoLog;
import hageldave.visnlp.data.KomoRestClient;
import hageldave.visnlp.eval.TrajectoryProjectionGoodness;
import hageldave.visnlp.eval.TrajectoryProjectionGoodness.PlaneOrientationStrategy;
import hageldave.visnlp.util.CheckerBoardRenderer;
import hageldave.visnlp.util.DataPrep;
import hageldave.visnlp.util.FeatureHandle;
import hageldave.visnlp.util.HSlider;
import hageldave.visnlp.util.MatUtil;
import hageldave.visnlp.util.SimpleSelectionModel;
import smile.stat.distribution.GaussianDistribution;

public class LandscapeView {

	public final KomoLog log;

	public final CoordSysRenderer coordsys;
	public final BlankCanvas canvas;
	public final Container landscapeViewWidget = new Container();
	public final HSlider constraintOpacitySlider;

	public final SimpleSelectionModel<Pair<String, FeatureHandle>> featureSelectionModel;

	public final SimpleSelectionModel<Integer> querySelectionModel;

	public final SimpleSelectionModel<Double> customIsoValue = new SimpleSelectionModel<>();
	
	public final SimpleSelectionModel<Double> constraintViolationThreshold = new SimpleSelectionModel<>();

	public final Triangles infeasibleBands;

	public final KomoRestClient rest;

	final Consumer<OptimStepSample> calcSample;

	final Runnable calculateContours;

	final Runnable calculateCustomContour;

	SimpleMatrix[] pcas;
	SimpleMatrix globalPCA;

	double[][] trajecData;

	public static final int OBJECTIVE_LAGRANGIAN=0;
	public static final int OBJECTIVE_COSTFEATURES=-1;

	public int displayedObjective = OBJECTIVE_COSTFEATURES;//OBJECTIVE_LAGRANGIAN;

	final double[] colormapRange = new double[] {0.0,1.0};
	final ColorMap cmap = DefaultColorMap.S_VIRIDIS.resample(20, 0.2, 1.0);
	final int numColorLevels = 10;
	final SimpleColorMap bandcmap = cmap.resample(numColorLevels+1, 0, 1);
	
	double coordinateScaling = 1.0;

	static class OptimStepSample {
		double[][][] Z;
		double[][] X,Y;
		Rectangle2D view;
		int resX,resY;
		int[] queryIdx;
		boolean isCalculated = false;

		public OptimStepSample(int resX, int resY, int numZ, Rectangle2D view, int[] queryIdx) {
			Z = new double[numZ][resY][resX];
			X = new double[resY][resX];
			Y = new double[resY][resX];
			this.view = view;
			this.queryIdx = queryIdx;
			this.resX=resX;
			this.resY=resY;
		}
	}

	final ArrayList<OptimStepSample> predefinedSamples = new ArrayList<>();
	OptimStepSample tempSample = null;
	int tempNumSamples;

	private OptimStepSample overviewSample;

	@SuppressWarnings("resource")
	public LandscapeView(SimpleSelectionModel<Integer> queryModel, KomoLog log, String komoserver) {
		if(komoserver == null || komoserver.isEmpty()) {
			rest = new KomoRestClient("http:127.0.0.1:8080");
		} else {
			rest = new KomoRestClient(komoserver);
		}
		
		
		this.log = log;
		this.querySelectionModel = Objects.nonNull(queryModel) ? queryModel:new SimpleSelectionModel<>();
		this.featureSelectionModel = new SimpleSelectionModel<>((f1,f2)->{
			int comp1 = f1.first.compareTo(f2.first);
			if(comp1 != 0)
				return comp1;
			if(f1.second==f2.second)
				return 0;
			if(f1.second==null)
				return -1;
			if(f2.second==null)
				return 1;
			return f1.second.compareTo(f2.second);
		});

		trajecData = log.streamGraphQueriesX().map(MatUtil::stackVectors).map(x->x.getDDRM().data.clone()).toArray(double[][]::new);
		final int dimensionality = trajecData[0].length;
		this.pcas = new SimpleMatrix[log.numGraphQueries];
		
		trajecData = Arrays.stream(trajecData)
				.map(MatUtil::vectorOf)
				.map(v->v.scale(coordinateScaling))
				.map(v->v.getDDRM().data)
				.toArray(double[][]::new);

//		{				
//			double[] avgStepsize = new double[log.numGraphQueries];
//			// calculate average step sizes for inversely weighting trajectory points
//			double[][] stepsizeData = trajecData;
//			for(int i=0; i<log.numGraphQueries-1; i++) {
//				SimpleMatrix step = MatUtil.vectorOf(stepsizeData[i+1]).minus(MatUtil.vectorOf(stepsizeData[i]));
//				double sum = step.elementSum();
//				avgStepsize[i] = step.normF()+0.0001;
//			} 
//			avgStepsize[log.numGraphQueries-1] = avgStepsize[log.numGraphQueries-2];
//			// smooth stepsizes for averaging
//			DataPrep.boxSmoothing(avgStepsize, avgStepsize, 7);
//
//			// calculate pca dataset weights from violations
//			double[][] weights = new double[log.numGraphQueries][dimensionality];
//			for(int i=0; i<log.numGraphQueries; i++) {
//				// initialize weights uniformly
//				for(int j=0; j<dimensionality; j++)
//					weights[i][j] = 1.0/dimensionality;
//
//				double[] phi = log.getGraphQueryPhi(i);
//				// equalities
//				for(int p : log.equalityFeatureIndices) {
//					double phiVal = Math.abs(phi[p]);
//					// phiVars contain robot time steps corresponding to this feature
//					int[] phiVars = log.featureVariables.get(p).stream().mapToInt(Number::intValue).toArray();
//					// find block for each of the robot times
//					for(int t : phiVars) {
//						for(int j=log.varStarts[t]; j<log.varStarts[t]+log.varDims[t]; j++) {
//							weights[i][j] += phiVal;
//						}
//					}
//				}
//				// inequalities
//				for(int p : log.inequalityFeatureIndices) {
//					double phiVal = Math.max(0,phi[p]);
//					// phiVars contain robot time steps corresponding to this feature
//					int[] phiVars = log.featureVariables.get(p).stream().mapToInt(Number::intValue).toArray();
//					// find block for each of the robot times
//					for(int t : phiVars) {
//						for(int j=log.varStarts[t]; j<log.varStarts[t]+log.varDims[t]; j++) {
//							weights[i][j] = phiVal;
//						}
//					}
//				}
//				// normalize weights
//				double sum = Arrays.stream(weights[i]).sum();
//				double max = Arrays.stream(weights[i]).max().getAsDouble();
//				double norm = 1.0/sum;
//				double maxTo1 = 1.0/(max*norm);
//				double normalization = norm*maxTo1;
//				for(int j=0; j<dimensionality; j++)
//					weights[i][j] *= normalization;
//			}
//
//			double[][] data = DataPrep.copy(trajecData);
//
////			if(Boolean.valueOf(false))
//			{	// inverse weighting by average step size (accounting for decreasing step sizes over the process)
//				double[][] data_ = data;
//				data = IntStream.range(0, data_.length)
//						.mapToObj(i->{
//							double[] row = data_[i];
//							SimpleMatrix weightedRow = MatUtil.vectorOf(row)
//									.scale(1.0/avgStepsize[i])
//									;
//							return weightedRow.getDDRM().data;
//						})
//						.toArray(double[][]::new);
//			}
//			int skip = trajecData.length/8;
//			double[][] meansAndVariances = DataPrep.getMeansAndVariances(Arrays.copyOfRange(data, skip, data.length));
//			DataPrep.normalizeData(data, meansAndVariances);
//
//			if(Boolean.valueOf(false))
//			{	// apply weighting by constraint violation
//				double[][] data_ = data;
//				data = IntStream.range(0, data.length)
//						.mapToObj(i->{
//							double[] row = data_[i];
//							double[] weight = weights[i];
//							SimpleMatrix weightedRow = MatUtil.vectorOf(row)
//									.elementMult(MatUtil.vectorOf(weight))
//									;
//							return weightedRow.getDDRM().data;
//						})
//						.toArray(double[][]::new);
//			}
//			
//			// gloabl pca
//			{
//				DataPrep.normalizeData(data, DataPrep.getMeansAndVariances(data));
//				SimpleMatrix dataMatrix = MatUtil.rowmajorMat(data);
//				SimpleSVD<SimpleMatrix> svd = dataMatrix.svd(true);
//				this.globalPCA = svd.getV().transpose();
//			}
//			
//			
//			for(int k=0; k<log.numGraphQueries; k++){
//				this.pcas[k] = globalPCA;
//			}
//			// calculate local pcas for all graph queries ( = optimization steps)
//			if(Boolean.valueOf(false))
//			for(int k=0; k<log.numGraphQueries; k++){
//				System.out.println("LandscapeView: calculating dataset weights ("+ k+"/"+log.numGraphQueries+")");
//
//				double[][] localdata = data;
////				if(Boolean.valueOf(false))
//				{
//					// localization (weighting by trajectory point index difference)
//					GaussianDistribution distrib = new GaussianDistribution(0, 3/*windowsize*/);
//					double distribScale = 1/distrib.p(0);
//					int k_=k;
//					DoubleUnaryOperator kernel = (idx)->distrib.p(idx-k_)*distribScale;
//					double[][] data_ = data;
//					localdata = IntStream.range(0,data.length)
//							.mapToObj(i->{
//								double[] row = data_[i];
//								SimpleMatrix weightedRow = MatUtil.vectorOf(row)
//										.scale(kernel.applyAsDouble(i))
//										;
//								return weightedRow.getDDRM().data;
//							})
//							.toArray(double[][]::new);
//				}
//
//				// get rid of initialization
//				localdata = Arrays.copyOfRange(localdata, skip, log.numGraphQueries);
//				DataPrep.normalizeData(localdata, DataPrep.getMeansAndVariances(localdata));
//
//				System.out.println("LandscapeView: calculating pca "+ k+"/"+log.numGraphQueries+")");
//
//				SimpleMatrix dataMatrix = MatUtil.rowmajorMat(localdata);
//				SimpleSVD<SimpleMatrix> svd = dataMatrix.svd(false);
//				this.pcas[k] = svd.getV().transpose();
//			}
//		}
//
//		// fix mirrored pca vectors
//		for(int i=1; i<log.numGraphQueries; i++) {
//			System.out.println("checking pca mirroring " + i);
//			SimpleMatrix pca1 = pcas[i-1];
//			SimpleMatrix pca2 = pcas[i];
//			SimpleMatrix mult = pca1.mult(pca2.transpose());
//			for(int j=0; j<mult.numRows(); j++) {
//				// negative projection of corresponding principal vectors -> flipped axis
//				if(mult.get(j, j) < 0) {
//					for(int k=0; k<Math.min(3,pca2.numCols()); k++) {
//						pca2.set(j,k, -pca2.get(j,k));
//					}
//				}
//			}
//		}
//
//		//		// wait for hessian directions to be calculated
//		//		while(!hessianCalcTasks.isEmpty()) {
//		//			Iterator<ForkJoinTask<?>> it = hessianCalcTasks.iterator();
//		//			while(it.hasNext()) {
//		//				ForkJoinTask<?> task = it.next();
//		//				if(task.isDone())
//		//					it.remove();
//		//			}
//		//		}
//		//		for(int k=0; k<log.numGraphQueries; k++){
//		//			pcas[k]=pHdirs[k];
//		//		}
//		System.gc();
		
		SimpleMatrix[][] planeVecs = TrajectoryProjectionGoodness.calcPlaneVecs(log,trajecData,PlaneOrientationStrategy.LOCAL_TOARGMIN_LOCAL_PCA, false, 10);
		SimpleMatrix[] plane1Vecs = planeVecs[0];
		SimpleMatrix[] plane2Vecs = planeVecs[1];
		
		this.pcas = new SimpleMatrix[log.numGraphQueries];
		for(int i=0; i<log.numGraphQueries; i++) {
			this.pcas[i] = MatUtil.rowmajorMat(new double[][] {plane1Vecs[i].getDDRM().data, plane2Vecs[i].getDDRM().data});
		}
		
		SimpleMatrix[][] globalPlaneVecs = TrajectoryProjectionGoodness.calcPlaneVecs(log,trajecData,PlaneOrientationStrategy.GLOBAL_PCA, false, 10);
		this.globalPCA = MatUtil.rowmajorMat(new double[][] {globalPlaneVecs[0][0].getDDRM().data, globalPlaneVecs[1][0].getDDRM().data});
		


//		SimpleMatrix[] p1,p2,p1T,p2T, pca2D;
//		p1=new SimpleMatrix[log.numGraphQueries];
//		p2=new SimpleMatrix[log.numGraphQueries];
//		p1T=new SimpleMatrix[log.numGraphQueries];
//		p2T=new SimpleMatrix[log.numGraphQueries];
//		pca2D=new SimpleMatrix[log.numGraphQueries];
//		for(int k=0; k<log.numGraphQueries; k++){
//			// plane spanning vectors
//			p1T[k] = pcas[k].extractVector(true, 0);
//			p2T[k] = pcas[k].extractVector(true, 1);
//			p1[k]=p1T[k].transpose();
//			p2[k]=p2T[k].transpose();
//			pca2D[k] = pcas[k].extractMatrix(0, 2, 0, pcas[k].numCols());
//		}
//		
//		// precalculate plane vectors for single optimization steps
//		SimpleMatrix[] plane1Vecs = new SimpleMatrix[log.numGraphQueries];
//		SimpleMatrix[] plane2Vecs = new SimpleMatrix[log.numGraphQueries];
//		for(int i=0; i<log.numGraphQueries; i++) {
//			int idx1 = i;
//			int idx2 = log.numGraphQueries-1;
//			if(idx1 == log.numGraphQueries-1) {
//				idx1--;
//			}
//			SimpleMatrix p1_ = MatUtil.normalizeInPlace(MatUtil.vectorOf(trajecData[idx2]).minus(MatUtil.vectorOf(trajecData[idx1])));
//			SimpleMatrix p2_ = getPerpendicularInPlane(p1_, p1[i], p2[i]);
//			plane1Vecs[i] = p1_;
//			plane2Vecs[i] = p2_;
//		}
//		
//		// fix mirrored plane vectors
//		for(int i=1; i<log.numGraphQueries; i++) {
//			System.out.println("checking pca mirroring " + i);
//			SimpleMatrix v1 = plane2Vecs[i-1];
//			SimpleMatrix v2 = plane2Vecs[i];
//			double dot = v1.dot(v2);
//			if(dot < 0) {
//				for(int k=0; k<v2.getNumElements(); k++) {
//						v2.set(k, -v2.get(k));
//				}
//			}
//		}
		
		
		

		// build viz

		Lines traj = new Lines().setGlobalThicknessMultiplier(3);
		Points trajP = new Points(DefaultGlyph.CIRCLE_F).setGlobalScaling(0.7);
		Points trajPOutline = new Points(DefaultGlyph.CIRCLE).setGlobalScaling(0.7);
		Point2D[] trajPoints = new Point2D[log.numGraphQueries];
		double[] trajThickness = new double[log.numGraphQueries];
		int[] trajPointColors = new int[log.numGraphQueries]; Arrays.fill(trajPointColors, 0xff888888);
		double[] trajAlpha = new double[log.numGraphQueries];
		for(int i=0; i<log.numGraphQueries; i++) {
			int i_=i;
			trajPoints[i]=new Point2D.Double();
			trajP.addPoint(trajPoints[i]).setColor(()->trajPointColors[i_]);
			trajThickness[i] = 1;
		}
		for(int i=0; i<log.numGraphQueries; i++) {
			trajPOutline.addPoint(trajPoints[i])
			.setColor(0xff888888)
			;
		}
		for(int i=0; i<log.numGraphQueries-1; i++) {
			SegmentDetails seg = traj.addSegment(trajPoints[i], trajPoints[i+1]);
			int i_ = i;
			seg.setThickness(()->trajThickness[i_], ()->trajThickness[i_+1]);
			seg.setColor0(()->Pixel.argb((int)(trajAlpha[i_]*0xff), 0x55, 0x55, 0x55));
			seg.setColor1(()->Pixel.argb((int)(trajAlpha[i_+1]*0xff), 0x55, 0x55, 0x55));
		}

		Lines contours = new Lines();
		Lines customContours = new Lines();
		Lines boundaries = new Lines().setStrokePattern(0b1001001001001000);
		Triangles bands = new Triangles().setGlobalAlphaMultiplier(0.7);
		infeasibleBands = new Triangles().setGlobalAlphaMultiplier(0.5);

		Points indicatorPoints = new Points(DefaultGlyph.SQUARE).setGlobalScaling(1.3);
		Points argminPoint = new Points(DefaultGlyph.CROSS).setGlobalScaling(2);
		Point2D indicatorP;
		Point2D argminP;
		{
			double[] pi = pcas[0].mult(MatUtil.vectorOf(trajecData[0])).getDDRM().data;
			indicatorP = new Point2D.Double(pi[0], pi[1]);
			indicatorPoints.addPoint(indicatorP);
			pi = pcas[0].mult(MatUtil.vectorOf(trajecData[log.numGraphQueries-1])).getDDRM().data;
			argminP = new Point2D.Double(pi[0], pi[1]);
			argminPoint.addPoint(argminP);
			argminPoint.addPoint(argminP).setRotation(Math.PI/4);
		}

		this.canvas = new BlankCanvas();
		this.canvas.setMinimumSize(new Dimension(1, 1));
		this.coordsys = new CoordSysRenderer();
		coordsys.setTickMarkGenerator(TickMarkGenerator.NO_TICKS);
		canvas.setRenderer(coordsys);
		coordsys.setContent(
				new PointsRenderer()
				.addItemToRender(indicatorPoints)
				.addItemToRender(argminPoint)
				.withPrepended(
						new LinesRenderer()
						.addItemToRender(customContours)
						//						.addItemToRender(boundaries)
						.addItemToRender(traj)
						)
				.withPrepended(
						new PointsRenderer()
						.addItemToRender(trajPOutline)
						.addItemToRender(trajP)
						)
				.withPrepended(
						//						new TrianglesRenderer()
						new CheckerBoardRenderer()
						.setCheckerSize(2)
						.addItemToRender(infeasibleBands)
						)
				.withPrepended(
						new TrianglesRenderer()
						.addItemToRender(bands)
						)
				);

		canvas.setBackground(Color.white);


		Function<int[], SimpleMatrix[]> getProjectionForQuery = new Function<int[], SimpleMatrix[]>() {
			@Override
			public SimpleMatrix[] apply(int[] idx) {
				int idx1,idx2;
				if(idx == null || idx.length==0) {
					idx1 = log.numGraphQueries/8;
					idx2 = log.numGraphQueries-1;
				} else if(idx.length==1){
					// fwd difference
					idx1 = idx[0];
					idx2 = log.numGraphQueries-1;
					if(idx1 == log.numGraphQueries-1) {
						idx1--;
					}
				} else {
					idx1 = idx[0];
					idx2 = idx[idx.length-1];
				}
				
				if(idx != null && idx.length > 2) {
					SimpleMatrix orig = MatUtil.vectorOf(trajecData[idx1]);
					SimpleMatrix p1_ = MatUtil.normalizeInPlace(MatUtil.vectorOf(trajecData[idx2]).minus(orig));
					SimpleMatrix others = MatUtil.vector(p1_.getNumElements());
					int n = idx.length-2;
					for(int i=0; i<n; i++) {
						others = others.plus(1.0/n, MatUtil.vectorOf(trajecData[idx[i+1]]));
					}
					others = others.minus(orig);
					double dot = p1_.dot(others);
					SimpleMatrix p2_ = MatUtil.normalizeInPlace(others.plus(-dot, p1_));
					
					SimpleMatrix projection = MatUtil.rowmajorMat(new double[][] {p1_.getDDRM().data,p2_.getDDRM().data});
					return new SimpleMatrix[] {p1_,p2_,projection};
				} else if(idx != null && idx.length == 1){
					SimpleMatrix p1_ = plane1Vecs[idx1];
					SimpleMatrix p2_ = plane2Vecs[idx1];
					
					SimpleMatrix projection = MatUtil.rowmajorMat(new double[][] {p1_.getDDRM().data,p2_.getDDRM().data});
					return new SimpleMatrix[] {p1_,p2_,projection};
				} else {
					SimpleMatrix p1_ = MatUtil.normalizeInPlace(MatUtil.vectorOf(trajecData[idx2]).minus(MatUtil.vectorOf(trajecData[idx1])));
					SimpleMatrix p2_ = getPerpendicularInPlane(p1_, globalPCA.extractVector(true, 0).transpose(), globalPCA.extractVector(true, 1).transpose());
					
					SimpleMatrix projection = MatUtil.rowmajorMat(new double[][] {p1_.getDDRM().data,p2_.getDDRM().data});
					return new SimpleMatrix[] {p1_,p2_,projection};
				}
			}
		};


		System.out.println("LandscapeView: calulating trajectory distances");
		BiConsumer<double[], int[]> distanceCalculator = (distances,indexes)->{
			int idx = indexes==null || indexes.length == 0 ? log.numGraphQueries-1 : indexes[0];
			SimpleMatrix x0 = MatUtil.vectorOf(trajecData[idx]);
			SimpleMatrix[] proj = getProjectionForQuery.apply(indexes);
			for(int i=0; i<log.numGraphQueries; i++) {
				SimpleMatrix x = MatUtil.vectorOf(trajecData[i]);
				// x0 is coordinate origin
				x = x.minus(x0);		
				double a = proj[0].dot(x);
				double b = proj[1].dot(x);
				SimpleMatrix n = proj[0].scale(a).plus(b, proj[1]);
				SimpleMatrix perpendicular = x.minus(n);
				
				double planeDist = perpendicular.normF();
				distances[i] = planeDist;
			}
		};
		double[][] trajectoryDistance = new double[log.numGraphQueries+1][log.numGraphQueries];
		for(int k=0; k<log.numGraphQueries+1; k++){	// pre-calculate trajectory distances
			int[] idx;
			if(k==log.numGraphQueries) {
				idx=null;
			} else {
				idx = new int[]{k};
			}
			distanceCalculator.accept(trajectoryDistance[k], idx);
		}
		// normalize trajectory distances
		{
			SimpleMatrix[] distVecs = Arrays.stream(trajectoryDistance)
			.map(MatUtil::vectorOf)
			.toArray(SimpleMatrix[]::new);
			double[] dists = MatUtil.stackVectors(distVecs).getDDRM().data;
			Arrays.sort(dists);
			double median = dists[dists.length/2];
			double scale = 1.0/median;
			for(double[] dist : trajectoryDistance) {
				for(int i=0; i<dist.length; i++)
					dist[i] *= scale;
			}
		}
		
		Runnable calculateTrajectoryAndIndicator = new Runnable() {
			@Override
			public void run() {
				SimpleMatrix[] prj = getProjectionForQuery.apply(getCurrentQueryIdx());
				Integer idx = querySelectionModel.getFirstOrDefault(null);
				int distIdx = idx==null ? log.numGraphQueries:idx;
				idx = idx==null ? log.numGraphQueries-1:idx;

				SimpleMatrix x0 = MatUtil.vectorOf(trajecData[idx]);
				double[] distances;
				if(querySelectionModel.getSelection().size() < 2) {
					distances = trajectoryDistance[distIdx];
				} else {
					distances = new double[log.numGraphQueries];
					distanceCalculator.accept(distances, getCurrentQueryIdx());
					// normalize
					double[] clone = distances.clone();
					Arrays.sort(clone);
					double median = clone[clone.length/2];
					double scale = 1.0/median;
					for(int i=0; i<distances.length; i++)
						distances[i] *= scale;
				}
				double distOrigin = distances[idx];

				SimpleOperations_DDRM op = new SimpleOperations_DDRM();
				SimpleMatrix projection = MatUtil.vector(x0.getNumElements());

				SimpleMatrix planeorigin = prj[2].mult(x0);
				
				for(int i=0; i<log.numGraphQueries; i++) {
					double planeDist = distances[i]-distOrigin;
					planeDist *= planeDist; // squared distance
					double proximity = (1.0/(1+planeDist));
					trajThickness[i] = proximity < 0.2 ? 0.1:proximity;
					double alpha = proximity < 0.05 ? 0.1: Math.min(1.0, proximity*10);
					trajAlpha[i] = alpha;

					op.mult(prj[2].getDDRM(), MatUtil.vectorOf(trajecData[i]).getDDRM(), projection.getDDRM());
					trajPoints[i].setLocation(projection.get(0)-planeorigin.get(0), projection.get(1)-planeorigin.get(1));
				}
				traj.setDirty();
				
				indicatorPoints.removeAllPoints();
				querySelectionModel.getSelection().forEach(i->{
					indicatorPoints.addPoint(trajPoints[i]);
				});
				
				SimpleMatrix argmin = prj[2].mult(MatUtil.vectorOf(trajecData[log.numGraphQueries-1]));
				argminP.setLocation(argmin.get(0)-planeorigin.get(0), argmin.get(1)-planeorigin.get(1));
				argminPoint.setDirty();
				
				trajP.setDirty();
				trajPOutline.setDirty();
			}
		};
		calculateTrajectoryAndIndicator.run();

		System.out.println("LandscapeView: preparing sampling objects");
		// create samples
		final int numSamples = tempNumSamples = 8;//128;
		for(int i=0; i<log.numGraphQueries; i++) {
			OptimStepSample s = new OptimStepSample(numSamples, numSamples, log.numFeatures+1, new Rectangle2D.Double(-.5, -.5, 1, 1), new int[]{i});
			this.predefinedSamples.add(s);
		}
		this.overviewSample = new OptimStepSample(numSamples, numSamples, log.numFeatures+1, new Rectangle2D.Double(-.5, -.5, 1, 1), new int[0]);

		// pre-calculate view rectangle for each optimization step
		{
			int viewCalcWindowSize = log.numGraphQueries/10;
			double[] widths = new double[log.numGraphQueries];
			double[] heights = new double[log.numGraphQueries];
			double[] xlocs = new double[log.numGraphQueries];
			double[] ylocs = new double[log.numGraphQueries];
//			for(int i=0; i<log.numGraphQueries; i++) {
//				int idx = i;
//				SimpleMatrix[] prj = getProjectionForQuery.apply(new int[]{idx});
//
//				SimpleMatrix x0 = MatUtil.vectorOf(trajecData[idx]);
//				SimpleMatrix o = prj[2].mult(x0);
//				double dx = 0;
//				double dy = 0;
//				for(int j = Math.max(i-viewCalcWindowSize,0); j < Math.min(i+viewCalcWindowSize, log.numGraphQueries); j++) {
//					SimpleMatrix v = prj[2].mult(MatUtil.vectorOf(trajecData[j]));
//					double x = v.get(0)-o.get(0);
//					double y = v.get(1)-o.get(1);
//					double d_x = Math.abs(x);
//					double d_y = Math.abs(y);
//					dx = Math.max(dx,d_x);
//					dy = Math.max(dy,d_y);
//				}
//				widths[i] = Math.max(dx*2, 5e-7);
//				heights[i] = Math.max(dy*2, 5e-7);
//				xlocs[i] = 0;//o.get(0);
//				ylocs[i] = 0;//o.get(1);
//			}
//			widths = DataPrep.boxSmoothing(widths, null, 5); 
//			heights = DataPrep.boxSmoothing(heights, null, 5);
			// set views
			for(int i=0; i<log.numGraphQueries; i++) {
				OptimStepSample sample = predefinedSamples.get(i);
				sample.view.setRect(xlocs[i]-widths[i]/2, ylocs[i]-heights[i]/2, widths[i], heights[i]);
			}

			// overview
			{
				int idx = log.numGraphQueries-1;
				SimpleMatrix[] prj = getProjectionForQuery.apply(null);
				double minx,miny,maxx,maxy; 
				minx=miny=Double.POSITIVE_INFINITY;
				maxx=maxy=Double.NEGATIVE_INFINITY;
				
				SimpleMatrix x0 = MatUtil.vectorOf(trajecData[idx]);
				SimpleMatrix o = prj[2].mult(x0);
				for(int j = 0; j < log.numGraphQueries; j++) {
					SimpleMatrix v = prj[2].mult(MatUtil.vectorOf(trajecData[j]));
					double x = v.get(0)-o.get(0);
					double y = v.get(1)-o.get(1);
					minx = Math.min(minx,x);
					miny = Math.min(miny,y);
					maxx = Math.max(maxx,x);
					maxy = Math.max(maxy,y);
				}
				double w = Math.max(maxx,-minx);
				double h = Math.max(maxy,-miny);
				overviewSample.view.setRect(-w*1.1, -h*1.1, w*2.2, h*2.2);
			}
		}
		coordsys.setCoordinateView(overviewSample.view);
		
		Supplier<OptimStepSample> getCurrentSample = new Supplier<OptimStepSample>() {
			public OptimStepSample get() {
				OptimStepSample s;
				int[] idx = getCurrentQueryIdx();
				Rectangle2D coordsysview = coordsys.getCoordinateView();
				if(idx == null || idx.length == 0 || idx.length == 1) {
					s = idx == null || idx.length == 0 ? overviewSample : predefinedSamples.get(idx[0]);
					if(s.view.equals(coordsysview) && tempNumSamples==numSamples) {
						if(!s.isCalculated) {
							scheduleTempSampleCalculation(s);
						}
						return s;
					} else if(	Objects.nonNull(tempSample) && Arrays.equals(tempSample.queryIdx,idx) && tempSample.view.equals(coordsysview)
							&& tempSample.resX==tempNumSamples && tempSample.resY == tempNumSamples) 
					{
						return tempSample;
					} else {
						// schedule calculation of new sample
						OptimStepSample futureSample = new OptimStepSample(tempNumSamples, tempNumSamples, log.numFeatures+1, Utils.copy(coordsysview), idx);
						scheduleTempSampleCalculation(futureSample);
						return Objects.nonNull(tempSample) && Arrays.equals(tempSample.queryIdx,idx) ? tempSample : s;
					}
				} else {
					// multiple indices, need to create new sample if temp is not matching
					if(Objects.nonNull(tempSample) && Arrays.equals(tempSample.queryIdx,idx) && tempSample.view.equals(coordsysview)
							&& tempSample.resX==tempNumSamples && tempSample.resY == tempNumSamples) 
					{
						return tempSample;
					} else {
						OptimStepSample futureSample = new OptimStepSample(tempNumSamples, tempNumSamples, log.numFeatures+1, Utils.copy(coordsysview), idx);
						scheduleTempSampleCalculation(futureSample);
						return Objects.nonNull(tempSample) && Arrays.equals(tempSample.queryIdx,idx) ? tempSample : futureSample;
					}
				}
			}
		};


		int[] featurecolors = new int[log.numFeatures];
		Arrays.fill(featurecolors, 0x55999999);
		List<TriangleDetails>[] constraintTris = new List[log.numFeatures];

		Runnable orderAndColorConstraints = new Runnable() {
			@Override
			public void run() {
				Arrays.fill(featurecolors, 0x55999999);
				int highlightColor = 0xff8888cc;
				SortedSet<Integer> toAddLater = new TreeSet<>();
				featureSelectionModel.getSelection().forEach(p->{
					if(p.second==null) {
						for (int i = 0; i < log.numFeatures; i++) {
							if(p.first.equals(log.featureNames.get(i))) {
								featurecolors[i] = highlightColor;
								toAddLater.add(i);
							}
						}
					} else {
						int i = p.second.index;
						featurecolors[i] = highlightColor;
						toAddLater.add(i);
					}
				});

				infeasibleBands.removeAllTriangles();
				if(featureSelectionModel.getSelection().isEmpty()) {
					for(int i=0; i<log.numFeatures; i++) {
						if(constraintTris[i]!=null && !toAddLater.contains(i)) {
							infeasibleBands.getTriangleDetails().addAll(constraintTris[i]);
						}
					}
				}
				for(Integer i:toAddLater) {
					List<TriangleDetails> tris = constraintTris[i];
					if(tris != null)
						infeasibleBands.getTriangleDetails().addAll(tris);
				}

				infeasibleBands.setDirty();
				canvas.scheduleRepaint();
			}
		};

		Function<double[][][], double[][]> getObjective = new Function<double[][][], double[][]>() {
			@Override
			public double[][] apply(double[][][] Z) {
				switch (displayedObjective) {
				case OBJECTIVE_LAGRANGIAN:{
					return Z[0];
				}
				case OBJECTIVE_COSTFEATURES:{
					int nrows=Z[0].length;
					int ncols=Z[0][0].length;
					double[][] z = new double[nrows][ncols];
					double[][] tmp = new double[nrows][ncols];
					for(int c:log.directCostFeatureIndices) {
						z = DataPrep.plus(z, Z[c+1], z);
					}
					for(int c:log.costFeatureIndices) {
						tmp = DataPrep.sqr(Z[c+1], tmp);
						z = DataPrep.plus(z, tmp, z);
					}
					return z;
				}
				default:
					return Z[displayedObjective+1];
				}
			}
		};

		calculateContours = new Runnable() {
			@Override
			public void run() {
				OptimStepSample s = getCurrentSample.get();
				
				double[][][] Z = s.Z;
				double[][] X = s.X;
				double[][] Y = s.Y;
				
				int[] idx = s.queryIdx;
				LinkedHashMap<String,Object> optConstraintEntry = log.getOptConstraintEntry(log.graphQueryIndices[idx==null||idx.length==0 ? log.numGraphQueries-1:idx[0]]);
				double mu = ((Number) optConstraintEntry.get("mu")).doubleValue();
				double nu = ((Number) optConstraintEntry.get("nu")).doubleValue();
				double[] lambda = (double[]) optConstraintEntry.get("lambda");
				
				{	//// contour for lagrangian / objective ////
					double[][] z = getObjective.apply(Z);
					contours.removeAllSegments();
					bands.removeAllTriangles();

					int nrows = z.length;
					int ncols = z[0].length;

					{
						// losses
						//					double l0 = ((ArrayList<Number>)log.getGraphQuery(idx).get("errors")).get(0).doubleValue();
						//					double l1 = ((ArrayList<Number>)log.getGraphQuery(idx == 0 ? 0:idx-1).get("errors")).get(0).doubleValue();
						// values at viewport corners
						double crnr0 = z[0][0];
						double crnr1 = z[0][ncols-1];
						double crnr2 = z[nrows-1][0];
						double crnr3 = z[nrows-1][ncols-1];
						double centr1 = z[nrows/2][ncols/2+1];
						double centr2 = z[nrows/2+1][ncols/2];
						double min = Arrays.stream(new double[]{centr1,centr2,crnr0,crnr1,crnr2,crnr3}).min().getAsDouble();
						double max = Arrays.stream(new double[]{centr1,centr2,crnr0,crnr1,crnr2,crnr3}).max().getAsDouble();
						double range = Math.abs(min-max)*1.5;
						double v0 = max+range*0.25;
						colormapRange[0] = v0-range;
						colormapRange[1] = v0;
					}

					double v0 = colormapRange[1];
					double range = colormapRange[1]-colormapRange[0];
					List<TriangleDetails> tris = Contours.computeContourBands(X, Y, z, Double.POSITIVE_INFINITY, v0, bandcmap.getColor(0), bandcmap.getColor(0));
					bands.getTriangleDetails().addAll(tris);
					for(int i=0; i<bandcmap.numColors()-1; i++) {
						double m0 = bandcmap.getLocation(i);
						double m1 = bandcmap.getLocation(i+1);
						tris = Contours.computeContourBands(X, Y, z, v0-range*m0, v0-range*m1, bandcmap.getColor(i), bandcmap.getColor(i));
						bands.getTriangleDetails().addAll(tris);
					}

					// color trajectory points according to current color map
					for(int i=0; i<log.numGraphQueries; i++) {
						double v;
						switch (displayedObjective) {
						case OBJECTIVE_COSTFEATURES:
							v = ((ArrayList<Number>)log.getGraphQuery(i).get("errors")).get(0).doubleValue();
							break;
						case OBJECTIVE_LAGRANGIAN:
							v = ((Number)log.getGraphQuery(i).get("L_x")).doubleValue();
							break;
						default:
							v = log.getGraphQueryPhi(i)[displayedObjective+1];
							break;
						}
						
						double m = (v-(v0-range)) / range;
						m = 1-Utils.clamp(0.0, m, 1.0);
						trajPointColors[i] = cmap.interpolate(m);
					}
					trajP.setDirty();
					trajPOutline.setDirty();
				}

				{	//// isolines and bands for constraints ////
					boundaries.removeAllSegments();
					infeasibleBands.removeAllTriangles();
					double thresh = constraintViolationThreshold.getFirstOrDefault(0.01);
					for(int c : log.equalityFeatureIndices) {
						int c_=c;
						List<SegmentDetails> segs = Contours.computeContourLines(X,Y,Z[c+1], 0.0, 0xffaaaaaa);
						List<TriangleDetails> tris = Contours.computeContourBands(X, Y, Z[c+1], thresh, Double.POSITIVE_INFINITY, 0xffaaaaaa, 0xffaaaaaa);
						tris.addAll(Contours.computeContourBands(X, Y, Z[c+1], Double.NEGATIVE_INFINITY, -thresh, 0xffaaaaaa, 0xffaaaaaa));

						segs.forEach(seg->seg.setColor(()->featurecolors[c_]));
						tris.forEach(tri->tri.setColor(()->featurecolors[c_]));

						boundaries.getSegments().addAll(segs);
						constraintTris[c] = tris;
					}
					for(int c : log.inequalityFeatureIndices) {
						int c_=c;
						List<SegmentDetails> segs = Contours.computeContourLines(X,Y,Z[c+1], thresh, 0xffaaaaaa);
						List<TriangleDetails> tris = Contours.computeContourBands(X, Y, Z[c+1], thresh, Double.POSITIVE_INFINITY, 0xffaaaaaa, 0xffaaaaaa);

						segs.forEach(seg->seg.setColor(()->featurecolors[c_]));
						tris.forEach(tri->tri.setColor(()->featurecolors[c_]));

						boundaries.getSegments().addAll(segs);
						constraintTris[c] = tris;
					}
					orderAndColorConstraints.run();
				}
				canvas.scheduleRepaint();
			}
		};

		calculateCustomContour = new Runnable() {
			@Override
			public void run() {
				OptimStepSample s = getCurrentSample.get();
				double[][][] Z = s.Z;
				double[][] X = s.X;
				double[][] Y = s.Y;
				
				double[][] z = getObjective.apply(Z);

				customContours.removeAllSegments();
				canvas.scheduleRepaint();
				if(customIsoValue.getSelection().isEmpty())
					return;
				double iso = customIsoValue.getSelection().first();

				List<SegmentDetails> segs = Contours.computeContourLines(X,Y,z, iso, 0xff0088ff);
				customContours.getSegments().addAll(segs);
			}
		};

		calcSample = new Consumer<OptimStepSample>() {
			@Override
			public void accept(OptimStepSample s) {
				s.isCalculated =  true;
				int[] idx = s.queryIdx;
				LinkedHashMap<String,Object> optConstraintEntry = log.getOptConstraintEntry(log.graphQueryIndices[idx==null||idx.length==0 ? log.numGraphQueries-1:idx[0]]);
				double mu = ((Number) optConstraintEntry.get("mu")).doubleValue();
				double nu = ((Number) optConstraintEntry.get("nu")).doubleValue();
				double[] lambda = (double[]) optConstraintEntry.get("lambda");

				SimpleMatrix[] projection = getProjectionForQuery.apply(idx);

				Rectangle2D view = s.view;
				SimpleMatrix x0 = MatUtil.vectorOf(trajecData[idx==null||idx.length==0 ? log.numGraphQueries-1:idx[0]]);
				// plane origin
				SimpleMatrix origin = x0;
				SimpleMatrix originP = projection[2].mult(origin);
				SimpleMatrix viewOrigin = origin.plus(view.getX(),projection[0]).plus(view.getY(),projection[1]);
				// translate origin on plane to match view port origin
//				origin = origin.plus(view.getMinX()-originP.get(0), projection[0]);
//				origin = origin.plus(view.getMinY()-originP.get(1), projection[1]);

				int nrows = s.X.length;
				int ncols = s.X[0].length;

				// define coordinates of samples
				double resX = view.getWidth()/(ncols-1);
				double resY = view.getHeight()/(nrows-1);
				for(int i=0; i<nrows; i++) {
					SimpleMatrix vec_ = viewOrigin.plus(i*resY, projection[1]);
					for(int j=0; j<ncols;j++) {
						SimpleMatrix vec = vec_.plus(j*resX, projection[0]);
						double[] point = projection[2].mult(vec).getDDRM().data;
						s.X[i][j] = point[0]-originP.get(0);
						s.Y[i][j] = point[1]-originP.get(1);
					}
				}
				rest.sample_(
						viewOrigin.scale(1/coordinateScaling),
						projection[0], projection[1], 
						resX/coordinateScaling, resY/coordinateScaling, 
						s.Z[0][0].length, s.Z[0].length, log.numFeatures+1, 
						s.Z
						,mu,nu,lambda
						);
			}
		};

		Runnable setViewBasedOnOptimStep = new Runnable() {
			@Override
			public void run() {
				// TODO
				Integer idx = queryModel.getFirstOrDefault(null);
				OptimStepSample sample = idx == null ? overviewSample:predefinedSamples.get(idx);
				coordsys.setCoordinateView(sample.view);
				canvas.scheduleRepaint();
			}
		};

		featureSelectionModel.addSelectionListener(s->{
			orderAndColorConstraints.run();
		});

		queryModel.addSelectionListener(s->{
			calculateTrajectoryAndIndicator.run();
//			setViewBasedOnOptimStep.run();
			calculateContours.run();
			calculateCustomContour.run();
		});
		customIsoValue.addSelectionListener(s->{
			calculateCustomContour.run();
		});

		this.constraintViolationThreshold.addSelectionListener(s->calculateContours.run());
		

		this.constraintOpacitySlider = new HSlider(0, 0xff, (int)(infeasibleBands.getGlobalAlphaMultiplier()*0xff));
		constraintOpacitySlider.setTitle("constraint opacity");
		constraintOpacitySlider.slider.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				infeasibleBands.setGlobalAlphaMultiplier(constraintOpacitySlider.getNormalizedValue());
				canvas.scheduleRepaint();
			}
		});
		
		coordsys.addCoordinateViewListener((src,view)->{
			calculateContours.run();
			calculateCustomContour.run();
		});

		new CoordSysPanning(canvas, coordsys).register();
		new CoordSysScrollZoom(canvas,coordsys).register();
		new CoordSysViewSelector(canvas,coordsys) {
			@Override
			public void areaSelected(double minX, double minY, double maxX, double maxY) {
				coordsys.setCoordinateView(minX, minY, maxX, maxY);
			}
		}.register();
		MouseAdapter isoSelector = new MouseAdapter() {
			boolean isMyEvent(MouseEvent e) {
				if(
						SwingUtilities.isLeftMouseButton(e) && 
						coordsys.getCoordSysArea().contains(Utils.swapYAxis(e.getPoint(), canvas.getHeight()))) 
				{
					boolean shiftDown = (e.getModifiersEx() & MouseEvent.SHIFT_DOWN_MASK) != 0;
					boolean ctrlDown = (e.getModifiersEx() & MouseEvent.CTRL_DOWN_MASK) != 0;
					return !(shiftDown || ctrlDown);
				}
				return false;
			}

			boolean dragActive = false;

			public void mousePressed(MouseEvent e) {
				if(!isMyEvent(e))
					return;
				dragActive = true;
				calcIsoUnderMouse(e);
			}

			void calcIsoUnderMouse(MouseEvent e) {
				Rectangle2D view = coordsys.getCoordinateView();
				Point2D mousep = coordsys.transformAWT2CoordSys(e.getPoint(),canvas.getHeight());
				if(!view.contains(mousep))
					return;

				int[] idx = getCurrentQueryIdx();
				SimpleMatrix[] prj = getProjectionForQuery.apply(idx);

				LinkedHashMap<String,Object> optConstraintEntry = log.getOptConstraintEntry(log.graphQueryIndices[idx==null||idx.length==0 ? log.numGraphQueries-1:idx[0]]);
				double mu = ((Number) optConstraintEntry.get("mu")).doubleValue();
				double nu = ((Number) optConstraintEntry.get("nu")).doubleValue();
				double[] lambda = (double[]) optConstraintEntry.get("lambda");
				
				// plane origin
				SimpleMatrix x0 = MatUtil.vectorOf(trajecData[idx==null||idx.length==0 ? log.numGraphQueries-1:idx[0]]);
				
				// function to calc contours on
				SimpleMatrix vec_ = x0.plus(mousep.getY(), prj[1]);
				SimpleMatrix vec = vec_.plus(mousep.getX(), prj[0]);
				double[][][] z = new double[log.numFeatures+1][1][1];
				try {
					rest.sample_(vec, prj[0], prj[1], 0, 0, 1, 1, log.numFeatures+1, z, mu, nu, lambda);
				} catch (Exception ex) {
					System.err.println("exception on REST sampling: " + ex.getClass());
				}
				double[][] obj = getObjective.apply(z);
				customIsoValue.setSelection(obj[0][0]);
			}

			public void mouseDragged(MouseEvent e) {
				if(dragActive)
					calcIsoUnderMouse(e);
			}

			public void mouseReleased(MouseEvent e) {
				dragActive = false;
			}
		};
//		canvas.addMouseListener(isoSelector);
//		canvas.addMouseMotionListener(isoSelector);
		canvas.enableInputMethods(true);
		canvas.addKeyListener(new KeyAdapter() {
			public void keyPressed(KeyEvent e) {
				if(e.getKeyCode() == KeyEvent.VK_F5) {
					tempNumSamples = numSamples;
					calculateContours.run();
					calculateCustomContour.run();
				}
				if(e.getKeyCode() == KeyEvent.VK_F6) {
					tempNumSamples = numSamples*2;
					calculateContours.run();
					calculateCustomContour.run();
				}
			};
		});


		landscapeViewWidget.setLayout(new BorderLayout());
		landscapeViewWidget.add(canvas, BorderLayout.CENTER);
		landscapeViewWidget.add(constraintOpacitySlider, BorderLayout.SOUTH);
	}
	
	
	Pair[] scheduledTempSample = {null};
	Runnable samplingTask = null;
	
	
	Thread samplingThread = new Thread(new Runnable() {
		@Override
		public void run() {
			while(!Thread.interrupted()) {
				
				if(samplingTask != null)
					samplingTask.run();
				
				Thread.yield();
			}
		}
	});

	protected void scheduleTempSampleCalculation(OptimStepSample futureSample) {
		Pair<Long, OptimStepSample> samplingItem = Pair.of(System.currentTimeMillis()+500, futureSample);
		synchronized (scheduledTempSample) {
			scheduledTempSample[0] = samplingItem;
			if(!samplingThread.isAlive() && !samplingThread.isInterrupted()) {
				samplingThread.start();
			}
		}
		if(samplingTask == null) {
			synchronized (this) {
				if(samplingTask == null) {
					this.samplingTask = new Runnable() {
						@Override
						public void run() {
							Pair<Long, OptimStepSample> sample = scheduledTempSample[0];
							if(sample != null && sample.first < System.currentTimeMillis()) {
								calcSample.accept(sample.second);
								// some time has past check if still same sample
								synchronized (scheduledTempSample) {
									if(scheduledTempSample[0] == sample) {
										scheduledTempSample[0] = null;
										tempSample = sample.second;
										SwingUtilities.invokeLater(()->{
											calculateContours.run();
											calculateCustomContour.run();
										});
									}
								}
							}
						}
					};
				}
			}
		}
	}

	public static interface AsyncTask extends Runnable {
		boolean isComplete();
		double getProgress();
		void cancel();
	}

	public AsyncTask calculateSamples() {
		AsyncTask task = new AsyncTask() {
			boolean cancelled = false;
			int nsubtasks = predefinedSamples.size();
			int tasksdone = 0;
			AtomicBoolean running = new AtomicBoolean();

			@Override
			public void run() {
				if(running.compareAndSet(false, true));
				else return;

				System.out.format("sampling overview");
				calcSample.accept(overviewSample);
				if(querySelectionModel.getSelection().isEmpty()) {
					SwingUtilities.invokeLater(()->{
						calculateContours.run();
						calculateCustomContour.run();
					});
				}

				for(;tasksdone<nsubtasks;) {
					System.out.format("sampling %d / %d %n", tasksdone+1,nsubtasks);
					OptimStepSample s = predefinedSamples.get(nsubtasks-1-tasksdone);
					calcSample.accept(s);
					if(querySelectionModel.getSelection().contains(s.queryIdx[0])) {
						SwingUtilities.invokeLater(()->{
							calculateContours.run();
							calculateCustomContour.run();
						});
					}
					tasksdone++;
					if(cancelled)
						break;
				}
				running.set(false);
			}

			@Override
			public boolean isComplete() {
				return tasksdone==nsubtasks;
			}

			@Override
			public double getProgress() {
				return tasksdone*1.0/(nsubtasks);
			}

			@Override
			public void cancel() {
				cancelled = true;
			}

		};
		ForkJoinPool.commonPool().execute(task);
		return task;
	}

	LinkedList<ForkJoinTask<?>> queryPrincipleHessianDirections() {
		int dim = trajecData[0].length;
		double[][] hessvals = new double[dim][dim];
		LinkedList<ForkJoinTask<?>> tasks = new LinkedList<>();
		for(int i=0; i<log.numGraphQueries; i++) {
			System.out.format("query hessian (%d/%d)%n",i,log.numGraphQueries);
			LinkedHashMap<String,Object> optConstraintEntry = log.getOptConstraintEntry(log.graphQueryIndices[i]);
			double mu = ((Number) optConstraintEntry.get("mu")).doubleValue();
			double nu = ((Number) optConstraintEntry.get("nu")).doubleValue();
			double[] lambda = (double[]) optConstraintEntry.get("lambda");

			SimpleMatrix origin = MatUtil.vectorOf(trajecData[i]);
			rest.hessian(origin, hessvals, mu, nu, lambda);
			SimpleMatrix hessian = MatUtil.rowmajorMat(hessvals);

			int i_=i;
			ForkJoinTask<?> eigTask = ForkJoinPool.commonPool().submit(()->{
				System.out.println("svd hessian "+ i_);
				long t = System.currentTimeMillis();
				SimpleSVD<SimpleMatrix> svd = hessian.svd();
				//				pHdirs[i_] = svd.getV().transpose();
				//				System.out.format("hess svd time: %d  veclen: %f %n", System.currentTimeMillis()-t, pHdirs[i_].extractMatrix(0, 1, 0, dim).normF());
			});
			tasks.add(eigTask);
		}
		return tasks;
	}
	
	private int[] getCurrentQueryIdx() {
		return this.querySelectionModel.getSelection().stream().mapToInt(Integer::intValue).toArray();
	}

	public void hideConstraints(boolean hide) {
		this.infeasibleBands.hide(hide);
	}

	static double tol = 1e-5;
	public static SimpleMatrix getPerpendicularInPlane(SimpleMatrix v, SimpleMatrix p1, SimpleMatrix p2) {
		double dot1 = p1.dot(v);
		double dot2 = p2.dot(v);
		if(Math.abs(dot1) < tol)
			return p1;
		if(Math.abs(dot2) < tol)
			return p2;
		// need to find linear combination of a*p1+b*p2 that is perpendicular to v
		double c = dot2/dot1;
		double d = c*c;
		double b = -1.0/Math.sqrt(d+1);
		double a = -b*c;
		return MatUtil.normalizeInPlace(p1.scale(a).plus(b, p2));
	}






}
