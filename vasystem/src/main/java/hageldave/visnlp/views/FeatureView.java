package hageldave.visnlp.views;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowAdapter;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.DoubleBinaryOperator;
import java.util.stream.Collectors;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import hageldave.jplotter.canvas.BlankCanvas;
import hageldave.jplotter.color.ColorMap;
import hageldave.jplotter.color.DefaultColorMap;
import hageldave.jplotter.interaction.CoordSysPanning;
import hageldave.jplotter.interaction.CoordSysScrollZoom;
import hageldave.jplotter.interaction.CoordSysViewSelector;
import hageldave.jplotter.renderables.Lines;
import hageldave.jplotter.renderables.Lines.SegmentDetails;
import hageldave.jplotter.renderables.Triangles;
import hageldave.jplotter.renderers.CoordSysRenderer;
import hageldave.jplotter.renderers.LinesRenderer;
import hageldave.jplotter.renderers.TrianglesRenderer;
import hageldave.jplotter.util.Pair;
import hageldave.jplotter.util.PickingRegistry;
import hageldave.jplotter.util.Utils;
import hageldave.visnlp.data.KomoLog;
import hageldave.visnlp.util.FeatureHandle;
import hageldave.visnlp.util.SimpleSelectionModel;
import hageldave.visnlp.util.SimpleSelectionModel.SimpleSelectionListener;
import smile.math.Math;

public class FeatureView {

	public final KomoLog log;
	
	public final CoordSysRenderer coordsys;
	public final BlankCanvas canvas;
	
	public final SimpleSelectionModel<Pair<String, FeatureHandle>> selectionModel;
	
	public final SimpleSelectionModel<String> aggregateSelectionModel;
	
	public final SimpleSelectionModel<Integer> querySelectionModel;

