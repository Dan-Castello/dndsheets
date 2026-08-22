package net.hawthorn.dndsheets.client.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>La aritmética de repartir la Guía en páginas del libro escrito. Sin nada de Minecraft dentro a
 * propósito: quien mide el texto es {@link GuideBook}, que tiene el {@code Font} del cliente, y aquí solo
 * se reparte lo ya medido.</p>
 *
 * <p>Está separada porque es la parte que puede fallar en silencio —de cuántas páginas ocupe el índice
 * depende el número al que salta CADA fila suya, así que una línea de más manda todos los enlaces a la
 * página equivocada— y {@code JsonContentSelfTest} corre sin juego: junto al {@code Font} no habría forma
 * de comprobarla. Es el mismo motivo por el que existe {@code CharacterRules}.</p>
 */
public final class GuideLayout {

	private GuideLayout() {
	}

	/**
	 * <p>Junta líneas ya partidas en trozos que quepan en una página. El primero puede llevar menos sitio
	 * que el resto, que es donde entra el título de la entrada.</p>
	 *
	 * <p>Se re-unen con un espacio porque es justo lo que el repartidor quita al cortar: el texto vuelve
	 * a partirse igual al pintarse.</p>
	 */
	public static List<String> wrap(List<String> lines, int firstLimit, int restLimit) {
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int used = 0;
		int limit = firstLimit;
		for (String line : lines) {
			if (used == limit) {
				chunks.add(current.toString());
				current.setLength(0);
				used = 0;
				limit = restLimit;
			}
			if (used > 0) current.append(' ');
			current.append(line);
			used++;
		}
		//Sin el guardia de arriba, un texto que acaba justo en el límite dejaría una página en blanco
		//detrás. Con él, el último trozo siempre lleva algo.
		chunks.add(current.toString());
		return chunks;
	}

	/**
	 * <p>Reparte filas de altura conocida en páginas de {@code limit} líneas, y devuelve cuántas filas
	 * lleva cada página. Una fila más alta que la página entera se queda sola en la suya en vez de
	 * bloquear el reparto.</p>
	 */
	public static List<Integer> paginate(List<Integer> heights, int limit) {
		List<Integer> pages = new ArrayList<>();
		int rows = 0;
		int used = 0;
		for (int height : heights) {
			if (used + height > limit && rows > 0) {
				pages.add(rows);
				rows = 0;
				used = 0;
			}
			rows++;
			used += height;
		}
		pages.add(rows);
		return pages;
	}
}
