package hageldave.visnlp.eval;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.IntStream;

import org.ejml.simple.SimpleMatrix;
import org.ejml.simple.SimpleSVD;

import hageldave.imagingkit.core.Img;
import hageldave.imagingkit.core.util.ImageFrame;
import hageldave.jplotter.color.DefaultColorMap;
import hageldave.visnlp.data.KomoLog;
import hageldave.visnlp.util.DataPrep;
import hageldave.visnlp.util.MatUtil;
import hageldave.visnlp.views.LandscapeView;
import smile.stat.distribution.GaussianDistribution;

public class TrajectoryProjectionGoodness {
	
	public static void main(String[] args) throws IOException {
		File logfile = new File("../motionplanner/problem0/z.log");
		PlaneOrientationStrategy strategy = PlaneOrientationStrategy.GLOBAL_TOARGMIN_GLOBAL_PCA;
		assess(logfile, strategy, false);
	}
	
	static enum PlaneOrientationStrategy {
		GLOBAL_TOARGMIN_GLOBAL_PCA(true),
		GLOBAL_PCA(true),
		GLOBAL_TOARGMIN_LOCAL_PCA(false),
		LOCAL_TOARGMIN_GLOBAL_PCA(true),
		LOCAL_TOARGMIN_LOCAL_PCA(false),
		LOCAL_PCA(false),
		;
		
		public final boolean globalPCA;
		
		private PlaneOrientationStrategy(boolean globalpca) {
			this.globalPCA = globalpca;
		}
	}

	public static void assess(File logfile, PlaneOrientationStrategy strategy, boolean inverseStepsizeWeighting) throws IOException {
		KomoLog log = KomoLog.loadLog(logfile);
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
		DataPrep.boxSmoothing(avgStepsize, avgStepsize, 7);

		final int dimensionality = traj[0].length;
		double[][] data = DataPrep.copy(traj);

		if(inverseStepsizeWeighting){	// inverse weighting by average step size (accounting for decreasing step sizes over the process)
			double[][] data_ = data;
			data = IntStream.range(0, data.length)
					.mapToObj(i->{
						double[] row = data_[i];
						SimpleMatrix weightedRow = MatUtil.vectorOf(row)
								.scale(1.0/avgStepsize[i])
								;
						return weightedRow.getDDRM().data;
					})
					.toArray(double[][]::new);
		}

		SimpleMatrix[] pcas = new SimpleMatrix[log.numGraphQueries];
		
		if(strategy.globalPCA){
			// global pca
			int skip = traj.length/8;
			double[][] meansAndVariances = DataPrep.getMeansAndVariances(Arrays.copyOfRange(data, skip, data.length));
			DataPrep.normalizeData(data, meansAndVariances);
//			DataPrep.normalizeData(data, DataPrep.getMeansAndVariances(data));
			SimpleMatrix dataMatrix = MatUtil.rowmajorMat(data);
			SimpleSVD<SimpleMatrix> svd = dataMatrix.svd(true);
			SimpleMatrix pca = svd.getV().transpose();
			for(int k=0; k<log.numGraphQueries; k++){
				pcas[k] = pca;
			}
		}
		else {
			// calculate local pcas for all graph queries ( = optimization steps)
			double[][] dat = data;
			IntStream.range(0, log.numGraphQueries).parallel().forEach(k->{
//			for(int k=0; k<log.numGraphQueries; k++){
				System.out.println("calculating dataset weights ("+ k+"/"+log.numGraphQueries+")");

				double[][] localdata = dat;
				{
					// localization (weighting by trajectory point index difference)
					GaussianDistribution distrib = new GaussianDistribution(0, 5/*windowsize*/);
					double distribScale = 1/distrib.p(0);
					int k_=k;
					DoubleUnaryOperator kernel = (idx)->distrib.p(idx-k_)*distribScale;
					double[][] data_ = dat;
					localdata = IntStream.range(0,dat.length)
							.mapToObj(i->{
								double[] row = data_[i];
								SimpleMatrix weightedRow = MatUtil.vectorOf(row)
										.scale(kernel.applyAsDouble(i))
										;
								return weightedRow.getDDRM().data;
							})
							.toArray(double[][]::new);
				}

				// get rid of initialization
//				int skip = log.numGraphQueries/8;
//				localdata = Arrays.copyOfRange(localdata, skip, log.numGraphQueries);
				DataPrep.normalizeData(localdata, DataPrep.getMeansAndVariances(localdata));

				System.out.println("LandscapeView: calculating pca "+ k+"/"+log.numGraphQueries+")");

				SimpleMatrix dataMatrix = MatUtil.rowmajorMat(localdata);
				SimpleSVD<SimpleMatrix> svd = dataMatrix.svd(false);
				pcas[k] = svd.getV().transpose();
				pcas[k] = pcas[k].extractMatrix(0, 2, 0, pcas[k].numCols());
			}
			);
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
			int idx2 = log.numGraphQueries-1;
			if(idx1 == log.numGraphQueries-1) {
				idx1--;
			}
			// first direction
			SimpleMatrix p1_;
			switch (strategy) {
			case GLOBAL_TOARGMIN_GLOBAL_PCA:
			case GLOBAL_TOARGMIN_LOCAL_PCA:
				p1_ = MatUtil.normalizeInPlace(MatUtil.vectorOf(traj[idx2]).minus(MatUtil.vectorOf(traj[0])));
				break;
			default:
				break;
			}
			p1_ = MatUtil.normalizeInPlace(MatUtil.vectorOf(traj[idx2]).minus(MatUtil.vectorOf(traj[0])));
			SimpleMatrix p2_ = LandscapeView.getPerpendicularInPlane(p1_, p1[i], p2[i]);
//			plane1Vecs[i] = p1_;
//			plane2Vecs[i] = p2_;
			plane2Vecs[i] = MatUtil.vector(p1_.getNumElements());
			plane1Vecs[i] = p1[i];
//			plane2Vecs[i] = p2[i];
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
		ImageFrame.display(img);
	}

}
