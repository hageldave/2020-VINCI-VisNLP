package hageldave.visnlp.app;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;

import org.w3c.dom.Document;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import hageldave.imagingkit.core.Img;
import hageldave.imagingkit.core.Pixel;
import hageldave.imagingkit.core.io.ImageSaver;
import hageldave.jplotter.canvas.BlankCanvas;
import hageldave.jplotter.canvas.FBOCanvas;
import hageldave.jplotter.color.ColorMap;
import hageldave.jplotter.color.ColorOperations;
import hageldave.jplotter.color.DefaultColorMap;
import hageldave.jplotter.color.SimpleColorMap;
import hageldave.jplotter.coordsys.ExtendedWilkinson;
import hageldave.jplotter.font.FontProvider;
import hageldave.jplotter.interaction.CoordSysPanning;
import hageldave.jplotter.interaction.CoordSysScrollZoom;
import hageldave.jplotter.interaction.CoordSysViewSelector;
import hageldave.jplotter.misc.DefaultGlyph;
import hageldave.jplotter.renderables.Legend;
import hageldave.jplotter.renderables.Lines;
import hageldave.jplotter.renderables.Lines.SegmentDetails;
import hageldave.jplotter.renderables.Points;
import hageldave.jplotter.renderables.Points.PointDetails;
import hageldave.jplotter.renderables.Triangles;
import hageldave.jplotter.renderers.CoordSysRenderer;
import hageldave.jplotter.renderers.LinesRenderer;
import hageldave.jplotter.renderers.PointsRenderer;
import hageldave.jplotter.renderers.Renderer;
import hageldave.jplotter.renderers.TrianglesRenderer;
import hageldave.jplotter.svg.SVGUtils;
import hageldave.jplotter.util.GenericKey;
import hageldave.jplotter.util.Pair;
import hageldave.visnlp.data.KomoLog;
import hageldave.visnlp.util.DataPrep;
import hageldave.visnlp.util.FeatureHandle;
import hageldave.visnlp.util.HSlider;
import hageldave.visnlp.util.MatUtil;
import hageldave.visnlp.util.SimpleSelectionModel;
import hageldave.visnlp.util.SimpleSelectionModel.SimpleSelectionListener;
import hageldave.visnlp.views.FeatureView;
import hageldave.visnlp.views.SpeedView;
import smile.math.Math;
import smile.projection.PCA;

public class Offline {

	static class TrajectoryDisplayMode {
		String name;

		public TrajectoryDisplayMode(String name) {
			this.name = name;
		}

		public void updatePoints(){}

		@Override
		public String toString() {
			return name;
		}
	}

	static class LogVizModel {
		SimpleSelectionModel<Integer> timeStep = new SimpleSelectionModel<Integer>();
		SimpleSelectionModel<Integer> graphQuery = new SimpleSelectionModel<Integer>();

		public SortedSet<Integer> getSelectedGraphQuery() {
			return graphQuery.getSelection();
		}

		public SortedSet<Integer> getSelectedTimeStep() {
			return timeStep.getSelection();
		}

		public void setSelectedGraphQuery(Iterable<Integer> selection){
			graphQuery.setSelection(selection);
		}

		public void setSelectedGraphQuery(Integer... selection){
			graphQuery.setSelection(selection);
		}

		public void setSelectedTimeStep(Iterable<Integer> selection){
			timeStep.setSelection(selection);
		}

		public void setSelectedTimeStep(Integer... selection){
			timeStep.setSelection(selection);
		}

		public synchronized LogVizModel addGraphQueryChangeListener(ActionListener l){
			graphQuery.addSelectionListener((s)->l.actionPerformed(new ActionEvent(LogVizModel.this, ActionEvent.ACTION_FIRST, "setSelectedGraphQuery")));
			return this;
		}

		public synchronized LogVizModel addTimeStepChangeListener(ActionListener l){
			timeStep.addSelectionListener((s)->l.actionPerformed(new ActionEvent(LogVizModel.this, ActionEvent.ACTION_FIRST, "setSelectedTimeStep")));
			return this;
		}
	}

