package hageldave.visnlp.app;

public class Application {

	public static void main(String[] args) throws Exception {
		args=new String[] {"problem1"};
		if(args==null || args.length < 1) {
			Offline.main(args);
		} else {
			Online.main(args);
		}
	}
	
}
