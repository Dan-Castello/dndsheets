package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * <p>Hechizos cargados en caliente por {@code /dndspells load}, en memoria (igual que
 * {@link MonsterRegistry}: se pierden al reiniciar a menos que se recargue el mismo archivo).</p>
 *
 * <p>Un hechizo se resuelve con la MISMA mecánica que ya existe en el mod, solo cambia el origen de las
 * estadísticas: {@code mode:"attack"} = tirada de ataque (1d20 + car. de lanzamiento + competencia del
 * lanzador) contra la CA real del objetivo, igual que un arma o un ataque de monstruo; {@code
 * mode:"save"} = el objetivo tira su propia salvación contra la CD del lanzador (8 + competencia +
 * car. de lanzamiento), igual que un hechizo de monstruo.</p>
 */
//Interno: no forma parte de la API pública versionada del mod (ver net.hawthorn.dndsheets.api.DndSheetsApi
//y su API_VERSION). Un mod externo que llame estos métodos directo en vez de a través de la fachada se
//expone a que cambien de firma sin aviso.
public class SpellRegistry {
	public record Spell(
		String id, String name, int level, String mode,
		String castingAbility, String saveAbility, String dice, boolean halfOnSave, String damageType,
		boolean concentration, int aoeRadius,
		String effectName, String effectDice, int effectTurns
	) {
		//Mismo patrón que MonsterRegistry.MonsterAttack/MonsterSpell: un hechizo de concentración
		//(Guardianes Espirituales, Rayo de Luna...) puede dejar un efecto de estado corriendo mientras dura
		//la concentración (ver ConcentrationManager/TurnManager.applyEffect), que se revierte solo si se
		//pierde la concentración — antes eso no existía, "perder concentración" solo tiraba el dado.
		public boolean appliesEffect() { return effectName != null; }
	}

	private static final NamedRegistry<Spell> REGISTRY = new NamedRegistry<>("hechizo", Spell::id);

	public static void register(Spell spell) {
		REGISTRY.register(spell);
	}

	public static Spell get(String id) {
		return REGISTRY.get(id);
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	public static boolean remove(String id) {
		return REGISTRY.remove(id);
	}

	//Público: usado por SpellCommand (/dndspells load) y por DndPaths para precargar solo todos los .json
	//de la carpeta al arrancar el servidor, sin que DndPaths tenga que depender de la capa de comandos —
	//ver AUDIT_TECHNICAL.md M-ARQ-1.
	private static final JsonRegistryLoader<Spell> LOADER = new JsonRegistryLoader<>("hechizo", SpellRegistry::parse, SpellRegistry::register);

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	//Añade un hechizo a la lista de conocidos de la hoja si no lo tenía ya — mismo formato {id,name,level}
	//que ya guarda /dndspells learn (el Grimorio los lee de ahí, no de este registro en memoria del
	//servidor). Reutilizado por SpellCommand.learn y por PresetRegistry.applyToSheet (rasgo icónico de un
	//preset caster). Devuelve false si el hechizo no existe en el registro o si ya lo conocía.
	public static boolean learn(JsonObject sheet, String spellId) {
		Spell spell = get(spellId);
		if (spell == null) return false;
		JsonArray known = sheet.getAsJsonArray("spells");
		for (JsonElement el : known) {
			JsonObject entry = el.getAsJsonObject();
			if (entry.has("id") && entry.get("id").getAsString().equals(spellId)) return false;
		}
		JsonObject entry = new JsonObject();
		entry.addProperty("id", spellId);
		entry.addProperty("name", spell.name());
		entry.addProperty("level", spell.level());
		known.add(entry);
		return true;
	}

	public static Spell parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.has("name") ? json.get("name").getAsString() : id;
		int level = json.has("level") ? json.get("level").getAsInt() : 0;
		String mode = json.has("mode") ? json.get("mode").getAsString().toLowerCase(Locale.ROOT) : "attack";
		String castingAbility = json.has("castingAbility") ? json.get("castingAbility").getAsString().toLowerCase(Locale.ROOT) : "int";
		String saveAbility = json.has("saveAbility") ? json.get("saveAbility").getAsString().toLowerCase(Locale.ROOT) : "dex";
		//Opcional desde que existen las condiciones: un hechizo puede no hacer daño ninguno y aun así tener
		//todo su efecto (Inmovilizar Persona, Dormir, Sugestión). Antes era obligatorio, así que esos
		//hechizos ni siquiera se podían escribir — el parser los descartaba con un aviso.
		String dice = json.has("dice") ? json.get("dice").getAsString() : "0";
		boolean halfOnSave = !json.has("halfOnSave") || json.get("halfOnSave").getAsBoolean();
		String damageType = json.has("damageType") ? json.get("damageType").getAsString().toLowerCase(Locale.ROOT) : "fisico";
		boolean concentration = json.has("concentration") && json.get("concentration").getAsBoolean();
		//Techo defensivo: sin esto, un radio absurdo en el JSON (a propósito o por error de tipeo) hace que
		//SpellCastManager.findAoeTargets escanee todas las entidades cargadas del servidor en cada lanzado,
		//sin límite superior — un vector de lag real, no solo un valor raro.
		int aoeRadius = json.has("aoeRadius") ? Math.max(0, Math.min(json.get("aoeRadius").getAsInt(), 40)) : 0;

		//Mismo formato anidado que MonsterRegistry.parse/parseAttack usan para sus propios monstruos:
		//"appliesEffect": {"name": "...", "dice": "...", "turns": N}.
		JsonObject effect = json.has("appliesEffect") ? json.getAsJsonObject("appliesEffect") : null;
		String effectName = effect != null ? effect.get("name").getAsString() : null;
		String effectDice = effect != null ? effect.get("dice").getAsString() : null;
		int effectTurns = effect != null && effect.has("turns") ? effect.get("turns").getAsInt() : 0;

		return new Spell(id, name, level, mode, castingAbility, saveAbility, dice, halfOnSave, damageType, concentration, aoeRadius,
			effectName, effectDice, effectTurns);
	}

	//--- Báculo de lanzado rápido: cualquier ítem etiquetado {dndsheets:{quickSpell:"id"}} (mismo patrón que las armas personalizadas) ---

	public static String quickSpellIdOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets")) return null;
		CompoundTag dndTag = tag.getCompound("dndsheets");
		return dndTag.contains("quickSpell") ? dndTag.getString("quickSpell") : null;
	}
}
