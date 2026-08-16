package net.hawthorn.dndsheets.client.gui.components;

import net.minecraft.client.gui.GuiGraphics;

/**
 * <p>Marco de latón para los huecos de la hoja de personaje: los campos de texto y la lista de ataques.</p>
 *
 * <p>Vanilla dibuja los dos igual —un anillo gris de un píxel (blanco al tener el foco) alrededor de un
 * relleno negro— y los dos colores están fijos dentro de {@code EditBox.renderWidget} y
 * {@code AbstractScrollWidget.renderBorder}. Sobre el pergamino eso se lee como widgets prestados de otra
 * interfaz.</p>
 *
 * <p>Quitar el borde no es opción en el caso del {@code EditBox}: {@code setBordered(false)} se lleva por
 * delante el relleno negro Y mueve el texto (de centrado con margen a pegado a la esquina superior
 * izquierda), y sin fondo oscuro detrás el texto tendría que ser tinta sobre pergamino — que con la sombra
 * fija de Minecraft se ve duplicado. Así que el anillo no se quita: se repinta encima. Ocupa exactamente
 * un píxel por fuera del hueco, o sea que taparlo no toca ni el texto ni el interior.</p>
 *
 * <p>Vive en este paquete y no junto a {@code GuiStyle} porque lo usan las dos partes: la pantalla de la
 * hoja ({@code client.gui}) para sus campos, y {@code RollScrollWidget} (aquí) para la lista de ataques y
 * para el nombre de cada fila. {@code GuiStyle} no es visible desde aquí.</p>
 */
public final class TomeField {

	//Misma paleta que GuiStyle y TomeButton. Duplicada a conciencia: el paquete components no ve GuiStyle,
	//y un color repetido es menos daño que exponer toda la clase de estilo solo por tres enteros.
	private static final int RING = 0xFF6B5636;
	private static final int RING_FOCUSED = 0xFFC9A227;
	private static final int SHADOW = 0xFF8A7B5E;
	/** Relleno de un hueco grande (la lista de ataques): cuero, no negro puro. */
	public static final int WELL_FILL = 0xFF15100A;

	private TomeField() {
	}

	/**
	 * <p>Repinta el anillo de un hueco en latón, y le añade sombra por fuera arriba y a la izquierda —de
	 * donde vendría la luz en el biselado de Minecraft— para que se lea hundido en la hoja.</p>
	 *
	 * <p>Las coordenadas son las del ANILLO, es decir un píxel por fuera del widget.</p>
	 */
	public static void frame(GuiGraphics guiGraphics, int left, int top, int right, int bottom, boolean focused) {
		int ring = focused ? RING_FOCUSED : RING;

		guiGraphics.fill(left, top, right, top + 1, ring);
		guiGraphics.fill(left, bottom - 1, right, bottom, ring);
		guiGraphics.fill(left, top, left + 1, bottom, ring);
		guiGraphics.fill(right - 1, top, right, bottom, ring);

		guiGraphics.fill(left - 1, top - 1, right, top, SHADOW);
		guiGraphics.fill(left - 1, top - 1, left, bottom, SHADOW);
	}

	/** Sobrecarga para un widget: el anillo va justo por fuera de su rectángulo. */
	public static void frameWidget(GuiGraphics guiGraphics, int x, int y, int width, int height, boolean focused) {
		frame(guiGraphics, x - 1, y - 1, x + width + 1, y + height + 1, focused);
	}
}
