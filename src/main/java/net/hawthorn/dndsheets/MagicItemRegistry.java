package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <p>Objetos mágicos. Sexto tipo de contenido, con la misma forma que los otros cinco:
 * {@link NamedRegistry} en memoria + {@link JsonRegistryLoader} desde un JSON por carpeta.</p>
 *
 * <p><b>Por qué su importación no se parece a la de hechizos y monstruos:</b> el SRD publica los objetos
 * mágicos solo como <em>prosa</em> — nombre, rareza, categoría y un párrafo de descripción. No hay campo
 * de bonificador ni de resistencia que leer, así que sus mecánicas no se pueden derivar automáticamente
 * sin inventárselas. De ahí que un objeto tenga dos mitades: la de <b>referencia</b> (nombre, rareza,
 * descripción), importable entera y ya útil para que un DM lo consulte y lo entregue, y la <b>mecánica</b>
 * (CA, salvaciones, resistencias), que se escribe a mano y solo existe donde de verdad repite un patrón.</p>
 *
 * <p>Un objeto sin mecánicas no es un objeto roto: es un objeto que el DM narra, que es como funciona la
 * mayoría de los objetos mágicos en una mesa real.</p>
 */
public class MagicItemRegistry {

	/**
	 * @param acBonus        suma a la CA mientras está equipado o sintonizado.
	 * @param saveBonus      suma a todas las salvaciones (Anillo/Capa de Protección).
	 * @param affinities     resistencias que concede, mismo vocabulario que el resto del mod.
	 * @param grantsSpellId  hechizo que permite lanzar (varitas y bastones), o {@code null}.
	 * @param attunement     si necesita sintonización. En 5e limita a 3 objetos por personaje, que es lo
	 *                       que impide que alguien acumule veinte anillos — y en Minecraft resuelve además
	 *                       que no haya ranura de anillo ni de capa donde "llevarlos" puestos.
	 */
	public record MagicItem(String id, String name, String rarity, String description, String itemId,
	                        int acBonus, int saveBonus, Map<String, String> affinities,
	                        String grantsSpellId, boolean attunement) {

		/** Un objeto puramente narrativo: el DM lo describe, el motor no tiene nada que aplicar. */
		public boolean hasMechanics() {
			return acBonus != 0 || saveBonus != 0 || !affinities.isEmpty() || grantsSpellId != null;
		}
	}

	private static final NamedRegistry<MagicItem> REGISTRY = new NamedRegistry<>("objeto mágico", MagicItem::id);

	public static void register(MagicItem item) { REGISTRY.register(item); }

	public static MagicItem get(String id) { return REGISTRY.get(id); }

	public static Set<String> ids() { return REGISTRY.ids(); }

	public static boolean remove(String id) { return REGISTRY.remove(id); }

	private static final JsonRegistryLoader<MagicItem> LOADER =
		new JsonRegistryLoader<>("objeto mágico", MagicItemRegistry::parse, MagicItemRegistry::register);

	public static int loadFile(Path file) throws IOException { return LOADER.loadFile(file); }

	public static MagicItem parse(JsonObject json) {
		String id = json.get("id").getAsString();
		Map<String, String> affinities = new HashMap<>();
		if (json.has("damageAffinities")) {
			JsonObject declared = json.getAsJsonObject("damageAffinities");
			for (String type : declared.keySet()) {
				affinities.put(type.toLowerCase(Locale.ROOT), declared.get(type).getAsString().toLowerCase(Locale.ROOT));
			}
		}
		return new MagicItem(
			id,
			json.has("name") ? json.get("name").getAsString() : id,
			json.has("rarity") ? json.get("rarity").getAsString() : "común",
			json.has("description") ? json.get("description").getAsString() : "",
			//El ítem vanilla que le presta la apariencia. Un lingote de oro por defecto: neutro, y no se
			//confunde con un arma o una armadura de verdad.
			json.has("item") ? json.get("item").getAsString() : "minecraft:gold_ingot",
			json.has("acBonus") ? json.get("acBonus").getAsInt() : 0,
			json.has("saveBonus") ? json.get("saveBonus").getAsInt() : 0,
			affinities,
			json.has("grantsSpell") ? json.get("grantsSpell").getAsString() : null,
			json.has("attunement") && json.get("attunement").getAsBoolean());
	}

	//--- Etiqueta NBT del ItemStack, mismo patrón que quickSpell y monsterSpawn ---

	public static ItemStack tag(ItemStack stack, String itemId) {
		CompoundTag tag = stack.getOrCreateTag();
		CompoundTag dnd = tag.getCompound("dndsheets");
		dnd.putString("magicItem", itemId);

		//Una varita o un bastón que lanza un conjuro se etiqueta ADEMÁS como báculo rápido: ese camino ya
		//existe entero (ver QuickSpellManager y AbilityItemDispatcher) y hace justo lo que hace falta —
		//clic derecho lanza el conjuro con las características y los espacios del portador. Reutilizarlo
		//sale gratis; escribir un segundo mecanismo de "objeto que lanza algo" no.
		MagicItem item = get(itemId);
		if (item != null && item.grantsSpellId() != null && SpellRegistry.get(item.grantsSpellId()) != null) {
			dnd.putString("quickSpell", item.grantsSpellId());
		}

		tag.put("dndsheets", dnd);
		return stack;
	}

