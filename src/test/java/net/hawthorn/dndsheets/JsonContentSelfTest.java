package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <p>Comprobación mínima (sin JUnit, sin fixtures) de que los JSON de ejemplo en {@code test/dndsheets/}
 * siguen siendo compatibles con los parsers reales del mod ({@code WeaponRegistry}... en realidad
 * {@code Config.registerWeapon}, {@code SpellRegistry.parse}, {@code MonsterRegistry.parse},
 * {@code PresetRegistry.parse}). Si alguien cambia un nombre de campo en un registro (p.ej.
 * {@code MonsterRegistry.MonsterAttack}) sin actualizar estos ejemplos, esto revienta con un mensaje
 * claro en vez de descubrirse in-game.</p>
 *
 * <p>Llama a los registros directamente en vez de a {@code command.WeaponCommand.loadFile} (etc.):
 * esas clases de comando tienen un campo estático que fuerza a inicializar {@link DndPaths}, que a su
 * vez necesita {@code FMLPaths.GAMEDIR} — solo disponible dentro de una instancia real de Forge
 * arrancada. Los registros en sí (esta clase, {@link SpellRegistry}, {@link MonsterRegistry},
 * {@link PresetRegistry}, {@link Config#registerWeapon}) no dependen de eso, así que se pueden probar
 * de pie, fuera del juego.</p>
 *
 * <p>Se ejecuta a mano desde la raíz del repo (las rutas son relativas a {@code test/dndsheets/...}):
 * {@code java -cp <classpath> net.hawthorn.dndsheets.JsonContentSelfTest}</p>
 */
public class JsonContentSelfTest {
	public static void main(String[] args) throws Exception {
		checkWeapons();
		checkSpells();
		checkMonsters();
		checkTraits(); //Antes de checkPresets(): el preset de monje concede este rasgo por id.
		checkPresets();

		System.out.println("JsonContentSelfTest: OK, los 5 JSON de ejemplo parsean con los registros reales.");
	}

	private static JsonArray readArray(String... pathParts) throws Exception {
		String json = Files.readString(Path.of("test", "dndsheets", pathParts[0], pathParts[1]));
		return JsonParser.parseString(json).getAsJsonArray();
	}

	private static void checkWeapons() throws Exception {
		JsonArray weapons = readArray("weapons", "ejemplo.json");
		int count = 0;
		for (JsonElement el : weapons) {
			JsonObject w = el.getAsJsonObject();
			require(w, "id", "dice", "ability", "name", "item");
			Config.registerWeapon(w.get("id").getAsString(), w.get("dice").getAsString(), w.get("ability").getAsString(),
				w.has("damageType") ? w.get("damageType").getAsString() : "fisico",
				w.has("hands") ? w.get("hands").getAsString() : "one",
				w.has("versatileDice") ? w.get("versatileDice").getAsString() : null,
				w.get("name").getAsString(), w.get("item").getAsString());
			count++;
		}
		expect("armas", count, 4);
		Config.WeaponDefault dagger = Config.weaponDefaultFor("dndsheets:dagger");
		assertTrue(dagger != null && "cortante".equals(dagger.damageType()), "dndsheets:dagger debería tener damageType=cortante");
		Config.WeaponDefault greataxe = Config.weaponDefaultFor("dndsheets:greataxe");
		assertTrue(greataxe != null && "two".equals(greataxe.hands()), "dndsheets:greataxe debería ser a dos manos");
		Config.WeaponDefault warhammer = Config.weaponDefaultFor("dndsheets:warhammer");
		assertTrue(warhammer != null && warhammer.isVersatile() && "1d10".equals(warhammer.versatileDice()), "dndsheets:warhammer debería ser versátil con 1d10 a dos manos");
	}

	private static void checkSpells() throws Exception {
		JsonArray spells = readArray("spells", "ejemplo.json");
		int count = 0;
		for (JsonElement el : spells) {
			SpellRegistry.register(SpellRegistry.parse(el.getAsJsonObject()));
			count++;
		}
		expect("hechizos", count, 6);
		SpellRegistry.Spell fireball = SpellRegistry.get("dndsheets:fireball");
		assertTrue(fireball != null && fireball.aoeRadius() == 6, "fireball debería tener aoeRadius=6");
		SpellRegistry.Spell guardians = SpellRegistry.get("dndsheets:spirit_guardians");
		assertTrue(guardians != null && guardians.concentration(), "spirit_guardians debería tener concentration=true");
		SpellRegistry.Spell cureWounds = SpellRegistry.get("dndsheets:cure_wounds");
		assertTrue(cureWounds != null && "heal".equals(cureWounds.mode()) && cureWounds.dice().contains("$wis"), "cure_wounds debería ser mode=heal con $wis en el dado");
	}

	private static void checkMonsters() throws Exception {
		JsonArray monsters = readArray("monsters", "ejemplo.json");
		int count = 0;
		for (JsonElement el : monsters) {
			MonsterRegistry.register(MonsterRegistry.parse(el.getAsJsonObject()));
			count++;
		}
		expect("monstruos", count, 4);
		MonsterRegistry.MonsterStatBlock spider = MonsterRegistry.get("dndsheets:giant_spider");
		assertTrue(spider != null, "giant_spider debería haberse registrado");
		MonsterRegistry.MonsterAttack bite = spider.attacks().get(0);
		assertTrue(bite.appliesEffect() && "veneno".equals(bite.effectName()), "el mordisco de la araña debería aplicar veneno");
	}

	private static void checkTraits() throws Exception {
		JsonArray traits = readArray("traits", "ejemplo.json");
		int count = 0;
		for (JsonElement el : traits) {
			TraitRegistry.register(TraitRegistry.parse(el.getAsJsonObject()));
			count++;
		}
		expect("rasgos", count, 2);

		TraitRegistry.UnarmedProfile lvl1 = TraitRegistry.unarmedProfileFor(sheetWithTrait("monje:artes_marciales"), 1);
		assertTrue(lvl1 != null && "1d4".equals(lvl1.dice()) && "dex".equals(lvl1.ability()), "Artes Marciales a nivel 1 debería dar 1d4 por Destreza");
		TraitRegistry.UnarmedProfile lvl5 = TraitRegistry.unarmedProfileFor(sheetWithTrait("monje:artes_marciales"), 5);
		assertTrue(lvl5 != null && "1d6".equals(lvl5.dice()), "Artes Marciales a nivel 5 debería escalar a 1d6");

		JsonObject rogueSheet = sheetWithTrait("picaro:ataque_furtivo");
		assertTrue("1d6".equals(TraitRegistry.sneakAttackDiceFor(rogueSheet, 1)), "Ataque Furtivo a nivel 1 debería dar 1d6");
		assertTrue("2d6".equals(TraitRegistry.sneakAttackDiceFor(rogueSheet, 3)), "Ataque Furtivo a nivel 3 debería escalar a 2d6");
		assertTrue(TraitRegistry.sneakAttackDiceFor(sheetWithTrait("monje:artes_marciales"), 20) == null, "Artes Marciales no debería dar Ataque Furtivo");
	}

	private static JsonObject sheetWithTrait(String traitId) {
		JsonObject sheet = new JsonObject();
		JsonArray granted = new JsonArray();
		granted.add(traitId);
		sheet.add("traits", granted);
		return sheet;
	}

	private static void checkPresets() throws Exception {
		JsonArray presets = readArray("presets", "ejemplo.json");
		int count = 0;
		for (JsonElement el : presets) {
			PresetRegistry.register(PresetRegistry.parse(el.getAsJsonObject()));
			count++;
		}
		expect("presets", count, 5);
		PresetRegistry.ClassPreset wizard = PresetRegistry.get("wizard");
		assertTrue(wizard != null && wizard.spellSlotsMax() == 2, "wizard debería tener spellSlotsMax=2");

		//Integración: aplicar el preset de monje/pícaro debe conceder el rasgo, y ese rasgo debe dar dado real.
		JsonObject monkSheet = new JsonObject();
		PresetRegistry.applyToSheet(monkSheet, PresetRegistry.get("monk"));
		assertTrue(TraitRegistry.unarmedProfileFor(monkSheet, 1) != null, "aplicar el preset de monje debería conceder Artes Marciales");

		JsonObject rogueSheet = new JsonObject();
		PresetRegistry.applyToSheet(rogueSheet, PresetRegistry.get("rogue"));
		assertTrue(TraitRegistry.sneakAttackDiceFor(rogueSheet, 1) != null, "aplicar el preset de pícaro debería conceder Ataque Furtivo");
	}

	private static void require(JsonObject obj, String... fields) {
		for (String field : fields) {
			if (!obj.has(field)) throw new AssertionError("Falta el campo \"" + field + "\" en " + obj);
		}
	}

	private static void expect(String label, int actual, int expected) {
		if (actual != expected) throw new AssertionError(label + ": se esperaban " + expected + " entradas, se cargaron " + actual);
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
