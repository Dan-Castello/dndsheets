package net.hawthorn.dndsheets;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * <p>Un nombre de contenido ({@code "name"} de un pack: arma, hechizo, monstruo, objeto mágico, dote,
 * rasgo, preset, encuentro) convertido en el texto que se enseña. <b>Todo</b> lo que ponga uno de esos
 * nombres en pantalla —chat, tooltip de ítem, lista de GUI, compendio— tiene que pasar por aquí.</p>
 *
 * <p>El motivo es que el idioma lo elige cada cliente, no el servidor: con {@code Component.literal(name)}
 * el nombre se resuelve donde se construye —el servidor— y todo el mundo lo lee igual, así que un jugador
 * con el juego en inglés recibía una "Daga" y un "Báculo de Rayo de Fuego". Como el nombre viaja además
 * dentro del NBT del ItemStack, se quedaba en español para siempre.</p>
 *
 * <p>Los packs de serie del mod traen una <b>clave</b> de idioma en ese campo
 * ({@code "name": "content.dndsheets.weapon.dagger"}), que cada cliente resuelve en el suyo. Un pack
 * escrito a mano por un DM trae el nombre literal ({@code "name": "Espada del Rey"}) y no hay que hacer
 * nada especial: Minecraft ya devuelve la clave tal cual cuando no existe en los ficheros de idioma, así
 * que ese caso se pinta exactamente igual que antes. Por eso no hay que distinguirlos aquí ni marcar el
 * pack de ninguna forma.</p>
 */
public final class ContentNames {

	private ContentNames() {
	}

	public static MutableComponent of(String name) {
		if (name == null || name.isEmpty()) return Component.empty();
		//Un % en un nombre escrito a mano ("Poción 50%") lo tomaría TranslatableContents por un hueco de
		//formato y se comería el texto a partir de ahí. Ninguna clave del mod lleva %, así que descartarlo
		//aquí no pierde nada y sí salva al pack de un DM.
		return name.indexOf('%') >= 0 ? Component.literal(name) : Component.translatable(name);
	}

	/**
	 * <p>Para los sitios que necesitan un {@code String} y no un {@code Component}: el resumen de una
	 * linea del registro de tiradas, o el nombre que se guarda en la hoja para el panel de efectos.</p>
	 *
	 * <p>ponytail: esto resuelve en el idioma del SERVIDOR, no en el de quien mira — es lo unico
	 * posible cuando el destino es texto plano. Vale para un resumen; si algun dia hace falta que el
	 * registro de tiradas tambien siga el idioma del cliente, hay que llevar {@code Component} hasta
	 * el final de esa tuberia en vez de un {@code String}.</p>
	 */
	public static String plain(String name) {
		return name == null ? "" : of(name).getString();
	}
}