	@SuppressWarnings("resource")
	public FeatureView(SimpleSelectionModel<Integer> queryModel, KomoLog log, Map<String,Integer> featureNames2color, Integer ... fTypes) {
		this.log = log;
		List<Integer> featureTypes = Arrays.asList(fTypes);
		// extract features by name
		TreeMap<String, LinkedList<Integer>> fname2indices = new TreeMap<>();
		for(int i=0; i < log.numFeatures; i++){
			if(featureTypes.contains(log.featureTypes[i])){
				String name = log.featureNames.get(i);
				if(!fname2indices.containsKey(name)){
					fname2indices.put(name, new LinkedList<>());
				}
				fname2indices.get(name).add(i);
			}
		}
		Map<String,Integer> fnames2color;
		if(featureNames2color==null){
			fnames2color = new HashMap<>();
			int j = 0;
			ColorMap colorMap = DefaultColorMap.Q_8_SET2;
			for(Map.Entry<String, LinkedList<Integer>> entry: fname2indices.entrySet()){
				if(entry.getValue().isEmpty()){
					continue;
				}
				int color = colorMap.getColor(j++%colorMap.numColors());
				fnames2color.put(entry.getKey(), color);
			}
		} else {
			fnames2color = featureNames2color;
		}
		System.out.println("FeatureView: building feature matrix");
		// features along optimization steps as high dimensional points
		double[][] featureVectors = new double[log.numFeatures][log.numGraphQueries];
		for(int t=0; t<log.numGraphQueries; t++){
			double[] phi = log.getGraphQueryPhi(t);
			for(int i=0; i<log.numFeatures; i++){
				featureVectors[i][t] = phi[i];
			}
		}
		// for equalities flip feature values when having negative balance
		for(int f: log.equalityFeatureIndices) {
			double[] vec = featureVectors[f];
			// calc balance
			double sum=0;
			for(int i = log.numGraphQueries/5; i < vec.length; i++)
				sum += vec[i];
			if(sum < 0)
				for(int i = 0; i < vec.length; i++)
					vec[i] *= -1.0;
		}
		
		// aggregation per feature name
		System.out.println("FeatureView: building feature aggregates");
		TreeMap<String, double[]> fname2aggregate = new TreeMap<>();
		fname2indices.forEach((name,indices)->{
			int type = indices.size() == 0 ? 2 : log.featureTypes[indices.get(0)];
			DoubleBinaryOperator aggregator = type == 3 ? Math::max : FeatureView::signedMax;
			double[] aggregate = indices.stream()
				.map(i->featureVectors[i])
				.reduce(new double[log.numGraphQueries], (a,b)->aggregate(a, b, aggregator));
			fname2aggregate.put(name, aggregate);
		});
		
		System.out.println("FeatureView: building view");
		
		PickingRegistry<Pair<String, FeatureHandle>> pick2nameNphi = new PickingRegistry<>();
		HashMap<Pair<String, FeatureHandle>, Lines> handle2lines = new HashMap<>();
		
		TreeMap<String, Lines> fname2aggrLine = new TreeMap<>();
		TreeMap<String, List<Lines>> fname2lines = new TreeMap<>();
		fname2indices.forEach((name,indices)->{
			int color = Optional.ofNullable(fnames2color.get(name)).orElseGet(()->0xff000000);
			// add lines for each feature
			ArrayList<Lines> lines = new ArrayList<>(indices.size());
			fname2lines.put(name, lines);
			for(int i : indices){
				double[] yCoords = featureVectors[i];
				Lines line = new Lines();
				lines.add(line);
				Pair<String, FeatureHandle> handle = Pair.of(name, new FeatureHandle(i, log));
				int pick = pick2nameNphi.register(handle);
				handle2lines.put(handle, line);
				for(int q = 0; q < log.numGraphQueries-1; q++){
					SegmentDetails seg = line.addSegment(q, yCoords[q], q+1, yCoords[q+1]);
					seg.setColor(color);
					seg.setPickColor(pick);
				}
				line.hide(true);
			}
			// add aggregate line
			Lines aggrLine = new Lines();
			fname2aggrLine.put(name, aggrLine);
			double[] yCoords = fname2aggregate.get(name);
			Pair<String, FeatureHandle> handle = Pair.of(name, null);
			int pick = pick2nameNphi.register(handle);
			handle2lines.put(handle, aggrLine);
			for(int q = 0; q < log.numGraphQueries-1; q++){
				SegmentDetails seg = aggrLine.addSegment(q, yCoords[q], q+1, yCoords[q+1]);
				seg.setColor(color);
				seg.setPickColor(pick);
			}
			aggrLine
			.setGlobalThicknessMultiplier(2)
//			.setStrokePattern(0xfff0)
			;
		});
		
		this.coordsys = new CoordSysRenderer();
		this.canvas = new BlankCanvas();
		canvas.setRenderer(coordsys);
		LinesRenderer aggrRenderer = new LinesRenderer();
		fname2aggrLine.forEach((name,line)->aggrRenderer.addItemToRender(line));
		LinesRenderer linesRenderer = new LinesRenderer();
		fname2lines.forEach((name,lines)->{
			lines.forEach(line->linesRenderer.addItemToRender(line));
		});
		Lines zeroLine = new Lines().setVertexRoundingEnabled(true);
		zeroLine.addSegment(0, 0, log.numGraphQueries, 0).setColor(0xffdddddd);
		Lines queryXVertical = new Lines();
		queryXVertical.setVertexRoundingEnabled(true).setStrokePattern(0x1ce7).addSegment(0, 0, 0, 0);
		LinesRenderer queryXRenderer = new LinesRenderer();
		Triangles queryIntervalHighlight = new Triangles();
		double[] leftRightInterval = {0,0};
		{
			Point2D lu = new Point2D() {
				public double getX() {return coordsys.getCoordinateView().getMinX();}
				public double getY() {return coordsys.getCoordinateView().getMaxY();}
				public void setLocation(double x, double y) {/*ignore*/};
			};
			Point2D ru = new Point2D() {
				public double getX() {return coordsys.getCoordinateView().getMaxX();}
				public double getY() {return coordsys.getCoordinateView().getMaxY();}
				public void setLocation(double x, double y) {/*ignore*/};
			};
			Point2D lb = new Point2D() {
				public double getX() {return coordsys.getCoordinateView().getMinX();}
				public double getY() {return coordsys.getCoordinateView().getMinY();}
				public void setLocation(double x, double y) {/*ignore*/};
			};
			Point2D rb = new Point2D() {
				public double getX() {return coordsys.getCoordinateView().getMaxX();}
				public double getY() {return coordsys.getCoordinateView().getMinY();}
				public void setLocation(double x, double y) {/*ignore*/};
			};
			Point2D u1 = new Point2D.Double() {
				public double getY() {return lu.getY();}
				public double getX() {return leftRightInterval[0];}
			};
			Point2D u2 = new Point2D.Double() {
				public double getY() {return lu.getY();}
				public double getX() {return leftRightInterval[1];}
			};
			Point2D b1 = new Point2D.Double() {
				public double getY() {return lb.getY();}
				public double getX() {return leftRightInterval[0];}
			};
			Point2D b2 = new Point2D.Double() {
				public double getY() {return lb.getY();}
				public double getX() {return leftRightInterval[1];}
			};
			queryIntervalHighlight.addQuad(lb, lu, u1, b1);
			queryIntervalHighlight.addQuad(b2, u2, ru, rb);
			queryIntervalHighlight.setGlobalAlphaMultiplier(0.3);
			queryIntervalHighlight.hide(true);
		}
		queryXRenderer.addItemToRender(queryXVertical);
		coordsys.setContent(
				queryXRenderer
				.withPrepended(new TrianglesRenderer().addItemToRender(queryIntervalHighlight))
				.withPrepended(linesRenderer)
				.withPrepended(aggrRenderer)
				.withPrepended(new LinesRenderer()
						.addItemToRender(zeroLine)));
		coordsys.setCoordinateView(0,-10,log.numGraphQueries-1,10);
		canvas.setBackground(Color.WHITE);
		coordsys.setxAxisLabel("");
		coordsys.setyAxisLabel("");
		
		new CoordSysPanning(canvas,coordsys)
//		.setPannedAxes(CoordSysPanning.Y_AXIS)
		.register();
		new CoordSysScrollZoom(canvas,coordsys)
//		.setZoomedAxes(CoordSysScrollZoom.Y_AXIS)
		.setZoomFactor(1.2)
		.register();
		new CoordSysViewSelector(canvas,coordsys) {
			@Override
			public void areaSelected(double minX, double minY, double maxX, double maxY) {
				coordsys.setCoordinateView(minX, minY, maxX, maxY);
				canvas.scheduleRepaint();
			}
		}
		.register();
		canvas.enableInputMethods(true);
		canvas.addKeyListener(new KeyAdapter() {
			public void keyTyped(java.awt.event.KeyEvent e) {
				if(e.getKeyChar() == 'r'){
					coordsys.setCoordinateView(0,-10,log.numGraphQueries-1,10);
					canvas.scheduleRepaint();
				}
					
			};
		});
		
		// selection model and selection changes
		selectionModel = new SimpleSelectionModel<>((f1,f2)->{
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
		selectionModel.addSelectionListener(new SimpleSelectionListener<Pair<String, FeatureHandle>>() {
			@Override
			public void selectionChanged(SortedSet<Pair<String, FeatureHandle>> selection) {
				if(selection.isEmpty()){
					// reset everything 
					fname2lines.forEach((n,lines)-> lines.forEach(l->l.setGlobalAlphaMultiplier(1).setGlobalThicknessMultiplier(1)));
					fname2aggrLine.forEach((n,l)->l.setGlobalAlphaMultiplier(1));
				} else {
					List<Lines> selectedlines = selection.stream()
							.map(h->handle2lines.get(h))
							.filter(Objects::nonNull)
							.collect(Collectors.toList());
					// make all lines translucent and of standard size
					fname2lines.forEach((n,lines)->lines.forEach(l->l.setGlobalAlphaMultiplier(0.1).setGlobalThicknessMultiplier(1)));
					fname2aggrLine.forEach((n,l)->l.setGlobalAlphaMultiplier(0.1));
					// make selected lines solid
					selectedlines.stream().forEach(l->l.setGlobalAlphaMultiplier(1).setGlobalThicknessMultiplier(2));
					if(!selection.stream().filter(p->p.second != null).findAny().isPresent()) {
						// only group(s) selected
						selection.forEach(p->{
							List<Lines> lines = fname2lines.get(p.first);
							if(lines != null) lines.forEach(l->l.setGlobalAlphaMultiplier(1).setGlobalThicknessMultiplier(1));
						});
					}
				}
				canvas.scheduleRepaint();
			}
		});
		aggregateSelectionModel = new SimpleSelectionModel<>();
		aggregateSelectionModel.setSelection(fname2indices.keySet());
		aggregateSelectionModel.addSelectionListener(new SimpleSelectionListener<String>() {
			@Override
			public void selectionChanged(SortedSet<String> selection) {
				// display all except for those in selection
				fname2lines.forEach((name,lines)->{
					if(selection.contains(name)){
						lines.forEach(l->l.hide(true));
					} else {
						lines.forEach(l->l.hide(false));
					}
				});
				if(selection.containsAll(fname2aggrLine.keySet())){
					fname2aggrLine.forEach((name,line)->{
						line.hide(false);
					});
				} else {
					fname2aggrLine.forEach((name,line)->{
						line.hide(true);
					});
				}
				canvas.scheduleRepaint();
			}
		});
		querySelectionModel = Objects.nonNull(queryModel) ? queryModel:new SimpleSelectionModel<>();
		querySelectionModel.addSelectionListener(new SimpleSelectionListener<Integer>() {
			public void selectionChanged(java.util.SortedSet<Integer> selection) {
				queryXVertical.removeAllSegments();
				for(int q: selection) {
					queryXVertical.addSegment(q, coordsys.getCoordinateView().getMinY(), q, coordsys.getCoordinateView().getMaxY());
				}
				queryXVertical.setDirty();
				if(selection.size() > 1) {
					leftRightInterval[0] = selection.first();
					leftRightInterval[1] = selection.last();
					queryIntervalHighlight.setDirty().hide(false);
				} else {
					queryIntervalHighlight.hide(true);
				}
				canvas.scheduleRepaint();
			}
		});
		coordsys.addCoordinateViewListener((src,view)->{
			double mx = view.getMaxY();
			double mn = view.getMinY();
			queryXVertical.getSegments().forEach(seg->{
				seg.p0.setLocation(seg.p0.getX(), mn);
				seg.p1.setLocation(seg.p1.getX(), mx);
			});
			queryXVertical.setDirty();
			queryIntervalHighlight.setDirty();
		});
		
		// picking (selecting a point)
		canvas.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
//				if(SwingUtilities.isLeftMouseButton(e)){
//					int pixel = canvas.getPixel(e.getX(), e.getY(), true, 5);
//					Pair<String, FeatureHandle> feature = pick2nameNphi.lookup(pixel);
//					if(feature != null){
//						selectionModel.setSelection(feature);
//					} else {
//						selectionModel.setSelection();
//					}
//				} else 
				if(SwingUtilities.isRightMouseButton(e)){
					int pixel = canvas.getPixel(e.getX(), e.getY(), true, 5);
					Pair<String, FeatureHandle> feature = pick2nameNphi.lookup(pixel);
					TreeSet<String> selection = new TreeSet<>(fname2indices.keySet());
					if(feature != null && feature.second == null){
						selection.remove(feature.first);
					}
					aggregateSelectionModel.setSelection(selection);
				}
					
			}
		});
		
