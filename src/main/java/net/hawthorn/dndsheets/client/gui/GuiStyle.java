package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.gui.GuiGraphics;

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
final class GuiStyle {

	//--- Colores de texto ---
	/** Pergamino, no blanco puro: el blanco absoluto sobre cuero oscuro vibra y cansa la vista. */
	static final int TITLE_COLOR = 0xFFE9D8B4;
	static final int SUBTITLE_COLOR = 0xFFB9A88C;
	static final int MUTED_COLOR = 0xFF8C8071;
	/** Latón envejecido, para lo que debe destacar sin gritar (marcas, valores activos). */
	static final int ACCENT_COLOR = 0xFFC9A227;

	//--- Colores del panel ---
	private static final int FILL_COLOR = 0xF21A140E;   //Cuero oscuro, casi opaco.
	private static final int BEVEL_LIGHT = 0xFF6B5636;  //Latón gastado: luz arriba e izquierda.
	private static final int BEVEL_DARK = 0xFF0B0906;   //Sombra abajo y derecha.
	private static final int EDGE_COLOR = 0xFF2E2418;   //Contorno exterior, un tono sobre el relleno.
	private static final int STUD_COLOR = 0xFFC9A227;   //Cantoneras de latón.

	private static final int STUD_SIZE = 3;

	private GuiStyle() {
	}

	/**
	 * <p>Panel de fondo. La firma no cambia respecto a la versión anterior: las más de cuarenta pantallas
	 * que lo llaman siguen funcionando sin tocarlas.</p>
	 */
	static void panel(GuiGraphics guiGraphics, int left, int top, int right, int bottom) {
		//Contorno exterior primero, un píxel por fuera del bisel: separa el panel del mundo desenfocado
		//sin necesidad de sombra difusa, que a la escala de píxel de Minecraft se ve sucia.
		guiGraphics.fill(left - 1, top - 1, right + 1, bottom + 1, EDGE_COLOR);
		guiGraphics.fill(left, top, right, bottom, FILL_COLOR);

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
	static void rule(GuiGraphics guiGraphics, int left, int right, int y) {
		guiGraphics.fill(left, y, right, y + 1, BEVEL_LIGHT);
	}
}
