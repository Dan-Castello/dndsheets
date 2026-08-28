package net.hawthorn.dndsheets;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

//Esqueleto repetido en TraitRegistry/PresetRegistry/SpellRegistry/MonsterRegistry: un mapa en memoria de
//id -> definición, cargado en caliente por su comando /dnd... load, perdido al reiniciar el servidor salvo
//que se recargue el mismo archivo.
public class NamedRegistry<T> {
	private final Map<String, T> items = new LinkedHashMap<>();
	private final Function<T, String> idOf;
	private final String kindName; //Para el aviso de sobreescritura, p.ej. "rasgo", "preset", "hechizo", "monstruo".

	public NamedRegistry(String kindName, Function<T, String> idOf) {
		this.kindName = kindName;
		this.idOf = idOf;
	}

	public void register(T item) {
		String id = idOf.apply(item);
		if (items.containsKey(id)) {
			DndsheetsMod.LOGGER.warn("El {} \"{}\" ya estaba cargado, se pisa con la nueva definición.", kindName, id);
		}
		items.put(id, item);
	}

	/**
	 * <p>Igual, pero sin avisar de que pisa lo anterior. Para quien reescribe una entrada <b>a propósito y
	 * en cada uso</b>: el bloque de una invocación se regenera en cada lanzado para recoger los cambios del
	 * JSON del conjuro (ver {@code SummonManager}), así que el aviso salía una vez por Esfera Flamígera
	 * lanzada — un WARN por algo que funciona como debe, que es la clase de ruido que hace que se dejen de
	 * leer los avisos de verdad.</p>
	 */
	public void replace(T item) {
		items.put(idOf.apply(item), item);
	}

	/**
	 * <p>El id tal y como está guardado y, si no aparece, el mismo sin el {@code minecraft:} de delante.</p>
	 *
	 * <p>Todos los comandos de contenido leen su id con {@code ResourceLocationArgument}, y eso le completa
	 * el namespace por defecto a cualquier palabra sin {@code ":"} — no es cosa de los comandos, es lo que
	 * hace {@code ResourceLocation} con cualquier id pelado. Pero acá los ids se guardan <b>tal cual vienen
	 * del JSON</b>, y lo que crea el DM in-game no lleva namespace ("emboscada_goblin", "fighter"): escribir
	 * lo mismo que sugiere el autocompletado no encontraba nada, y los botones del Panel de DM —que mandan
	 * ese comando— fallaban igual. Se parcheó una vez para los presets ({@code PresetCommand}) y volvió a
	 * aparecer en encuentros, que es de donde salió esto: el arreglo va donde pasan TODAS las búsquedas.</p>
	 *
	 * <p>Un id de un addon con namespace propio ({@code miaddon:algo}) nunca entra por esta rama: ahí el
	 * namespace es real y la primera búsqueda ya acierta.</p>
	 */
	public T get(String id) {
		T item = items.get(id);
		if (item != null || id == null) return item;
		return id.startsWith(DEFAULT_NAMESPACE) ? items.get(id.substring(DEFAULT_NAMESPACE.length())) : null;
	}

	private static final String DEFAULT_NAMESPACE = "minecraft:";

	public Set<String> ids() {
		return items.keySet();
	}

	//Público: usado por el creador de contenido in-game para borrar una entrada creada en el propio juego
	//(ver ContentPackFile) — sin esto no había forma de sacar algo de un *Registry una vez cargado salvo
	//reiniciar el servidor sin recargar el archivo que lo trajo.
	public boolean remove(String id) {
		return items.remove(id) != null;
	}
}
