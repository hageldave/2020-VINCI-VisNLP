package hageldave.visnlp.util;

import java.util.Objects;

import org.lwjgl.opengl.GL20;

import hageldave.jplotter.gl.Shader;
import hageldave.jplotter.renderables.Renderable;
import hageldave.jplotter.renderers.TrianglesRenderer;
import hageldave.jplotter.util.Annotations.GLContextRequired;

public class CheckerBoardRenderer extends TrianglesRenderer {

	protected static final char NL = '\n';
	protected static final String fragmentShaderSrc = ""
			+ "" + "#version 330"
			+ NL + "layout(location = 0) out vec4 frag_color;"
			+ NL + "layout(location = 1) out vec4 pick_color;"
			+ NL + "in vec4 vColor;"
			+ NL + "in vec4 vPickColor;"
			+ NL + "uniform float alphaMultiplier;"
			+ NL + "uniform float checkerSize;"
			
			+ NL + "vec2 truncateVec(vec2 v) {"
			+ NL + "   return vec2(int(v.x), int(v.y));"
			+ NL + "}"
			
			+ NL + "void main() {"
			+ NL + "   vec3 color = vColor.rgb;"
			+ NL + "   vec2 checker = truncateVec((1.0/checkerSize) * gl_FragCoord.xy);"
			+ NL + "   if(int(checker.x + checker.y) % 2 == 0) {"
			+ NL + "      color = 1-color;"
			+ NL + "   }"
			+ NL + "   frag_color = vec4(color, vColor.a*alphaMultiplier);"
			+ NL + "   pick_color = vPickColor;"
			+ NL + "}"
			+ NL
			;
	
	protected String svgTriangleStrategy=null;
	protected int checkerSize;

	/**
	 * Creates the shader if not already created and 
	 * calls {@link Renderable#initGL()} for all items 
	 * already contained in this renderer.
	 * Items that are added later on will be initialized during rendering.
	 */
	@Override
	@GLContextRequired
	public void glInit() {
		if(Objects.isNull(shader)){
			shader = new Shader(vertexShaderSrc, fragmentShaderSrc);
			itemsToRender.forEach(Renderable::initGL);
		}
	}
	
	@Override
	protected void renderStart(int w, int h) {
		super.renderStart(w, h);
		int loc = GL20.glGetUniformLocation(shader.getShaderProgID(), "checkerSize");
		GL20.glUniform1f(loc, (float)this.checkerSize);
	}
	
	public CheckerBoardRenderer setCheckerSize(int checkerSize) {
		this.checkerSize = checkerSize;
		return this;
	}
	
	public int getCheckerSize() {
		return checkerSize;
	}
	
}
