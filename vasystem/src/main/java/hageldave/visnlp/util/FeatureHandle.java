package hageldave.visnlp.util;

import java.util.ArrayList;
import java.util.Objects;

import hageldave.visnlp.data.KomoLog;

public class FeatureHandle implements Comparable<FeatureHandle> {

	public final int index;
	public final KomoLog log;
	
	public FeatureHandle(int i, KomoLog log) {
		index = i;
		this.log = log;
	}
	
	public int getIndex() {
		return index;
	}
	
	public String getName(){
		return log.featureNames.get(index);
	}
	
	public int getType(){
		return log.featureTypes[index];
	}
	
	public ArrayList<Number> getVars(){
		return log.featureVariables.get(index);
	}
	
	@Override
	public boolean equals(Object obj) {
		if(obj != null && obj instanceof FeatureHandle){
			FeatureHandle other = (FeatureHandle)obj;
			return other.log == this.log && other.index == this.index;
		}
		return false;
	}
	
	@Override
	public int hashCode() {
		return Objects.hash(log,index);
	}
	
	@Override
	public int compareTo(FeatureHandle other) {
		int comp1 = Integer.compare(this.log.hashCode(), other.log.hashCode());
		if(comp1 != 0)
			return comp1;
		return Integer.compare(this.index, other.index);
	}
	
}