	@SuppressWarnings({"resource" })
	public static void main(String[] args) throws JsonParseException, JsonMappingException, IOException {
		KomoLog log = KomoLog.loadLog("/z2.dat.yaml");
//		KomoLog log = KomoLog.loadLog("/push.log.yaml");

		System.gc();

		int minDim = Arrays.stream(log.varDims).min().getAsInt();
		int maxDim = Arrays.stream(log.varDims).max().getAsInt();

		// data [dimensionality][numQueries*numTimesteps(dimnsnlty)][dim]
		double[][][] data = new double[log.numDimensionalities][][];
		for(int d = 0; d < log.numDimensionalities; d++){
			int dimStart = log.dimensionalityStarts[d];
			int dimEnd = log.dimensionalityStarts[d+1];
			data[d] = log.streamGraphQueriesX()
					// extract robot configs for current dimensionality
					.map(m->Arrays.asList(m).subList(dimStart, dimEnd))
					.flatMap(List::stream)
					.map(m->MatUtil.streamValues(m).toArray())
					.toArray(double[][]::new);
		}

		for(double[][] perTimestepData : data){
			double[][] meansAndVariances = DataPrep.getMeansAndVariances(perTimestepData);
			DataPrep.normalizeData(perTimestepData, meansAndVariances);
		}


		TreeMap<String, List<int[/*(q,cfg,phi)*/]>> fName2violations = new TreeMap<>();
		double feasibilityThresh = 0.4;
		double[][] infeasibility = new double[log.numDimensionalities][];
		for(int d = 0; d < log.numDimensionalities; d++){
			int dimStart = log.dimensionalityStarts[d];
			int dimEnd = log.dimensionalityStarts[d+1];
			infeasibility[d] = new double[log.numGraphQueries*(dimEnd-dimStart)];
			for(int i = 0; i < log.numGraphQueries; i++){
				double[] phis = log.getGraphQueryPhi(i);

				for(int p=0; p<log.numInequalityFeatures; p++){
					int idx = log.inequalityFeatureIndices[p];
					String name = log.featureNames.get(idx);
					if(!fName2violations.containsKey(name)){
						fName2violations.put(name, new LinkedList<>());
					}
					ArrayList<Number> vars = log.featureVariables.get(idx);
					for(int cfg = dimStart; cfg < dimEnd; cfg++){
						if(vars.contains(cfg)){
							double value = phis[idx];
							if(value >= feasibilityThresh){
								infeasibility[d][(dimEnd-dimStart)*i+cfg-dimStart]++;
								fName2violations.get(name).add(new int[]{i, cfg, idx});
							}
						}
					}
				}
				for(int p=0; p<log.numEqualityFeatures; p++){
					int idx = log.equalityFeatureIndices[p];
					String name = log.featureNames.get(idx);
					if(!fName2violations.containsKey(name)){
						fName2violations.put(name, new LinkedList<>());
					}
					ArrayList<Number> vars = log.featureVariables.get(idx);
					for(int cfg = dimStart; cfg < dimEnd; cfg++){
						if(vars.contains(cfg)){
							double value = Math.abs(phis[idx]);
							if(value >= feasibilityThresh){
								infeasibility[d][(dimEnd-dimStart)*i+cfg-dimStart]++;
								fName2violations.get(name).add(new int[]{i, cfg, idx});
							}
						}
					}
				}
			}
		}
		double maxInfeasibility = Arrays.stream(infeasibility).flatMapToDouble(Arrays::stream).max().getAsDouble();

		boolean aggreagateRobotTime[] = {true};
		
		// initialize displayed points
		Point2D.Double[][] points = new Point2D.Double[log.numDimensionalities][];
			
		for(int d = 0; d < log.numDimensionalities; d++){
			int numTimestepsForDimensionality = log.getNumTimestepsForDimensionality(d);
			points[d] = new Point2D.Double[data[d].length];
			for(int i = 0; i < log.numGraphQueries; i++){
				ArrayList<Point2D.Double> pset = new ArrayList<Point2D.Double>(numTimestepsForDimensionality);
				for(int t = 0; t < numTimestepsForDimensionality; t++){
					int j = i*numTimestepsForDimensionality+t;
					Point2D.Double p = new Point2D.Double(data[d][j][0], data[d][j][1]) {
						private static final long serialVersionUID = 1L;
						
						@Override
						public double getX() {
							if(aggreagateRobotTime[0]) {
								return pset.stream().mapToDouble(p->p.x).average().getAsDouble();
							} else 
								return x;
						}
						@Override
						public double getY() {
							if(aggreagateRobotTime[0]) {
								return pset.stream().mapToDouble(p->p.y).average().getAsDouble();
							} else 
								return y;
						}
					};
					points[d][j] = p;
					pset.add(p);
				}
			}
		}
		
//		Point2D[][] robotTimeAggregatedPoints = new Point2D[log.numDimensionalities][log.numGraphQueries];
//		for(int d=0; d<log.numDimensionalities; d++){
//			int numTimestepsForDimensionality = log.getNumTimestepsForDimensionality(d);
//			for(int i = 0; i < log.numGraphQueries; i++){
//				ArrayList<Point2D> pset = new ArrayList<>(log.numTimesteps);
//				for(int t = 0; t < numTimestepsForDimensionality; t++){
//					pset.add( points[d][i*numTimestepsForDimensionality+t] );
//				}
//				Point2D p = new Point2D() {
//					@Override
//					public double getX() {
//						return pset.stream().mapToDouble(Point2D::getX).average().getAsDouble();
//					}
//					@Override
//					public double getY() {
//						return pset.stream().mapToDouble(Point2D::getY).average().getAsDouble();
//					}
//					@Override
//					public void setLocation(double x, double y) {
//						// ignore
//					}
//				};
//				robotTimeAggregatedPoints[d][i] = p;
//			}
//		}

		Runnable points2unitRange = new Runnable() {
			@Override
			public void run() {
				for(int d = 0; d < log.numDimensionalities; d++){
					double minX = Arrays.stream(points[d]).mapToDouble(p->p.x).min().getAsDouble();
					double minY = Arrays.stream(points[d]).mapToDouble(p->p.y).min().getAsDouble();
					double maxX = Arrays.stream(points[d]).mapToDouble(p->p.x).max().getAsDouble();
					double maxY = Arrays.stream(points[d]).mapToDouble(p->p.y).max().getAsDouble();
					for(int i = 0; i<points[d].length; i++){
						points[d][i].setLocation(
								(points[d][i].x-minX)*1.8/(maxX-minX) + d*2 + 0.1, 
								(points[d][i].y-minY)*1.8/(maxY-minY) -0.9
								);
					}
				}
			}
		};
		points2unitRange.run();


		LogVizModel model = new LogVizModel();


		// BUILD VIS

		LinesRenderer trajectoryContent = new LinesRenderer();
		LinesRenderer robotTrajContent = new LinesRenderer();
		LinesRenderer lineSearchContent = new LinesRenderer();
		lineSearchContent.setEnabled(false);
		PointsRenderer feasibilityIndicatorContent = new PointsRenderer();
		ArrayList<Lines> optimTrajGraphs = new ArrayList<>(log.numTimesteps);
		ArrayList<Lines> robotTrajGraphs = new ArrayList<>(log.numTimesteps);
		Lines lineSearches = new Lines().setGlobalThicknessMultiplier(2);
		lineSearchContent.addItemToRender(lineSearches);
		// x trajectories (dim reduced)
		ColorMap robotTimeCmap = DefaultColorMap.S_PLASMA.resample(10, 0, 0.8);
		int aggregateTrajColor = DefaultColorMap.Q_8_SET2.getColor(0);
		for(int d = 0; d < log.numDimensionalities; d++){
			int numTimestepsForDimensionality = log.getNumTimestepsForDimensionality(d);
			for(int t = 0; t < numTimestepsForDimensionality; t++){
				int rt = log.dimensionalityStarts[d]+t;
				Lines trj = new Lines().setGlobalAlphaMultiplier(0.3);
				optimTrajGraphs.add(trj);
				for(int i = 0; i < log.numGraphQueries-1; i++){
					int i_ = i;
					boolean isNewton = log.graphQueryStepContainsNewton(i);
					int color = robotTimeCmap.interpolate(t*1.0/(log.getNumTimestepsForDimensionality(d)-1));
					//color = robotTimeCmap.getColor(log.dimensionalityStarts[d]+t);
					Point2D p0= points[d][i*numTimestepsForDimensionality+t];
					Point2D p1 = points[d][(i+1)*numTimestepsForDimensionality+t];
					IntSupplier colorByQuery = () -> {
						int c = aggreagateRobotTime[0] ? aggregateTrajColor:color;
						if(model.getSelectedGraphQuery().isEmpty())
							return c;
						if(model.getSelectedGraphQuery().size() == 1) {
							int q = model.getSelectedGraphQuery().last();
							if(q < i_) return 0;
							return ColorOperations.interpolateColor(c, c & 0x00ffffff, Math.max(-i_+q,0)/(log.numGraphQueries/1.0));
						} else {
							int q1 = model.getSelectedGraphQuery().first();
							int q2 = model.getSelectedGraphQuery().last();
							if(q1 <= i_ && q2 >= i_) {
								return c;
							} else {
								return aggreagateRobotTime[0] ? 0xffeeeeee:0;
							}
						}
					};
					IntSupplier colorByStepAndQuery = ()-> {
						if(model.getSelectedTimeStep().isEmpty()) {
							// no traj selection
							return colorByQuery.getAsInt();
						} else {
							// traj selection
							if(model.getSelectedTimeStep().contains(rt)) {
								return color;
							} else {
								return (colorByQuery.getAsInt() & 0xff000000) & 0xff888888;
							}
						}
					};
					SegmentDetails segment = trj.addSegment(p0,p1)
							.setColor(colorByStepAndQuery)
							.setPickColor(Pixel.rgb(t, i, d+1));
					if(!isNewton){
						lineSearches.getSegments().add(segment.clone());
					}
				}
				trajectoryContent.addItemToRender(trj);
			}
		}
		lineSearches.getSegments().forEach(seg->{
			IntSupplier c = seg.color0;
			seg.setColor(()-> c.getAsInt() == 0 ? 0:0xffff00ff);
		});
		// robot trajectories
		for(int i = 0; i < log.numGraphQueries; i++){
			int color = 0xff000000;
			Lines trj = new Lines()
					.setGlobalAlphaMultiplier(0.6)
					.setGlobalThicknessMultiplier(2)
					.setStrokePattern(0xf0f0);
			robotTrajGraphs.add(trj);
			for(int d = 0; d < log.numDimensionalities; d++){
				int numTimestepsForDimensionality = log.getNumTimestepsForDimensionality(d);
				for(int t = 0; t < numTimestepsForDimensionality-1; t++){
					Point2D p0= points[d][i*numTimestepsForDimensionality+t];
					Point2D p1 = points[d][i*numTimestepsForDimensionality+t+1];
					trj.addSegment(p0,p1).setColor(color);
				}
			}
			robotTrajContent.addItemToRender(trj);
		}
		// feasibility indicators
		ColorMap infeasibilityCmap = DefaultColorMap.D_SPECTRAL.reversed().resample(10, 0.7, 1);
		int colorFeasible = DefaultColorMap.Q_8_ACCENT.getColor(0);
		Points infeasibilityIndicators = new Points(DefaultGlyph.CIRCLE_F).setGlobalScaling(1.0);
		HashMap<GenericKey, PointDetails> queryCfg2pointdetail = new HashMap<>();
		for(int d = 0; d < log.numDimensionalities; d++){
			int numTimestepsForDimensionality = log.getNumTimestepsForDimensionality(d);
			for(int t = 0; t < numTimestepsForDimensionality; t++){
				int cfg = log.dimensionalityStarts[d]+t;
				for(int i = 0; i < log.numGraphQueries; i++){
					Point2D p0= points[d][i*numTimestepsForDimensionality+t];
					double infeasible = infeasibility[d][i*numTimestepsForDimensionality+t];
					if(infeasible > 0){
						PointDetails pd = infeasibilityIndicators.addPoint(p0).setColor(infeasibilityCmap.interpolate(infeasible/maxInfeasibility));
						queryCfg2pointdetail.put(new GenericKey(i,cfg), pd);
					}
				}
			}
		}
		feasibilityIndicatorContent
		//		.addItemToRender(feasibilityIndicators)
		.addItemToRender(infeasibilityIndicators.setGlobalAlphaMultiplier(0.8));

		// config&step selection boxes
		Points configStepBoxes = new Points(DefaultGlyph.SQUARE).setGlobalScaling(1.5);
		Runnable configStepHighlight = () ->{
			configStepBoxes.removeAllPoints().setDirty();
			for(int t : model.getSelectedTimeStep()){
				int d = 0;
				while(d+1 < log.numDimensionalities && log.dimensionalityStarts[d+1] <= t)
					d++;
				for(int q : model.getSelectedGraphQuery()){
					Point2D[] pnts = points[d];
					int idx = q*log.getNumTimestepsForDimensionality(d)+t-log.dimensionalityStarts[d];
					double infeasible = infeasibility[d][idx];
					Point2D p = pnts[idx];
					configStepBoxes.addPoint(p);
					PointDetails pointDetails = configStepBoxes.addPoint(p).setScaling(0.8);
					if(infeasible == 0){
						pointDetails.setColor(colorFeasible);
					} else {
						pointDetails.setColor(infeasibilityCmap.interpolate(infeasible/maxInfeasibility));
					}
				}
			}
		};
		model.addGraphQueryChangeListener(e->configStepHighlight.run());
		model.addTimeStepChangeListener(e->configStepHighlight.run());
		
		Runnable setAllDirty = () ->{
			optimTrajGraphs.forEach(Lines::setDirty);
			lineSearches.setDirty();
			robotTrajGraphs.forEach(Lines::setDirty);
			infeasibilityIndicators.setDirty();
			configStepBoxes.setDirty();
		};


		CoordSysRenderer trajectoryCoordsys = new CoordSysRenderer();
		BlankCanvas trajectoryCanvas = new BlankCanvas();
		trajectoryCanvas.setRenderer(trajectoryCoordsys);
		trajectoryCanvas.setDisposeOnRemove(false);
		trajectoryCanvas.setBackground(Color.white);
		trajectoryCoordsys.setCoordinateView(0,-1,log.numDimensionalities*2,1);
		trajectoryCanvas.setPreferredSize(new Dimension(500, 300));
		trajectoryCoordsys.setContent(
				(feasibilityIndicatorContent).withAppended
				(robotTrajContent).withAppended
				(trajectoryContent).withAppended
				(lineSearchContent).withAppended
				(new PointsRenderer().addItemToRender(configStepBoxes))
				);
		trajectoryCoordsys.setxAxisLabel("Robot path evolution");
		trajectoryCoordsys.setyAxisLabel("");
		new CoordSysPanning(trajectoryCanvas,trajectoryCoordsys).register();
		new CoordSysScrollZoom(trajectoryCanvas,trajectoryCoordsys).setZoomFactor(1.2).register();
		new CoordSysViewSelector(trajectoryCanvas,trajectoryCoordsys) {
			@Override
			public void areaSelected(double minX, double minY, double maxX, double maxY) {
				coordsys.setCoordinateView(minX, minY, maxX, maxY);
			}
		}.register();
		trajectoryCoordsys.setTickMarkGenerator(new ExtendedWilkinson(){
			// custom tick marks to indicate robot path sections
			public Pair<double[],String[]> genTicksAndLabels(double min, double max, int desiredNumTicks, boolean verticalAxis) {
				if(verticalAxis || log.numDimensionalities == 1) {
					return Pair.of(new double[0], new String[0]);
				} else {
					if((int)(max/2) == (int)(min/2)){
						int idx = (int)(min/2);
						if(idx >= 0 && idx < log.numDimensionalities){
							double x = (min+max)/2;
							String label = "part "+(idx+1);
							return Pair.of(new double[]{x}, new String[]{label});
						}
					}
					LinkedList<Double> locations = new LinkedList<>();
					LinkedList<String> labels = new LinkedList<>();
					for(int i = 0; i < log.numDimensionalities+1; i++){
						double x = i*2;
						if(min <= x && max >= x){
							locations.add(x);
							String label = i < log.numDimensionalities ? "[part "+(i+1) : "     ";
							if(i>0){
								label = "part " + i +"] " + label;
							} else {
								label = "      " + label;
							}

							labels.add(label);
						}
					}
					double[] locs = locations.stream().mapToDouble(d->d.doubleValue()).toArray();
					String[] array = labels.stream().toArray(String[]::new);
					return Pair.of(locs, array);
				}
			};
		});



		// feature graph colors and lookup structures
		TreeSet<String> featureNames = new TreeSet<>();
		HashMap<String, Integer> featureNames2color = new HashMap<>();
		HashMap<String, ArrayList<FeatureHandle>> fNames2Indices = new HashMap<>();
		{
			Arrays.stream(log.equalityFeatureIndices).mapToObj(log.featureNames::get).forEach(featureNames::add);
			Arrays.stream(log.inequalityFeatureIndices).mapToObj(log.featureNames::get).forEach(featureNames::add);
			int j = 0;
			ColorMap colorMap = new SimpleColorMap(Arrays.copyOf(DefaultColorMap.Q_12_PAIRED.getColors(),10));
			for(String name: featureNames){
				int color = colorMap.getColor(j++%colorMap.numColors());
				featureNames2color.put(name, color);
			}
			Arrays.stream(log.equalityFeatureIndices).forEach(i->{
				String name = log.featureNames.get(i);
				if(!fNames2Indices.containsKey(name)){
					fNames2Indices.put(name, new ArrayList<>());
				}
				fNames2Indices.get(name).add(new FeatureHandle(i, log));
			});
			Arrays.stream(log.inequalityFeatureIndices).forEach(i->{
				String name = log.featureNames.get(i);
				if(!fNames2Indices.containsKey(name)){
					fNames2Indices.put(name, new ArrayList<>());
				}
				fNames2Indices.get(name).add(new FeatureHandle(i, log));
			});
		}
		// feature graphs
		FeatureView ineqFeatView = new FeatureView(model.graphQuery, log, featureNames2color, 3);
		FeatureView eqFeatView = new FeatureView(model.graphQuery, log, featureNames2color, 4);
		ineqFeatView.coordsys.setPaddingLeft(5).setPaddingRight(2);
		eqFeatView.coordsys.setPaddingLeft(2).setPaddingRight(5);

		ineqFeatView.coordsys.setxAxisLabel("Inequalities (step,φ)");
		eqFeatView.coordsys.setxAxisLabel("Equalities (step,φ)");

		ineqFeatView.canvas.setDisposeOnRemove(false);
		eqFeatView.canvas.setDisposeOnRemove(false);
		
		SpeedView speedView = new SpeedView(log, model.graphQuery);


		// add indicator stripes that show the feasibility threshold (infeasible is grey)
		{
			int color = 0x11000000;
			Triangles ineqInfeasibleArea = new Triangles();
			ineqInfeasibleArea.addQuad(new Rectangle2D.Double(0, feasibilityThresh, log.numGraphQueries, 10-feasibilityThresh)).forEach(tri->tri.setColor(color));
			ineqInfeasibleArea.addQuad(new Rectangle2D.Double(0, 10, log.numGraphQueries, 1000)).forEach(tri->tri.setColor(color));
			ineqInfeasibleArea.addQuad(new Rectangle2D.Double(0, 1000, log.numGraphQueries, 100000)).forEach(tri->tri.setColor(color));
			Renderer content = ineqFeatView.coordsys.getContent();
			content = content.withPrepended(new TrianglesRenderer().addItemToRender(ineqInfeasibleArea));
			ineqFeatView.coordsys.setContent(content);

			Triangles eqInfeasibleArea = new Triangles();
			eqInfeasibleArea.getTriangleDetails().addAll(ineqInfeasibleArea.getTriangleDetails());
			eqInfeasibleArea.addQuad(new Rectangle2D.Double(0, -feasibilityThresh, log.numGraphQueries, -(10-feasibilityThresh))).forEach(tri->tri.setColor(color));
			eqInfeasibleArea.addQuad(new Rectangle2D.Double(0, -10, log.numGraphQueries, -1000)).forEach(tri->tri.setColor(color));
			eqInfeasibleArea.addQuad(new Rectangle2D.Double(0, -1000, log.numGraphQueries, -100000)).forEach(tri->tri.setColor(color));
			content = eqFeatView.coordsys.getContent();
			content = content.withPrepended(new TrianglesRenderer().addItemToRender(eqInfeasibleArea));
			eqFeatView.coordsys.setContent(content);
		}

		// checkboxes to toggle trajectories
		JCheckBox cbRobotTrajectory = new JCheckBox("robot path");
		JCheckBox cbOptimizationTrajectory = new JCheckBox("optim. traj.");
		JCheckBox cbViolations = new JCheckBox("violations", true);
		JCheckBox cbLineSearches = new JCheckBox("line search");
		JCheckBox cbExpandTrajectory = new JCheckBox("expand traj.");
		cbRobotTrajectory.setSelected(true);
		cbOptimizationTrajectory.setSelected(true);
		cbLineSearches.setSelected(false);

		cbRobotTrajectory.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				if(cbRobotTrajectory.isSelected()){
					robotTrajContent.setEnabled(true);
				} else {
					robotTrajContent.setEnabled(false);
				}
				trajectoryCanvas.scheduleRepaint();
			}
		});
		cbOptimizationTrajectory.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				if(cbOptimizationTrajectory.isSelected()){
					trajectoryContent.setEnabled(true);
				} else {
					trajectoryContent.setEnabled(false);
				}
				trajectoryCanvas.scheduleRepaint();
			}
		});
		cbLineSearches.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				if(cbLineSearches.isSelected()){
					lineSearchContent.setEnabled(true);
				} else {
					lineSearchContent.setEnabled(false);
				}
				trajectoryCanvas.scheduleRepaint();
			}
		});
		cbViolations.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				if(cbViolations.isSelected()){
					feasibilityIndicatorContent.setEnabled(true);
				} else {
					feasibilityIndicatorContent.setEnabled(false);
				}
				trajectoryCanvas.scheduleRepaint();
			}
		});
		cbExpandTrajectory.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				aggreagateRobotTime[0] = !cbExpandTrajectory.isSelected();
				setAllDirty.run();
				trajectoryCanvas.scheduleRepaint();
			}
		});


		// build feature tree
		DefaultMutableTreeNode root = new DefaultMutableTreeNode("Constraints");
		JTree tree = new JTree(root){
			private static final long serialVersionUID = 1L;

			public Dimension getPreferredScrollableViewportSize() {return new Dimension(200, 400);};
		};
		TreeMap<String, DefaultMutableTreeNode> name2group = new TreeMap<>();
		TreeMap<FeatureHandle, DefaultMutableTreeNode> handle2Node = new TreeMap<>();
		{
			for(String name:featureNames){
				DefaultMutableTreeNode group = new DefaultMutableTreeNode(name);
				name2group.put(name, group);
				root.add(group);
				for(FeatureHandle handle: fNames2Indices.get(name)){
					DefaultMutableTreeNode constr = new DefaultMutableTreeNode(handle){
						private static final long serialVersionUID = 1L;

						@Override
						public String toString() {
							return handle.index + " " + handle.getVars().toString();
						}
					};
					handle2Node.put(handle, constr);
					group.add(constr);
				}
			}
		}
		{
			TreeCellRenderer delegate = tree.getCellRenderer();
			tree.setCellRenderer(new TreeCellRenderer() {

				private Img img = new Img(22,18);
				private ImageIcon icon = new ImageIcon(img.getRemoteBufferedImage());

				private ImageIcon getIcon(Color color, String text){
					img.fill(0);
					img.paint(g->{
						g.setColor(color);
						g.fillRect(1, 1, 19, 16);
						Pixel.getLuminance(color.getRGB());
						g.setColor(Pixel.getLuminance(color.getRGB()) > 128 ? Color.black:Color.white);
						g.setFont(FontProvider.getUbuntuMono(10, Font.PLAIN));
						g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
						g.drawString(text, 3, 13);
					});
					return icon;
				}

				@Override
				public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
						boolean leaf, int row, boolean hasFocus) 
				{
					JLabel comp = (JLabel) delegate.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
					DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
					if(node != root && node.getUserObject() instanceof String){
						String groupName = (String) node.getUserObject();
						ArrayList<FeatureHandle> features = fNames2Indices.get(groupName);
						int type = features.get(0).getType();
						int color = featureNames2color.get(groupName);
						int numConstraints = fNames2Indices.get(groupName).size();
						comp.setIcon(getIcon(new Color(color), String.format("%3d", numConstraints)));
					}
					return comp;
				}
			});
		}
		JScrollPane treeScroll = new  JScrollPane(tree);
		tree.setExpandsSelectedPaths(false);
		SimpleSelectionModel<String> featureGroupSelection = new SimpleSelectionModel<>();
		tree.addTreeSelectionListener(new TreeSelectionListener() {
			@Override
			public void valueChanged(TreeSelectionEvent e) {
				TreePath[] selectionPaths = tree.getSelectionModel().getSelectionPaths();
				LinkedList<Pair<String,FeatureHandle>> selection = new LinkedList<>();
				LinkedList<String> selectedGroups = new LinkedList<>();
				for(TreePath path:selectionPaths){
					DefaultMutableTreeNode lastNode = (DefaultMutableTreeNode) path.getLastPathComponent();
					if(lastNode==root)
						continue;
					Object userObject = lastNode.getUserObject();
					if(userObject instanceof String){
						String name = (String) userObject;
						selection.add(Pair.of(name, null));
						selectedGroups.add(name);
					} else if(userObject instanceof FeatureHandle){
						FeatureHandle handle = (FeatureHandle) userObject;
						selection.add(Pair.of(handle.getName(), handle));
					}
				}
				ineqFeatView.selectionModel.setSelection(selection);
				eqFeatView.selectionModel.setSelection(selection);
				featureGroupSelection.setSelection(selectedGroups);
			}
		});
		tree.expandRow(0);
		tree.addTreeExpansionListener(new TreeExpansionListener() {
			@Override
			public void treeExpanded(TreeExpansionEvent event) {
				handleExpansion();
			}
			@Override
			public void treeCollapsed(TreeExpansionEvent event) {
				handleExpansion();
			}

			void handleExpansion(){
				Enumeration<TreePath> expandedDescendants = tree.getExpandedDescendants(new TreePath(root));
				TreeSet<String> collapsed = new TreeSet<>(featureNames);
				if(expandedDescendants != null){
					while(expandedDescendants.hasMoreElements()){
						TreePath path = expandedDescendants.nextElement();
						DefaultMutableTreeNode lastNode = (DefaultMutableTreeNode) path.getLastPathComponent();
						if(lastNode != root){
							String name = (String) lastNode.getUserObject();
							collapsed.remove(name);
						}
					}
				}
				ineqFeatView.aggregateSelectionModel.setSelection(collapsed);
				eqFeatView.aggregateSelectionModel.setSelection(collapsed);
			}
		});
		SimpleSelectionListener<Pair<String,FeatureHandle>> featureselectTreeupdate = new SimpleSelectionListener<Pair<String,FeatureHandle>>() {
			@Override
			public void selectionChanged(SortedSet<Pair<String, FeatureHandle>> selection) {
				LinkedList<TreePath> selectionPaths = new LinkedList<>();
				for(Pair<String, FeatureHandle> item: selection){
					if(item.second == null){
						selectionPaths.add(new TreePath(new Object[]{root, name2group.get(item.first)}));
					} else {
						selectionPaths.add(new TreePath(new Object[]{root, name2group.get(item.first), handle2Node.get(item.second)}));
					}
				}
				tree.setSelectionPaths(selectionPaths.toArray(new TreePath[0]));
			}
		};
		ineqFeatView.selectionModel.addSelectionListener(featureselectTreeupdate);
		eqFeatView.selectionModel.addSelectionListener(featureselectTreeupdate);

		Runnable updateVisibleIndicators = ()->{
			Set<Integer> selectedTimestep = model.getSelectedTimeStep();
			if(/* no group or constraint selected :*/ 
					featureGroupSelection.getSelection().isEmpty() && 
					ineqFeatView.selectionModel.getSelection().isEmpty())
			{
				if(selectedTimestep.isEmpty()){
					infeasibilityIndicators.getPointDetails().forEach(pd->pd.setScaling(1));
				} else {
					queryCfg2pointdetail.forEach((key,pd)->{
						Integer cfg = key.getComponent(1, Integer.class);
						pd.setScaling(selectedTimestep.contains(cfg) ? 1:0.1);
					});
				}
			} else {
				infeasibilityIndicators.getPointDetails().forEach(pd->pd.setScaling(0));
			}
			for(String name:featureGroupSelection.getSelection()){
				List<int[]> violations = fName2violations.get(name);
				for(int[] v:violations){
					PointDetails pd = queryCfg2pointdetail.get(new GenericKey(v[0],v[1]));
					if(selectedTimestep.isEmpty()){
						pd.setScaling(1);
					} else {
						pd.setScaling(selectedTimestep.contains(v[1]) ? 1:0.1);
					}
				}
			}
			for(Pair<String,FeatureHandle> item : ineqFeatView.selectionModel.getSelection()){
				if(item.second == null)
					continue;
				List<int[]> violations = fName2violations.get(item.first);
				for(int[] v:violations){
					if(v[2] == item.second.index) {
						PointDetails pd = queryCfg2pointdetail.get(new GenericKey(v[0],v[1]));
						if(selectedTimestep.isEmpty()){
							pd.setScaling(1);
						} else {
							pd.setScaling(selectedTimestep.contains(v[1]) ? 1:0.1);
						}
					}
				}
			}
			infeasibilityIndicators.setDirty();
			trajectoryCanvas.scheduleRepaint();
		};
		featureGroupSelection.addSelectionListener(selection->updateVisibleIndicators.run());
		model.addTimeStepChangeListener(e->updateVisibleIndicators.run());
		ineqFeatView.selectionModel.addSelectionListener(s->updateVisibleIndicators.run());

		// build config table
		TableModel configTableModel = new AbstractTableModel() {
			private static final long serialVersionUID = 1L;

			@Override
			public Object getValueAt(int rowIndex, int columnIndex) {
				switch (columnIndex) {
				case 0: return log.timestepNames.get(rowIndex);
				case 1: {
					int d = findDimensionalityIdx(rowIndex, log);
					return (d+1) + " [" + log.varDims[rowIndex] + "]";
				}
				default:return null;
				}
			}

			public String getColumnName(int column) {
				switch (column) {
				case 0: return "var name";
				case 1: return "part [dim]";
				default:return "";
				}
			};

			@Override
			public int getRowCount() {
				return log.numTimesteps;
			}

			@Override
			public int getColumnCount() {
				return 2;
			}
		};
		JTable configTable = new JTable(configTableModel);
		JScrollPane tableScroll = new JScrollPane(configTable);
		DefaultTableCellRenderer configtablecellrenderer = new DefaultTableCellRenderer(){
			private static final long serialVersionUID = 1L;
			Color lightgray = new Color(0xeeeef2);
			public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
				JLabel label = (JLabel)super.getTableCellRendererComponent(configTable, value, isSelected, hasFocus, row, column);
				if(!isSelected){
					int dim = findDimensionalityIdx(row, log);
					dim = column == 0 ? row:dim;
					label.setBackground((dim%2==0) ? Color.white:lightgray);
				}
				return label;
			};
		};
		configTable.getColumnModel().getColumn(0).setCellRenderer(configtablecellrenderer);
		configTable.getColumnModel().getColumn(1).setCellRenderer(configtablecellrenderer);

		JCheckBox cbxSelectFeatures = new JCheckBox("autoslect features");

		configTable.setPreferredScrollableViewportSize(new Dimension(150, 400));
		model.addTimeStepChangeListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				SortedSet<Integer> step = model.getSelectedTimeStep();
				ListSelectionModel selectionModel = configTable.getSelectionModel();
				selectionModel.setValueIsAdjusting(true);
				selectionModel.clearSelection();
				for(Integer i: step)
					selectionModel.addSelectionInterval(i, i);
				selectionModel.setValueIsAdjusting(false);
			}
		});

		Runnable selectCorrespondingFeatures = ()->{
			SortedSet<Integer> configs = model.getSelectedTimeStep();
			// find features that correspond to selected configs
			TreeSet<Integer> features = new TreeSet<>();
			Arrays.stream(log.inequalityFeatureIndices)
			.filter(i->{
				for(Number cfg : log.featureVariables.get(i))
					if(configs.contains(cfg))
						return true;
				return false;
			})
			.forEach(features::add);
			Arrays.stream(log.equalityFeatureIndices)
			.filter(i->{
				for(Number cfg : log.featureVariables.get(i))
					if(configs.contains(cfg))
						return true;
				return false;
			})
			.forEach(features::add);
			List<Pair<String, FeatureHandle>> featureSelection = features.stream()
					.map(f->Pair.of(log.featureNames.get(f), new FeatureHandle(f,log)))
					.collect(Collectors.toList());
			Set<Pair<String, FeatureHandle>> featureSelectionGruops = featureSelection.stream()
					.map(pair->new Pair<String,FeatureHandle>(pair.first, null))
					.collect(Collectors.toSet());
			featureSelection.addAll(featureSelectionGruops);
			ineqFeatView.selectionModel.setSelection(featureSelection);
			eqFeatView.selectionModel.setSelection(featureSelection);
		};

		model.addTimeStepChangeListener(e->{
			if(cbxSelectFeatures.isSelected()){
				selectCorrespondingFeatures.run();
			}
		});

		SimpleSelectionListener<Pair<String,FeatureHandle>> featureselectToConfigselect = new SimpleSelectionListener<Pair<String,FeatureHandle>>()
		{
			@Override
			public void selectionChanged(SortedSet<Pair<String,FeatureHandle>> selection) {
				if(!cbxSelectFeatures.isSelected()){
					Set<Integer> configs = selection.stream()
							.filter(p->p.second != null)
							.map(p->p.second.index)
							.map(log.featureVariables::get)
							.flatMap(ArrayList::stream)
							.map(Number::intValue)
							.collect(Collectors.toSet());
					model.setSelectedTimeStep(configs);
				}
			}
		};
		ineqFeatView.selectionModel.addSelectionListener(featureselectToConfigselect);
		eqFeatView.selectionModel.addSelectionListener(featureselectToConfigselect);
		
		Consumer<Lines> updatethickness = l->{
			l.getSegments().forEach(seg->{
				int id = seg.pickColor;
				int d = Pixel.b(id)-1;
				int t = Pixel.r(id);
				int i = Pixel.g(id);
				if(model.getSelectedGraphQuery().isEmpty()){
					seg.setThickness(1);
				} else if(i == model.getSelectedGraphQuery().first()){
					seg.setThickness(2);
				} else {
					int step = model.getSelectedGraphQuery().first();
					if(i-step == 1){
						seg.setThickness(2, 1);
					} else if(i-step == -1){
						seg.setThickness(1, 2);
					} else {
						seg.setThickness(1);
					}
				}
			});
			l.setDirty();
		};


		Runnable setRobotTrajectoryAppearance = new Runnable() {
			@Override
			public void run() {
				SortedSet<Integer> graphQuery = model.getSelectedGraphQuery();
				for(int i=0; i<log.numGraphQueries ; i++){
					Lines line = robotTrajGraphs.get(i);
					if(!graphQuery.contains(i)){
						line.setGlobalAlphaMultiplier(0);
					} else {
						line.setGlobalAlphaMultiplier(0.6);
					}
					line.setDirty();
				}
			}
		};
		LinesRenderer linesRenderer = trajectoryContent;
		Runnable setGraphAppearance = new Runnable() {
			@Override
			public void run() {
				int selectedTimesteps = model.getSelectedTimeStep().size();
				switch (selectedTimesteps) {
				case 0:// reset to default view
				{
					linesRenderer.getItemsToRender().forEach(updatethickness);
					linesRenderer.getItemsToRender().forEach(lines -> lines.setGlobalAlphaMultiplier(0.3).setGlobalThicknessMultiplier(1));
				}
				break;
				case 1:// single selection
				{
					int row = model.getSelectedTimeStep().first();
					linesRenderer.getItemsToRender().clear();
					for(int i=0; i<log.numTimesteps; i++){
						if(i != row){
							Lines lines = optimTrajGraphs.get(i).setGlobalAlphaMultiplier(0.1).setGlobalThicknessMultiplier(1).setDirty();
							linesRenderer.getItemsToRender().addFirst(lines);
						} else {
							Lines lines = optimTrajGraphs.get(i).setGlobalAlphaMultiplier(1).setGlobalThicknessMultiplier(2);
							updatethickness.accept(lines);
							linesRenderer.getItemsToRender().add(lines);
						}
					}
				}
				break;
				default:// multiple selected;
				{
					linesRenderer.getItemsToRender().clear();
					for(int i=0; i < log.numTimesteps; i++){
						if(!model.getSelectedTimeStep().contains(i)){
							Lines lines = optimTrajGraphs.get(i).setGlobalAlphaMultiplier(0.1).setGlobalThicknessMultiplier(1).setDirty();
							linesRenderer.getItemsToRender().addFirst(lines);
						} else {
							Lines lines = optimTrajGraphs.get(i).setGlobalAlphaMultiplier(0.5).setGlobalThicknessMultiplier(1);
							updatethickness.accept(lines);
							linesRenderer.getItemsToRender().add(lines);
						}
					}
				}
				}
				setRobotTrajectoryAppearance.run();
				lineSearches.setDirty();
				trajectoryCanvas.scheduleRepaint();
			}
		};
		ActionListener modelChangeUpdateGraphs = new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				setGraphAppearance.run();
			}
		};
		model.addGraphQueryChangeListener(modelChangeUpdateGraphs).addTimeStepChangeListener(modelChangeUpdateGraphs);


		// make log overview table
		TableModel logTableModel = new AbstractTableModel() {
			private static final long serialVersionUID = 1L;
			@Override
			public Object getValueAt(int rowIndex, int columnIndex) {
				LinkedHashMap<String, Object> entry = log.getLogEntry(rowIndex);
				switch(columnIndex){
				case 0: return entry.get("entryType");
				case 1: {
					int idx = Arrays.binarySearch(log.graphQueryIndices, rowIndex);
					return idx < 0 ? "-" : ""+idx;
				}
				default:return "";
				}
			}
			@Override
			public int getRowCount() {
				return log.numLogEntries;
			}
			@Override
			public int getColumnCount() {
				return 2;
			}

			public String getColumnName(int column) {
				switch (column) {
				case 0: return "Log Entries";
				case 1: return "Step";
				default:return "";
				}
			};
		};
		JTable logTable = new JTable(logTableModel);
		logTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer(){
			private static final long serialVersionUID = 1L;
			Color lineSearchColor = new Color(0xffbbff);
			public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
				Component comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
				comp.setFont(comp.getFont().deriveFont(Font.PLAIN));
				if(value.equals(KomoLog.graphQuery)){
					comp.setForeground(table.getForeground());
					if(!isSelected){
						comp.setBackground(table.getBackground());
					}
				} else {
					if(value.equals(KomoLog.optConstraint)){
						comp.setFont(comp.getFont().deriveFont(Font.BOLD));
					}
					comp.setForeground(Color.gray);
					// find last graph query
					int insertionPoint = Arrays.binarySearch(log.graphQueryIndices, row);
					int idx = -(insertionPoint) - 2;
					if(idx >= 0 && log.graphQueryStepContainsNewton(idx)){
						comp.setBackground(table.getBackground());
					} else {
						comp.setBackground(lineSearchColor);
					}
				}
				return comp;
			};
		});
		logTable.getColumnModel().getColumn(1).setPreferredWidth(25);
		JScrollPane logTableScroll = new JScrollPane(logTable);
		logTable.setPreferredScrollableViewportSize(new Dimension(150, 400));
		logTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		{
			boolean[] ignore = {false};
			model.addGraphQueryChangeListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					ignore[0]=true;
					SortedSet<Integer> graphQuery = model.getSelectedGraphQuery();
					ListSelectionModel selectionModel = logTable.getSelectionModel();
					selectionModel.setValueIsAdjusting(true);
					selectionModel.clearSelection();
					for(Integer i : graphQuery){
						int idx = log.graphQueryIndices[i];
						selectionModel.addSelectionInterval(idx, idx);
					}
					selectionModel.setValueIsAdjusting(false);
					ignore[0]=false;
				}
			});

			// add selection listsner on log entry table
			logTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
				@Override
				public void valueChanged(ListSelectionEvent e) {
					if(e.getValueIsAdjusting() || ignore[0]){
						return;
					}
					if(logTable.getSelectedRowCount() == 0){
						model.setSelectedGraphQuery();
					} else {
						int select = logTable.getSelectedRow();
						int idx = Arrays.binarySearch(log.graphQueryIndices, select);
						if(idx < 0){
							return;
						}
						model.setSelectedGraphQuery(idx);
					}
					scrollToVisible(logTable, logTable.getSelectedRow(), 0);
				}
			});
		}


		// make optimization step selector slider
		JLabel optimizationStepLabel = new JLabel("all");
		optimizationStepLabel.setPreferredSize(new Dimension(30, optimizationStepLabel.getPreferredSize().height));
		JSlider optimizationStepSlider = new JSlider(-1, log.numGraphQueries-1);
		optimizationStepSlider.setValue(-1);
		{
			boolean[] ignore = {false};
			optimizationStepSlider.addChangeListener(e->{
				if(ignore[0])
					return;
				int step = optimizationStepSlider.getValue();
				if(step < 0){
					model.setSelectedGraphQuery();
				} else {
					model.setSelectedGraphQuery(step);
				}
			});

			model.addGraphQueryChangeListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					ignore[0]=true;
					SortedSet<Integer> graphQuery = model.getSelectedGraphQuery();
					if(graphQuery.isEmpty()){
						optimizationStepSlider.setValue(-1);
						optimizationStepLabel.setText("all");
					} else {
						optimizationStepSlider.setValue(graphQuery.first());
						optimizationStepLabel.setText(""+graphQuery.first());
					}
					ignore[0]=false;
				}
			});
		}


		// add selection listener to table that will highlight the selected graphs
		configTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {				
			@Override
			public void valueChanged(ListSelectionEvent e) {
				if(e.getValueIsAdjusting()){
					return;
				}
				if(configTable.getSelectedRowCount()==1){
					scrollToVisible(configTable, configTable.getSelectedRow(), 0);
				}
				int[] selectedRows = configTable.getSelectedRows();
				model.setSelectedTimeStep(Arrays.stream(selectedRows).boxed().toArray(Integer[]::new));
			}
		});

		// single selection
		trajectoryCanvas.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if(SwingUtilities.isLeftMouseButton(e)){
					int pixel1 = trajectoryCanvas.getPixel(e.getX(), e.getY(), true, 1);
					int pixel5 = trajectoryCanvas.getPixel(e.getX(), e.getY(), true, 5);
					int pixel = pixel1 != 0 ? pixel1:pixel5;
					if(pixel==0){
						model.setSelectedTimeStep();
					} else {
						int d = Pixel.b(pixel)-1;
						int t = Pixel.r(pixel);
						model.setSelectedTimeStep(log.getStartTimestepForDimensionality(d)+t);
					}
				}
			};
		});
		// brush&link
		new CoordSysViewSelector(trajectoryCanvas,trajectoryCoordsys) {
			{
				this.extModifierMask = InputEvent.ALT_GRAPH_DOWN_MASK;
			}

			public void areaSelectedOnGoing(double minX, double minY, double maxX, double maxY) {
				areaSelected(minX, minY, maxX, maxY);
			};

			@Override
			public void areaSelected(double minX, double minY, double maxX, double maxY) {
				Rectangle2D rect = new Rectangle2D.Double(minX, minY, maxX-minX, maxY-minY);
				LinkedList<Integer> selectedGraphs = new LinkedList<>();
				for(int i=0; i < log.numTimesteps; i++){
					Lines lines = optimTrajGraphs.get(i);
					if(lines.intersects(rect)){
						selectedGraphs.add(i);
					}
				}
				model.setSelectedTimeStep(selectedGraphs);
			}
		}.register();

		// Dimensionality Reductions
		JComboBox<?>[] comboboxReference = {null};
		Vector<TrajectoryDisplayMode> displayModes = new Vector<>();

		// PCA
		PCA[] pca = new PCA[log.numDimensionalities];
		displayModes.add(new TrajectoryDisplayMode("PCA"){
			public void updatePoints() {
				for(int d=0; d<log.numDimensionalities; d++){
					if(pca[d] == null){
						pca[d] = new PCA(data[d]);
					}
					double[][] projection = pca[d].project(data[d]);
					for(int i = 0; i<points[d].length; i++){
						points[d][i].setLocation(projection[i][0], projection[i][1]);
					}
				}
				
				points2unitRange.run();
				setAllDirty.run();
				trajectoryCanvas.scheduleRepaint();
			};
		});
		// optimization steps on x, pca on y
		displayModes.add(new TrajectoryDisplayMode("OptimSteps & PCA"){
			public void updatePoints() {
				for(int d=0; d<log.numDimensionalities; d++){
					if(pca[d] == null){
						pca[d] = new PCA(data[d]);
					}
					double[][] projection = pca[d].project(data[d]);
					int numConfigs = points[d].length/log.numGraphQueries;
					for(int i = 0; i<points[d].length; i++){
						int step = i / numConfigs;
						points[d][i].setLocation(step, projection[i][0]);
					}
				}
				points2unitRange.run();
				setAllDirty.run();
				trajectoryCanvas.scheduleRepaint();
			};
		});
		// configurations on x, pca on y
		displayModes.add(new TrajectoryDisplayMode("Configs & PCA"){
			public void updatePoints() {
				for(int d=0; d<log.numDimensionalities; d++){
					if(pca[d] == null){
						pca[d] = new PCA(data[d]);
					}
					double[][] projection = pca[d].project(data[d]);
					int numConfigs = points[d].length/log.numGraphQueries;
					for(int i = 0; i<points[d].length; i++){
						int cfg = i % numConfigs;
						points[d][i].setLocation(cfg, projection[i][0]);
					}
				}
				points2unitRange.run();
				setAllDirty.run();
				trajectoryCanvas.scheduleRepaint();
			};
		});
		// optimization steps on x, configs on y
		displayModes.add(new TrajectoryDisplayMode("OptimSteps & Configs"){
			public void updatePoints() {
				for(int d=0; d<log.numDimensionalities; d++){
					int numConfigs = points[d].length/log.numGraphQueries;
					for(int i = 0; i<points[d].length; i++){
						int step = i / numConfigs;
						int cfg = i % numConfigs;
						points[d][i].setLocation(cfg, -step);
					}
				}
				points2unitRange.run();
				setAllDirty.run();
				trajectoryCanvas.scheduleRepaint();
			};
		});


		// pairwise actual dimensions
		TrajectoryDisplayMode mode01 = null;
		for(int i=0; i < maxDim; i++){
			for(int j = i+1; j<maxDim; j++){
				int i_ = i;
				int j_ = j;
				TrajectoryDisplayMode mode = new TrajectoryDisplayMode("Dims " + i + " & " + j){
					public void updatePoints() {
						for(int d=0; d<log.numDimensionalities; d++)
							for(int k = 0; k<points[d].length; k++){
								double[] vec = data[d][k];
								double x = (vec.length > i_) ? vec[i_] : 0;
								double y = (vec.length > j_) ? vec[j_] : 0;
								points[d][k].setLocation(x, y);
							}
						points2unitRange.run();
						setAllDirty.run();
						trajectoryCanvas.scheduleRepaint();
					};
				};
				displayModes.add(mode);
				if(i==0&&j==1){
					mode01 = mode;
				}
			}
		}
		JComboBox<TrajectoryDisplayMode> displayMode = new JComboBox<>(displayModes);
		displayMode.setPreferredSize(new Dimension(250, displayMode.getPreferredSize().height));
		displayMode.setMaximumSize(displayMode.getPreferredSize());
		comboboxReference[0] = displayMode;
		displayMode.setSelectedItem(mode01);
		displayMode.addActionListener(e->{
			TrajectoryDisplayMode mode = (TrajectoryDisplayMode) displayMode.getSelectedItem();
			mode.updatePoints();
			System.out.println("switched to " + mode.name);
		});
		
		HSlider speedWWSlider = new HSlider( 1, log.numGraphQueries/3, speedView.getWindowWidth());
		speedWWSlider.setTitle("smoothing: ");
		speedWWSlider.slider.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				int value = speedWWSlider.slider.getValue();
				speedView.setWindowWidth(value);
			}
		});


		optimizationStepSlider.setValue(optimizationStepSlider.getMaximum());

		// legends
		BlankCanvas topLegendCanvas = new BlankCanvas(trajectoryCanvas);
		BlankCanvas botLegendCanvas = new BlankCanvas(trajectoryCanvas);
		topLegendCanvas.setPreferredSize(new Dimension(400, 20));
		botLegendCanvas.setPreferredSize(new Dimension(400, 20));
		topLegendCanvas.setBackground(Color.WHITE);
		botLegendCanvas.setBackground(Color.WHITE);
		Legend toplegend = new Legend();
		Legend botlegend = new Legend();
		topLegendCanvas.setRenderer(toplegend);
		botLegendCanvas.setRenderer(botlegend);

		toplegend.addGlyphLabel(DefaultGlyph.CIRCLE_F, infeasibilityCmap.interpolate(0), "few violations");
		toplegend.addGlyphLabel(DefaultGlyph.CIRCLE_F, infeasibilityCmap.interpolate(1), "many violations");
		toplegend.addLineLabel(2, 0xff000000, 0xf0f0, "current robot path", 0);
		toplegend.addLineLabel(1, 0xff555555, "optimization trajectory");

		botlegend.addLineLabel(2, 0xff000000, 0xff0f, "constraint group aggregate", 0);
		botlegend.addLineLabel(1, 0xff000000, "single constraint");

		
		// collecting canvases for cleanup later
		LinkedList<FBOCanvas> canvases = new LinkedList<>();
		canvases.add(ineqFeatView.canvas);
		canvases.add(eqFeatView.canvas);
		canvases.add(speedView.canvas);
		canvases.add(topLegendCanvas);
		canvases.add(botLegendCanvas);
		canvases.add(trajectoryCanvas);
		
		
		// UI LAYOUT
		JFrame frame = new JFrame("KOMO Log Viz 08");
		
		Container speedviewContainer = new Container();
		speedviewContainer.setLayout(new BorderLayout());
		speedviewContainer.add(speedView.canvas, BorderLayout.CENTER);
		speedviewContainer.add(speedWWSlider, BorderLayout.SOUTH);
