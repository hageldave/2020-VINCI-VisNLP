package hageldave.visnlp.views;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowAdapter;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.function.DoubleBinaryOperator;
import java.util.stream.IntStream;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.ejml.simple.SimpleMatrix;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import hageldave.jplotter.canvas.BlankCanvas;
import hageldave.jplotter.interaction.CoordSysViewSelector;
import hageldave.jplotter.renderables.Lines;
import hageldave.jplotter.renderers.CoordSysRenderer;
import hageldave.jplotter.renderers.LinesRenderer;
import hageldave.jplotter.util.Utils;
import hageldave.visnlp.data.KomoLog;
import hageldave.visnlp.util.MatUtil;
import hageldave.visnlp.util.SimpleSelectionModel;

public class SpeedView {

	public final KomoLog log;
	public final CoordSysRenderer coordsys;
	public final BlankCanvas canvas;
	protected int windowWidth = 9;
	protected Point2D[] linePoints;
	protected SimpleMatrix[] fwdDiff;
	protected Lines line;

	@SuppressWarnings("resource")
	public SpeedView(KomoLog log, SimpleSelectionModel<Integer> graphQuerySelection) {
		this.log = log;
		System.out.println("SpeedView: building view");


		linePoints = new Point2D[log.numGraphQueries];
		for(int i=0; i<log.numGraphQueries; i++) {
			linePoints[i] = new Point2D.Double();
		}

		line = new Lines();
		for(int i=0; i<log.numGraphQueries-1; i++) {
			line.addLineStrip(linePoints);
		}

		// calculate gradients
		{
			SimpleMatrix[] X = log.streamGraphQueriesX().map(MatUtil::stackVectors).toArray(SimpleMatrix[]::new);
			fwdDiff = new SimpleMatrix[log.numGraphQueries-1];
			for(int i=0; i<log.numGraphQueries-1; i++) {
				// calc fwd diff
				SimpleMatrix diff = X[i+1].minus(X[i]);
				fwdDiff[i] = diff;
			}
			X=null;
		}

		calculateDescend(linePoints, windowWidth);

		Lines queryRule = new Lines().setStrokePattern(0x1ce7).setVertexRoundingEnabled(true);
		for(int q:graphQuerySelection.getSelection()) {
			queryRule.addSegment(q, 0, q, 1);
		}

		this.coordsys = new CoordSysRenderer();
		this.canvas = new BlankCanvas();
		canvas.setRenderer(coordsys);
		coordsys.setContent(new LinesRenderer().addItemToRender(line).addItemToRender(queryRule));
		coordsys.setCoordinateView(0,0,log.numGraphQueries-1,1);
		canvas.setBackground(Color.WHITE);
		canvas.setPreferredSize(new Dimension(300,300));
		coordsys.setxAxisLabel("");
		coordsys.setyAxisLabel("");

		graphQuerySelection.addSelectionListener(s->{
			queryRule.removeAllSegments();
			for(int q:s) {
				queryRule.addSegment(q, 0, q, 1);
			}
			queryRule.setDirty();
			canvas.scheduleRepaint();
		});
		
		new CoordSysViewSelector(canvas,coordsys) {
			{
				extModifierMask = 0;
			}
			@Override
			public void areaSelected(double minX, double minY, double maxX, double maxY) {
				int q1 = Utils.clamp(0, (int)minX, log.numGraphQueries-1);
				int q2 = Utils.clamp(0, (int)maxX, log.numGraphQueries-1);
				graphQuerySelection.setSelection(q1,q2);
			}
			
			@Override
			protected void createSelectionAreaBorder() {
				Point start_ = Utils.swapYAxis(start, canvas.getHeight());
				Point2D end_ = Utils.swapYAxis(end, canvas.getHeight());
				Rectangle2D coordSysArea = Utils.swapYAxis(coordsys.getCoordSysArea(),canvas.getHeight());
				areaBorder.removeAllSegments();
				areaBorder.addSegment(start.getX(), coordSysArea.getMinY(), start.getX(), coordSysArea.getMaxY());
				areaBorder.addSegment(end.getX(), coordSysArea.getMinY(), end.getX(), coordSysArea.getMaxY());
			}
		}
		.register();

		MouseListener clickListener = new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if(!SwingUtilities.isLeftMouseButton(e))
					return;
				Point2D p = coordsys.transformAWT2CoordSys(e.getPoint(), canvas.getHeight());
				int q = Utils.clamp(0, (int)p.getX(), log.numGraphQueries-1);
				graphQuerySelection.setSelection(q);
			}
		};
		canvas.addMouseListener(clickListener);
	}

	private void calculateDescend(Point2D[] linePoints, int windowWidth) {
		double[] speed = new double[log.numGraphQueries-1];
		double[] descend = new double[log.numGraphQueries];
		// speed calc
		double[] speedSum= {0};
		IntStream.range(0, log.numGraphQueries-1)
		.parallel()
		.forEach((i)-> 
		{
//		for(int i=0; i<log.numGraphQueries-1; i++) {
			SimpleMatrix aggr = MatUtil.vector(fwdDiff[0].getNumElements());
			int numAggr = 0;
			for(int j=0; j<windowWidth; j++) {
				int k = (i-windowWidth/2)+j;
				if(k >= 0 && k < fwdDiff.length) {
					aggr = aggr.plus(fwdDiff[k]);
					numAggr++;
				}
			}
			double norm = aggr.scale(1.0/numAggr).normF();
			speed[i] = norm;
			synchronized (speedSum) {
				speedSum[0] += speed[i];
			}
		});
		// calc descend
		double normalization = 1.0/speedSum[0];
		for(int i=0; i<log.numGraphQueries-1; i++) {
			descend[i] = speedSum[0]*normalization;
			speedSum[0] = speedSum[0]-speed[i];
		}
		descend[log.numGraphQueries-1] = 0;

		for(int i=0; i<log.numGraphQueries; i++) {
			linePoints[i].setLocation(i, descend[i]);
		}
	}

	static double[] aggregate(double[] a, double[] b, DoubleBinaryOperator aggregation){
		double[] c = a.clone();
		for(int i=0;i<a.length;i++)
			c[i] = aggregation.applyAsDouble(a[i], b[i]);
		return c;
	}

	public int getWindowWidth() {
		return windowWidth;
	}
	
	public void setWindowWidth(int windowWidth) {
		this.windowWidth = windowWidth;
		calculateDescend(linePoints, windowWidth);
		line.setDirty();
		canvas.scheduleRepaint();
	}
	
	public static void main(String[] args) throws JsonParseException, JsonMappingException, IOException {
		System.out.println("main: loading log");
		KomoLog log = KomoLog.loadLog("/push.log.yaml");
		SpeedView featureView = new SpeedView(log, new SimpleSelectionModel<>());

		JFrame frame = new JFrame("speedview");
		frame.getContentPane().add(featureView.canvas);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			public void windowClosing(java.awt.event.WindowEvent e) {
				featureView.canvas.close();
			};
		});

		SwingUtilities.invokeLater(()->{
			frame.pack();
			frame.setVisible(true);
		});
	}

}
