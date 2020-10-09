package hageldave.visnlp.util;

import java.util.Objects;

public class Triple<T1,T2,T3> {

		public final T1 e1;
		
		public final T2 e2;
		
		public final T3 e3;
		
		public Triple(T1 e1, T2 e2, T3 e3) {
			this.e1=e1;
			this.e2=e2;
			this.e3=e3;
		}
		
		public static <T1,T2,T3> Triple<T1, T2, T3> of(T1 e1, T2 e2, T3 e3){
			return new Triple<T1, T2, T3>(e1, e2, e3);
		}
		
		
		@Override
		public boolean equals(Object obj) {
			if(obj != null && obj instanceof Triple){
				Triple<?,?,?> other = (Triple<?,?,?>)obj;
				return Objects.equals(e1, other.e1) && Objects.equals(e2, other.e2) && Objects.equals(e3, other.e3);
			}
			return false;
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(e1,e2,e3);
		}
		
		@Override
		public String toString() {
			return String.format("{%s, %s, %s}", e1,e2,e3);
		}
	
}