//		speedView.canvas.setPreferredSize(new Dimension(100, 100));
		
		JSplitPane hsplitpane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, ineqFeatView.canvas, eqFeatView.canvas);
		hsplitpane.setDividerSize(6);
		hsplitpane.setResizeWeight(0.5);
		hsplitpane.setBorder(BorderFactory.createEmptyBorder());
		hsplitpane.setContinuousLayout(true);
		
		Container featureViews = new Container();
		featureViews.setLayout(new BorderLayout());
		featureViews.add(hsplitpane);

		Container graphContainer = new Container();
		Container header = new Container();
		Container footer = new Container();
		graphContainer.setLayout(new BorderLayout());
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		footer.setLayout(new BoxLayout(footer, BoxLayout.X_AXIS));

		featureViews.setMinimumSize(new Dimension());
		trajectoryCanvas.setMinimumSize(new Dimension());
		ineqFeatView.canvas.setMinimumSize(new Dimension());
		eqFeatView.canvas.setMinimumSize(new Dimension());
		speedView.canvas.setMinimumSize(new Dimension());
		
		JSplitPane vsplitpane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, trajectoryCanvas, featureViews);
		vsplitpane.setDividerSize(6);
		vsplitpane.setResizeWeight(0.5);
		vsplitpane.setBorder(BorderFactory.createEmptyBorder());
		vsplitpane.setContinuousLayout(true);
		
		graphContainer.add(vsplitpane, BorderLayout.CENTER);
		graphContainer.add(topLegendCanvas, BorderLayout.NORTH);
		graphContainer.add(botLegendCanvas, BorderLayout.SOUTH);

		Container configTableContainer = new Container();
		configTableContainer.setLayout(new BorderLayout());
		configTableContainer.add(tableScroll);
		configTableContainer.add(cbxSelectFeatures, BorderLayout.SOUTH);
		
		Container treeAndSpeed = new Container();
		treeAndSpeed.setLayout(new BorderLayout());
		treeAndSpeed.add(treeScroll, BorderLayout.CENTER);
		treeAndSpeed.add(speedviewContainer, BorderLayout.SOUTH);

		Container tableContainer = new Container();
		tableContainer.setLayout(new BoxLayout(tableContainer, BoxLayout.X_AXIS));
		tableContainer.add(treeAndSpeed);
		tableContainer.add(configTableContainer);
		tableContainer.add(logTableScroll);

		header.add(cbExpandTrajectory);
		header.add(cbOptimizationTrajectory);
		header.add(cbRobotTrajectory);
		header.add(cbViolations);
		header.add(cbLineSearches);
		header.add(new JSeparator(JSeparator.VERTICAL));
		header.add(new JLabel("Optimization Step:"));
		header.add(optimizationStepSlider);
		header.add(optimizationStepLabel);
		header.add(new JSeparator(JSeparator.VERTICAL));
		header.add(Box.createHorizontalGlue());
		header.add(Box.createHorizontalGlue());
		header.add(new JLabel("Traj. Mode:"));
		header.add(displayMode);
