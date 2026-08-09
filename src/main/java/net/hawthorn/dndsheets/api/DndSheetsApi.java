package net.hawthorn.dndsheets.api;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DiceManager;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.hawthorn.dndsheets.PresetRegistry;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.SpellRegistry;
import net.hawthorn.dndsheets.TraitRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/**
 * <p>Superficie pública de dndsheets para que otro mod lea/escriba hojas de personaje, tire dados con su
 * sintaxis, registre contenido (hechizos, rasgos, monstruos, presets, armas) y lo asigne a un jugador o
 * lo invoque en el mundo. Es el único paquete ({@code net.hawthorn.dndsheets.api}) con promesa de
 * compatibilidad: los métodos de aquí no cambian de firma entre versiones, solo se añaden nuevos. Todo lo
 * demás del mod (SheetLoader, *Registry, Config...) es detalle interno y puede cambiar sin aviso.</p>
 *
 * <p><b>Cómo depender de esta API sin acoplar tu mod a que dndsheets esté instalado:</b> añade una
 * dependencia {@code compileOnly} contra el jar de dndsheets en tu {@code build.gradle}, y antes de llamar
 * a cualquier método de aquí comprueba en runtime {@code ModList.get().isLoaded("dndsheets")}.</p>
 *
 * <p><b>Cuándo registrar contenido:</b> en el {@code FMLCommonSetupEvent} de tu propio mod. Es antes de
 * que dndsheets cargue sus JSON en {@code ServerStartingEvent}, aunque el orden no es crítico — un id que
 * choca con otro solo genera un aviso en el log y se sobrescribe, nunca revienta.</p>
 */
public final class DndSheetsApi {
	private DndSheetsApi() {}

	/** Sube solo si un método existente de esta clase cambia de firma (no al añadir métodos nuevos). */
	public static final int API_VERSION = 1;

	// --- Hoja de personaje ---

	/**
	 * Copia de la hoja del jugador guardada en el servidor; mutarla no afecta a la hoja real.
	 * @return {@code null} si se llama del lado cliente, o antes de que el jugador termine de unirse al
	 * servidor (ver {@code SheetLoader.clientJoinedServer}) — no hay hoja cargada todavía para esa UUID.
	 */
	public static JsonObject getSheet(Player player) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		return sheet != null ? sheet.deepCopy() : null;
	}

	/** Aplica y sincroniza al cliente solo los campos presentes en {@code patch} (un valor {@code JsonNull} borra el campo). */
	public static void patchSheet(ServerPlayer player, JsonObject patch) {
		DndsheetsMod.sendSheetFieldUpdate(player, patch);
	}

	// --- Dados ---

	/**
	 * Resuelve una expresión con la sintaxis del mod (p.ej. {@code "1d20 + $str"}) contra una hoja.
	 * @return {@code null} si la expresión fue rechazada (sintaxis inválida o conteo de dados absurdo).
	 */
	public static RollResult roll(JsonObject sheet, String expression) {
		return RollResult.from(DiceManager.roll(sheet, expression));
	}

	// --- Registrar contenido ---

	public static void registerSpell(SpellRegistry.Spell spell) {
		SpellRegistry.register(spell);
	}

	public static void registerTrait(TraitRegistry.Trait trait) {
		TraitRegistry.register(trait);
	}

	public static void registerMonster(MonsterRegistry.MonsterStatBlock monster) {
		MonsterRegistry.register(monster);
	}

	public static void registerPreset(PresetRegistry.ClassPreset preset) {
		PresetRegistry.register(preset);
	}

	public static void registerWeapon(WeaponRegistration weapon) {
		Config.registerWeapon(weapon.id(), weapon.dice(), weapon.ability(), weapon.damageType(), weapon.hands(),
			weapon.versatileDice(), weapon.classes(), weapon.displayName(), weapon.baseItemId(), weapon.customModelData());
	}

	// --- Consultar contenido ---

	public static SpellRegistry.Spell getSpell(String id) { return SpellRegistry.get(id); }
	public static Set<String> spellIds() { return SpellRegistry.ids(); }

	public static TraitRegistry.Trait getTrait(String id) { return TraitRegistry.get(id); }
	public static Set<String> traitIds() { return TraitRegistry.ids(); }

	public static MonsterRegistry.MonsterStatBlock getMonster(String id) { return MonsterRegistry.get(id); }
	public static Set<String> monsterIds() { return MonsterRegistry.ids(); }

	public static PresetRegistry.ClassPreset getPreset(String id) { return PresetRegistry.get(id); }
	public static Set<String> presetIds() { return PresetRegistry.ids(); }

	public static Config.WeaponDefault weaponDefaultFor(String weaponId) { return Config.weaponDefaultFor(weaponId); }
	public static Set<String> weaponIds() { return Config.loadedWeaponIds(); }

	// --- Asignar contenido a una hoja / jugador ---

	/** @return true si el hechizo existía y se aprendió. */
	public static boolean learnSpell(JsonObject sheet, String spellId) {
		return SpellRegistry.learn(sheet, spellId);
	}

	public static void grantTrait(JsonObject sheet, String traitId) {
		TraitRegistry.grant(sheet, traitId);
	}

	/** No hace nada si {@code presetId} no existe. */
	public static void applyPreset(JsonObject sheet, String presetId) {
		PresetRegistry.ClassPreset preset = PresetRegistry.get(presetId);
		if (preset != null) PresetRegistry.applyToSheet(sheet, preset);
	}

	/** Construye el ítem del arma (con su lore/encantamiento como en {@code /dndweapons give}) y lo mete en el inventario del jugador. */
	public static void giveWeapon(ServerPlayer player, String weaponId, int count) {
		ItemStack stack = Config.buildWeaponStack(weaponId, count);
		player.getInventory().add(stack.copy());
	}

	// --- Monstruos ---

	/** Invoca el monstruo registrado con id {@code monsterId}, o null si no existe o su ítem base no es válido. */
	public static Entity spawnMonster(ServerLevel level, double x, double y, double z, String monsterId) {
		return MonsterRegistry.spawnAt(level, x, y, z, monsterId);
	}

	/** Registra y a la vez invoca un monstruo ad-hoc (sin definición JSON previa), como la carta de invocación genérica. */
	public static Entity spawnGenericMonster(ServerLevel level, double x, double y, double z, String name, String baseEntityId, int ac, int hp) {
		return MonsterRegistry.spawnGeneric(level, x, y, z, name, baseEntityId, ac, hp);
	}

	public static String monsterIdOf(Entity entity) { return MonsterRegistry.monsterIdOf(entity); }
	public static MonsterRegistry.MonsterStatBlock statBlockOf(Entity entity) { return MonsterRegistry.statBlockOf(entity); }
	public static int currentHpOf(Entity entity) { return MonsterRegistry.currentHpOf(entity); }
	public static void setCurrentHp(Entity entity, int hp) { MonsterRegistry.setCurrentHp(entity, hp); }
}
