package hageldave.visnlp.util;

import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

public class LookNFeel {

	public static void setSystemLAF() {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
				| UnsupportedLookAndFeelException e) {
			e.printStackTrace();
		}
	}
	
}
