package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * <p>Identidad visual compartida por las pantallas "planas" del mod (paneles de lista, formularios
 * cortos, diálogos): un tomo encuadernado en cuero con cantoneras de latón, dibujado con el biselado
 * que usa la GUI de Minecraft. Es el único sitio donde vive ese aspecto — cambiarlo aquí lo cambia en
 * las más de cuarenta pantallas que cuelgan de {@code ListPickerScreen}, {@code SmallFormScreen} y
 * {@code ModalDialogScreen}.</p>
 *
 * <p><b>Por qué biselado y no un borde plano.</b> Minecraft dibuja TODA su interfaz con dos líneas de
 * bisel — clara arriba e izquierda, oscura abajo y derecha— sobre un relleno liso. Es lo que hace que un
 * panel "pertenezca" al juego en vez de parecer una ventana pegada encima. El tema de D&amp;D entra por
 * el color (cuero oscuro y latón envejecido) y por las cantoneras, no por romper esa gramática.</p>
 *
 * <p>El relleno es casi opaco a propósito: el fondo del mundo sigue viéndose desenfocado detrás, pero
 * sin competir con el texto. Un panel translúcido sobre un bioma nevado deja el texto blanco ilegible.</p>
 */
public final class GuiStyle {

	//--- Colores de texto ---
	/** Pergamino, no blanco puro: el blanco absoluto sobre cuero oscuro vibra y cansa la vista.
	 *  Público: lo usa también el HUD de turnos (client.TurnHudOverlay), fuera de este paquete. */
	public static final int TITLE_COLOR = 0xFFE9D8B4;
	public static final int SUBTITLE_COLOR = 0xFFB9A88C;
	/** Público: es el único color de GuiStyle que consume el addon del toolkit de mazmorras (módulo aparte). */
	public static final int MUTED_COLOR = 0xFF8C8071;
	/** Latón envejecido, para lo que debe destacar sin gritar (marcas, valores activos). Público, ver arriba. */
	public static final int ACCENT_COLOR = 0xFFC9A227;

	//--- Colores del panel ---
	//El relleno es una textura de cuero tileable (generada por tools/make_panel_texture.py, ruido
	//rosa por FFT: sin costuras por construcción, color medio = el antiguo FILL_COLOR 0x1A140E) con
	//un gradiente de profundidad translúcido encima — la luz cae de arriba, como en el bisel. La
	//textura da el poro; el gradiente, el volumen; el contraste del texto no cambia respecto a lo
	//ya probado porque el tono medio es el mismo.
	private static final ResourceLocation LEATHER = new ResourceLocation("dndsheets", "textures/screens/panel_leather.png");
	private static final int LEATHER_TILE = 64;
	private static final int DEPTH_TOP = 0x2EFFD9A0;    //Luz cálida arriba, translúcida.
	private static final int DEPTH_BOTTOM = 0x66000000; //Sombra abajo, translúcida.
	private static final int BEVEL_LIGHT = 0xFF6B5636;  //Latón gastado: luz arriba e izquierda.
	private static final int BEVEL_DARK = 0xFF0B0906;   //Sombra abajo y derecha.
	private static final int EDGE_COLOR = 0xFF2E2418;   //Contorno exterior, un tono sobre el relleno.
	private static final int STUD_COLOR = 0xFFC9A227;   //Cantoneras de latón.

	private static final int STUD_SIZE = 3;

	private GuiStyle() {
	}

	/**
	 * <p>Panel de fondo. La firma no cambia respecto a la versión anterior: las más de cuarenta pantallas
	 * que lo llaman siguen funcionando sin tocarlas. Público: también lo usa el HUD de turnos (client.
	 * TurnHudOverlay) para que el tablero de iniciativa tenga el mismo aspecto de tomo que el resto del mod
	 * en vez de un overlay que no pertenece a nada.</p>
	 */
	public static void panel(GuiGraphics guiGraphics, int left, int top, int right, int bottom) {
		//Contorno exterior primero, un píxel por fuera del bisel: separa el panel del mundo desenfocado
		//sin necesidad de sombra difusa, que a la escala de píxel de Minecraft se ve sucia.
		guiGraphics.fill(left - 1, top - 1, right + 1, bottom + 1, EDGE_COLOR);

		//Cuero tileado con la misma leve transparencia que tenía el relleno plano (0xF2): el mundo
		//sigue insinuándose detrás sin competir con el texto. setColor afecta al blit siguiente y se
		//restaura siempre — dejarlo puesto teñiría todo lo que la pantalla pinte después. El blend se
		//activa a mano: blit() dibuja con el estado que haya (fill() lo gestiona solo, blit() no), y
		//sin blend el alpha de setColor se ignora en silencio.
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
		guiGraphics.setColor(1.0F, 1.0F, 1.0F, 0.95F);
		for (int tileY = top; tileY < bottom; tileY += LEATHER_TILE) {
			for (int tileX = left; tileX < right; tileX += LEATHER_TILE) {
				int tileW = Math.min(LEATHER_TILE, right - tileX);
				int tileH = Math.min(LEATHER_TILE, bottom - tileY);
				guiGraphics.blit(LEATHER, tileX, tileY, 0, 0, tileW, tileH, LEATHER_TILE, LEATHER_TILE);
			}
		}
		guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);

