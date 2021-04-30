package hageldave.visnlp.eval;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.IntStream;

import javax.naming.directory.InitialDirContext;

import org.ejml.simple.SimpleMatrix;
import org.ejml.simple.SimpleSVD;
import org.jblas.DoubleMatrix;
import org.jblas.Singular;
import org.jblas.exceptions.LapackConvergenceException;

import hageldave.imagingkit.core.Img;
import hageldave.imagingkit.core.io.ImageSaver;
import hageldave.imagingkit.core.util.ImageFrame;
import hageldave.jplotter.color.DefaultColorMap;
import hageldave.visnlp.data.KomoLog;
import hageldave.visnlp.util.DataPrep;
import hageldave.visnlp.util.MatUtil;
import hageldave.visnlp.views.LandscapeView;
import smile.stat.distribution.GaussianDistribution;

public class TrajectoryProjectionGoodness {
	
	public static void main(String[] args) throws IOException {
		File logfile = new File("../motionplanner/problem2/z.log");
		KomoLog log = KomoLog.loadLog(logfile);
		
//		for(PlaneOrientationStrategy strategy : PlaneOrientationStrategy.values()) {
//			assess(log, strategy, false, 10);
//			assess(log, strategy, true, 10);
//		}
		
		assess(log, PlaneOrientationStrategy.LOCAL_PROMINENT_DIRECTIONS, false, 10);
	}
	
	final static String USE_GLOBAL_PCA = "gpca";
	final static String USE_LOCAL_PCA = "lpca";
	final static String USE_GLOBAL_PROMINENT = "gpdir";
	final static String USE_LOCAL_PROMINENT = "lpdir";
	
	static enum PlaneOrientationStrategy {
		LOCAL_TOARGMIN_1D(USE_GLOBAL_PCA),
		GLOBAL_TOARGMIN_GLOBAL_PCA(USE_GLOBAL_PCA),
		GLOBAL_PCA(USE_GLOBAL_PCA),
		GLOBAL_TOARGMIN_LOCAL_PCA(USE_LOCAL_PCA),
		LOCAL_TOARGMIN_GLOBAL_PCA(USE_GLOBAL_PCA),
		LOCAL_TOARGMIN_LOCAL_PCA(USE_LOCAL_PCA),
		LOCAL_PCA(USE_LOCAL_PCA),
		GLOBAL_PROMINENT_DIRECTIONS(USE_GLOBAL_PROMINENT),
		LOCAL_PROMINENT_DIRECTIONS(USE_LOCAL_PROMINENT),
		;
		
		public final String globalPCA;
		
		private PlaneOrientationStrategy(String globalpca) {
			this.globalPCA = globalpca;
		}
	}

