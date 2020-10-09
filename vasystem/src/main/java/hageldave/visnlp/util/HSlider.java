package hageldave.visnlp.util;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;

import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.event.ChangeListener;

public class HSlider extends Container {
	private static final long serialVersionUID = 1L;
	
	public final JSlider slider;
	public JLabel title; 
	public JLabel value;
	public ChangeListener updateValueListener;
	
	public HSlider(int min, int max, int init) {
		slider = new JSlider(min, max, init);
		title = new JLabel();
		value = new JLabel();
		value.setPreferredSize(new Dimension((""+max).length()*9, value.getPreferredSize().height));
		this.setLayout(new BorderLayout());
		this.add(title, BorderLayout.WEST);
		this.add(slider, BorderLayout.CENTER);
		this.add(value, BorderLayout.EAST);
		
		this.setBackground(Color.white);
		slider.setOpaque(true);
		title.setOpaque(true);
		value.setOpaque(true);
		
		slider.setBackground(Color.white);
		title.setBackground(Color.white);
		value.setBackground(Color.white);
		
		updateValueListener = e->value.setText(""+slider.getValue());
		slider.addChangeListener(updateValueListener);
		value.setText(""+slider.getValue());
	}
	
	public void setTitle(String txt) {
		this.title.setText(txt);
	}
	
	public int getValue() {
		return slider.getValue();
	}
	
	public double getNormalizedValue() {
		return (getValue()-slider.getMinimum())*1.0/(slider.getMaximum()-slider.getMinimum());
	}
	
}