		guiGraphics.fillGradient(left, top, right, bottom, DEPTH_TOP, DEPTH_BOTTOM);

		//Bisel de Minecraft: claro arriba/izquierda, oscuro abajo/derecha. Dos píxeles, no uno — a GUI
		//Scale 2 (lo normal) un bisel de un píxel desaparece.
		guiGraphics.fill(left, top, right - 2, top + 2, BEVEL_LIGHT);
		guiGraphics.fill(left, top, left + 2, bottom - 2, BEVEL_LIGHT);
		guiGraphics.fill(left + 2, bottom - 2, right, bottom, BEVEL_DARK);
		guiGraphics.fill(right - 2, top + 2, right, bottom, BEVEL_DARK);

		corners(guiGraphics, left, top, right, bottom);
	}

	//Cantoneras: cuatro escuadras de latón en las esquinas, como las de un libro encuadernado. Es lo que
	//da la lectura de "tomo" sin dibujar un solo píxel de textura ni depender de un PNG.
	private static void corners(GuiGraphics guiGraphics, int left, int top, int right, int bottom) {
		int s = STUD_SIZE;
		int arm = s * 3;

		//Superior izquierda.
		guiGraphics.fill(left + 2, top + 2, left + 2 + arm, top + 2 + s, STUD_COLOR);
		guiGraphics.fill(left + 2, top + 2, left + 2 + s, top + 2 + arm, STUD_COLOR);
		//Superior derecha.
		guiGraphics.fill(right - 2 - arm, top + 2, right - 2, top + 2 + s, STUD_COLOR);
		guiGraphics.fill(right - 2 - s, top + 2, right - 2, top + 2 + arm, STUD_COLOR);
		//Inferior izquierda.
		guiGraphics.fill(left + 2, bottom - 2 - s, left + 2 + arm, bottom - 2, STUD_COLOR);
		guiGraphics.fill(left + 2, bottom - 2 - arm, left + 2 + s, bottom - 2, STUD_COLOR);
		//Inferior derecha.
		guiGraphics.fill(right - 2 - arm, bottom - 2 - s, right - 2, bottom - 2, STUD_COLOR);
		guiGraphics.fill(right - 2 - s, bottom - 2 - arm, right - 2, bottom - 2, STUD_COLOR);
	}

	/**
	 * <p>Filete horizontal de latón, para separar un título de su contenido. Público para las pantallas
	 * que quieran marcar secciones sin inventarse cada una su propio color de línea.</p>
	 */
	public static void rule(GuiGraphics guiGraphics, int left, int right, int y) {
		guiGraphics.fill(left, y, right, y + 1, BEVEL_LIGHT);
	}

	/**
	 * <p>El mismo filete con un rombo de latón en el centro — el adorno de cabecera de un manual de
	 * D&amp;D, en cinco fills. Para el filete bajo el TÍTULO de una pantalla; las separaciones internas
	 * siguen usando {@link #rule}, porque un adorno repetido por sección deja de ser un adorno.</p>
	 */
	public static void ruleOrnate(GuiGraphics guiGraphics, int left, int right, int y) {
		rule(guiGraphics, left, right, y);
		int cx = (left + right) / 2;
		guiGraphics.fill(cx - 2, y - 1, cx + 3, y + 2, BEVEL_LIGHT);
		guiGraphics.fill(cx - 1, y - 1, cx + 2, y + 2, STUD_COLOR);
		guiGraphics.fill(cx, y - 2, cx + 1, y + 3, STUD_COLOR);
	}
}
