package hageldave.visnlp.data;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.ejml.simple.SimpleMatrix;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import hageldave.visnlp.util.MatUtil;

public class KomoLog {

	public static final String graphQuery = "graphQuery";
	public static final String newton = "newton";
	public static final String optConstraint = "optConstraint";
	public static final String lagrangianQuery = "lagrangianQuery";
	public static final String lineSearch = "lineSearch";
	public static final String[] entryTypes = {graphQuery,newton,optConstraint,lagrangianQuery,lineSearch};

	public static final int directCostFeatureType = 1;
	public static final int costFeatureType = 2;
	public static final int inequalityFeatureType = 3;
	public static final int equalityFeatureType = 4;



	public final LinkedHashMap<String, Object> header;
	public final int numLogEntries;
	public final int numGraphQueries;
	public final int numTimesteps;
	public final int numFeatures;
	public final int numDimensionalities;

	public final ArrayList<String> timestepNames;
	public final ArrayList<String> featureNames;
	public final ArrayList<ArrayList<Number>> featureVariables;
	public final int[] featureTypes;
	public final int[] varDims;
	public final int[] dimensionalityStarts;
	public final int[] varStarts;

	public final String[] logEntryTypes;
	public final ArrayList<LinkedHashMap<String, Object>> logEntries;
	public final int[] graphQueryIndices;

	public final int[] equalityFeatureIndices;
	public final int[] inequalityFeatureIndices;
	public final int[] costFeatureIndices;
	public final int[] directCostFeatureIndices;

	public final int numDirectCostFeatures;
	public final int numCostFeatures;
	public final int numInequalityFeatures;
	public final int numEqualityFeatures;

	@SuppressWarnings("unchecked")
	public KomoLog(ArrayList<LinkedHashMap<String, Object>> log) {
		header = log.get(0);
		numTimesteps = (int)header.get("numVariables");
		// dimensionality things
		{
			varDims = ((ArrayList<Integer>) header.get("variableDimensions")).stream().mapToInt(Number::intValue).toArray();
			int numDimensionalities = 1;
			int dim = varDims[0];
			for(int i=1; i<varDims.length; i++){
				if(varDims[i] != dim){
					numDimensionalities ++;
					dim = varDims[i];
				}
			}
			this.numDimensionalities = numDimensionalities;
			dimensionalityStarts = new int[numDimensionalities+1];
			dimensionalityStarts[0] = 0;
			dimensionalityStarts[numDimensionalities] = varDims.length;
			int j = 1;
			dim = varDims[0];
			for(int i=0; i<varDims.length; i++){
				if(varDims[i] != dim){
					dimensionalityStarts[j++] = i;
					dim = varDims[i];
				}
			}

		}

		timestepNames = (ArrayList<String>) header.get("variableNames");
		featureNames = (ArrayList<String>) header.get("featureNames");
		numFeatures = (int)header.get("numFeatures");
		featureVariables = (ArrayList<ArrayList<Number>>) header.get("featureVariables");
		featureTypes = ((List<Number>) header.get("featureTypes")).stream().mapToInt(Number::intValue).toArray();
		// extract indices for different types of features
		{
			int eq=0;
			int in=0;
			int co=0;
			int dc=0;
			// counting
			for(int i=0; i<numFeatures; i++){
				switch (featureTypes[i]) {
				case directCostFeatureType:
					dc++;
					break;
				case costFeatureType:
					co++;
					break;
				case inequalityFeatureType:
					in++;
					break;
				case equalityFeatureType:
					eq++;
					break;
				default:
					break;
				}
			}
			equalityFeatureIndices = new int[numEqualityFeatures=eq];
			inequalityFeatureIndices = new int[numInequalityFeatures=in];
			costFeatureIndices = new int[numCostFeatures=co];
			directCostFeatureIndices = new int[numDirectCostFeatures=dc];
			// filling
			eq = in = co = dc = 0;
			for(int i=0; i<numFeatures; i++){
				switch (featureTypes[i]) {
				case directCostFeatureType:
					directCostFeatureIndices[dc++]=i;
					featureNames.set(i, "[DC] "+featureNames.get(i));
					break;
				case costFeatureType:
					costFeatureIndices[co++]=i;
					featureNames.set(i, "[CO] "+featureNames.get(i));
					break;
				case inequalityFeatureType:
					inequalityFeatureIndices[in++]=i;
					featureNames.set(i, "[IQ] "+featureNames.get(i));
					break;
				case equalityFeatureType:
					equalityFeatureIndices[eq++]=i;
					featureNames.set(i, "[EQ] "+featureNames.get(i));
					break;
				default:
					break;
				}
			}
		}

		// calculate vector blocks for robot timesteps
		varStarts = new int[numTimesteps];
		{
			int j=0;
			for(int i=0; i<numTimesteps; i++) {
				varStarts[i] = j;
				j += varDims[i];
			}
		}

		// remove header from rest of log
		log.remove(header);
		log.trimToSize();
		logEntries = log;
		numLogEntries = log.size();
		// add type labels to log entries
		logEntryTypes = new String[numLogEntries];
		int i=0;
		int numGraphQueries = 0;
		for(LinkedHashMap<String, Object> entry: log){
			entry.put("entryType", "");
			for(String type:entryTypes){
				if(entry.containsKey(type)){
					entry.put("entryType", type);
					logEntryTypes[i] = type;
					if(type.equals(graphQuery)){
						numGraphQueries++;
						ArrayList<Number> x = (ArrayList<Number>)entry.get("x");
						// replace list by vectors
						SimpleMatrix[] vectors = new SimpleMatrix[numTimesteps];
						int j = 0;
						for(int var = 0; var < numTimesteps; var++){
							int varDim = varDims[var];
							double[] vec = x.subList(j, j+varDim).stream().mapToDouble(Number::doubleValue).toArray();
							vectors[var] = MatUtil.vector(varDim, vec);
							j += varDim;
						}
						entry.replace("x", vectors);
						// replace list by array
						double[] phi = ((ArrayList<Number>)entry.get("phi")).stream().mapToDouble(Number::doubleValue).toArray();
						entry.replace("phi", phi);
					}
					if(type.equals(optConstraint)) {
						ArrayList<Number> lam = (ArrayList<Number>) entry.get("lambda");
						double[] lambda = lam.stream().mapToDouble(Number::doubleValue).toArray();
						entry.replace("lambda", lambda);
					}
					break;
				}
			}
			i++;
		}
		this.numGraphQueries = numGraphQueries;
		// retrieve indices of graph queries in log
		graphQueryIndices = new int[numGraphQueries];
		int j=0;
		for(i=0; i<numLogEntries; i++){
			if(logEntryTypes[i].equals(graphQuery)){
				graphQueryIndices[j++] = i;
			}
		}
	}

