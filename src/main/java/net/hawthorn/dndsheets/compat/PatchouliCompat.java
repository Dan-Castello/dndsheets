package net.hawthorn.dndsheets.compat;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * <p>Integración <b>opcional</b> con Patchouli, el mod estándar para manuales dentro del juego.</p>
 *
 * <p><b>Qué cambia.</b> La Guía existía como libro escrito de vanilla: 26 páginas seguidas, sin índice,
 * sin búsqueda y sin forma de volver a una en concreto. Sirve para leérsela una vez y no para consultarla
 * a mitad de una partida, que es cuando de verdad hace falta. Con Patchouli instalado, la misma Guía se
 * abre como un manual con categorías, índice, búsqueda y marcapáginas, y además queda como un ítem que el
 * jugador puede guardar en su inventario.</p>
 *
 * <p><b>El texto no se duplica.</b> Las entradas del libro de Patchouli apuntan a las <em>mismas</em>
 * claves de idioma que usa el libro escrito ({@code gui.dndsheets.guide.page.*}, con {@code i18n: true} en
 * {@code book.json}). Escribir la guía dos veces habría garantizado que las dos versiones se separaran a
 * la primera corrección; así, una página corregida lo está en las dos a la vez. El self-test comprueba que
 * cada página aparece en exactamente una entrada.</p>
 *
 * <p><b>Por qué son dos clases</b>, igual que en {@link CuriosCompat}: esta no importa ni un tipo de
 * Patchouli, para que cargarla en una instalación sin Patchouli no reviente con
 * {@code NoClassDefFoundError}. Todo lo que toca su API vive en {@link PatchouliBook}, que solo se carga
 * después de comprobar {@link #isLoaded()}.</p>
 */
public final class PatchouliCompat {

	/** El libro que define {@code data/dndsheets/patchouli_books/guide/book.json}. */
	public static final String BOOK_ID = "dndsheets:guide";

	private PatchouliCompat() {}

	private static final boolean LOADED = ModList.get().isLoaded("patchouli");

	public static boolean isLoaded() {
		return LOADED;
	}

	/**
	 * <p>Abre la Guía en el cliente. Devuelve {@code false} si Patchouli no está, y entonces quien llama
	 * abre el libro escrito de siempre — la Guía nunca deja de estar disponible por no tener un mod.</p>
	 */
	public static boolean openOnClient() {
		if (!LOADED) return false;
		return PatchouliBook.openOnClient();
	}

	/** El ítem del manual, o vacío sin Patchouli. Para que el jugador se lo pueda quedar. */
	public static ItemStack bookStack() {
		if (!LOADED) return ItemStack.EMPTY;
		return PatchouliBook.bookStack();
	}
}