		new CoordSysViewSelector(canvas,coordsys) {
			{
				extModifierMask = 0;
				extModifierMaskExcludes.add(InputEvent.SHIFT_DOWN_MASK);
				extModifierMaskExcludes.add(InputEvent.CTRL_DOWN_MASK);
			}
			@Override
			public void areaSelected(double minX, double minY, double maxX, double maxY) {
				int q1 = Utils.clamp(0, (int)minX, log.numGraphQueries-1);
				int q2 = Utils.clamp(0, (int)maxX, log.numGraphQueries-1);
				querySelectionModel.setSelection(q1,q2);
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
				querySelectionModel.setSelection(q);
			}
		};
		canvas.addMouseListener(clickListener);
		
	}
	
	static double signedMax(double a, double b){
		double a_ = Math.abs(a);
		double b_ = Math.abs(b);
		double max = Math.max(a_,b_);
//		return max==a_ ? Math.signum(a)*max : Math.signum(b)*max;
		return max;
	}
	
	static double[] aggregate(double[] a, double[] b, DoubleBinaryOperator aggregation){
		double[] c = a.clone();
		for(int i=0;i<a.length;i++)
			c[i] = aggregation.applyAsDouble(a[i], b[i]);
		return c;
	}

	public static void main(String[] args) throws JsonParseException, JsonMappingException, IOException {
		System.out.println("main: loading log");
		KomoLog log = KomoLog.loadLog("/push.log.yaml");
		FeatureView featureView = new FeatureView(null, log,null, 4);
		
		JFrame frame = new JFrame("featureview");
		frame.getContentPane().add(featureView.canvas);
		featureView.canvas.setPreferredSize(new Dimension(300, 300));
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			public void windowClosing(java.awt.event.WindowEvent e) {
				featureView.coordsys.close();
			};
		});
		
		SwingUtilities.invokeLater(()->{
			frame.pack();
			frame.setVisible(true);
		});
	}

}