	public LinkedHashMap<String, Object> getLogEntry(int i){
		return logEntries.get(i);
	}

	public LinkedHashMap<String, Object> getGraphQuery(int i){
		return getLogEntry(graphQueryIndices[i]);
	}

	public SimpleMatrix[] getGraphQueryX(int i){
		return (SimpleMatrix[])getGraphQuery(i).get("x");
	}

	public double[] getGraphQueryPhi(int i){
		return (double[])getGraphQuery(i).get("phi");
	}

	public Stream<LinkedHashMap<String, Object>> streamEntries(){
		return logEntries.stream();
	}

	public Stream<LinkedHashMap<String, Object>> streamGraphQueries(){
		return IntStream.range(0, numGraphQueries).mapToObj(this::getGraphQuery);
	}

	public Stream<SimpleMatrix[]> streamGraphQueriesX(){
		return IntStream.range(0, numGraphQueries).mapToObj(this::getGraphQueryX);
	}

	public Stream<double[]> streamGraphQueriesPhi(){
		return IntStream.range(0, numGraphQueries).mapToObj(this::getGraphQueryPhi);
	}

	public int getStartTimestepForDimensionality(int dimensionalityIdx){
		return dimensionalityStarts[dimensionalityIdx];
	}

	public int getNumTimestepsForDimensionality(int dimensionalityIdx){
		return getStartTimestepForDimensionality(dimensionalityIdx+1)-getStartTimestepForDimensionality(dimensionalityIdx);
	}



	public boolean graphQueryStepContainsNewton(int query){
		int intervalStart = graphQueryIndices[query]+1;
		int intervalEnd = query+1 < numGraphQueries-1 ? graphQueryIndices[query+1] : numLogEntries;

		for(int i = intervalStart; i < intervalEnd; i++){
			if(logEntryTypes[i].equals(newton))
				return true;
		}
		return false;
	}

	public LinkedHashMap<String, Object> getOptConstraintEntry(int idx){
		LinkedHashMap<String, Object> entry = null;
		for(int i=idx; i>=0; i--) {
			if(logEntryTypes[i].equals(optConstraint)) {
				entry = logEntries.get(i);
				break;
			}
		}
		return entry;
	}


	public static KomoLog loadLog(String resource) throws JsonParseException, IOException {
		return loadLog(()->KomoLog.class.getResourceAsStream(resource));
	}

	public static KomoLog loadLog(File f) throws JsonParseException, IOException {
		return loadLog(()->{
			try {
				return new BufferedInputStream(new FileInputStream(f));
			} catch (FileNotFoundException e) {
				throw new RuntimeException(e);
			}
		});
	}


	public static KomoLog loadLog(Supplier<InputStream> resource) throws JsonParseException, IOException{
		ObjectMapper om = new ObjectMapper(new YAMLFactory());
		try {
			@SuppressWarnings("unchecked")
			ArrayList<LinkedHashMap<String, Object>> log = om.readValue(resource.get(), ArrayList.class);
			return new KomoLog(log);
		} catch(MismatchedInputException e) {
			e.printStackTrace();
			return loadLog_withLabel(resource);
		}
	}

	public static KomoLog loadLog_withLabel(Supplier<InputStream> resource) throws JsonParseException, JsonMappingException, IOException{
		ObjectMapper om = new ObjectMapper(new YAMLFactory());
		@SuppressWarnings("unchecked")
		LinkedHashMap<String, ArrayList<LinkedHashMap<String, Object>>> log = om.readValue(resource.get(), LinkedHashMap.class);
		return new KomoLog(log.get(log.keySet().iterator().next()));
	}



}