//		header.add(new JButton(new AbstractAction("SVG export") {
//			@Override
//			public void actionPerformed(ActionEvent e) {
//				System.out.println("building SVG");
//				Document doc = SVGUtils.containerToSVG(frame.getContentPane());
//				System.out.println("saving SVG");
//				SVGUtils.documentToXMLFile(doc, new File("komologviz_"+Instant.now()+".svg"));
//				System.out.println("exported SVG");
//			}
//		}));

		frame.getContentPane().setLayout(new BorderLayout());
		frame.getContentPane().add(graphContainer, BorderLayout.CENTER);
		frame.getContentPane().add(header, BorderLayout.NORTH);
		frame.getContentPane().add(footer, BorderLayout.SOUTH);
		frame.getContentPane().add(tableContainer, BorderLayout.EAST);
		/////////////


		cbViolations.setSelected(false);


		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		SwingUtilities.invokeLater(()->{
			frame.pack();
			frame.setVisible(true);
		});
		frame.addWindowListener(new WindowAdapter() {
			public void windowClosing(java.awt.event.WindowEvent e) {
				canvases.forEach(canvas->{
					try {
					canvas.runInContext(()->canvas.close());
					} catch(Throwable ex) {
						ex.printStackTrace();
					}
				});
			};
		});

		for(FBOCanvas canv : Arrays.asList(trajectoryCanvas,eqFeatView.canvas, ineqFeatView.canvas)){
			canv.enableInputMethods(true);
			canv.addKeyListener(new KeyAdapter() {
				public void keyTyped(java.awt.event.KeyEvent e) {
					if(e.getKeyChar() == 's'){
						Document svg = canv.paintSVG();
						SVGUtils.documentToXMLFile(svg, new File("logviz08_"+Instant.now()+"_"+canv.getWidth()+"x"+canv.getHeight()+".svg"));
						System.out.println("exported svg");
					}
					if(e.getKeyChar() == 'i'){
						Container c = frame.getContentPane();
						Img img = new Img(c.getSize());
						img.paint(g->c.paintAll(g));
						ImageSaver.saveImage(img.getRemoteBufferedImage(), new File("logviz08_"+Instant.now()+"_"+c.getWidth()+"x"+c.getHeight()+".png"));
						System.out.println("exported png");
					}
					if(e.getKeyChar() == 'a'){
						Container c = frame.getContentPane();
						Document svg = SVGUtils.containerToSVG(c);
						SVGUtils.documentToXMLFile(svg, new File("logviz08_"+Instant.now()+"_"+c.getWidth()+"x"+c.getHeight()+".svg"));
						System.out.println("exported svg");
					}
				}
			});
		}
	}

	static int findDimensionalityIdx(int config, KomoLog log){
		int d = 0;
		while(d+1 < log.numDimensionalities && log.dimensionalityStarts[d+1] <= config){
			d++;
		}
		return d;
	}


	public static void scrollToVisible(JTable table, int rowIndex, int vColIndex) {
		if (!(table.getParent() instanceof JViewport)) {
			return;
		}
		JViewport viewport = (JViewport)table.getParent();

		// This rectangle is relative to the table where the
		// northwest corner of cell (0,0) is always (0,0).
		Rectangle rect = table.getCellRect(rowIndex, vColIndex, true);

		// The location of the viewport relative to the table
		Point pt = viewport.getViewPosition();

		// Translate the cell location so that it is relative
		// to the view, assuming the northwest corner of the
		// view is (0,0)
		rect.setLocation(rect.x-pt.x, rect.y-pt.y);

		//        table.scrollRectToVisible(rect);

		// Scroll the area into view
		viewport.scrollRectToVisible(rect);
	}

}

