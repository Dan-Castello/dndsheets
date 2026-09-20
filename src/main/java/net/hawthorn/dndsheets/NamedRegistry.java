package net.hawthorn.dndsheets;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;

//Skeleton repeated in TraitRegistry/PresetRegistry/SpellRegistry/MonsterRegistry: an in-memory map of
//id -> definition, hot-loaded by its /dnd... load command, lost on server restart unless the same file
//is reloaded.
public class NamedRegistry<T> {
	private final Map<String, T> items = new LinkedHashMap<>();
	private final Function<T, String> idOf;
	private final String kindName; //For the overwrite warning, e.g. "trait", "preset", "spell", "monster".

	public NamedRegistry(String kindName, Function<T, String> idOf) {
		this.kindName = kindName;
		this.idOf = idOf;
	}

	public void register(T item) {
		String id = idOf.apply(item);
		if (items.containsKey(id)) {
			DndsheetsMod.LOGGER.warn("The {} \"{}\" was already loaded; overwriting it with the new definition.", kindName, id);
		}
		items.put(id, item);
	}

	/**
	 * <p>Same thing, but without warning that it's overwriting the previous entry. For whoever rewrites an
	 * entry <b>on purpose and on every use</b>: a summon's stat block gets regenerated on every cast to
	 * pick up changes to the spell's JSON (see {@code SummonManager}), so the warning used to fire once per
	 * Flaming Sphere cast — a WARN for something working as intended, which is exactly the kind of noise
	 * that makes people stop reading the warnings that actually matter.</p>
	 */
	public void replace(T item) {
		items.put(idOf.apply(item), item);
	}

	/**
	 * <p>The id exactly as stored and, if not found, the same id without a leading {@code minecraft:}.</p>
	 *
	 * <p>Every content command reads its id with {@code ResourceLocationArgument}, and that fills in the
	 * default namespace for any word without a {@code ":"} — that's not something the commands do, it's
	 * what {@code ResourceLocation} does with any bare id. But here ids are stored <b>exactly as they come
	 * from the JSON</b>, and what a DM creates in-game has no namespace ("goblin_ambush", "fighter"):
	 * typing exactly what autocomplete suggested would find nothing, and the DM Panel buttons — which send
	 * that same command — failed the same way. It got patched once for presets ({@code PresetCommand}) and
	 * resurfaced for encounters, which is where this fix came from: it belongs wherever ALL lookups pass
	 * through.</p>
	 *
	 * <p>An id from an addon with its own namespace ({@code myaddon:something}) never falls into this
	 * branch: there the namespace is real and the first lookup already succeeds.</p>
	 */
	@Nullable
	public T get(String id) {
		T item = items.get(id);
		if (item != null || id == null) return item;
		return id.startsWith(DEFAULT_NAMESPACE) ? items.get(id.substring(DEFAULT_NAMESPACE.length())) : null;
	}

	private static final String DEFAULT_NAMESPACE = "minecraft:";

	public Set<String> ids() {
		return items.keySet();
	}

	//Public: used by the in-game content creator to delete an entry created in-game itself
	//(see ContentPackFile) — without this there was no way to remove something from a *Registry once
	//loaded except restarting the server without reloading the file that brought it in.
	public boolean remove(String id) {
		return items.remove(id) != null;
	}
}
