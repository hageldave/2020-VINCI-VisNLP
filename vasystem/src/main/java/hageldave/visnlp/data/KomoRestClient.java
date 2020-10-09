package hageldave.visnlp.data;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.LongBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.TreeMap;

import org.ejml.simple.SimpleMatrix;

import hageldave.visnlp.util.MatUtil;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Request.Builder;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class KomoRestClient {

	OkHttpClient client = new OkHttpClient();
	String baseurl;
	
	public KomoRestClient(String baseurl) {
		this.baseurl = baseurl;
	}
	
	public Response getRequest(String path, Map<String, ?> params, Map<String, ?> headers, byte[] body) {
		String url = baseurl+"/"+path;
		if(Objects.nonNull(params) && !params.isEmpty()) {
			url += "?";
			for(String p:params.keySet()) {
				url += p+"="+params.get(p)+"&";
			}
		}
		Builder reqbuilder = new Request.Builder().url(url);
		if(Objects.nonNull(headers) && !headers.isEmpty()) {
			for(String h : headers.keySet()) {
				reqbuilder.addHeader(h, headers.get(h).toString());
			}
		}
		if(Objects.nonNull(body)) {
			reqbuilder.post(RequestBody.create(body, MediaType.parse("application/octet-stream")));
		}
		
		Request request = reqbuilder.build();
		try {
			return client.newCall(request).execute();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	static String vec2values(SimpleMatrix m) {
		return MatUtil.streamValues(m)
				.mapToObj(Double::toString)
				.reduce("", (a,b)->a+" "+b);
	}
	
	static byte[] vec2values_(SimpleMatrix m) {
		byte[] empty = {};
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		MatUtil.streamValues(m)
				.forEach(v->{
					try {
						dos.writeDouble(v);
					} catch (IOException e) {
						e.printStackTrace();
					}
				});
		return baos.toByteArray();
	}
	
	static byte[] double2bytes(double v) {
		long bits = Double.doubleToLongBits(v);
		byte[] chars = new byte[8];
		for(int i=7; i>=0; i--) {
			long l = bits & 0xff;
			bits = bits >> 8;
			byte b = (byte)l;
			chars[i] = b;
		}
		return chars;
	}
	
	static byte[] concat(byte[] a, byte[] b) {
		byte[] c = new byte[a.length+b.length];
		System.arraycopy(a, 0, c, 0, a.length);
		System.arraycopy(b, 0, c, a.clone().length, b.length);
		return c;
	}
	
	public double[][][] sample(SimpleMatrix origin, SimpleMatrix v0, SimpleMatrix v1, double d0, double d1, int n0, int n1, int nfeatures, double[][][] samples)
	{
		long t = System.currentTimeMillis();
		TreeMap<String, Object> params = new TreeMap<String, Object>();
		params.put("fn", "all");
		params.put("n0", n0);
		params.put("n1", n1);
		params.put("d0", d0);
		params.put("d1", d1);
		params.put("format", "text");
		
		TreeMap<String, Object> headers = new TreeMap<String, Object>();
		headers.put("origin", vec2values(origin));
		headers.put("v0", vec2values(v0));
		headers.put("v1", vec2values(v1));
		
		long t2=0;
		try(
				Response response = getRequest("sample", params, headers, null);
				ResponseBody body = response.body();
				BufferedInputStream bis = new BufferedInputStream(body.byteStream());
				Scanner sc = new Scanner(bis);
		){
			t2 = System.currentTimeMillis();
			int i = 0;
			while(sc.hasNextDouble() && i < n0*n1*nfeatures) {
				double v = sc.nextDouble();
				int k = i/(n0*n1);
				int l = (i%(n0*n1))/n0;
				int m = i%n0;
				samples[k][l][m] = v;
				i++;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		t2 = System.currentTimeMillis()-t2;
		t = System.currentTimeMillis()-t;
		System.out.println("time to matrix:" + t2 + " ms");
		System.out.println("time to sample:" + t + " ms");
		return samples;
	}
	
	public double[][][] sample_(SimpleMatrix origin, SimpleMatrix v0, SimpleMatrix v1, double d0, double d1, int n0, int n1, int nfeatures, double[][][] samples)
	{
		return sample_(origin, v0, v1, d0, d1, n0, n1, nfeatures, samples, Double.NaN, Double.NaN, new double[0]);
	}
	
	public double[][][] sample_(SimpleMatrix origin, SimpleMatrix v0, SimpleMatrix v1, double d0, double d1, int n0, int n1, int nfeatures, double[][][] samples, double mu, double nu, double[] lambda)
	{
		long t = System.currentTimeMillis();
		TreeMap<String, Object> params = new TreeMap<String, Object>();
		params.put("fn", "all");
		params.put("n0", n0);
		params.put("n1", n1);
		params.put("d0", d0);
		params.put("d1", d1);
		params.put("mu", mu);
		params.put("nu", nu);
		
		byte[] reqbody = {};
		reqbody = concat(reqbody,vec2values_(origin));
		reqbody = concat(reqbody,vec2values_(v0));
		reqbody = concat(reqbody,vec2values_(v1));
		if(lambda.length > 0)
		reqbody = concat(reqbody,vec2values_(MatUtil.vectorOf(lambda)));
		
		long t2=0;
		try(
				Response response = getRequest("sample", params, null, reqbody);
				ResponseBody body = response.body();
				BufferedInputStream bis = new BufferedInputStream(body.byteStream());
				DataInputStream dis = new DataInputStream(bis);
		){
			t2 = System.currentTimeMillis();
			int i = 0;
			int[] bytes = new int[8];
			while( i < n0*n1*nfeatures) {
				double v = dis.readDouble();
				int k = i/(n0*n1);
				int l = (i%(n0*n1))/n0;
				int m = i%n0;
				samples[k][l][m] = v;
				i++;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		t2 = System.currentTimeMillis()-t2;
		t = System.currentTimeMillis()-t;
		System.out.println("time to matrix:" + t2 + " ms");
		System.out.println("time to sample:" + t + " ms");
		return samples;
	}
	
	public double[][] hessian(SimpleMatrix origin, double[][] hessian, double mu, double nu, double[] lambda)
	{
		long t = System.currentTimeMillis();
		TreeMap<String, Object> params = new TreeMap<String, Object>();
		params.put("mu", mu);
		params.put("nu", nu);
		
		byte[] reqbody = {};
		reqbody = concat(reqbody,vec2values_(origin));
		if(lambda.length > 0)
		reqbody = concat(reqbody,vec2values_(MatUtil.vectorOf(lambda)));
		
		long t2=0;
		try(
				Response response = getRequest("hessian", params, null, reqbody);
				ResponseBody body = response.body();
				BufferedInputStream bis = new BufferedInputStream(body.byteStream());
				DataInputStream dis = new DataInputStream(bis);
		){
			t2 = System.currentTimeMillis();
			int i = 0;
			int[] bytes = new int[8];
			int dim = origin.getNumElements();
			while( i < dim*dim) {
				double v = dis.readDouble();
				int k = i/(dim);
				int m = i%dim;
				hessian[k][m] = v;
				i++;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		t2 = System.currentTimeMillis()-t2;
		t = System.currentTimeMillis()-t;
		System.out.println("time to matrix:" + t2 + " ms");
		System.out.println("time to hessian:" + t + " ms");
		return hessian;
	}
	
	static boolean readBytes(InputStreamReader reader, int[] buffer) throws IOException {
		boolean available = true;
		for(int i=0; i<8; i++) {
			int v = reader.read();
			buffer[i] = v;
			if(v < 0)
				available = false;
		}
		return available;
	}
	
	static double doubleFromBytes(int[] bytes) {
		long l = 0;
		for(int i=7; i>=0; i--) {
			long b = bytes[i];
			l = (l<<8) | b;
		}
		return Double.longBitsToDouble(l);
	}
	
	
}