	public static void assess(KomoLog log, PlaneOrientationStrategy strategy, boolean inverseStepsizeWeighting, int localWindowSize) throws IOException {
		
		double[][] traj = log.streamGraphQueriesX()
				.map(MatUtil::stackVectors)
				.map(x->x.getDDRM().data.clone())
				.toArray(double[][]::new);

		double[] avgStepsize = new double[log.numGraphQueries];
		// calculate average step sizes for inversely weighting trajectory points
		for(int i=0; i<log.numGraphQueries-1; i++) {
			SimpleMatrix step = MatUtil.vectorOf(traj[i+1]).minus(MatUtil.vectorOf(traj[i]));
			avgStepsize[i] = step.normF()+0.0001;
		} 
		avgStepsize[log.numGraphQueries-1] = avgStepsize[log.numGraphQueries-2];
		// smooth stepsizes for averaging
		DataPrep.boxSmoothing(avgStepsize, avgStepsize, 5);

		final int dimensionality = traj[0].length;
		// data is steps taken
//		double[][] data = new double[traj.length-1][dimensionality];
//		for(int i=0; i<log.numGraphQueries-1; i++) {
//			SimpleMatrix step = MatUtil.vectorOf(traj[i+1]).minus(MatUtil.vectorOf(traj[i]));
//			data[i] = step.getDDRM().data;
//		}
		
		// data is trajectory points
		double[][] data = Arrays.stream(traj).map(row->row.clone()).toArray(double[][]::new);

		SimpleMatrix[] pcas = new SimpleMatrix[log.numGraphQueries];
		
		if(strategy.globalPCA.equals(USE_GLOBAL_PCA)){
			// global pca
			int skip = Math.min(10, traj.length/8);
//			skip=0;
			double[] weights;
			if(inverseStepsizeWeighting)
				weights = Arrays.stream(avgStepsize).map(h->1/h).toArray();
			else
				weights = Arrays.stream(avgStepsize).map(h->1).toArray();
			
			data = Arrays.copyOfRange(data, skip, data.length);
			weights = Arrays.copyOfRange(weights, skip, weights.length);
			
			double[][] meansAndVariances = DataPrep.getMeansAndVariances(data, weights);
			DataPrep.normalizeData(data, meansAndVariances);
			
			DoubleMatrix dataMatrix = new DoubleMatrix(data);
			// center argmin
//			dataMatrix.subiRowVector(dataMatrix.getRow(dataMatrix.rows-1));
			
			DoubleMatrix weightmatrix = DoubleMatrix.diag(new DoubleMatrix(weights));
			DoubleMatrix[] svd = Singular.sparseSVD(weightmatrix.mmul(dataMatrix));
			SimpleMatrix pca = MatUtil.matrix(svd[2].columns, svd[2].rows, svd[2].transpose().data);
			for(int k=0; k<log.numGraphQueries; k++){
				pcas[k] = pca;
			}
		}
		else if(strategy.globalPCA.equals(USE_LOCAL_PCA)){
			// calculate local pcas for all graph queries ( = optimization steps)
			AtomicInteger kDone = new AtomicInteger();
			double[][] dat = data;
			
			double[] weights;
			if(inverseStepsizeWeighting)
				weights = Arrays.stream(avgStepsize).map(h->1/h).toArray();
			else
				weights = Arrays.stream(avgStepsize).map(h->1).toArray();
			
			IntStream.range(0, log.numGraphQueries).parallel().forEach(k->{
//			for(int k=0; k<log.numGraphQueries; k++){
				System.out.println("calculating dataset weights ("+ k+"/"+log.numGraphQueries+")");

				double[] localizationweights;
				{
					// localization (weighting by trajectory point index difference)
					GaussianDistribution distrib = new GaussianDistribution(0, localWindowSize/*windowsize*/);
					double distribScale = 1/distrib.p(0);
					int k_=k;
					DoubleUnaryOperator kernel = (idx)->distrib.p(idx-k_)*distribScale;
					localizationweights = IntStream.range(0,dat.length).mapToDouble(i->kernel.applyAsDouble(i)*weights[i]).toArray();
				}
				
				// remove data that is effectively zero
				int[] indicesToKeep = IntStream.range(0, localizationweights.length).filter(i->localizationweights[i] > 0.000001).toArray();
				double[][] dat_ = Arrays.stream(indicesToKeep).mapToObj(i->dat[i]).toArray(double[][]::new);
				double[] weight_ = Arrays.stream(indicesToKeep).mapToDouble(i->localizationweights[i]).toArray();

				double[][] meansAndVariances = DataPrep.getMeansAndVariances(dat_, weight_);
				DataPrep.normalizeData(dat_, meansAndVariances);

				System.out.println("LandscapeView: calculating pca "+ kDone.getAndIncrement() +"/"+log.numGraphQueries+")");
				
				DoubleMatrix dataMatrix = new DoubleMatrix(dat_);
				DoubleMatrix weightmatrix = DoubleMatrix.diag(new DoubleMatrix(weight_));
				DoubleMatrix weightedData = weightmatrix.mmul(dataMatrix);
				try {
					DoubleMatrix[] svd = Singular.sparseSVD(weightedData);
					pcas[k] = MatUtil.matrix(svd[2].columns, svd[2].rows, svd[2].transpose().data);
					pcas[k] = pcas[k].extractMatrix(0, 2, 0, pcas[k].numCols());
				} catch(LapackConvergenceException e) {
					System.err.println(k + " : SVD did not converge. strategy: " + strategy.name() + " issw:" + inverseStepsizeWeighting );
				}
			}
			);
		} 
		else if(strategy.globalPCA.equals(USE_GLOBAL_PROMINENT)) {
			double[] weights;
			
			double[][] unitSteps = new double[traj.length-1][dimensionality];
			for(int i=0; i<log.numGraphQueries-1; i++) {
				SimpleMatrix step = MatUtil.normalizeInPlace( MatUtil.vectorOf(traj[i+1]).minus(MatUtil.vectorOf(traj[i])) );
				unitSteps[i] = step.getDDRM().data;
			}
			
			if(inverseStepsizeWeighting)
				weights = Arrays.stream(avgStepsize).map(h->1/h).toArray();
			else
				weights = Arrays.stream(avgStepsize).map(h->1).toArray();
			
			weights = Arrays.copyOfRange(weights, 0, unitSteps.length);
			
			int skip = Math.min(10, traj.length/8);
//			skip=0;
			unitSteps = Arrays.copyOfRange(unitSteps, skip, unitSteps.length);
			weights = Arrays.copyOfRange(weights, skip, weights.length);
			
			
			DoubleMatrix stepMatrix = new DoubleMatrix(unitSteps);
			DoubleMatrix weightmatrix = DoubleMatrix.diag(new DoubleMatrix(weights));
			DoubleMatrix dotproducts = stepMatrix.mmul(stepMatrix.transpose());
			dotproducts.mmuli(weightmatrix);
			double scale = 1.0/dotproducts.columns;
			
			DoubleMatrix promDir1 = DoubleMatrix.zeros(dimensionality);
			for(int r=0; r<dotproducts.rows; r++) {
				DoubleMatrix dir = stepMatrix.getRow(r);
				for(int c=0; c<dotproducts.columns; c++) {
					promDir1.addi(dir.mul(dotproducts.get(r, c)*scale));
				}
			}
			promDir1.muli(1/promDir1.norm2());
			
			DoubleMatrix degreeOfRep = stepMatrix.mmul(promDir1);
			DoubleMatrix promDir1Comps = degreeOfRep.mmul(promDir1.transpose());
			
			DoubleMatrix stepRemainder = stepMatrix.sub(promDir1Comps);
			dotproducts = stepRemainder.mmul(stepRemainder.transpose());
			dotproducts.mmuli(weightmatrix);
			
			DoubleMatrix promDir2 = DoubleMatrix.zeros(dimensionality);
			for(int r=0; r<dotproducts.rows; r++) {
				DoubleMatrix dir = stepRemainder.getRow(r);
				for(int c=0; c<dotproducts.columns; c++) {
					promDir2.addi(dir.mul(dotproducts.get(r, c)*scale));
				}
			}
			promDir2.muli(1/promDir2.norm2());
			
			SimpleMatrix pca = MatUtil.rowmajorMat(new double[][] {promDir1.data,promDir2.data});
			for(int k=0; k<log.numGraphQueries; k++){
				pcas[k] = pca;
			}
		}
		else if(strategy.globalPCA.equals(USE_LOCAL_PROMINENT)) {
			double[] weights;
			
			double[][] unitSteps = new double[traj.length-1][dimensionality];
			for(int i=0; i<log.numGraphQueries-1; i++) {
				SimpleMatrix step = MatUtil.normalizeInPlace( MatUtil.vectorOf(traj[i+1]).minus(MatUtil.vectorOf(traj[i])) );
				unitSteps[i] = step.getDDRM().data;
			}
			
			if(inverseStepsizeWeighting)
				weights = Arrays.stream(avgStepsize).map(h->1/h).toArray();
			else
				weights = Arrays.stream(avgStepsize).map(h->1).toArray();
			
			weights = Arrays.copyOfRange(weights, 0, unitSteps.length);
			double[] weightsies_ = weights;
			
			IntStream.range(0, log.numGraphQueries).parallel().forEach(k->{
//				for(int k=0; k<log.numGraphQueries; k++){
					System.out.println("calculating dataset weights ("+ k+"/"+log.numGraphQueries+")");

					double[] localizationweights;
					{
						// localization (weighting by trajectory point index difference)
						GaussianDistribution distrib = new GaussianDistribution(0, localWindowSize/*windowsize*/);
						double distribScale = 1/distrib.p(0);
						int k_=k;
						DoubleUnaryOperator kernel = (idx)->distrib.p(idx-k_)*distribScale;
						localizationweights = IntStream.range(0,unitSteps.length).mapToDouble(i->kernel.applyAsDouble(i)*weightsies_[i]).toArray();
					}
					
					// remove data that is effectively zero
					int[] indicesToKeep = IntStream.range(0, localizationweights.length).filter(i->localizationweights[i] > 0.000001).toArray();
					double[][] unitSteps_ = Arrays.stream(indicesToKeep).mapToObj(i->unitSteps[i]).toArray(double[][]::new);
					double[] weight_ = Arrays.stream(indicesToKeep).mapToDouble(i->localizationweights[i]).toArray();

					DoubleMatrix stepMatrix = new DoubleMatrix(unitSteps_);
					DoubleMatrix weightmatrix = DoubleMatrix.diag(new DoubleMatrix(weight_));
					DoubleMatrix dotproducts = stepMatrix.mmul(stepMatrix.transpose());
					dotproducts.mmuli(weightmatrix);
					double scale = 1.0/dotproducts.columns;

					DoubleMatrix promDir1 = DoubleMatrix.zeros(dimensionality);
					for(int r=0; r<dotproducts.rows; r++) {
						DoubleMatrix dir = stepMatrix.getRow(r);
						for(int c=0; c<dotproducts.columns; c++) {
							promDir1.addi(dir.mul(dotproducts.get(r, c)*scale));
						}
					}
					promDir1.muli(1/promDir1.norm2());

					DoubleMatrix degreeOfRep = stepMatrix.mmul(promDir1);
					DoubleMatrix promDir1Comps = degreeOfRep.mmul(promDir1.transpose());

					DoubleMatrix stepRemainder = stepMatrix.sub(promDir1Comps);
					dotproducts = stepRemainder.mmul(stepRemainder.transpose());
					dotproducts.mmuli(weightmatrix);

					DoubleMatrix promDir2 = DoubleMatrix.zeros(dimensionality);
					for(int r=0; r<dotproducts.rows; r++) {
						DoubleMatrix dir = stepRemainder.getRow(r);
						for(int c=0; c<dotproducts.columns; c++) {
							promDir2.addi(dir.mul(dotproducts.get(r, c)*scale));
						}
					}
					promDir2.muli(1/promDir2.norm2());

					SimpleMatrix pca = MatUtil.rowmajorMat(new double[][] {promDir1.data,promDir2.data});
					pcas[k] = pca;
			});
		}
		
		// extract 2 principal vectors
		SimpleMatrix[] p1,p2, pca2D;
		p1=new SimpleMatrix[log.numGraphQueries];
		p2=new SimpleMatrix[log.numGraphQueries];
		pca2D=new SimpleMatrix[log.numGraphQueries];
		for(int k=0; k<log.numGraphQueries; k++){
			// principal vectors
			SimpleMatrix p1T = pcas[k].extractVector(true, 0);
			SimpleMatrix p2T = pcas[k].extractVector(true, 1);
			p1[k]=p1T.transpose();
			p2[k]=p2T.transpose();
			pca2D[k] = pcas[k].extractMatrix(0, 2, 0, pcas[k].numCols());
		}
		
		// precalculate plane vectors for single optimization steps
		SimpleMatrix[] plane1Vecs = new SimpleMatrix[log.numGraphQueries];
		SimpleMatrix[] plane2Vecs = new SimpleMatrix[log.numGraphQueries];
		for(int i=0; i<log.numGraphQueries; i++) {
			int idx1 = i;
			int idxLast = log.numGraphQueries-1;
			if(idx1 == log.numGraphQueries-1) {
				idx1--;
			}
			// first direction
			SimpleMatrix p1_;
			switch (strategy) {
			case GLOBAL_TOARGMIN_GLOBAL_PCA:
			case GLOBAL_TOARGMIN_LOCAL_PCA:
				p1_ = MatUtil.normalizeInPlace(MatUtil.vectorOf(traj[idxLast]).minus(MatUtil.vectorOf(traj[0])));
				break;
			case LOCAL_TOARGMIN_1D:
			case LOCAL_TOARGMIN_GLOBAL_PCA:
			case LOCAL_TOARGMIN_LOCAL_PCA:
				p1_ = MatUtil.normalizeInPlace(MatUtil.vectorOf(traj[idxLast]).minus(MatUtil.vectorOf(traj[idx1])));
				break;
			case GLOBAL_PCA:
			case LOCAL_PCA:
			case GLOBAL_PROMINENT_DIRECTIONS:
			case LOCAL_PROMINENT_DIRECTIONS:
				p1_ = p1[i];
				break;
			default:
				throw new RuntimeException("unhandled case " + strategy);
			}
			
			// second direction
			SimpleMatrix p2_;
			switch (strategy) {
			case GLOBAL_PCA:
			case LOCAL_PCA:
			case GLOBAL_PROMINENT_DIRECTIONS:
			case LOCAL_PROMINENT_DIRECTIONS:
				p2_ = p2[i];
				break;
			case GLOBAL_TOARGMIN_GLOBAL_PCA:
			case GLOBAL_TOARGMIN_LOCAL_PCA:
			case LOCAL_TOARGMIN_GLOBAL_PCA:
			case LOCAL_TOARGMIN_LOCAL_PCA:
				p2_ = LandscapeView.getPerpendicularInPlane(p1_, p1[i], p2[i]);
				break;
			case LOCAL_TOARGMIN_1D:
				p2_ = p1_.scale(0);
				break;
			default:
				throw new RuntimeException("unhandled case " + strategy);
			}
			
			plane1Vecs[i] = p1_;
			plane2Vecs[i] = p2_;
//			plane2Vecs[i] = MatUtil.vector(p1_.getNumElements());
		}
		
		// fix mirrored plane vectors
		for(int i=1; i<log.numGraphQueries; i++) {
			System.out.println("checking pca mirroring " + i);
			SimpleMatrix v1 = plane2Vecs[i-1];
			SimpleMatrix v2 = plane2Vecs[i];
			double dot = v1.dot(v2);
			if(dot < 0) {
				for(int k=0; k<v2.getNumElements(); k++) {
						v2.set(k, -v2.get(k));
				}
			}
		}
		
		// project all trajectory segments
		double[][] trajDirGoodness = new double[log.numGraphQueries][log.numGraphQueries-1];
		for(int k=0; k<log.numGraphQueries; k++) {
			SimpleMatrix p1_ = plane1Vecs[k];
			SimpleMatrix p2_ = plane2Vecs[k];
			SimpleMatrix projection = MatUtil.rowmajorMat(new double[][] {p1_.getDDRM().data,p2_.getDDRM().data});
			for(int i=1; i<log.numGraphQueries; i++) {
				SimpleMatrix segment = MatUtil.vectorOf(traj[i]).minus(MatUtil.vectorOf(traj[i-1]));
				SimpleMatrix segDir = MatUtil.normalize(segment);
				// project
				SimpleMatrix p = projection.mult(segDir);
				trajDirGoodness[k][i-1] = p.normF();
			}
		}
		double max = Arrays.stream(trajDirGoodness).flatMapToDouble(Arrays::stream).max().getAsDouble();
		System.out.println("max p=" + max );
		Img img = new Img(log.numGraphQueries-1, log.numGraphQueries);
		img.forEach(px->{
			double v = trajDirGoodness[px.getY()][px.getX()];
			int color = DefaultColorMap.S_PLASMA.interpolate(v);
			px.setValue(color);
		});
		ImageSaver.saveImage(img.toBufferedImage(), String.format("%s-%s.png", strategy.name(), inverseStepsizeWeighting?"issw":""));
		System.gc();
	}

}
