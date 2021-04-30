package hageldave.visnlp.util;

import java.util.Arrays;

import hageldave.jplotter.util.Utils;

public class DataPrep {
	
	public static double[][] getMeansAndVariances(double[][] data, double[] weights){
		double[] mean = new double[data[0].length];
		double weightSum = 0;
		for(int i=0; i<data.length; i++){
			for(int j=0; j<mean.length; j++){
				mean[j] += data[i][j] * weights[i];
				weightSum = weights[i];
			}
		}
		double divBySum = 1/weightSum;
		for(int j=0; j<mean.length; j++){
			mean[j] *= divBySum;
		}
		
		double[] std = new double[mean.length];
		for(int i=0; i<data.length; i++){
			for(int j=0; j<mean.length; j++){
				double v = data[i][j]-mean[j];
				std[j] += v*v * weights[i];
			}
		}
		for(int j=0; j<mean.length; j++){
			std[j] *= divBySum;
			std[j] = Math.sqrt(std[j]);
		}
		return new double[][]{mean,std};
	}
	
	public static double[][] getMeansAndVariances(double[][] data){
		double[] mean = new double[data[0].length];
		for(int i=0; i<data.length; i++){
			for(int j=0; j<mean.length; j++){
				mean[j] += data[i][j];
			}
		}
		for(int j=0; j<mean.length; j++){
			mean[j] /= data.length;
		}
		
		double[] std = new double[mean.length];
		for(int i=0; i<data.length; i++){
			for(int j=0; j<mean.length; j++){
				double v = data[i][j]-mean[j];
				std[j] += v*v;
			}
		}
		for(int j=0; j<mean.length; j++){
			std[j] /= data.length;
			std[j] = Math.sqrt(std[j]);
		}
		return new double[][]{mean,std};
	}
	
	public static void normalizeData(double[][] data, double[][] meanAndStd){
		double[] mean = meanAndStd[0];
		double[] std = meanAndStd[1];
		for(int i=0; i<data.length; i++){
			for(int j=0; j<mean.length; j++){
				data[i][j] = (data[i][j]-mean[j])/std[j];
			}
		}
	}
	
	public static double[][] distanceMatrix(double[][] data){
		double[][] dist = new double[data.length][data.length];
		for(int i=0; i<data.length; i++){
			double[] a = data[i];
			for(int j=i; j<data.length; j++){
				double[] b = data[j];
				dist[i][j] = dist[j][i] = euclidDist(a, b);
			}
		}
		return dist;
	}
	
	public static double[][] distanceMatrix_gauss(double[][] data){
		double[][] dist = new double[data.length][data.length];
		for(int i=0; i<data.length; i++){
			double[] a = data[i];
			for(int j=i; j<data.length; j++){
				double[] b = data[j];
				double d = euclidDist(a, b);
				dist[i][j] = dist[j][i] = d*d;
			}
		}
		return dist;
	}
	
	private static double euclidDist(double[] a, double[] b){
		double sum=0;
		for(int i=0; i<a.length; i++){
			double diff = a[i]-b[i];
			sum += diff*diff;
		}
		return Math.sqrt(sum);
	}

	public static double[][] copy(double[][] arr){
		return Arrays.stream(arr).map(a->a.clone()).toArray(double[][]::new);
	}
	
	public static double[][] sqr(double[][] arr, double[][] dest) {
		for(int i=0; i<arr.length; i++)
			for(int j=0; j<arr[0].length; j++) {
				double d = arr[i][j];
				dest[i][j] = d*d;
			}
		return dest; 
	}
	
	public static double[][] plus(double[][] a, double[][] b, double[][] dest){
		for(int i=0; i<a.length; i++)
			for(int j=0; j<a[0].length; j++) {
				double d = a[i][j]+b[i][j];
				dest[i][j] = d;
			}
		return dest;
	}
	
	public static double[] boxSmoothing(double[] src, double[] dest, int boxSize) {
		if(dest==src)
			return boxSmoothing(src.clone(), dest, boxSize);
		if(dest==null)
			dest = src.clone();
		for(int i=0; i<src.length; i++) {
			double sum=0;
			int cnt=0;
			for(int j=-boxSize/2; j<(boxSize+1)/2; j++) {
				int idx = Utils.clamp(0, i+j, src.length-1);
				sum += src[idx];
				cnt++;
			}
			dest[i] = sum/cnt;
		}
		return dest;
	}
	
}
