package hageldave.visnlp.util;

import java.util.Objects;

public class Quadrupel<T1,T2,T3,T4> {

		public final T1 e1;
		
		public final T2 e2;
		
		public final T3 e3;
		
		public final T4 e4;
		
		public Quadrupel(T1 e1, T2 e2, T3 e3, T4 e4) {
			this.e1=e1;
			this.e2=e2;
			this.e3=e3;
			this.e4=e4;
		}
		
		public static <T1,T2,T3,T4> Quadrupel<T1, T2, T3, T4> of(T1 e1, T2 e2, T3 e3, T4 e4){
			return new Quadrupel<T1, T2, T3, T4>(e1, e2, e3, e4);
		}
		
		
		@Override
		public boolean equals(Object obj) {
			if(obj != null && obj instanceof Quadrupel){
				Quadrupel<?,?,?,?> other = (Quadrupel<?,?,?,?>)obj;
				return  Objects.equals(e1, other.e1) && 
						Objects.equals(e2, other.e2) && 
						Objects.equals(e3, other.e3) &&
						Objects.equals(e4, other.e4);
			}
			return false;
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(e1,e2,e3,e4);
		}
		
		@Override
		public String toString() {
			return String.format("{%s, %s, %s, %s}", e1,e2,e3,e4);
		}
	
}
