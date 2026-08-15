package net.hawthorn.dndsheets;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

//Esqueleto repetido en TraitRegistry/PresetRegistry/SpellRegistry/MonsterRegistry: un mapa en memoria de
//id -> definición, cargado en caliente por su comando /dnd... load, perdido al reiniciar el servidor salvo
//que se recargue el mismo archivo. Ver AUDIT_TECHNICAL.md M-DUP-6.
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

	public T get(String id) {
		return items.get(id);
	}

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
