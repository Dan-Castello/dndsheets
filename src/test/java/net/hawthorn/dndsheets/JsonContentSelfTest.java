package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
		checkDice();
		checkDungeonPools();
		checkConditions();
		checkAoeShapes();
		checkCombatantRules();
		checkCharacterRules();

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
				List.of(), w.get("name").getAsString(), w.get("item").getAsString(), null);
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

		//El pack masivo, no solo el de ejemplo: es el que trae el contenido importado del SRD, y el que de
		//verdad hay que validar contra el parser real cada vez que crece un lote.
		JsonArray bulk = readArray("spells", "spells.json");
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (JsonElement el : bulk) {
			JsonObject json = el.getAsJsonObject();
			String id = json.get("id").getAsString();
			assertTrue(ids.add(id), "id de hechizo duplicado en spells.json: " + id);
			//parse() SIEMPRE (es lo que valida el esquema), pero register() solo si el id es nuevo:
			//NamedRegistry.register avisa por el logger de DndsheetsMod al detectar un duplicado, y eso
			//inicializa el canal de red de Forge, que fuera del juego no existe. Ejemplo.json y spells.json
			//comparten ids a propósito, asi que aqui siempre habria duplicados.
			SpellRegistry.Spell parsed = SpellRegistry.parse(json);
			if (SpellRegistry.get(id) == null) SpellRegistry.register(parsed);
		}
		assertTrue(bulk.size() >= 83, "spells.json debería traer al menos los 83 hechizos importados del SRD, trae " + bulk.size());

		//Un muro no se resuelve al lanzarlo: se coloca y daña por asaltos (ver WallManager). Si alguien lo
		//degradara a esfera, explotaría una vez en la cara del lanzador en vez de quedarse ahí.
		SpellRegistry.Spell wall = SpellRegistry.get("dndsheets:wall_of_fire");
		assertTrue(wall != null && wall.isWall(), "wall_of_fire debería ser un muro");
		assertTrue(wall.concentration(), "un muro es de concentración: perderla tiene que apagarlo");
		assertTrue(wall.aoeRadius() > 0, "un muro necesita longitud");

		//Formas de área: Relámpago es una línea y Cono de Frío un cono, y las dos nacen en el lanzador.
		//Si alguien las degradara a esfera "para simplificar", golpearían al grupo propio sin fallar nada.
		SpellRegistry.Spell bolt = SpellRegistry.get("dndsheets:lightning_bolt");
		assertTrue(bolt != null && "line".equals(bolt.aoeShape()), "lightning_bolt debería ser una línea");
		assertTrue(bolt.originatesAtCaster(), "una línea tiene que nacer en el lanzador");
		SpellRegistry.Spell cone = SpellRegistry.get("dndsheets:cone_of_cold");
		assertTrue(cone != null && "cone".equals(cone.aoeShape()), "cone_of_cold debería ser un cono");
		//Bola de Fuego sigue siendo esférica y NO nace en el lanzador: es el caso que no debía cambiar.
		assertTrue(!SpellRegistry.get("dndsheets:fireball").originatesAtCaster(),
			"la Bola de Fuego explota donde impacta, no en la cara del lanzador");

		//Hechizo de solo condición: sin daño ninguno. Antes ni se podía escribir — "dice" era obligatorio y
		//el efecto solo se aplicaba si había daño, así que Inmovilizar Persona no habría hecho nada.
		SpellRegistry.Spell hold = SpellRegistry.get("dndsheets:hold_person");
		assertTrue(hold != null && "save".equals(hold.mode()), "hold_person debería ser un hechizo de salvación");
		assertTrue(hold.appliesEffect() && "paralizado".equals(hold.effectName()), "hold_person debería aplicar paralizado");
		assertTrue("0".equals(hold.dice()), "hold_person no hace daño: su dado debería ser 0");
		assertTrue(Condition.fromLabel(hold.effectName()) == Condition.PARALIZADO,
			"el nombre del efecto tiene que coincidir EXACTO con una condición o el motor lo trata como daño con nombre libre");

		//Un nombre de efecto libre ("rayo de luna", "fuego") sigue siendo válido: es un temporizador de daño,
		//y el mod lo soporta a propósito. Lo que NO puede pasar es un efecto sin daño Y sin condición real:
		//eso no hace absolutamente nada (TurnManager.tickEffects se salta los ticks de 0), y la causa típica
		//sería una errata en el nombre de una condición — "paralizados", "paralized" — que degrada el hechizo
		//en silencio en vez de fallar.
		for (JsonElement el : bulk) {
			JsonObject json = el.getAsJsonObject();
			if (!json.has("appliesEffect")) continue;
			JsonObject effect = json.getAsJsonObject("appliesEffect");
			String effectName = effect.get("name").getAsString();
			boolean noDamage = "0".equals(effect.get("dice").getAsString());
			if (!noDamage) continue; //Efecto de daño con nombre libre: correcto, no hay nada que comprobar.
			assertTrue(Condition.fromLabel(effectName) != null,
				"el efecto \"" + effectName + "\" de " + json.get("id").getAsString() + " no hace daño y no es una condición real: no haría nada");
		}
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

		//Resistencias del monstruo: campo opcional, así que hay que comprobar las dos ramas — que se lea
		//cuando está y que no estorbe cuando no.
		MonsterRegistry.MonsterStatBlock fireMage = MonsterRegistry.get("dndsheets:fire_mage");
		assertTrue(fireMage != null && "resistant".equals(fireMage.damageAffinities().get("fuego")), "el mago de fuego debería resistir el fuego");
		assertTrue("vulnerable".equals(fireMage.damageAffinities().get("frio")), "el mago de fuego debería ser vulnerable al frío");
		assertTrue(spider.damageAffinities().isEmpty(), "un monstruo sin damageAffinities debería quedar con el mapa vacío, no null");

		//El bestiario masivo importado del SRD, con el parser real. Es donde de verdad entra el contenido.
		JsonArray bestiary = readArray("monsters", "monsters.json");
		java.util.Set<String> monsterIds = new java.util.HashSet<>();
		java.util.Set<String> damageTypes = java.util.Set.of("fisico", "cortante", "perforante", "contundente",
			"fuego", "frio", "rayo", "acido", "veneno", "psiquico", "radiante", "necrotico", "fuerza", "trueno");
		for (JsonElement el : bestiary) {
			JsonObject json = el.getAsJsonObject();
			String id = json.get("id").getAsString();
			assertTrue(monsterIds.add(id), "id de monstruo duplicado en monsters.json: " + id);
			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.parse(json); //Ver checkSpells: parse siempre, register solo si es nuevo.
			if (MonsterRegistry.get(id) == null) MonsterRegistry.register(block);

			//Un monstruo sin ataques se puede invocar pero no puede hacer nada: no es contenido, es un adorno.
			assertTrue(!block.attacks().isEmpty(), id + " no tiene ningún ataque");
			assertTrue(block.maxHp() > 0 && block.ac() > 0, id + " debería tener PG y CA positivos");

			//Un tipo de daño mal escrito no falla en ningún sitio: simplemente deja de coincidir con las
			//resistencias, y el monstruo recibe daño completo de algo a lo que debería ser inmune.
			for (MonsterRegistry.MonsterAttack attack : block.attacks()) {
				assertTrue(damageTypes.contains(attack.damageType()),
					"tipo de daño desconocido \"" + attack.damageType() + "\" en el ataque " + attack.name() + " de " + id);
			}
			for (Map<String, String> affinities : java.util.List.of(block.damageAffinities(), block.nonmagicalAffinities())) {
				for (Map.Entry<String, String> affinity : affinities.entrySet()) {
					assertTrue(damageTypes.contains(affinity.getKey()),
						"afinidad sobre un tipo de daño desconocido \"" + affinity.getKey() + "\" en " + id);
					assertTrue(java.util.List.of("resistant", "vulnerable", "immune").contains(affinity.getValue()),
						"afinidad desconocida \"" + affinity.getValue() + "\" en " + id);
				}
			}
		}
		assertTrue(bestiary.size() >= 330, "monsters.json debería traer al menos los 330 de los lotes 3 y 4 del SRD, trae " + bestiary.size());

		//Resistencia condicional: el hombre rata es inmune al daño físico NO mágico, y normal frente al
		//mágico. Es la mitad del bestiario de VD medio, así que conviene fijar que las dos ramas difieren
		//de verdad y no que una tapa a la otra.
		MonsterRegistry.MonsterStatBlock wererat = MonsterRegistry.get("dndsheets:wererat_human");
		assertTrue(wererat != null, "wererat_human debería haberse registrado");
		assertTrue(wererat.damageAffinities().isEmpty(), "el hombre rata no tiene resistencias incondicionales");
		assertTrue("immune".equals(wererat.nonmagicalAffinities().get("cortante")),
			"el hombre rata debería ser inmune al daño cortante no mágico");
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

	//No cubierto hasta ahora pese a no depender del runtime de Forge (mismo perfil que los *Registry de
	//arriba) — ver AUDIT_REPORT_2026.md F26. "1d1" tira siempre 1: da un total determinista sin depender
	//de aleatoriedad para poder comprobar la sustitución de $str/$prof/$hprof con un assert exacto.
	private static void checkDice() {
		JsonObject sheet = new JsonObject();
		sheet.addProperty("strength", "16"); //modificador +3
		sheet.addProperty("proficiencyBonus", "4");

		DiceManager.RollOutcome flat = DiceManager.roll(sheet, "1d1 + 5");
		assertTrue(flat.result() != null && flat.result().getValue() == 6, "1d1 + 5 debería dar total 6");

		DiceManager.RollOutcome withStr = DiceManager.roll(sheet, "1d1 + $str");
		assertTrue(withStr.result() != null && withStr.result().getValue() == 4, "1d1 + $str (Fue 16) debería dar total 4");

		DiceManager.RollOutcome withProf = DiceManager.roll(sheet, "1d1 + $prof");
		assertTrue(withProf.result() != null && withProf.result().getValue() == 5, "1d1 + $prof (comp. 4) debería dar total 5");

		DiceManager.RollOutcome withHalfProf = DiceManager.roll(sheet, "1d1 + $hprof");
		assertTrue(withHalfProf.result() != null && withHalfProf.result().getValue() == 3, "1d1 + $hprof (comp. 4 / 2) debería dar total 3");

		//Bug de precedencia de la librería de dados de terceros (ver DiceManager.wrapDiceTermsInParens y el
		//README, "Known Bugs"): un segundo grupo de dados con conteo explícito después de un "+" fallaba al
		//parsear entero. "1d1 + 1d1 + 3" ejercita justo ese caso con total determinista (1+1+3=5).
		DiceManager.RollOutcome multiGroup = DiceManager.roll(sheet, "1d1 + 1d1 + 3");
		assertTrue(multiGroup.result() != null && multiGroup.result().getValue() == 5, "1d1 + 1d1 + 3 debería dar total 5 (antes fallaba al parsear el segundo grupo)");

		//Techo defensivo de hasAbsurdDiceCount: un conteo de dados absurdo se rechaza (result null) en vez
		//de intentar tirarlo y agotar memoria.
		DiceManager.RollOutcome absurd = DiceManager.roll(sheet, "999999d6");
		assertTrue(absurd.result() == null, "999999d6 debería rechazarse por conteo de dados absurdo");

		//Sintaxis inválida: no debe propagar una excepción (catch (Throwable) en DiceManager.roll), solo
		//devolver un resultado vacío.
		DiceManager.RollOutcome invalid = DiceManager.roll(sheet, "esto no es una expresión de dados $$$");
		assertTrue(invalid.result() == null, "una expresión inválida debería devolver result() == null, no lanzar");
	}

	//Única lógica con ramas reales de la feature de mazmorras (ver DungeonManager): agrupar por pool,
	//acotar weight a [1,150] (StructureTemplatePool.DIRECT_CODEC lo exige, un valor fuera de rango tumba
	//el parseo del archivo entero) y saltar piezas con structureId corrupto. El resto (copiar el .nbt,
	///reload, JigsawPlacement.generateJigsaw) son llamadas finas a APIs vanilla, no hace falta re-testearlas.
	private static void checkDungeonPools() {
		List<DungeonPieceRegistry.DungeonPiece> pieces = List.of(
			new DungeonPieceRegistry.DungeonPiece("entrance", "dndsheets_dm:rooms/entrance", "start", 200, ""),
			new DungeonPieceRegistry.DungeonPiece("corridor1", "dndsheets_dm:rooms/corridor1", "corridor", 3, ""),
			new DungeonPieceRegistry.DungeonPiece("corridor2", "dndsheets_dm:rooms/corridor2", "corridor", 0, ""),
			new DungeonPieceRegistry.DungeonPiece("broken", "esto no es un id valido", "corridor", 5, "")
		);

		Map<String, JsonObject> pools = DungeonManager.buildPoolJsons(pieces);
		assertTrue(pools.size() == 2, "buildPoolJsons debería producir 2 pools (start, corridor), dio " + pools.size());

		JsonObject start = pools.get("start");
		assertTrue(start != null && "minecraft:empty".equals(start.get("fallback").getAsString()), "el pool start debería tener fallback=minecraft:empty");
		JsonArray startElements = start.getAsJsonArray("elements");
		assertTrue(startElements.size() == 1, "el pool start debería tener 1 elemento");
		JsonObject startWrapper = startElements.get(0).getAsJsonObject();
		assertTrue(startWrapper.get("weight").getAsInt() == 150, "el peso 200 debería acotarse a 150");
		JsonObject startElement = startWrapper.getAsJsonObject("element");
		assertTrue("minecraft:single_pool_element".equals(startElement.get("element_type").getAsString()), "element_type debería ser minecraft:single_pool_element");
		assertTrue("dndsheets_dm:rooms/entrance".equals(startElement.get("location").getAsString()), "location debería ser el structureId de la pieza");
		assertTrue("minecraft:empty".equals(startElement.get("processors").getAsString()), "processors debería ser minecraft:empty");
		assertTrue("rigid".equals(startElement.get("projection").getAsString()), "projection debería ser rigid");

		JsonObject corridor = pools.get("corridor");
		JsonArray corridorElements = corridor.getAsJsonArray("elements");
		//"broken" tiene un structureId inválido y se salta: solo corridor1 y corridor2 quedan.
		assertTrue(corridorElements.size() == 2, "el pool corridor debería tener 2 elementos (la pieza con structureId inválido se salta), dio " + corridorElements.size());
		boolean foundClampedZero = false;
		for (JsonElement el : corridorElements) {
			if (el.getAsJsonObject().get("weight").getAsInt() == 1) foundClampedZero = true;
		}
		assertTrue(foundClampedZero, "el peso 0 debería acotarse a 1");
	}

	/**
	 * <p>La tabla de condiciones de 5e ({@link Condition}) y la regla de que ventaja y desventaja se anulan
	 * ({@link DiceManager#combineAdvantage}). Ninguna de las dos toca clases de Minecraft, así que se
	 * comprueban de pie aquí mismo; el resto de {@link Combatant} sí necesita una entidad real y se queda
	 * fuera. Se prueban los casos que de verdad se pueden escribir mal: los que combinan varias reglas.</p>
	 */
	private static void checkConditions() {
		//Paralizado: la condición más cargada del manual (incapacita, velocidad 0, ventaja a los atacantes,
		//crítico automático en cuerpo a cuerpo, falla salvaciones de FUE/DES). Si algún switch se queda
		//corto, se nota aquí.
		assertTrue(Condition.PARALIZADO.preventsActions(), "paralizado debería impedir actuar");
		assertTrue(Condition.PARALIZADO.preventsMovement(), "paralizado debería impedir moverse");
		assertTrue(Condition.PARALIZADO.attackersAdvantage(), "atacar a un paralizado debería dar ventaja");
		assertTrue(Condition.PARALIZADO.autoCritInMelee(), "un golpe cuerpo a cuerpo a un paralizado debería ser crítico");
		assertTrue(Condition.PARALIZADO.autoFailsStrDexSaves(), "paralizado debería fallar salvaciones de FUE/DES");

		//Invisible es la única que da ventaja propia y desventaja a quien le ataca: el caso invertido, fácil
		//de escribir al revés por copiar el switch de al lado.
		assertTrue(Condition.INVISIBLE.selfAttackAdvantage(), "invisible debería atacar con ventaja");
		assertTrue(Condition.INVISIBLE.attackersDisadvantage(), "atacar a un invisible debería dar desventaja");
		assertTrue(!Condition.INVISIBLE.attackersAdvantage(), "atacar a un invisible NO debería dar ventaja");

		//Derribado queda fuera de attackersAdvantage() a propósito: depende de la distancia y lo resuelve
		//Combatant.advantageAgainst. Si alguien lo mete en el switch "para completar la tabla", rompe el
		//caso a distancia sin que nada más lo note.
		assertTrue(!Condition.DERRIBADO.attackersAdvantage(), "derribado no debe resolverse sin saber la distancia");
		assertTrue(Condition.DERRIBADO.selfAttackDisadvantage(), "derribado debería atacar con desventaja");

		//Solo petrificado da resistencia a todo el daño en 5e.
		assertTrue(Condition.PETRIFICADO.resistsAllDamage(), "petrificado debería resistir todo el daño");
		long resisting = java.util.Arrays.stream(Condition.values()).filter(Condition::resistsAllDamage).count();
		assertTrue(resisting == 1, "solo petrificado debería resistir todo el daño, resisten " + resisting);

		//Ida y vuelta por etiqueta: es el formato que usan el JSON de la hoja, el NBT del monstruo y los
		//comandos, así que si se rompe, las condiciones dejan de sobrevivir a un reinicio en silencio.
		for (Condition condition : Condition.values()) {
			assertTrue(Condition.fromLabel(condition.label()) == condition, "ida y vuelta por etiqueta rota en " + condition);
		}
		assertTrue(Condition.fromLabel("fuego") == null, "un efecto libre como \"fuego\" no debería ser una condición");
		assertTrue(Condition.fromLabel(null) == null, "fromLabel(null) debería devolver null, no reventar");

		//La regla que más fácil se implementa mal: ventaja + desventaja no es ventaja, es una tirada normal,
		//por muchas fuentes de cada lado que haya.
		DiceManager.Advantage adv = DiceManager.Advantage.ADVANTAGE;
		DiceManager.Advantage dis = DiceManager.Advantage.DISADVANTAGE;
		DiceManager.Advantage normal = DiceManager.Advantage.NORMAL;
		assertTrue(DiceManager.combineAdvantage(adv, dis) == normal, "ventaja + desventaja debería anularse");
		assertTrue(DiceManager.combineAdvantage(adv, adv, adv, dis) == normal, "las ventajas no se acumulan para vencer a una desventaja");
		assertTrue(DiceManager.combineAdvantage(adv, normal, normal) == adv, "una sola ventaja debería mantenerse");
		assertTrue(DiceManager.combineAdvantage(dis, normal) == dis, "una sola desventaja debería mantenerse");
		assertTrue(DiceManager.combineAdvantage() == normal, "sin fuentes debería quedar una tirada normal");

		System.out.println("checkConditions: OK, las 14 condiciones de 5e y la combinación de ventaja se comportan.");
	}

	/**
	 * <p>Geometría de las formas de área ({@link SpellCastManager#inShape}). Es pura —sin mundo ni
	 * entidades— y es justo la clase de lógica que no falla en ningún sitio cuando está mal: un cono que
	 * se abre hacia atrás simplemente alcanza al grupo propio en vez de al enemigo, y eso solo se descubre
	 * en mitad de una sesión.</p>
	 */
	private static void checkAoeShapes() {
		net.minecraft.world.phys.Vec3 origin = new net.minecraft.world.phys.Vec3(0, 0, 0);
		net.minecraft.world.phys.Vec3 forward = new net.minecraft.world.phys.Vec3(1, 0, 0); //Mirando a +X.

		//--- Línea: 1 bloque de ancho (5 pies), y solo hacia delante ---
		assertTrue(SpellCastManager.inShape("line", origin, forward, 10, new net.minecraft.world.phys.Vec3(5, 0, 0)),
			"un objetivo justo sobre el eje de la línea debería entrar");
		assertTrue(SpellCastManager.inShape("line", origin, forward, 10, new net.minecraft.world.phys.Vec3(5, 0, 0.4)),
			"medio bloque de desviación sigue dentro de una línea de 5 pies");
		assertTrue(!SpellCastManager.inShape("line", origin, forward, 10, new net.minecraft.world.phys.Vec3(5, 0, 2)),
			"dos bloques de desviación quedan fuera de la línea");
		assertTrue(!SpellCastManager.inShape("line", origin, forward, 10, new net.minecraft.world.phys.Vec3(15, 0, 0)),
			"más allá del alcance no entra, aunque esté sobre la recta");
		//EL caso que motivó todo esto: nada detrás del lanzador debe recibir el efecto.
		assertTrue(!SpellCastManager.inShape("line", origin, forward, 10, new net.minecraft.world.phys.Vec3(-5, 0, 0)),
			"un aliado DETRÁS del lanzador nunca debe entrar en la línea");

		//--- Cono: tan ancho como largo en cada punto, y tampoco hacia atrás ---
		assertTrue(SpellCastManager.inShape("cone", origin, forward, 10, new net.minecraft.world.phys.Vec3(5, 0, 0)),
			"el centro del cono debería entrar");
		//A 6 bloques de distancia el cono mide ~6 de ancho, o sea ~3 a cada lado del eje.
		assertTrue(SpellCastManager.inShape("cone", origin, forward, 10, new net.minecraft.world.phys.Vec3(6, 0, 2)),
			"2 bloques de desviación a 6 de distancia siguen dentro del cono");
		assertTrue(!SpellCastManager.inShape("cone", origin, forward, 10, new net.minecraft.world.phys.Vec3(6, 0, 5)),
			"5 bloques de desviación a 6 de distancia quedan fuera");
		assertTrue(!SpellCastManager.inShape("cone", origin, forward, 10, new net.minecraft.world.phys.Vec3(-5, 0, 0)),
			"un aliado DETRÁS del lanzador nunca debe entrar en el cono");
		assertTrue(!SpellCastManager.inShape("cone", origin, forward, 10, new net.minecraft.world.phys.Vec3(20, 0, 0)),
			"más allá del alcance no entra en el cono");

		//--- Esfera: sin cambios, y SÍ alcanza hacia atrás, que es lo correcto para una explosión ---
		assertTrue(SpellCastManager.inShape("sphere", origin, forward, 10, new net.minecraft.world.phys.Vec3(-5, 0, 0)),
			"una esfera sí debería alcanzar en todas direcciones");
		assertTrue(!SpellCastManager.inShape("sphere", origin, forward, 10, new net.minecraft.world.phys.Vec3(0, 0, 15)),
			"fuera del radio no entra");
		//Una forma desconocida cae a esfera, igual que hace el parser: nunca deja un hechizo sin efecto.
		assertTrue(SpellCastManager.inShape("loquesea", origin, forward, 10, new net.minecraft.world.phys.Vec3(3, 0, 0)),
			"una forma desconocida debería comportarse como esfera, no dejar el hechizo inerte");

		//--- Muro: una SUPERFICIE, no un cilindro ---
		//La diferencia importa: si la distancia al eje se midiera en 3D como en la línea, saldría un tubo y
		//alguien de pie encima o debajo del muro se llevaría el daño sin haberlo tocado nunca.
		assertTrue(SpellCastManager.inShape("wall", origin, forward, 12, new net.minecraft.world.phys.Vec3(6, 0, 0)),
			"pegado al muro a media longitud debería contar");
		assertTrue(SpellCastManager.inShape("wall", origin, forward, 12, new net.minecraft.world.phys.Vec3(6, 3, 0)),
			"3 bloques de altura siguen dentro de un muro de 20 pies");
		assertTrue(!SpellCastManager.inShape("wall", origin, forward, 12, new net.minecraft.world.phys.Vec3(6, 8, 0)),
			"volar por encima del muro debería librarte");
		assertTrue(!SpellCastManager.inShape("wall", origin, forward, 12, new net.minecraft.world.phys.Vec3(6, -3, 0)),
			"estar por debajo del muro también debería librarte");
		assertTrue(!SpellCastManager.inShape("wall", origin, forward, 12, new net.minecraft.world.phys.Vec3(6, 0, 3)),
			"3 bloques a un lado del muro es estar fuera de él");
		assertTrue(!SpellCastManager.inShape("wall", origin, forward, 12, new net.minecraft.world.phys.Vec3(-6, 0, 0)),
			"el muro no se extiende hacia atrás del lanzador");

		System.out.println("checkAoeShapes: OK, línea y cono salen del lanzador, y el muro es superficie y no cilindro.");
	}

	/**
	 * <p>Combatiente falso, en memoria, para probar los métodos por defecto de {@link Combatant} sin un
	 * mundo de Minecraft: guardar/leer condiciones, la salvación con fallo automático y la ventaja según
	 * el estado del objetivo. Los métodos que sí necesitan una entidad real ({@code seesSourceOf} con
	 * fuente conocida, {@code cannotAttack}, {@code takeDamage}) se quedan fuera — se prueban en el juego.</p>
	 */
	private static final class FakeCombatant implements Combatant {
		private final Map<Condition, Integer> conditions = new java.util.EnumMap<>(Condition.class);
		private final int abilityMod;

		FakeCombatant(int abilityMod) { this.abilityMod = abilityMod; }

		@Override public net.minecraft.world.entity.Entity entity() { return null; }
		@Override public String name() { return "falso"; }
		@Override public int armorClass() { return 10; }
		@Override public int currentHp() { return 10; }
		@Override public int maxHp() { return 10; }
		@Override public int abilityModifier(String ability) { return abilityMod; }
		@Override public int proficiencyBonus() { return 2; }
		@Override public double damageMultiplier(String damageType, boolean magical) { return 1.0; }
		private int temporary;
		@Override public void applyRealDamage(int amount) { }
		@Override public int temporaryHp() { return temporary; }
		@Override public void setTemporaryHp(int amount) { temporary = Math.max(0, amount); }
		@Override public boolean isDefeated() { return false; }
		@Override public Map<Condition, Integer> conditionSources() { return conditions; }
		@Override public void setConditionSources(Map<Condition, Integer> sources) {
			conditions.clear();
			conditions.putAll(sources);
		}
	}

	/**
	 * <p>Las reglas de {@link Combatant} que no necesitan mundo. Las tres cosas que de verdad se pueden
	 * romper en silencio: el formato con el que las condiciones llegan al disco, cuándo una salvación falla
	 * sola, y que derribado cambie de signo según la distancia.</p>
	 */
	private static void checkCombatantRules() {
		//Formato en disco: "etiqueta" o "etiqueta@idFuente". Si esto se rompe, las condiciones dejan de
		//sobrevivir a un reinicio sin que falle nada visible.
		Map<Condition, Integer> parsed = new java.util.EnumMap<>(Condition.class);
		Combatant.parseEntry("derribado", parsed);
		Combatant.parseEntry("hechizado@42", parsed);
		Combatant.parseEntry("noexisto", parsed);       //Efecto de nombre libre: se ignora, no revienta.
		Combatant.parseEntry("apresado@basura", parsed); //Id manipulado a mano: entra sin fuente.
		assertTrue(parsed.size() == 3, "deberían haber entrado 3 condiciones, entraron " + parsed.size());
		assertTrue(parsed.get(Condition.DERRIBADO) == Combatant.NO_SOURCE, "derribado sin sufijo debería quedar sin fuente");
		assertTrue(parsed.get(Condition.HECHIZADO) == 42, "hechizado@42 debería recordar la fuente 42");
		assertTrue(parsed.get(Condition.APRESADO) == Combatant.NO_SOURCE, "un id no numérico debería degradar a sin fuente, no romper la carga");

		assertTrue("derribado".equals(Combatant.formatEntry(Map.entry(Condition.DERRIBADO, Combatant.NO_SOURCE))),
			"sin fuente no debería escribirse ningún sufijo");
		assertTrue("hechizado@42".equals(Combatant.formatEntry(Map.entry(Condition.HECHIZADO, 42))),
			"con fuente debería escribirse etiqueta@id");

		//Alta y baja de condiciones a través de los métodos por defecto.
		FakeCombatant combatant = new FakeCombatant(3);
		assertTrue(combatant.conditions().isEmpty(), "un combatiente nuevo no debería tener condiciones");
		combatant.addCondition(Condition.PARALIZADO);
		assertTrue(combatant.hasCondition(Condition.PARALIZADO), "paralizado debería quedar puesto");
		assertTrue(combatant.cannotAct(), "un paralizado no debería poder actuar");
		assertTrue(combatant.cannotMove(), "un paralizado no debería poder moverse");

		//Salvación con fallo automático: paralizado falla FUE y DES, pero NO el resto.
		Combatant.SaveRoll dexSave = combatant.rollSave("dex");
		assertTrue(dexSave.blockedBy() == Condition.PARALIZADO, "un paralizado debería fallar sola la salvación de DES");
		assertTrue(!dexSave.succeeds(1), "una salvación auto-fallada no debería superar ni una CD de 1");
		assertTrue(combatant.rollSave("dexterity").blockedBy() == Condition.PARALIZADO, "el nombre largo debería valer igual que el corto");
		Combatant.SaveRoll conSave = combatant.rollSave("con");
		assertTrue(conSave.blockedBy() == null, "paralizado NO debería hacer fallar sola la salvación de CON");
		assertTrue(conSave.formatted() != null, "una salvación que sí se tira debería traer texto para el chat");

		//Derribado: la única condición que cambia de signo según la distancia.
		FakeCombatant prone = new FakeCombatant(0);
		prone.addCondition(Condition.DERRIBADO);
		assertTrue(prone.advantageAgainst(true) == DiceManager.Advantage.ADVANTAGE, "atacar en cuerpo a cuerpo a un derribado debería dar ventaja");
		assertTrue(prone.advantageAgainst(false) == DiceManager.Advantage.DISADVANTAGE, "dispararle a un derribado debería dar desventaja");
		assertTrue(prone.ownAttackAdvantage() == DiceManager.Advantage.DISADVANTAGE, "un derribado debería atacar con desventaja");

		//Invisible y derribado a la vez en cuerpo a cuerpo: ventaja y desventaja de fuentes distintas, se anulan.
		prone.addCondition(Condition.INVISIBLE);
		assertTrue(prone.advantageAgainst(true) == DiceManager.Advantage.NORMAL,
			"derribado (ventaja) e invisible (desventaja) deberían anularse para quien ataca");

		prone.removeCondition(Condition.DERRIBADO);
		assertTrue(!prone.hasCondition(Condition.DERRIBADO), "quitar una condición debería quitarla de verdad");
		assertTrue(prone.hasCondition(Condition.INVISIBLE), "quitar una condición no debería llevarse las demás por delante");

		//Petrificado da resistencia a todo el daño encima de las afinidades declaradas.
		FakeCombatant petrified = new FakeCombatant(0);
		assertTrue(petrified.effectiveDamageMultiplier("fuego", false) == 1.0, "sin condiciones ni afinidades, el daño va entero");
		petrified.addCondition(Condition.PETRIFICADO);
		assertTrue(petrified.effectiveDamageMultiplier("fuego", false) == 0.5, "petrificado debería resistir todo el daño");

		//Resistencia condicional a lo no mágico, sobre el Combatant real de un monstruo. Se puede construir
		//con entidad null porque damageMultiplier solo mira el bloque de estadísticas — y esa es justo la
		//razón por la que la lógica vive ahí y no repartida por las rutas de combate.
		MonsterRegistry.MonsterStatBlock conditional = new MonsterRegistry.MonsterStatBlock(
			"test:licantropo", "Licantropo", "minecraft:zombie", 12, 30,
			Map.of("str", 10, "dex", 10, "con", 10, "int", 10, "wis", 10, "cha", 10), 2,
			List.of(), List.of(),
			Map.of("fuego", "vulnerable"),          //Incondicional.
			Map.of("cortante", "immune"));          //Solo frente a armas no mágicas.
		Combatant beast = new Combatant.MonsterCombatant(null, conditional);
		assertTrue(beast.damageMultiplier("cortante", false) == 0.0, "cortante no mágico debería rebotar en el licántropo");
		assertTrue(beast.damageMultiplier("cortante", true) == 1.0, "cortante mágico debería atravesarlo entero");
		assertTrue(beast.damageMultiplier("fuego", true) == 2.0, "una afinidad incondicional aplica sea mágico o no");
		assertTrue(beast.damageMultiplier("fuego", false) == 2.0, "y tambien cuando no lo es");
		assertTrue(beast.damageMultiplier("veneno", false) == 1.0, "un tipo sin afinidad declarada pasa entero");

		//--- Puntos de golpe temporales ---
		//La absorción vive en el método por defecto de la interfaz, así que basta un combatiente falso para
		//fijarla: es la misma regla para jugador, PNJ y monstruo, y esa es justo la razón de que esté ahí.
		FakeCombatant guarded = new FakeCombatant(0);
		assertTrue(guarded.temporaryHp() == 0, "sin conceder nada no debería haber PG temporales");
		guarded.grantTemporaryHp(10);
		assertTrue(guarded.temporaryHp() == 10, "conceder 10 debería dejar 10");

		//No se apilan: un montón nuevo menor no baja el que ya había, y uno mayor lo reemplaza.
		guarded.grantTemporaryHp(4);
		assertTrue(guarded.temporaryHp() == 10, "un montón menor no debería reducir el que ya había");
		guarded.grantTemporaryHp(15);
		assertTrue(guarded.temporaryHp() == 15, "un montón mayor sí debería reemplazarlo");

		//Absorción parcial: 15 temporales contra 20 de daño dejan 5 para los PG reales, y la reserva a 0.
		assertTrue(guarded.absorbWithTemporaryHp(20) == 5, "deberían pasar 5 a los PG reales");
		assertTrue(guarded.temporaryHp() == 0, "la reserva debería quedar agotada");

		//Absorción total: el golpe entero se lo come la reserva y los PG reales ni se tocan.
		guarded.grantTemporaryHp(12);
		assertTrue(guarded.absorbWithTemporaryHp(5) == 0, "un golpe menor que la reserva no debería llegar a los PG");
		assertTrue(guarded.temporaryHp() == 7, "la reserva debería bajar exactamente lo absorbido");
		assertTrue(guarded.absorbWithTemporaryHp(0) == 0, "un daño de 0 no debería gastar reserva");
		assertTrue(guarded.temporaryHp() == 7, "y la reserva sigue intacta");

		//--- Buff de arma con duración ---
		//A diferencia de Castigo Divino NO se consume por golpe: dura asaltos. Si alguien lo hiciera
		//consumible, Favor Divino pasaría de "1 minuto" a "un golpe" sin que fallara nada.
		JsonObject buffed = new JsonObject();
		assertTrue(WeaponBuffManager.active(buffed) == null, "una hoja sin buff no debería tener ninguno activo");
		WeaponBuffManager.grant(buffed, "Favor Divino", "1d4", "radiante", 2);
		assertTrue(WeaponBuffManager.active(buffed) != null, "el buff recién concedido debería estar activo");
		assertTrue("1d4".equals(WeaponBuffManager.active(buffed).dice()), "debería recordar sus dados");
		assertTrue(WeaponBuffManager.active(buffed) != null, "consultarlo dos veces no debería consumirlo");
		assertTrue(!WeaponBuffManager.tickRound(buffed), "tras el primer asalto todavía no expira");
		assertTrue(WeaponBuffManager.active(buffed) != null, "y sigue activo");
		assertTrue(WeaponBuffManager.tickRound(buffed), "al agotarse los asaltos debería expirar");
		assertTrue(WeaponBuffManager.active(buffed) == null, "y dejar de estar activo");

		//Vocabulario de afinidades, compartido ahora entre la hoja del jugador y el bloque de monstruo.
		assertTrue(DamageTypes.multiplierForLabel("resistant") == 0.5, "resistant debería ser 0.5");
		assertTrue(DamageTypes.multiplierForLabel("vulnerable") == 2.0, "vulnerable debería ser 2.0");
		assertTrue(DamageTypes.multiplierForLabel("immune") == 0.0, "immune debería ser 0.0");
		assertTrue(DamageTypes.multiplierForLabel(null) == 1.0, "sin afinidad declarada, el daño va entero");
		assertTrue(DamageTypes.multiplierForLabel("cualquier_cosa") == 1.0, "una afinidad desconocida no debería cambiar el daño");

		System.out.println("checkCombatantRules: OK, persistencia de condiciones, salvaciones y ventaja por estado se comportan.");
	}

	private static JsonObject characterSheet(String ownerUuid, boolean active) {
		JsonObject sheet = new JsonObject();
		if (ownerUuid != null) sheet.addProperty("ownerUuid", ownerUuid);
		sheet.addProperty("active", active);
		return sheet;
	}

	/**
	 * <p>Las reglas de "de quién es este personaje y cuál lleva puesto" ({@link CharacterRules}). Lo que
	 * hay que fijar aquí es sobre todo la <b>retrocompatibilidad</b>: una hoja guardada antes de que
	 * existieran los personajes no tiene {@code ownerUuid} ni {@code active}, y si dejara de reconocerse
	 * como suya, un jugador perdería su personaje de siempre sin que fallara nada visible.</p>
	 */
	private static void checkCharacterRules() {
		String alice = "11111111-1111-1111-1111-111111111111";
		String bob = "22222222-2222-2222-2222-222222222222";

		//Hoja legacy: sin ownerUuid, su dueño es su propio id (que era el UUID del jugador).
		assertTrue(alice.equals(CharacterRules.ownerOf(alice, new JsonObject())),
			"una hoja sin ownerUuid debería seguir siendo de quien da nombre a su archivo");
		assertTrue(bob.equals(CharacterRules.ownerOf(alice + "-2", characterSheet(bob, false))),
			"con ownerUuid, manda ownerUuid y no el id");
		assertTrue(CharacterRules.ownerOf("npc-guardia", characterSheet("", false)) == null,
			"ownerUuid vacío significa PNJ, sin dueño");

		Map<String, JsonObject> sheets = new java.util.HashMap<>();
		sheets.put(alice, new JsonObject());                       //Legacy de Alice, sin campos nuevos.
		sheets.put(alice + "-2", characterSheet(alice, true));      //Segundo personaje de Alice, puesto.
		sheets.put(bob, new JsonObject());                          //Legacy de Bob.
		sheets.put("npc-guardia", characterSheet("", false));       //PNJ del DM.

		List<String> aliceChars = CharacterRules.ownedBy(sheets, alice);
		assertTrue(aliceChars.size() == 2, "Alice debería tener 2 personajes, tiene " + aliceChars.size());
		assertTrue(aliceChars.get(0).equals(alice) && aliceChars.get(1).equals(alice + "-2"), "los personajes deberían salir en orden estable");
		assertTrue(CharacterRules.ownedBy(sheets, bob).size() == 1, "Bob debería tener solo su hoja de siempre");

		Map<String, String> active = CharacterRules.buildActive(sheets);
		assertTrue((alice + "-2").equals(active.get(alice)), "Alice debería estar llevando su segundo personaje");
		assertTrue(active.get(bob) == null, "Bob no marcó ninguna activa: debe caer al fallback, no aparecer aquí");
		assertTrue(!active.containsValue("npc-guardia"), "un PNJ no lo lleva puesto nadie");

		//Dos hojas activas por edición manual del JSON: el desempate tiene que ser determinista, o el
		//jugador se encontraría un personaje distinto según el arranque.
		Map<String, JsonObject> conflicted = new java.util.HashMap<>();
		conflicted.put(alice + "-3", characterSheet(alice, true));
		conflicted.put(alice + "-2", characterSheet(alice, true));
		assertTrue((alice + "-2").equals(CharacterRules.buildActive(conflicted).get(alice)),
			"con dos hojas activas debería ganar siempre la de id menor");

		//Ids nuevos: no deben chocar con los que ya existen.
		assertTrue((alice + "-3").equals(CharacterRules.nextCharacterId(sheets.keySet(), alice)),
			"el siguiente id de Alice debería saltarse el -2 que ya existe");
		assertTrue((bob + "-2").equals(CharacterRules.nextCharacterId(sheets.keySet(), bob)),
			"el primer personaje extra de Bob debería ser -2");

		assertTrue("npc-capitan-de-la-guardia".equals(CharacterRules.npcIdFor(Set.of(), "Capitán de la Guardia")),
			"el id de PNJ debería salir del nombre en minúsculas y con guiones, dio " + CharacterRules.npcIdFor(Set.of(), "Capitán de la Guardia"));
		assertTrue("npc-guardia-2".equals(CharacterRules.npcIdFor(sheets.keySet(), "Guardia")),
			"un PNJ con nombre repetido debería numerarse en vez de pisar al anterior");
		assertTrue("npc-pnj".equals(CharacterRules.npcIdFor(Set.of(), "白鬼")),
			"un nombre sin caracteres latinos no debería dar un id vacío");

		//--- PG máximos por clase/nivel/Constitución (regla de media del SRD) ---
		//Vive en CharacterRules y no en SheetLoader precisamente para poder comprobarse aquí: SheetLoader
		//resuelve FMLPaths al inicializarse y ni siquiera carga fuera de una instancia de Forge.
		//OJO: aquí el dado de golpe es SIEMPRE d8. Config.hitDiceByClass solo se llena al parsear el .toml,
		//que no se carga fuera del juego, así que Config.hitDieFor cae a su valor por defecto documentado
		//(8, el más común en 5e). Eso es justo lo que conviene fijar: la forma de la fórmula y el fallback.
		JsonObject hero = new JsonObject();
		hero.addProperty("characterClass", "fighter");
		hero.addProperty("constitution", "14"); //+2

		//Nivel 1 = dado completo + mod → 8 + 2.
		assertTrue(CharacterRules.maxHitPointsFor(hero, 1) == 10,
			"a d8 con CON 14 deberían salir 10 PG a nivel 1, dio " + CharacterRules.maxHitPointsFor(hero, 1));
		//Cada nivel siguiente suma media+1 (5) + mod (2) = 7.
		assertTrue(CharacterRules.maxHitPointsFor(hero, 2) == 17,
			"debería subir a 17 PG a nivel 2, dio " + CharacterRules.maxHitPointsFor(hero, 2));
		assertTrue(CharacterRules.maxHitPointsFor(hero, 3) == 24,
			"debería escalar de forma lineal por nivel, dio " + CharacterRules.maxHitPointsFor(hero, 3));
		//Nivel 0 o negativo se trata como 1: en 5e ningún personaje es de nivel 0.
		assertTrue(CharacterRules.maxHitPointsFor(hero, 0) == 10, "el nivel 0 debería tratarse como nivel 1");

		//Constitución penosa: nunca menos de 1 PG por nivel, aunque el modificador sea muy negativo.
		JsonObject frail = new JsonObject();
		frail.addProperty("characterClass", "wizard");
		frail.addProperty("constitution", "1"); //-5, peor que la media de cualquier dado
		assertTrue(CharacterRules.maxHitPointsFor(frail, 5) >= 5,
			"cada nivel debería aportar al menos 1 PG, dio " + CharacterRules.maxHitPointsFor(frail, 5));
		assertTrue(CharacterRules.maxHitPointsFor(frail, 1) >= 1, "los PG máximos nunca deberían quedar por debajo de 1");

		//Hoja corrupta o vacía: valores por defecto, no excepción. Una hoja vieja puede tener cualquier cosa.
		assertTrue(CharacterRules.maxHitPointsFor(new JsonObject(), 1) >= 1, "una hoja vacía no debería reventar el cálculo de PG");
		assertTrue(CharacterRules.maxHitPointsFor(null, 3) >= 1, "una hoja null tampoco debería reventar");

		//Nivel de una ficha sin jugador detrás: 1 por defecto, nunca 0.
		assertTrue(CharacterRules.levelOf(new JsonObject()) == 1, "una ficha sin nivel fijado debería ser de nivel 1");
		assertTrue(CharacterRules.levelOf(null) == 1, "una hoja null debería dar nivel 1, no reventar");
		JsonObject leveled = new JsonObject();
		leveled.addProperty("characterLevel", 7);
		assertTrue(CharacterRules.levelOf(leveled) == 7, "debería respetar el nivel fijado por el DM");

		System.out.println("checkCharacterRules: OK, personajes múltiples, PNJ, hojas antiguas y PG por nivel se comportan.");
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
