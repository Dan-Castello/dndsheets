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
import java.util.stream.Stream;

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
		checkMagicItems();
		checkTraits(); //Antes de checkPresets(): el preset de monje concede este rasgo por id.
		checkPresets();
		checkDice();
		checkDungeonPools();
		checkConditions();
		checkAoeShapes();
		checkCombatantRules();
		checkCharacterRules();
		checkSpellSlots();
		checkUpcasting();
		checkDefaultsRefresh();
		checkTabTextures();
		checkInteractHandlers();
		checkParchmentTextHasNoShadow();

		System.out.println("JsonContentSelfTest: OK, los 5 JSON de ejemplo parsean con los registros reales.");
	}

	/**
	 * <p>Las pestañas de la hoja son {@code ImageButton}, y esos NO tienen dos estados sino tres apilados
	 * en vertical: normal en v=0, hover en v=yDiffTex y <b>deshabilitado</b> en v=yDiffTex*2
	 * ({@code AbstractWidget.renderTexture}). Aquí importa más de lo normal porque
	 * {@code CharacterSheetScreen.updateTabs} marca la pestaña SELECCIONADA con {@code active = false}
	 * para que no se pueda pulsar la que ya estás viendo: la pestaña abierta se dibuja siempre con esa
	 * tercera fila.</p>
	 *
	 * <p>Los PNG que venían de MCreator solo traían dos filas, así que la seleccionada muestreaba fuera de
	 * la imagen y salía plana. No falla, no avisa y no se nota mirando el PNG — de ahí esta comprobación.
	 * Los PNG los genera {@code tools/make_tab_textures.py}.</p>
	 */
	/**
	 * <p>Un clic derecho llega como DOS eventos, uno por mano: el cliente recorre
	 * {@code InteractionHand.values()} y reintenta con la otra mano si la primera no consume nada
	 * ({@code Minecraft.startUseItem}). Un manejador de {@code PlayerInteractEvent} que pregunte por
	 * {@code getMainHandItem()} o {@code getOffhandItem()} responde que sí en LAS DOS pasadas, así que
	 * hace su trabajo dos veces por clic. Se ve como mensajes de chat duplicados; lo que de verdad pasa
	 * es que se ejecuta el manejador entero dos veces.</p>
	 *
	 * <p>Lo correcto es {@code event.getItemStack()}, que es el objeto de la mano de ESE evento — sigue
	 * valiendo llevar la herramienta en la secundaria. Esto ya se arregló una vez en
	 * {@code DungeonToolManager} y volvió a aparecer en otros cuatro manejadores, de ahí la comprobación.</p>
	 */
	/**
	 * <p>La sombra de Minecraft es una copia del texto un píxel abajo y a la derecha, en el color
	 * oscurecido a la cuarta parte. Con texto claro sobre fondo oscuro eso es relieve y ayuda a leer. Con
	 * <b>tinta oscura sobre pergamino</b> —las etiquetas de la hoja y el rótulo de la pestaña abierta— la
	 * copia queda tan oscura como el original: la palabra se lee escrita dos veces.</p>
	 *
	 * <p>El problema es que las dos formas cómodas de centrar texto encienden la sombra sin dejar
	 * apagarla: {@code GuiGraphics.drawCenteredString} llama a {@code drawString} con {@code shadow=true}
	 * fijo, y {@code AbstractWidget.renderString} acaba en esa misma llamada. Sobre pergamino hay que
	 * centrar a mano y usar {@code drawString(..., false)}.</p>
	 */
	private static void checkParchmentTextHasNoShadow() throws Exception {
		//Solo estas dos: son las que pintan sobre el pergamino. El resto del mod dibuja sobre cuero oscuro,
		//donde la sombra es correcta y se usa a propósito.
		assertNoShadowedCentering("client/gui/CharacterSheetScreen.java", "drawCenteredString");
		assertNoShadowedCentering("client/gui/components/AdjustableImageButton.java", "renderString(");
	}

	private static void assertNoShadowedCentering(String relativePath, String forbidden) throws Exception {
		Path file = Path.of("src", "main", "java", "net", "hawthorn", "dndsheets").resolve(relativePath);
		for (String line : Files.readAllLines(file)) {
			//Los comentarios sí lo nombran: explican por qué no se usa.
			if (line.trim().startsWith("//") || line.trim().startsWith("*")) continue;
			assertTrue(!line.contains(forbidden), relativePath + ": usa " + forbidden
				+ ", que fuerza la sombra del texto. Sobre pergamino eso se lee como la palabra escrita dos veces.");
		}
	}

	private static void checkInteractHandlers() throws Exception {
		Path dir = Path.of("src", "main", "java", "net", "hawthorn", "dndsheets");
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String source = Files.readString(file);
				if (!source.contains("PlayerInteractEvent")) continue;
				assertTrue(!source.contains("getMainHandItem()") && !source.contains("getOffhandItem()"),
					file.getFileName() + ": un manejador de PlayerInteractEvent mira las dos manos en vez de "
						+ "event.getItemStack(). Eso lo hace correr dos veces por clic (una por mano).");
			}
		}
	}

	private static void checkTabTextures() throws Exception {
		//Alto de fila declarado en CharacterSheetScreen (yDiffTex) para cada textura.
		assertTabRows("imagebutton_tabbutton.png", 15);
		assertTabRows("imagebutton_tabbutton_active.png", 20);
		checkIconButtons();
		checkAbilityIcons();
	}

	/**
	 * <p>Los seis iconos de característica ({@code tools/make_ability_icons.py}) son lo único que
	 * identifica cada fila del panel lateral de la hoja, y lo que los hace legibles de un vistazo es el
	 * <b>color</b>, no la silueta: seis siluetas del mismo tono son seis manchas parecidas.</p>
	 *
	 * <p>Por eso lo que se comprueba aquí es que cada uno tenga un pigmento propio. Repetir un color al
	 * regenerarlos no rompe nada, no avisa, y deja dos características indistinguibles en la columna.</p>
	 */
	private static void checkAbilityIcons() throws Exception {
		Path dir = Path.of("src", "main", "resources", "assets", "dndsheets", "textures", "screens");
		java.util.Map<Integer, String> pigmentos = new java.util.HashMap<>();
		for (String name : new String[] {"str", "dex", "cons", "int", "wis", "cha"}) {
			java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(dir.resolve(name + ".png").toFile());
			assertTrue(image != null && image.getWidth() == 16 && image.getHeight() == 16,
				name + ".png: debe medir 16x16");

			//El pigmento es el color opaco más repetido que NO sea la tinta del contorno.
			java.util.Map<Integer, Integer> cuenta = new java.util.HashMap<>();
			for (int x = 0; x < 16; x++) {
				for (int y = 0; y < 16; y++) {
					int pixel = image.getRGB(x, y);
					if ((pixel >>> 24) != 0xFF || pixel == 0xFF2A2118) continue;
					cuenta.merge(pixel, 1, Integer::sum);
				}
			}
			assertTrue(!cuenta.isEmpty(), name + ".png: no tiene relleno, solo contorno o nada");
			int pigmento = cuenta.entrySet().stream()
				.max(java.util.Map.Entry.comparingByValue()).orElseThrow().getKey();

			String previo = pigmentos.put(pigmento, name);
			assertTrue(previo == null, name + ".png usa el mismo pigmento que " + previo
				+ ".png: en la columna de características esas dos filas quedan indistinguibles.");
		}
	}

	/**
	 * <p>Los iconos de la hoja ({@code tools/make_icon_buttons.py}) llevan dos filas de estado: normal
	 * arriba, ratón encima abajo. Aquí basta con dos —y no tres como las pestañas— porque
	 * {@code setActiveVisible} y {@code RollScrollWidget.setInactive} apagan siempre {@code active} y
	 * {@code visible} a la vez, así que un icono deshabilitado no llega a dibujarse.</p>
	 *
	 * <p>Lo que sí se comprueba es que las dos filas sean <b>distintas</b>. La primera versión de las
	 * variantes "_edit" usaba pergamino en los dos estados: el botón se veía idéntico apuntado y sin
	 * apuntar, o sea que no respondía a nada. Eso no rompe nada, no avisa, y solo se nota pasando el ratón
	 * por encima en el juego.</p>
	 */
	private static void checkIconButtons() throws Exception {
		Path dir = Path.of("src", "main", "resources", "assets", "dndsheets", "textures", "screens", "atlas");
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(f -> f.getFileName().toString().startsWith("imagebutton_")
					&& !f.getFileName().toString().contains("tabbutton")).toList()) {
				String name = file.getFileName().toString();
				java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file.toFile());
				int size = image.getWidth();
				assertTrue(image.getHeight() == size * 2,
					name + ": " + size + "x" + image.getHeight() + ", se esperaban dos filas de " + size);

				boolean differs = false;
				boolean anyOpaque = false;
				for (int x = 0; x < size && !differs; x++) {
					for (int y = 0; y < size; y++) {
						int normal = image.getRGB(x, y);
						int hovered = image.getRGB(x, y + size);
						if ((normal >>> 24) != 0) anyOpaque = true;
						if (normal != hovered) { differs = true; break; }
					}
				}
				assertTrue(anyOpaque, name + ": la fila normal está entera transparente");
				assertTrue(differs, name + ": las filas de reposo y de ratón encima son idénticas, "
					+ "así que el botón no responde visualmente al apuntarlo");
			}
		}
	}

	private static void assertTabRows(String name, int rowHeight) throws Exception {
		Path path = Path.of("src", "main", "resources", "assets", "dndsheets", "textures", "screens", "atlas", name);
		java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(path.toFile());
		if (image == null) throw new AssertionError(name + ": no se pudo leer el PNG");
		assertTrue(image.getHeight() == rowHeight * 3,
			name + ": alto " + image.getHeight() + ", se esperaban 3 filas de " + rowHeight + " = " + (rowHeight * 3)
				+ ". Con menos filas, la pestaña seleccionada muestrea fuera de la imagen.");
		//La tercera fila es la de la pestaña abierta: si es transparente, la seleccionada se ve como un hueco.
		assertTrue((image.getRGB(image.getWidth() / 2, rowHeight * 2 + rowHeight / 2) >>> 24) == 0xFF,
			name + ": la fila de pestaña seleccionada (la tercera) está transparente");
	}

	/** Fixture pequeño y escrito a mano: el ejemplo mínimo de cada esquema, en {@code test/dndsheets/}. */
	private static JsonArray readArray(String... pathParts) throws Exception {
		String json = Files.readString(Path.of("test", "dndsheets", pathParts[0], pathParts[1]));
		return JsonParser.parseString(json).getAsJsonArray();
	}

	/**
	 * <p>El pack grande <b>que se envía de verdad</b> ({@code src/main/resources/dndsheets/defaults/}), que
	 * es el que {@code DndPaths} siembra en cada mundo nuevo.</p>
	 *
	 * <p>Antes esto leía una segunda copia bajo {@code test/dndsheets/}, y las dos ya se habían separado sin
	 * que nadie se enterara: el self-test daba el visto bueno a un archivo que ningún jugador llega a cargar.
	 * Se descubrió justo así — añadir un campo al pack enviado no cambió nada en la comprobación.</p>
	 */
	private static JsonArray readShippedPack(String fileName) throws Exception {
		String json = Files.readString(Path.of("src", "main", "resources", "dndsheets", "defaults", fileName));
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
		JsonArray bulk = readShippedPack("spells.json");
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
		assertTrue(bulk.size() >= 87, "spells.json debería traer al menos los 87 hechizos importados del SRD, trae " + bulk.size());

		//Invocación: deja algo en el mundo que entra en la iniciativa y ataca solo. Si alguien la degradara
		//a un hechizo de ataque normal, se resolvería una vez y no volvería a actuar nunca.
		SpellRegistry.Spell summon = SpellRegistry.get("dndsheets:spiritual_weapon");
		assertTrue(summon != null && summon.isSummon(), "spiritual_weapon debería ser una invocación");
		assertTrue(summon.summonEntityId() != null && !summon.summonEntityId().isEmpty(),
			"una invocación necesita un cuerpo vanilla que la represente");
		assertTrue(summon.isSelfTargeted() || summon.isSummon(),
			"una invocación no necesita objetivo delante para lanzarse");

		//Un muro no se resuelve al lanzarlo: se coloca y daña por asaltos (ver WallManager). Si alguien lo
		//degradara a esfera, explotaría una vez en la cara del lanzador en vez de quedarse ahí.
		SpellRegistry.Spell wall = SpellRegistry.get("dndsheets:wall_of_fire");
		assertTrue(wall != null && wall.isZone(), "wall_of_fire debería ser una zona persistente");
		//Lo que define una zona es la PERSISTENCIA, no la forma: un muro y un Rayo de Luna son la misma
		//capacidad con geometría distinta. Si alguien atara isZone() a la forma otra vez, el Rayo de Luna
		//volvería a resolverse una sola vez y a no volver a actuar.
		SpellRegistry.Spell beam = SpellRegistry.get("dndsheets:moonbeam");
		assertTrue(beam != null && beam.isZone(), "moonbeam debería ser una zona persistente");
		assertTrue("sphere".equals(beam.aoeShape()), "y su forma es esférica, no de muro");
		assertTrue(!beam.followsCaster(), "el Rayo de Luna se queda donde se puso");
		SpellRegistry.Spell guardians2 = SpellRegistry.get("dndsheets:spirit_guardians");
		assertTrue(guardians2 != null && guardians2.followsCaster(),
			"los Guardianes Espirituales sí se recentran en el lanzador cada asalto");
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
		JsonArray bestiary = readShippedPack("monsters.json");
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

			//Un tipo de criatura mal escrito tampoco falla en ningún sitio: cae a UNKNOWN en silencio y el
			//monstruo se queda fuera de toda regla que pregunte por el tipo (el Castigo Divino no le suma su
			//dado, y mañana Inmovilizar Persona le afectará sin ser humanoide). Se exige que TODOS lo tengan
			//porque el fallo natural aquí es olvidar uno al ampliar el bestiario, no escribirlo mal.
			assertTrue(block.type() != CreatureType.UNKNOWN, id + " no tiene tipo de criatura, o está mal escrito");

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

		//Muestras de tipo elegidas por lo que se equivocaría un humano clasificando a ojo, no por lo obvio.
		assertTypeOf("dndsheets:skeleton", CreatureType.UNDEAD);
		assertTypeOf("dndsheets:will_o_wisp", CreatureType.UNDEAD);         //Parece un elemental de luz.
		assertTypeOf("dndsheets:ogre_zombie", CreatureType.UNDEAD);         //Y no gigante, pese al ogro.
		assertTypeOf("dndsheets:night_hag", CreatureType.FIEND);
		assertTypeOf("dndsheets:green_hag", CreatureType.FEY);              //La otra bruja NO es inmunda.
		assertTypeOf("dndsheets:blink_dog", CreatureType.FEY);              //Parece una bestia.
		assertTypeOf("dndsheets:unicorn", CreatureType.CELESTIAL);          //También parece una bestia.
		assertTypeOf("dndsheets:pegasus", CreatureType.CELESTIAL);
		assertTypeOf("dndsheets:centaur", CreatureType.MONSTROSITY);        //No humanoide.
		assertTypeOf("dndsheets:otyugh", CreatureType.ABERRATION);          //No monstruosidad.
		assertTypeOf("dndsheets:azer", CreatureType.ELEMENTAL);             //Parece un enano.
		assertTypeOf("dndsheets:gargoyle", CreatureType.ELEMENTAL);         //Parece un autómata de piedra.
		assertTypeOf("dndsheets:flesh_golem", CreatureType.CONSTRUCT);      //Y este parece un no-muerto.
		assertTypeOf("dndsheets:wyvern", CreatureType.DRAGON);
		assertTypeOf("dndsheets:half_red_dragon_veteran", CreatureType.HUMANOID);  //"dragón" en el nombre.
		//Un licántropo es humanoide en 5e SIEMPRE, también en su forma animal — es el caso donde clasificar
		//por la forma en vez de por la criatura da la respuesta contraria.
		assertTypeOf("dndsheets:werewolf_wolf", CreatureType.HUMANOID);

		//El parser tiene que aguantar lo que escribe una persona: acentos, mayúsculas, guiones o inglés.
		assertTrue(CreatureType.parse("no-muerto") == CreatureType.UNDEAD
			&& CreatureType.parse("No Muerto") == CreatureType.UNDEAD
			&& CreatureType.parse("undead") == CreatureType.UNDEAD, "\"no-muerto\" se escribe de varias formas");
		assertTrue(CreatureType.parse("aberración") == CreatureType.ABERRATION
			&& CreatureType.parse("aberracion") == CreatureType.ABERRATION, "con acento y sin él es lo mismo");
		//Y lo que no reconoce cae a UNKNOWN en vez de tumbar la carga del pack por una palabra.
		assertTrue(CreatureType.parse("gelatina") == CreatureType.UNKNOWN
			&& CreatureType.parse(null) == CreatureType.UNKNOWN
			&& CreatureType.parse("") == CreatureType.UNKNOWN, "un tipo que no existe deja al monstruo sin tipo, sin más");
		//Ida y vuelta: lo que escribe toJson lo tiene que volver a leer parse, o guardar una plantilla desde
		//el juego perdería el tipo del monstruo capturado.
		for (CreatureType type : CreatureType.values()) {
			assertTrue(CreatureType.parse(type.label()) == type, "el tipo " + type + " no sobrevive a guardar y volver a leer");
		}

		//Resistencia condicional: el hombre rata es inmune al daño físico NO mágico, y normal frente al
		//mágico. Es la mitad del bestiario de VD medio, así que conviene fijar que las dos ramas difieren
		//de verdad y no que una tapa a la otra.
		MonsterRegistry.MonsterStatBlock wererat = MonsterRegistry.get("dndsheets:wererat_human");
		assertTrue(wererat != null, "wererat_human debería haberse registrado");
		assertTrue(wererat.damageAffinities().isEmpty(), "el hombre rata no tiene resistencias incondicionales");
		assertTrue("immune".equals(wererat.nonmagicalAffinities().get("cortante")),
			"el hombre rata debería ser inmune al daño cortante no mágico");
	}

	/**
	 * <p>Objetos mágicos. La comprobación clave no es que parseen, sino la distinción entre los que el
	 * motor aplica y los que narra el DM: un objeto con mecánicas escritas mal no falla en ningún sitio,
	 * simplemente deja de dar el bonificador que su descripción promete.</p>
	 */
	private static void checkMagicItems() throws Exception {
		JsonArray items = readShippedPack("items.json");
		java.util.Set<String> ids = new java.util.HashSet<>();
		java.util.Set<String> damageTypes = java.util.Set.of("fisico", "cortante", "perforante", "contundente",
			"fuego", "frio", "rayo", "acido", "veneno", "psiquico", "radiante", "necrotico", "fuerza", "trueno");
		int mechanical = 0;

		for (JsonElement el : items) {
			JsonObject json = el.getAsJsonObject();
			String id = json.get("id").getAsString();
			assertTrue(ids.add(id), "id de objeto mágico duplicado: " + id);
			MagicItemRegistry.MagicItem item = MagicItemRegistry.parse(json);
			if (MagicItemRegistry.get(id) == null) MagicItemRegistry.register(item);

			assertTrue(!item.name().isBlank(), id + " debería tener nombre");
			//Todo objeto tiene un ítem vanilla que le presta la apariencia: sin él, /dnditems give no puede
			//entregar nada y el objeto solo existe como texto.
			assertTrue(item.itemId().startsWith("minecraft:"), id + " debería declarar un ítem base vanilla");
			for (Map.Entry<String, String> affinity : item.affinities().entrySet()) {
				assertTrue(damageTypes.contains(affinity.getKey()),
					"tipo de daño desconocido \"" + affinity.getKey() + "\" en " + id);
			}
			if (item.hasMechanics()) mechanical++;
		}

		assertTrue(items.size() >= 362, "items.json debería traer los 362 del SRD, trae " + items.size());
		//Que HAYA objetos narrativos es correcto y esperado: el SRD publica los objetos mágicos como prosa,
		//así que la mayoría no tiene mecánicas derivables. Lo que se fija aquí es que las escritas a mano no
		//se hayan perdido por el camino.
		assertTrue(mechanical >= 78, "deberían quedar al menos 78 objetos con mecánicas reales, hay " + mechanical);

		//Consumibles: sus efectos NO son pasivos. Una poción de resistencia modelada como afinidad
		//permanente protegería a quien lleva la botella sin beberla — es el falso positivo que motivó
		//que existiera esta distinción, así que conviene fijarla.
		MagicItemRegistry.MagicItem potion = MagicItemRegistry.get("dndsheets:potion_of_resistance_fire");
		assertTrue(potion != null && potion.isConsumable(), "la poción de resistencia debería ser consumible");
		assertTrue(potion.affinities().isEmpty(), "y NO tener afinidad pasiva: solo la da al beberla");
		assertTrue("resistant".equals(potion.temporaryAffinities().get("fuego")), "su resistencia es temporal");
		assertTrue(potion.durationRounds() > 0, "y con duración en asaltos");

		MagicItemRegistry.MagicItem heal = MagicItemRegistry.get("dndsheets:potion_of_healing_greater");
		assertTrue(heal != null && heal.isConsumable() && heal.healDice() != null, "la poción de curación cura al beberla");

		//Una condición concedida por un consumible tiene que ser una condición REAL: si no, TurnManager la
		//trata como efecto de daño de nombre libre y con dados "0" no hace absolutamente nada.
		for (JsonElement el : items) {
			JsonObject json = el.getAsJsonObject();
			if (!json.has("grantsCondition")) continue;
			String condition = json.get("grantsCondition").getAsString();
			assertTrue(Condition.fromLabel(condition) != null,
				json.get("id").getAsString() + " concede \"" + condition + "\", que no es una condición real");
		}


		//Un objeto que concede un conjuro solo funciona si ese conjuro EXISTE: si no, se etiqueta como
		//báculo rápido apuntando a la nada y el clic derecho no hace absolutamente nada.
		for (JsonElement el : items) {
			JsonObject json = el.getAsJsonObject();
			if (!json.has("grantsSpell")) continue;
			String spellId = json.get("grantsSpell").getAsString();
			assertTrue(SpellRegistry.get(spellId) != null,
				json.get("id").getAsString() + " concede el conjuro \"" + spellId + "\", que no está importado");
		}

		MagicItemRegistry.MagicItem ring = MagicItemRegistry.get("dndsheets:ring_of_protection");
		assertTrue(ring != null && ring.acBonus() == 1 && ring.saveBonus() == 1,
			"el Anillo de Protección debería dar +1 a CA y a salvaciones");
		assertTrue(ring.attunement(), "y requerir sintonización, que es lo que limita a tres objetos");
		assertTrue(ring.hasMechanics(), "un objeto con bonificadores no es narrativo");
		//Y NO es consumible: pulsarlo lo gastaría, y además cancelaría el clic que hace falta para ponerlo
		//en una ranura de Curios.
		assertTrue(!ring.isConsumable(), "el Anillo de Protección es pasivo, no se bebe");

		//Sintonización: el límite de 3 es lo que impide acumular objetos sin freno, así que conviene fijarlo.
		JsonObject sheet = new JsonObject();
		assertTrue(MagicItemRegistry.attunedIds(sheet).isEmpty(), "una hoja nueva no tiene nada sintonizado");
		assertTrue(MagicItemRegistry.attune(sheet, "a"), "el primero debería entrar");
		assertTrue(!MagicItemRegistry.attune(sheet, "a"), "el mismo dos veces, no");
		assertTrue(MagicItemRegistry.attune(sheet, "b") && MagicItemRegistry.attune(sheet, "c"), "hasta tres");
		assertTrue(!MagicItemRegistry.attune(sheet, "d"), "el cuarto debería rechazarse");
		assertTrue(MagicItemRegistry.unattune(sheet, "b"), "desintonizar uno que sí estaba");
		assertTrue(!MagicItemRegistry.unattune(sheet, "b"), "y no dos veces");
		assertTrue(MagicItemRegistry.attune(sheet, "d"), "liberado un hueco, el cuarto ya entra");
		assertTrue(MagicItemRegistry.attunedIds(sheet).size() == MagicItemRegistry.MAX_ATTUNED,
			"deberían quedar exactamente " + MagicItemRegistry.MAX_ATTUNED);

		System.out.println("checkMagicItems: OK, " + items.size() + " objetos (" + mechanical
			+ " con mecánicas, el resto narrativos) y la sintonización respeta su límite.");
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
			Map.of("cortante", "immune"),           //Solo frente a armas no mágicas.
			CreatureType.HUMANOID);                 //Un licántropo es humanoide en 5e, también en forma de bestia.
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
	/**
	 * <p>Tablas de espacios de conjuro de 5e. Un escalón mal puesto no rompe nada y no avisa: simplemente
	 * un personaje lanza de más o de menos durante toda la campaña.</p>
	 */
	private static void checkSpellSlots() {
		//Lanzador completo, filas de referencia del SRD.
		assertSlots(SpellSlots.Caster.FULL, 1, new int[] {2});
		assertSlots(SpellSlots.Caster.FULL, 5, new int[] {4, 3, 2});
		assertSlots(SpellSlots.Caster.FULL, 11, new int[] {4, 3, 3, 3, 2, 1});
		assertSlots(SpellSlots.Caster.FULL, 20, new int[] {4, 3, 3, 3, 3, 2, 2, 1, 1});

		//Semilanzador: no lanza a nivel 1, y a partir de ahí es el completo a la mitad redondeando ARRIBA.
		//Con el redondeo al revés toda la progresión se desplaza un nivel sin que nada falle.
		assertSlots(SpellSlots.Caster.HALF, 1, new int[] {});
		assertSlots(SpellSlots.Caster.HALF, 2, new int[] {2});
		assertSlots(SpellSlots.Caster.HALF, 5, new int[] {4, 2});
		assertSlots(SpellSlots.Caster.HALF, 20, new int[] {4, 3, 3, 3, 2});

		//Total por nivel del lanzador completo, los veinte. Las cuatro filas de arriba comprueban el
		//REPARTO; esto comprueba que no falte ni sobre ningún espacio en las dieciséis que no se detallan.
		//Hace falta: al probar estas comprobaciones, romper un escalón de una fila no comprobada no saltaba.
		int[] totalPorNivel = {0, 2, 3, 6, 7, 9, 10, 11, 12, 14, 15, 16, 16, 17, 17, 18, 18, 19, 20, 21, 22};
		for (int level = 1; level <= 20; level++) {
			int total = SpellSlots.total(SpellSlots.maxSlots(SpellSlots.Caster.FULL, level));
			assertTrue(total == totalPorNivel[level], "lanzador completo de nivel " + level + ": "
				+ total + " espacios en total, se esperaban " + totalPorNivel[level]);
		}

		//Magia de Pacto: pocos espacios y TODOS del mismo nivel — no es "menos espacios", es otro recurso.
		//Los veinte niveles, porque la tabla es corta y sus escalones (2, 3, 5, 7, 9, 11 y 17) son justo
		//donde un dígito movido pasa inadvertido.
		int[] pactCount = {0, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4};
		int[] pactLevel = {0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5};
		for (int level = 1; level <= 20; level++) {
			int[] slots = SpellSlots.maxSlots(SpellSlots.Caster.PACT, level);
			assertTrue(SpellSlots.total(slots) == pactCount[level], "brujo de nivel " + level + ": "
				+ SpellSlots.total(slots) + " espacios, se esperaban " + pactCount[level]);
			assertTrue(slots[pactLevel[level]] == pactCount[level], "brujo de nivel " + level
				+ ": sus espacios deberían ser todos de nivel de conjuro " + pactLevel[level]);
		}

		assertSlots(SpellSlots.Caster.NONE, 20, new int[] {});

		//Las clases se reconocen por su nombre mostrado, que es lo que guarda la hoja ("Mago", no "wizard").
		assertTrue(SpellSlots.casterFor("Mago") == SpellSlots.Caster.FULL, "Mago debería ser lanzador completo");
		assertTrue(SpellSlots.casterFor("Explorador") == SpellSlots.Caster.HALF, "Explorador debería ser semilanzador");
		assertTrue(SpellSlots.casterFor("Brujo") == SpellSlots.Caster.PACT, "Brujo debería usar Magia de Pacto");
		assertTrue(SpellSlots.casterFor("Guerrero") == SpellSlots.Caster.NONE, "Guerrero no lanza conjuros");
		assertTrue(SpellSlots.casterFor("wizard") == SpellSlots.Caster.FULL, "el id inglés también debería valer");
		assertTrue(SpellSlots.casterFor(null) == SpellSlots.Caster.NONE, "una clase sin fijar no debería reventar");

		//Gastar coge el espacio MÁS BAJO que sirva: quemar uno alto pudiendo usar uno bajo tira el recurso caro.
		JsonObject sheet = new JsonObject();
		SpellSlots.applyProgression(sheet, "Mago", 5);      //4/3/2
		assertTrue(SpellSlots.spend(sheet, 1) == 1, "un mago de nivel 5 debería gastar el espacio de nivel 1");
		assertTrue(SpellSlots.currentSlots(sheet)[1] == 3 && SpellSlots.currentSlots(sheet)[3] == 2,
			"debería haber gastado del nivel 1, no de otro");

		//Sin espacios de nivel 1, un conjuro de nivel 1 sube al siguiente que quede.
		SpellSlots.spend(sheet, 1); SpellSlots.spend(sheet, 1); SpellSlots.spend(sheet, 1);
		assertTrue(SpellSlots.currentSlots(sheet)[1] == 0, "los cuatro de nivel 1 deberían estar gastados");
		assertTrue(SpellSlots.spend(sheet, 1) == 2, "debería lanzarlo con un espacio superior y decir cuál");
		assertTrue(SpellSlots.currentSlots(sheet)[2] == 2, "debería haber subido al nivel 2");

		//Un truco nunca gasta nada, ni siquiera sin espacios.
		JsonObject sinEspacios = new JsonObject();
		SpellSlots.applyProgression(sinEspacios, "Guerrero", 20);
		assertTrue(SpellSlots.hasSlotFor(sinEspacios, 0) && SpellSlots.spend(sinEspacios, 0) == 0,
			"un truco debería lanzarse siempre, también sin espacios, y no gastar nivel ninguno");
		assertTrue(!SpellSlots.hasSlotFor(sinEspacios, 1), "un guerrero no debería tener espacios de nivel 1");

		//Los totales antiguos siguen siendo la suma: el HUD y el Grimorio los leen sin cambiar.
		JsonObject mago = new JsonObject();
		SpellSlots.applyProgression(mago, "Mago", 20);
		assertTrue(mago.get("spellSlotsMax").getAsInt() == 22,
			"un mago de nivel 20 tiene 22 espacios en total, no " + mago.get("spellSlotsMax").getAsInt());

		//Subir de nivel entrega LLENOS los espacios nuevos y respeta los ya gastados.
		JsonObject sube = new JsonObject();
		SpellSlots.applyProgression(sube, "Mago", 1);       //2 de nivel 1
		SpellSlots.spend(sube, 1);
		SpellSlots.applyProgression(sube, "Mago", 3);       //pasa a 4/2
		assertTrue(SpellSlots.currentSlots(sube)[1] == 3,
			"debería conservar el gastado y sumar los dos nuevos, no rellenar del todo");
		assertTrue(SpellSlots.currentSlots(sube)[2] == 2, "el nivel 2 nuevo debería entrar lleno");

		//Recuperación Arcana: presupuesto de NIVELES SUMADOS, no de espacios. Un mago de nivel 10 tiene un
		//presupuesto de 5, así que gastándolo todo debería recuperar un espacio de nivel 5 (el más caro que
		//cabe) y no cinco de nivel 1 — con el mismo presupuesto, lo alto vale más.
		JsonObject arcano = new JsonObject();
		SpellSlots.applyProgression(arcano, "Mago", 10);           //4/3/3/3/2
		for (int level = 1; level <= 5; level++) {
			while (SpellSlots.currentSlots(arcano)[level] > 0) SpellSlots.spend(arcano, level);
		}
		assertTrue(SpellSlots.total(SpellSlots.currentSlots(arcano)) == 0, "debería haber gastado todo");
		assertTrue(SpellSlots.restoreBudget(arcano, 5, 5) == 1, "con presupuesto 5 debería devolver UN espacio");
		assertTrue(SpellSlots.currentSlots(arcano)[5] == 1, "y debería ser el de nivel 5, no varios bajos");

		//El tope de nivel se respeta: con presupuesto de sobra pero tope 2, nada por encima del 2.
		JsonObject topado = new JsonObject();
		SpellSlots.applyProgression(topado, "Mago", 10);
		for (int level = 1; level <= 5; level++) {
			while (SpellSlots.currentSlots(topado)[level] > 0) SpellSlots.spend(topado, level);
		}
		SpellSlots.restoreBudget(topado, 9, 2);
		assertTrue(SpellSlots.currentSlots(topado)[3] == 0 && SpellSlots.currentSlots(topado)[4] == 0,
			"no debería devolver espacios por encima del tope");
		assertTrue(SpellSlots.currentSlots(topado)[2] == 3 && SpellSlots.currentSlots(topado)[1] == 3,
			"con presupuesto 9 y tope 2: tres de nivel 2 (6) y tres de nivel 1 (3)");

		//Lanzar a nivel superior a propósito: el nivel pedido manda sobre "el más bajo que sirva".
		JsonObject sube3 = new JsonObject();
		SpellSlots.applyProgression(sube3, "Mago", 9);              //4/3/3/3/1
		assertTrue(SpellSlots.spend(sube3, 1, 3) == 3, "pedir un espacio de 3º para un conjuro de 1º debería gastar el de 3º");
		assertTrue(SpellSlots.currentSlots(sube3)[1] == 4, "y no debería haber tocado los de nivel 1");

		//Pedir un nivel agotado sube al siguiente en vez de negar el lanzado por un tecnicismo.
		while (SpellSlots.currentSlots(sube3)[3] > 0) SpellSlots.spend(sube3, 3);
		assertTrue(SpellSlots.spend(sube3, 1, 3) == 4, "con el 3º agotado debería subir al 4º, no fallar");

		//El parche que va al cliente tiene que llevar la TABLA, no solo el total. Mandando solo el total, el
		//Grimorio se quedaba con columnas viejas y ofrecía niveles ya gastados: se veía como que subir el
		//nivel "no se reflejaba en ningún sitio".
		JsonObject patch = SpellSlots.clientPatch(sube3);
		assertTrue(patch.has("spellSlotsByLevel") && patch.has("spellSlotsCurrent"),
			"el parche de espacios debería llevar la tabla por nivel y el total, no solo el total");
		assertTrue(patch.get("spellSlotsByLevel").toString().equals(sube3.get("spellSlotsByLevel").toString()),
			"y la tabla del parche debería ser la de la hoja tras gastar");
		//Un campo ausente se omite: en un parche, un nulo significa "borra esta clave" en la hoja del cliente.
		assertTrue(SpellSlots.clientPatch(new JsonObject()).size() == 0,
			"sin espacios en la hoja, el parche debería ir vacío y no borrar nada en el cliente");

		//Un nivel pedido POR DEBAJO del conjuro no lo abarata: sigue costando el suyo.
		JsonObject barato = new JsonObject();
		SpellSlots.applyProgression(barato, "Mago", 5);            //4/3/2
		assertTrue(SpellSlots.spend(barato, 3, 1) == 3, "un conjuro de 3º no se puede lanzar con un espacio de 1º");

		System.out.println("checkSpellSlots: OK, tablas de 5e, gasto por nivel, subida de nivel, progresión y recuperación se comportan.");
	}

	/**
	 * <p>Lanzar a nivel superior: los dados extra que suma un espacio más alto. Va aparte de
	 * {@link #checkSpells()} porque no comprueba el contenido del pack, sino la aritmética de
	 * {@code Spell.upcastTo} — que es la que decide cuánto daño hace de verdad una Bola de Fuego de 5º.</p>
	 */
	private static void checkUpcasting() throws Exception {
		SpellRegistry.Spell fireball = spellFromPack("dndsheets:fireball");
		assertTrue("8d6".equals(fireball.upcastTo(3).dice()), "a su propio nivel no debería cambiar nada");
		assertTrue("8d6".equals(fireball.upcastTo(2).dice()), "un nivel por debajo tampoco: no existe lanzarlo rebajado");
		assertTrue("8d6 + 2d6".equals(fireball.upcastTo(5).dice()),
			"Bola de Fuego con un espacio de 5º debería sumar 2d6, no " + fireball.upcastTo(5).dice());
		assertTrue(fireball.upcastTo(5).name().contains("nv. 5"),
			"el chat tiene que decir con qué nivel salió, o dos daños distintos se leen como un fallo");

		//Los dados iguales se juntan en uno: "1d6" tres veces es "3d6" y no tres sumandos.
		assertTrue("6d6".equals(SpellRegistry.repeatDice("2d6", 3)), "2d6 x3 debería ser 6d6");
		assertTrue("1d6".equals(SpellRegistry.repeatDice("1d6", 1)), "sin repetir debería quedarse igual");
		//Lo que no es un dado suelto se repite tal cual: Misil Mágico sube un dardo entero por nivel.
		assertTrue("1d4 + 1 + 1d4 + 1".equals(SpellRegistry.repeatDice("1d4 + 1", 2)),
			"un dardo con bonificador se repite entero, no se multiplica");

		//Un conjuro que no escala no cambia por gastar un espacio caro (y hay muchos así en el SRD).
		SpellRegistry.Spell meteor = spellFromPack("dndsheets:meteor_swarm");
		assertTrue(!meteor.scalesWithSlot() && meteor.upcastTo(9).dice().equals(meteor.dice()),
			"Enjambre de Meteoros no gana nada por subirlo de nivel");

		//Un conjuro sin daño que SÍ escalase no debe quedar con un "0 +" delante en el chat.
		SpellRegistry.Spell sinDano = SpellRegistry.parse(com.google.gson.JsonParser.parseString(
			"{\"id\":\"x\",\"level\":1,\"dice\":\"0\",\"upcastDice\":\"1d8\"}").getAsJsonObject());
		assertTrue("2d8".equals(sinDano.upcastTo(3).dice()), "sin daño base debería quedar solo lo añadido, no \"0 + 2d8\"");

		//Trucos: suben con el nivel de PERSONAJE, no con el espacio (no gastan ninguno). Los cuatro escalones
		//se comprueban enteros porque el defecto natural aquí es un límite mal puesto, no una fórmula rara.
		SpellRegistry.Spell fireBolt = spellFromPack("dndsheets:fire_bolt");
		assertTrue("1d10".equals(fireBolt.atCasterLevel(4).dice()), "hasta el nivel 4 un truco no crece");
		assertTrue("2d10".equals(fireBolt.atCasterLevel(5).dice()), "a nivel 5 debería sumar un dado");
		assertTrue("2d10".equals(fireBolt.atCasterLevel(10).dice()), "y quedarse ahí hasta el 11");
		assertTrue("3d10".equals(fireBolt.atCasterLevel(11).dice()), "a nivel 11 el tercero");
		assertTrue("4d10".equals(fireBolt.atCasterLevel(17).dice()), "a nivel 17 el cuarto");
		assertTrue("4d10".equals(fireBolt.atCasterLevel(20).dice()), "y 20 no añade un quinto");

		//Un conjuro con espacio NO escala por nivel de personaje: si lo hiciera, una Bola de Fuego de un
		//mago de nivel 17 haría 32d6 sin que nadie lo hubiera pedido.
		assertTrue("8d6".equals(fireball.atCasterLevel(20).dice()), "solo escalan los trucos, no todo conjuro");
		//Un truco sin daño (los hay) no gana dados de la nada.
		SpellRegistry.Spell sinDados = SpellRegistry.parse(com.google.gson.JsonParser.parseString(
			"{\"id\":\"y\",\"level\":0,\"dice\":\"0\"}").getAsJsonObject());
		assertTrue("0".equals(sinDados.atCasterLevel(20).dice()), "un truco sin daño se queda sin daño");

		System.out.println("checkUpcasting: OK, los dados extra por nivel de espacio y los trucos por nivel de personaje salen donde deben.");
	}

	/**
	 * <p>El contenido del mod tiene que llegar a un mundo que YA EXISTE. Antes se sembraba una sola vez y
	 * solo en una carpeta vacía, así que la copia del mundo se quedaba congelada en la versión del día que
	 * se creó la partida: hechizos nuevos, resistencias añadidas a un monstruo o el escalado por nivel de
	 * espacio no llegaban nunca. Fue justo el síntoma reportado — subir el nivel del conjuro no hacía nada
	 * en una partida en curso porque el servidor cargaba un pack anterior a la regla.</p>
	 */
	private static void checkDefaultsRefresh() throws Exception {
		Path dir = Files.createTempDirectory("dndsheets-defaults");
		try {
			//Un mundo de la versión anterior: el pack sembrado con el nombre viejo, sin escalado por nivel.
			Path legacy = dir.resolve("spells.json");
			Files.writeString(legacy, "[ { \"id\": \"dndsheets:fireball\", \"level\": 3, \"dice\": \"8d6\" } ]");

			Path retired = ContentDefaults.refresh(dir, "spells.json");

			assertTrue(retired != null && Files.exists(retired), "el pack antiguo debería quedar apartado, no borrado");
			assertTrue(!Files.exists(legacy), "y no debería seguir autocargándose con su nombre original");
			assertTrue(!retired.getFileName().toString().endsWith(".json"),
				"apartado tiene que dejar de terminar en .json o autoLoadAll lo seguiría cargando");
			String fresh = Files.readString(dir.resolve(ContentDefaults.FILE));
			assertTrue(fresh.contains("upcastDice"), "el pack al día debería traer el escalado por nivel de espacio");

			//Segunda pasada. El pack del mod se deja como lo habría dejado una versión anterior: si no se
			//reescribe, se queda ahí para siempre, que es exactamente el defecto que esto viene a cerrar.
			Files.writeString(dir.resolve(ContentDefaults.FILE), "[ ]");
			//Y un archivo del DM con el nombre que usaba la siembra vieja: pasada la migración ya no se toca.
			Files.writeString(legacy, "[ ]");

			assertTrue(ContentDefaults.refresh(dir, "spells.json") == null,
				"tras la migración, un archivo con ese nombre es del DM y no se aparta");
			assertTrue(Files.exists(legacy), "y tiene que seguir donde estaba");
			assertTrue(Files.readString(dir.resolve(ContentDefaults.FILE)).contains("upcastDice"),
				"el pack del mod debería reescribirse en cada arranque, no sembrarse una sola vez");
		} finally {
			try (Stream<Path> files = Files.walk(dir)) {
				files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}

		System.out.println("checkDefaultsRefresh: OK, un mundo ya existente recibe el contenido nuevo sin pisar lo del DM.");
	}

	private static void assertTypeOf(String monsterId, CreatureType expected) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(monsterId);
		assertTrue(block != null, "no está en el bestiario: " + monsterId);
		assertTrue(block.type() == expected, monsterId + " debería ser " + expected + " y es " + block.type());
	}

	private static SpellRegistry.Spell spellFromPack(String id) throws Exception {
		//Se lee del pack en vez de SpellRegistry.get() porque ejemplo.json y spells.json comparten ids, y el
		//que queda registrado es el primero: la subida de nivel hay que comprobarla sobre el pack grande.
		for (JsonElement element : readShippedPack("spells.json")) {
			JsonObject json = element.getAsJsonObject();
			if (id.equals(json.get("id").getAsString())) return SpellRegistry.parse(json);
		}
		throw new AssertionError("no está en el pack: " + id);
	}

	private static void assertSlots(SpellSlots.Caster caster, int level, int[] expected) {
		int[] actual = SpellSlots.maxSlots(caster, level);
		for (int spellLevel = 1; spellLevel <= SpellSlots.MAX_SPELL_LEVEL; spellLevel++) {
			int want = spellLevel <= expected.length ? expected[spellLevel - 1] : 0;
			assertTrue(actual[spellLevel] == want, caster + " nivel " + level + ", espacios de conjuro nivel "
				+ spellLevel + ": " + actual[spellLevel] + ", se esperaban " + want);
		}
	}

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

		//Tabla de competencia de 5e: +2 (1-4), +3 (5-8), +4 (9-12), +5 (13-16), +6 (17-20). Entra en toda
		//tirada de ataque y en toda CD de salvación, así que un escalón mal puesto desajusta el juego
		//entero sin que falle nada. Antes esto no lo calculaba nadie: la hoja se quedaba en "2" para
		//siempre pese a pintar el campo como automático.
		int[] esperado = {2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6};
		for (int level = 1; level <= 20; level++) {
			assertTrue(CharacterRules.proficiencyBonusFor(level) == esperado[level],
				"competencia de nivel " + level + ": " + CharacterRules.proficiencyBonusFor(level)
					+ ", se esperaba " + esperado[level]);
		}
		//Fuera de rango no debe salirse de la tabla: una hoja corrupta con nivel 0 o 99 sigue jugando.
		assertTrue(CharacterRules.proficiencyBonusFor(0) == 2, "nivel 0 debería caer en el escalón más bajo");
		assertTrue(CharacterRules.proficiencyBonusFor(99) == 6, "un nivel disparatado debería topar en +6");

		//Los otros dos números que estaban congelados en su valor de nivel 1. Se comprueban los escalones y
		//los bordes de cada uno, no una muestra: el fallo natural aquí es un límite corrido, no una fórmula
		//rara — el bono de Furia sube a los 9 y 16, el dado de Inspiración a los 5, 10 y 15.
		assertTrue(CharacterRules.rageDamageBonusFor(1) == 2 && CharacterRules.rageDamageBonusFor(8) == 2,
			"la Furia debería dar +2 hasta el nivel 8");
		assertTrue(CharacterRules.rageDamageBonusFor(9) == 3 && CharacterRules.rageDamageBonusFor(15) == 3,
			"+3 del 9 al 15");
		assertTrue(CharacterRules.rageDamageBonusFor(16) == 4 && CharacterRules.rageDamageBonusFor(20) == 4,
			"+4 del 16 en adelante");

		assertTrue("1d6".equals(CharacterRules.bardicInspirationDieFor(4)), "Inspiración: d6 hasta el nivel 4");
		assertTrue("1d8".equals(CharacterRules.bardicInspirationDieFor(5)), "d8 a partir del 5");
		assertTrue("1d10".equals(CharacterRules.bardicInspirationDieFor(10)), "d10 a partir del 10");
		assertTrue("1d12".equals(CharacterRules.bardicInspirationDieFor(15)), "d12 a partir del 15");
		assertTrue("1d12".equals(CharacterRules.bardicInspirationDieFor(20)), "y no hay un d20 al llegar arriba");

		//Castigo Divino: crece con el espacio que se gasta de verdad, con tope en 5d8.
		CreatureType nadie = CreatureType.UNKNOWN;
		assertTrue("2d8".equals(PaladinSmiteManager.diceForSlot(1, nadie)), "un espacio de nivel 1 son 2d8");
		assertTrue("4d8".equals(PaladinSmiteManager.diceForSlot(3, nadie)), "uno de nivel 3, 4d8");
		assertTrue("5d8".equals(PaladinSmiteManager.diceForSlot(4, nadie)) && "5d8".equals(PaladinSmiteManager.diceForSlot(9, nadie)),
			"y el tope del SRD son 5d8, por alto que sea el espacio");

		//Y un dado más contra no-muertos e inmundos, que va APARTE del tope: 5d8 topados + 1 son 6d8, no 5d8.
		assertTrue("3d8".equals(PaladinSmiteManager.diceForSlot(1, CreatureType.UNDEAD)), "contra un no-muerto, 3d8 con un espacio de 1º");
		assertTrue("3d8".equals(PaladinSmiteManager.diceForSlot(1, CreatureType.FIEND)), "y lo mismo contra un inmundo");
		assertTrue("6d8".equals(PaladinSmiteManager.diceForSlot(9, CreatureType.UNDEAD)),
			"el tope de 5d8 es el de la subida por espacio; el dado contra no-muertos se suma encima");
		assertTrue("2d8".equals(PaladinSmiteManager.diceForSlot(1, CreatureType.BEAST)),
			"un lobo no es un no-muerto por mucho que muerda");
		//Un objetivo sin tipo (mob de otro mod, PNJ genérico) NO se lleva el extra: ninguna regla debería
		//dispararse por adivinar.
		assertTrue("2d8".equals(PaladinSmiteManager.diceForSlot(1, CreatureType.UNKNOWN)), "sin tipo, sin dado extra");

		System.out.println("checkCharacterRules: OK, personajes múltiples, PNJ, hojas antiguas, PG, competencia, Furia, Inspiración y Castigo por nivel se comportan.");
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