	public static String magicItemIdOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets")) return null;
		CompoundTag dnd = tag.getCompound("dndsheets");
		String id = dnd.contains("magicItem") ? dnd.getString("magicItem") : null;
		return id == null || id.isEmpty() ? null : id;
	}

	//--- Qué objetos le están aplicando ahora mismo a un jugador ---

	//En 5e son 3 y no es un número redondo por casualidad: es el freno que impide acumular objetos sin
	//límite. Aquí además resuelve un problema propio de Minecraft — no hay ranura de anillo ni de capa, así
	//que sin sintonización no habría forma de "llevar puesto" un Anillo de Protección.
	public static final int MAX_ATTUNED = 3;

	public static List<String> attunedIds(JsonObject sheet) {
		List<String> attuned = new ArrayList<>();
		if (sheet == null || !sheet.has("attunedItems")) return attuned;
		for (JsonElement el : sheet.getAsJsonArray("attunedItems")) attuned.add(el.getAsString());
		return attuned;
	}

	/** @return false si ya llegó al límite o si ya lo tenía sintonizado. */
	public static boolean attune(JsonObject sheet, String itemId) {
		List<String> attuned = attunedIds(sheet);
		if (attuned.contains(itemId) || attuned.size() >= MAX_ATTUNED) return false;
		attuned.add(itemId);
		writeAttuned(sheet, attuned);
		return true;
	}

	public static boolean unattune(JsonObject sheet, String itemId) {
		List<String> attuned = attunedIds(sheet);
		if (!attuned.remove(itemId)) return false;
		writeAttuned(sheet, attuned);
		return true;
	}

	private static void writeAttuned(JsonObject sheet, List<String> attuned) {
		JsonArray array = new JsonArray();
		for (String id : attuned) array.add(id);
		sheet.add("attunedItems", array);
	}

	/**
	 * <p>Los objetos cuyos efectos están activos sobre ese jugador. Uno que exige sintonización cuenta
	 * solo si está sintonizado; uno que no la exige, solo si lo lleva en las manos o puesto como armadura.</p>
	 *
	 * <p>Esa distinción es lo que evita el caso absurdo de un jugador con el inventario lleno de objetos
	 * sumando bonificadores por el mero hecho de cargarlos.</p>
	 */
	public static List<MagicItem> activeFor(Player player, JsonObject sheet) {
		List<MagicItem> active = new ArrayList<>();
		List<String> attuned = attunedIds(sheet);

		List<String> wornIds = new ArrayList<>();
		for (ItemStack stack : equippedStacks(player)) {
			String id = magicItemIdOf(stack);
			if (id != null) wornIds.add(id);
		}

		for (String id : attuned) {
			MagicItem item = get(id);
			if (item == null) continue;
			//Con Curios instalado hay dónde llevar puesto un anillo o una capa, así que se exige lo mismo
			//que 5e: sintonizado Y equipado. Sin Curios no existe esa ranura, y exigirlo dejaría el objeto
			//inservible para siempre — ahí la sintonización hace de las dos cosas a la vez.
			if (net.hawthorn.dndsheets.compat.CuriosCompat.isLoaded() && !wornIds.contains(id)) continue;
			active.add(item);
		}

		for (String id : wornIds) {
			if (attuned.contains(id)) continue; //Ya contado arriba: no se aplica dos veces.
			MagicItem item = get(id);
			if (item != null && !item.attunement()) active.add(item);
		}
		return active;
	}

	/**
	 * <p>Lo que el jugador lleva puesto de verdad. Con Curios instalado esto incluye sus ranuras (anillo,
	 * collar, capa, cinturón), que es donde un Anillo de Protección debería ir de forma natural; sin
	 * Curios, la lista es la de siempre y esos objetos siguen dependiendo de la sintonización.</p>
	 *
	 * <p>La integración es blanda: {@link net.hawthorn.dndsheets.compat.CuriosCompat} devuelve una lista
	 * vacía si Curios no está, así que aquí no hay ninguna rama que mantener.</p>
	 */
	private static List<ItemStack> equippedStacks(Player player) {
		List<ItemStack> stacks = new ArrayList<>();
		stacks.add(player.getMainHandItem());
		stacks.add(player.getOffhandItem());
		player.getArmorSlots().forEach(stacks::add);
		stacks.addAll(net.hawthorn.dndsheets.compat.CuriosCompat.equippedStacks(player));
		return stacks;
	}
}
