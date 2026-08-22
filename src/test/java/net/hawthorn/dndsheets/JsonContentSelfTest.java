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
		checkMonsterAppearance();
		checkMonsterSkins();
		checkItemLooks();
		checkPatchouliBook();
		checkGuideLayout();
		checkKeepsOwnAi();
		checkWildShape();
		checkOwnClock();
		checkVanillaIds();
		checkMagicItems();
		checkTraits(); //Antes de checkPresets(): el preset de monje concede este rasgo por id.
		checkPresets();
		checkFeats();
		checkSubclasses();
		checkMulticlass(); //También después de checkPresets(): los nombres de las clases salen de ahí.
		checkDice();
		checkAttackAndDamageRolls();
		checkDungeonPools();
		checkConditions();
		checkAoeShapes();
		checkCombatantRules();
		checkCharacterRules();
		checkSpellSlots();
		checkUpcasting();
		checkSpellTargeting();
		checkInitiatorGoesFirst();
		checkCover();
		checkAbilityImprovements();
		checkLanguageFiles();
		checkTranslationKeysExist();
		checkPlaceholderParity();
		checkChatMessagesAreTranslatable();
		checkNetworkShape();
		checkNetworkWire();
		checkDmGuardIsShared();
		checkSheetWritesArePersisted();
		checkAddonContentLoads();
		checkSheetCoordinateSpaces();
		checkIncapacitatedCannotAct();
		checkCharacterLookup();
		checkCharacterAfterDelete();
		checkCharacterLevelIsPerCharacter();
		checkAttackPathsShareRules();
		checkDefaultsRefresh();
		checkTabTextures();
		checkInteractHandlers();
		checkParchmentTextHasNoShadow();
		checkVision();
		checkEncounters();
		checkStructureImport();
		checkSkillProficiency();
		checkCharacterSetup();
		checkPortabilityCoupling();
		checkImportedContent();

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
		java.util.Set<String> abilityFlags = new java.util.LinkedHashSet<>();
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String source = Files.readString(file);
				//Cada ítem de capacidad se construye con AbilityItem.build(item, "flag", ...): el segundo
				//argumento es la etiqueta NBT por la que el despachador lo reconoce.
				java.util.regex.Matcher built = java.util.regex.Pattern
					.compile("AbilityItem\\.build\\([^,]+,\\s*\"(\\w+)\"").matcher(source);
				while (built.find()) abilityFlags.add(built.group(1));

				if (!source.contains("PlayerInteractEvent")) continue;
				assertTrue(!source.contains("getMainHandItem()") && !source.contains("getOffhandItem()"),
					file.getFileName() + ": un manejador de PlayerInteractEvent mira las dos manos en vez de "
						+ "event.getItemStack(). Eso lo hace correr dos veces por clic (una por mano).");
			}
		}

		//Un ítem de capacidad que nadie despacha no falla en ningún sitio: se entrega, se ve en el inventario
		//con su nombre y su descripción, y al pulsarlo no pasa nada. Ya ocurrió — cuatro de ellos solo
		//estaban en la cadena de "clic al aire" y no hacían nada mirando a un monstruo, que es cuando se usan.
		String dispatcher = Files.readString(dir.resolve("AbilityItemDispatcher.java"));
		assertTrue(abilityFlags.size() >= 10, "esperaba encontrar los ítems de capacidad y encontré " + abilityFlags.size());
		for (String flag : abilityFlags) {
			assertTrue(dispatcher.contains("getBoolean(\"" + flag + "\")"),
				"el ítem de capacidad \"" + flag + "\" no lo despacha nadie: al pulsarlo no pasaría nada");
		}

		//Y la cadena común tiene que estar UNA vez, no copiada por evento: eran tres copias y se separaron.
		//Contar por un ítem que vale en los tres eventos es la forma barata de fijar que siguen unificados.
		int copias = dispatcher.split("getBoolean\\(\"rage\"\\)", -1).length - 1;
		assertTrue(copias == 1, "la cadena de reparto está duplicada " + copias + " veces; con copias se separan y unos ítems dejan de funcionar según a qué mires");

		//Un armor stand NUNCA llega por EntityInteract, así que el manejador de la Vara de DM necesita
		//además el de EntityInteractSpecific o borrarlo con ella no funciona — y no funcionó nunca, sin que
		//nada fallara: ArmorStand.interactAt devuelve CONSUME en el cliente antes de mirar nada, y
		//Minecraft.startUseItem solo manda el segundo paquete si el primero NO consumió. Es la clase de
		//causa que no se encuentra leyendo el mod, solo leyendo vanilla, así que queda anotada aquí.
		String wand = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets",
			"MonsterActionManager.java"));
		assertTrue(wand.contains("PlayerInteractEvent.EntityInteractSpecific"),
			"MonsterActionManager perdió el manejador de EntityInteractSpecific: la Vara de DM deja de"
				+ " funcionar sobre armor stands, y el evento normal no llega nunca para ellos");
		assertTrue(wand.contains("event.getTarget() instanceof ArmorStand"),
			"ese manejador tiene que filtrar por armor stand: cualquier otra entidad manda los dos paquetes"
				+ " y el manejador correría dos veces por clic");

		System.out.println("checkInteractHandlers: OK, los " + abilityFlags.size() + " ítems de capacidad se despachan y la cadena está sin duplicar.");
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

		//Resistencia Legendaria: la tienen los adultos y los ancianos de cada dragón, y una docena de jefes
		//sueltos. Los JÓVENES y las crías NO — es el error fácil de cometer al anotar 43 dragones, y el que
		//convierte un encuentro de nivel medio en una pared.
		assertTrue(MonsterRegistry.get("dndsheets:adult_red_dragon").legendaryResistances() == 3,
			"un dragón rojo adulto tiene 3 Resistencias Legendarias");
		assertTrue(MonsterRegistry.get("dndsheets:ancient_white_dragon").legendaryResistances() == 3,
			"y un anciano también");
		assertTrue(MonsterRegistry.get("dndsheets:young_red_dragon").legendaryResistances() == 0,
			"pero un dragón JOVEN no tiene ninguna");
		assertTrue(MonsterRegistry.get("dndsheets:red_dragon_wyrmling").legendaryResistances() == 0,
			"ni una cría");
		assertTrue(MonsterRegistry.get("dndsheets:lich").legendaryResistances() == 3, "el lich sí");
		assertTrue(MonsterRegistry.get("dndsheets:goblin").legendaryResistances() == 0,
			"y un goblin desde luego que no");

		//Acciones legendarias: la lista NO es la misma que la de Resistencia Legendaria, y tratarlas como si
		//lo fueran fue exactamente el error de la primera pasada. Se comprueban las dos direcciones del
		//desajuste, que es lo único que distingue una lista copiada de una escrita.
		assertTrue(MonsterRegistry.get("dndsheets:adult_red_dragon").legendaryActions() == 3,
			"un dragón adulto tiene 3 acciones legendarias");
		assertTrue(MonsterRegistry.get("dndsheets:vampire_vampire").legendaryActions() == 3
				&& MonsterRegistry.get("dndsheets:vampire_vampire").legendaryResistances() == 0,
			"el vampiro tiene acciones legendarias pero NO Resistencia Legendaria");
		assertTrue(MonsterRegistry.get("dndsheets:balor").legendaryResistances() == 0
				&& MonsterRegistry.get("dndsheets:balor").legendaryActions() == 0,
			"el balor no tiene ninguna de las dos: lo que tiene es Resistencia a la Magia, que es otra cosa");
		assertTrue(MonsterRegistry.get("dndsheets:young_red_dragon").legendaryActions() == 0,
			"y un dragón joven tampoco tiene acciones legendarias");

		//Multiataque: cuántos ataques hace en su turno. Un dragón adulto hacía UNO, o sea un tercio de su
		//amenaza. Es un eje distinto de los dos legendarios —un dragón joven multiataca y no es legendario—
		//y por eso se comprueba con un caso que los separa.
		assertTrue(MonsterRegistry.get("dndsheets:adult_red_dragon").attacksPerTurn() == 3,
			"un dragón adulto hace tres ataques por turno: mordisco y dos garras");
		assertTrue(MonsterRegistry.get("dndsheets:young_red_dragon").attacksPerTurn() == 2
				&& MonsterRegistry.get("dndsheets:young_red_dragon").legendaryActions() == 0,
			"un dragón joven multiataca sin ser legendario: son dos ejes distintos");
		assertTrue(MonsterRegistry.get("dndsheets:goblin").attacksPerTurn() == 1,
			"y lo que no lo declara hace uno, que es como se comportaba el bestiario entero");
		//El tope existe para que un número absurdo en un JSON no convierta un turno en una ráfaga ilegible.
		for (JsonElement el : readShippedPack("monsters.json")) {
			MonsterRegistry.MonsterStatBlock parsed = MonsterRegistry.parse(el.getAsJsonObject());
			assertTrue(parsed.attacksPerTurn() >= 1 && parsed.attacksPerTurn() <= 6,
				parsed.id() + " declara " + parsed.attacksPerTurn() + " ataques por turno, fuera de rango");
		}

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
	private static void checkMonsterAppearance() throws Exception {
		//Las que lee MonsterRegistry.parseAppearance. Escribir "mainhand" o "head" no rompe nada: el
		//monstruo sale sin arma y nadie se entera hasta verlo en la mesa.
		java.util.Set<String> keys = java.util.Set.of("mainHand", "offHand", "helmet", "chestplate",
			"leggings", "boots", "baby", "glowing");
		//Modelos que NO dibujan equipo. Vestir uno es tirar el trabajo: un aldeano con espada se ve igual
		//que uno sin ella. Fue el primer intento con el Guardia, y por eso está aquí.
		java.util.Set<String> noEquipment = java.util.Set.of("minecraft:villager", "minecraft:wandering_trader",
			"minecraft:iron_golem", "minecraft:ravager", "minecraft:vex", "minecraft:allay", "minecraft:slime",
			"minecraft:phantom", "minecraft:shulker", "minecraft:guardian", "minecraft:blaze", "minecraft:bat");

		JsonArray bestiary = readShippedPack("monsters.json");
		java.util.Set<String> models = new java.util.HashSet<>();
		int dressed = 0;
		for (JsonElement el : bestiary) {
			JsonObject json = el.getAsJsonObject();
			String id = json.get("id").getAsString();
			models.add(json.get("baseEntity").getAsString());
			if (!json.has("appearance")) continue;
			dressed++;

			JsonObject look = json.getAsJsonObject("appearance");
			boolean hasEquipment = false;
			for (String key : look.keySet()) {
				assertTrue(keys.contains(key), "\"" + key + "\" no es una clave de appearance (en " + id + ")");
				if (key.equals("baby") || key.equals("glowing")) continue;
				hasEquipment = true;
				assertTrue(look.get(key).getAsString().contains(":"),
					"el objeto de " + key + " en " + id + " necesita espacio de nombres (minecraft:...)");
			}
			assertTrue(!hasEquipment || !noEquipment.contains(json.get("baseEntity").getAsString()),
				id + " lleva equipo sobre un modelo que no lo dibuja (" + json.get("baseEntity").getAsString() + ")");

			//Ida y vuelta. Guardar una plantilla desde el juego pasa por toJson; si no escribe el aspecto, la
			//plantilla vuelve desnuda y el DM pierde justo lo que acaba de configurar.
			MonsterRegistry.MonsterStatBlock parsed = MonsterRegistry.parse(json);
			JsonObject again = MonsterRegistry.toJson(parsed);
			assertTrue(again.has("appearance") && again.getAsJsonObject("appearance").equals(look),
				"el aspecto de " + id + " no sobrevive a guardar y volver a leer");
		}

		//Las crías de dragón son Medianas y el resto Grandes o más. Es el único eje que separa a los 43
		//dragones, que comparten modelo porque vanilla solo tiene uno con esa forma.
		assertTrue(MonsterRegistry.parse(monsterJson(bestiary, "dndsheets:red_dragon_wyrmling")).appearance().baby(),
			"una cría de dragón debería salir como cría");
		assertTrue(!MonsterRegistry.parse(monsterJson(bestiary, "dndsheets:adult_red_dragon")).appearance().baby(),
			"y un adulto desde luego que no");

		//Tripwire de variedad: los números de partida eran 41 modelos y 0 vestidos.
		assertTrue(models.size() >= 55, "el bestiario debería usar al menos 55 modelos distintos, usa " + models.size());
		assertTrue(dressed >= 50, "al menos 50 monstruos deberían tener aspecto propio, lo tienen " + dressed);
		System.out.println("checkMonsterAppearance: OK, " + models.size() + " modelos y " + dressed + " monstruos con aspecto propio.");
	}

	/**
	 * <p>Packs de aspecto ({@link MonsterSkins}): la traducción entre un id del SRD y la entidad de un mod
	 * de criaturas instalado. Aquí solo se puede comprobar la mitad de cada línea —la de la izquierda—,
	 * porque la de la derecha vive en un mod que no está en el classpath del self-test. Es justo la mitad
	 * que se rompe sola: el id de la entidad lo protege {@code MonsterRegistry.reskin} en tiempo de
	 * ejecución (si no existe, no cambia nada), pero un monstruo mal escrito a la izquierda no lo protege
	 * nadie: no hay nada a lo que aplicar y el pack se queda corto en silencio.</p>
	 *
	 * <p>La otra comprobación es la lista {@code SHIPPED}: un pack añadido a los recursos y olvidado en la
	 * lista no se carga <b>nunca</b>, y no hay ningún síntoma que lo delate.</p>
	 */
	/**
	 * <p>Los iconos propios de los ítems del mod ({@link ItemLook}). Tres piezas tienen que coincidir y
	 * ninguna avisa cuando dejan de hacerlo: la constante del enum, el PNG y el modelo JSON. Si falta la
	 * textura, Minecraft pinta el cuadrado negro y morado; si falta el override en {@code token.json}, el
	 * ítem sale con el icono por defecto y parece que el aspecto "no se aplicó".</p>
	 *
	 * <p>Y lo que de verdad hace daño: el {@code CustomModelData} es la <b>posición</b> en el enum y queda
	 * escrito dentro de cada ItemStack ya repartido. Insertar una constante en medio le cambiaría el icono
	 * a todo lo que haya en el mundo de alguien, en silencio. Aquí se fija el orden.</p>
	 */
	/**
	 * <p>El libro de Patchouli. La Guía se lee de dos formas —libro escrito sin Patchouli, manual con
	 * índice con él— pero el texto es <b>uno</b>: las entradas apuntan a las mismas claves de idioma que
	 * las páginas del libro escrito.</p>
	 *
	 * <p>Lo que se comprueba es justo lo que nadie notaría: una página nueva que se añade al libro escrito
	 * y no al manual (o al revés) no falla en ningún sitio — simplemente falta en una de las dos versiones,
	 * y quien la lea por ahí no sabrá que existe. Se exige que cada página esté en exactamente una entrada,
	 * en las dos direcciones.</p>
	 */
	/**
	 * <p>Que todo id de vanilla que nombra el contenido exista <b>en 1.20.1</b>.</p>
	 *
	 * <p>Reportado desde una partida real: el Mangual declaraba {@code minecraft:mace}, que es un ítem de
	 * <b>1.21</b>. Aquí no resuelve, así que el arma caía a un palo y chocaba en la pestaña creativa con
	 * otra entrada. Nada falla: {@code buildWeaponStack} tiene un ítem por defecto justo para no caerse, y
	 * el único síntoma fue una línea de aviso en el log del cliente. Un id de otra versión, o mal escrito,
	 * se comporta exactamente igual — se traga el fallo y entrega otra cosa.</p>
	 *
	 * <p>La lista sale del {@code en_us.json} del cliente 1.20.1 (ver {@code tools/extract_vanilla_ids.py}),
	 * que es la única fuente que no hay que creerse: la trae el propio juego.</p>
	 */
	private static void checkVanillaIds() throws Exception {
		java.util.Set<String> vanilla = new java.util.HashSet<>();
		for (String line : Files.readAllLines(Path.of("src", "test", "resources", "vanilla_ids_1_20_1.txt"))) {
			if (!line.startsWith("#") && !line.isBlank()) vanilla.add(line.trim());
		}
		assertTrue(vanilla.size() > 1000, "la lista de ids de vanilla parece incompleta: " + vanilla.size());

		int checked = 0;
		for (JsonElement el : readShippedPack("weapons.json")) {
			JsonObject weapon = el.getAsJsonObject();
			checked += assertVanilla(vanilla, "item", weapon.has("item") ? weapon.get("item").getAsString() : null,
				"el arma " + weapon.get("id").getAsString());
		}
		for (JsonElement el : readShippedPack("items.json")) {
			JsonObject item = el.getAsJsonObject();
			checked += assertVanilla(vanilla, "item", item.has("item") ? item.get("item").getAsString() : null,
				"el objeto mágico " + item.get("id").getAsString());
		}
		for (JsonElement el : readShippedPack("presets.json")) {
			JsonObject preset = el.getAsJsonObject();
			String presetId = preset.get("id").getAsString();
			checked += assertVanilla(vanilla, "item",
				preset.has("startingWeapon") ? preset.get("startingWeapon").getAsString() : null,
				"el arma inicial de " + presetId);
			if (preset.has("startingGear")) {
				for (JsonElement gear : preset.getAsJsonArray("startingGear")) {
					checked += assertVanilla(vanilla, "item", gear.getAsString(), "el equipo inicial de " + presetId);
				}
			}
		}
		for (JsonElement el : readShippedPack("monsters.json")) {
			JsonObject monster = el.getAsJsonObject();
			String id = monster.get("id").getAsString();
			checked += assertVanilla(vanilla, "entity", monster.get("baseEntity").getAsString(), "el monstruo " + id);
			if (!monster.has("appearance")) continue;
			JsonObject look = monster.getAsJsonObject("appearance");
			for (String slot : look.keySet()) {
				if (slot.equals("baby") || slot.equals("glowing")) continue;
				checked += assertVanilla(vanilla, "item", look.get(slot).getAsString(), "el " + slot + " de " + id);
			}
		}
		System.out.println("checkVanillaIds: OK, " + checked + " ids de vanilla existen de verdad en 1.20.1.");
	}

	/** @return 1 si se comprobó, 0 si el id no era de vanilla (un mod, o ausente) y no toca comprobarlo. */
	private static int assertVanilla(java.util.Set<String> vanilla, String kind, String id, String who) {
		//Un id de otro mod no se puede comprobar aquí y no es un fallo: es justo lo que permite que un
		//addon apunte a la entidad de su mod. Lo que se exige es que lo que DICE ser de Minecraft lo sea.
		if (id == null || !id.startsWith("minecraft:")) return 0;
		assertTrue(vanilla.contains(kind + "/" + id.substring("minecraft:".length())),
			who + " usa \"" + id + "\", que no existe en Minecraft 1.20.1");
		return 1;
	}

	private static void checkPatchouliBook() throws Exception {
		Path book = Path.of("src", "main", "resources", "data", "dndsheets", "patchouli_books", "guide", "book.json");
		JsonObject meta = JsonParser.parseString(Files.readString(book)).getAsJsonObject();
		assertTrue(meta.get("i18n").getAsBoolean(), "el libro tiene que ir con i18n para reusar las claves de la Guía");
		assertTrue(meta.get("use_resource_pack").getAsBoolean(), "Patchouli quiere el contenido en assets/ desde 1.19");

		Path root = Path.of("src", "main", "resources", "assets", "dndsheets", "patchouli_books", "guide", "en_us");
		java.util.Set<String> categories = new java.util.HashSet<>();
		try (java.util.stream.Stream<Path> files = Files.list(root.resolve("categories"))) {
			for (Path file : files.toList()) categories.add(file.getFileName().toString().replace(".json", ""));
		}
		assertTrue(!categories.isEmpty(), "el libro no tiene ninguna categoría");

		//Las páginas que el libro escrito enumera, que es la lista de verdad: GuideBook.java. Y no solo
		//CUÁLES, también cómo están agrupadas y en qué orden: desde que el libro escrito se parte en los
		//mismos capítulos y entradas que el de Patchouli, que las dos listas contengan las mismas páginas
		//ya no basta — pueden repartirlas distinto y enseñarle dos libros diferentes al mismo lector,
		//según tenga Patchouli instalado o no.
		String guide = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets",
			"client", "gui", "GuideBook.java"));
		java.util.Map<String, String> entryChapter = new java.util.LinkedHashMap<>();
		java.util.Map<String, List<String>> entryPages = new java.util.LinkedHashMap<>();
		java.util.Set<String> written = new java.util.HashSet<>();
		String chapter = null;
		String current = null;
		java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("\"gui\\.dndsheets\\.guide\\.(cat|entry|page)\\.([a-z_0-9]+)\"").matcher(guide);
		while (m.find()) {
			switch (m.group(1)) {
				case "cat" -> chapter = m.group(2);
				case "entry" -> {
					current = m.group(2);
					assertTrue(chapter != null, "la entrada " + current + " de GuideBook no cuelga de ninguna categoría");
					entryChapter.put(current, chapter);
					entryPages.put(current, new java.util.ArrayList<>());
				}
				default -> {
					assertTrue(current != null, "la página " + m.group(2) + " de GuideBook no está en ninguna entrada");
					entryPages.get(current).add("gui.dndsheets.guide.page." + m.group(2));
					written.add("gui.dndsheets.guide.page." + m.group(2));
				}
			}
		}
		assertTrue(written.size() >= 26, "GuideBook debería enumerar las páginas de la Guía, encontré " + written.size());

		java.util.Map<String, String> placed = new java.util.HashMap<>();
		java.util.Set<String> filed = new java.util.HashSet<>();
		int entries = 0;
		try (java.util.stream.Stream<Path> files = Files.walk(root.resolve("entries"))) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
				JsonObject entry = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				entries++;
				String category = entry.get("category").getAsString();
				assertTrue(category.startsWith("dndsheets:") && categories.contains(category.substring(10)),
					file.getFileName() + " apunta a la categoría \"" + category + "\", que no existe");

				//El nombre del archivo ES el de la entrada en GuideBook: entries/dm/varas.json va con
				//"gui.dndsheets.guide.entry.varas". Sin esa convención no hay forma de emparejarlas.
				String name = file.getFileName().toString().replace(".json", "");
				filed.add(name);
				assertTrue(entryPages.containsKey(name), "la entrada " + name + " de Patchouli no está en GuideBook");
				assertTrue(category.equals("dndsheets:" + entryChapter.get(name)), name + " está en la categoría "
					+ category + " en Patchouli y en " + entryChapter.get(name) + " en GuideBook");

				List<String> here = new java.util.ArrayList<>();
				for (JsonElement page : entry.getAsJsonArray("pages")) {
					String key = page.getAsJsonObject().get("text").getAsString();
					here.add(key);
					assertTrue(written.contains(key), key + " (en " + file.getFileName() + ") no es una página de la Guía");
					String before = placed.put(key, file.getFileName().toString());
					assertTrue(before == null, key + " está en dos entradas: " + before + " y " + file.getFileName());
				}
				assertTrue(here.equals(entryPages.get(name)), name + " lleva otras páginas, o en otro orden, en"
					+ " GuideBook " + entryPages.get(name) + " que en Patchouli " + here);
			}
		}
		for (String key : written) {
			assertTrue(placed.containsKey(key), key + " está en el libro escrito pero en ninguna entrada de Patchouli");
		}
		for (String name : entryPages.keySet()) {
			assertTrue(filed.contains(name), "la entrada " + name + " de GuideBook no tiene archivo en Patchouli");
		}

		//Y que las claves nuevas (títulos de categoría y entrada) estén traducidas: checkLanguageFiles
		//compara los dos idiomas entre sí, pero no sabe que estos archivos las necesitan.
		JsonObject lang = JsonParser.parseString(Files.readString(
			Path.of("src", "main", "resources", "assets", "dndsheets", "lang", "es_es.json"))).getAsJsonObject();
		assertTrue(lang.has(meta.get("landing_text").getAsString()), "falta el texto de portada del libro");
		for (String category : categories) {
			assertTrue(lang.has("gui.dndsheets.guide.cat." + category), "falta el nombre de la categoría " + category);
		}
		//Los títulos de entrada los pide GuideBook por variable (entry.titleKey()), así que
		//checkTranslationKeysExist —que solo ve Component.translatable("literal")— no los alcanza.
		for (String name : entryPages.keySet()) {
			assertTrue(lang.has("gui.dndsheets.guide.entry." + name), "falta el nombre de la entrada " + name);
		}
		assertTrue(lang.has("gui.dndsheets.guide.index") && lang.has("gui.dndsheets.guide.back"),
			"faltan el título del índice o el enlace de vuelta del libro escrito");

		//Y que cada página quepa donde se lee. Una página de Patchouli que se pasa NO se recorta con
		//puntos suspensivos ni se parte en dos: lo que sobra desaparece, nada avisa, y solo se descubre
		//comparando el texto del archivo con el de la pantalla. Ya había pasado — "subir de nivel" medía
		//704 caracteres y enseñaba poco más de la mitad. El libro escrito sí parte solo lo que se pase
		//(GuideBook.wrap), así que este tope existe por Patchouli, que es el que no puede.
		//ponytail: 320 sale de medir el ancho de página de Patchouli, no de su código. Si alguna vez se
		//ve una página cortada por debajo de ese número, se baja el tope; no hay forma de calcularlo
		//desde aquí sin arrancar el juego.
		for (String file : new String[] { "es_es", "en_us" }) {
			JsonObject texts = JsonParser.parseString(Files.readString(
				Path.of("src", "main", "resources", "assets", "dndsheets", "lang", file + ".json"))).getAsJsonObject();
			for (String key : written) {
				assertTrue(texts.has(key), file + " no traduce la página " + key);
				int length = texts.get(key).getAsString().length();
				assertTrue(length <= 320, key + " mide " + length + " caracteres en " + file
					+ ": no cabe en una página de Patchouli, pártela en dos claves");
			}
		}

		System.out.println("checkPatchouliBook: OK, " + entries + " entradas en " + categories.size()
			+ " categorías cubren las " + written.size() + " páginas de la Guía.");
	}

	/**
	 * <p>El reparto en páginas del libro escrito de la Guía. Importa más de lo que parece: de cuántas
	 * páginas ocupe el índice depende el número al que salta CADA una de sus filas, así que una línea de
	 * más manda todos los enlaces a la página equivocada — y no falla nada, simplemente se abre otra.</p>
	 */
	private static void checkGuideLayout() {
		List<String> lines = new java.util.ArrayList<>();
		for (int i = 0; i < 25; i++) lines.add("linea" + i);

		//Primera página más corta (ahí va el título de la entrada), el resto enteras: 10 + 12 + 3.
		List<String> chunks = net.hawthorn.dndsheets.client.gui.GuideLayout.wrap(lines, 10, 12);
		assertTrue(chunks.size() == 3, "25 líneas en páginas de 10 y 12 son 3 páginas, no " + chunks.size());
		assertTrue(chunks.get(0).split(" ").length == 10 && chunks.get(1).split(" ").length == 12,
			"las páginas llenas no llegan a su límite: " + chunks.get(0).split(" ").length + " y "
				+ chunks.get(1).split(" ").length);
		//Lo que más duele si se rompe: texto perdido por el camino, que es justo lo que esto vino a
		//arreglar. Se compara el texto entero, no el número de trozos.
		assertTrue(String.join(" ", chunks).equals(String.join(" ", lines)), "wrap perdió o repitió texto");

		//Un texto que acaba justo en el límite no deja una página en blanco detrás.
		assertTrue(net.hawthorn.dndsheets.client.gui.GuideLayout.wrap(lines.subList(0, 10), 10, 12).size() == 1,
			"un texto que cabe justo debería ocupar una sola página");

		//El índice: filas de altura 1 salvo un título que ocupa dos, en páginas de 14 líneas.
		List<Integer> heights = new java.util.ArrayList<>();
		for (int i = 0; i < 20; i++) heights.add(i == 5 ? 2 : 1);
		List<Integer> pages = net.hawthorn.dndsheets.client.gui.GuideLayout.paginate(heights, 14);
		int rows = 0;
		int at = 0;
		for (int size : pages) {
			int tall = 0;
			for (int i = at; i < at + size; i++) tall += heights.get(i);
			assertTrue(tall <= 14, "una página del índice se pasa de 14 líneas: " + tall);
			at += size;
			rows += size;
		}
		assertTrue(rows == heights.size(), "el índice perdió filas al paginarse: " + rows + " de " + heights.size());
		assertTrue(pages.size() == 2, "21 líneas de índice caben en 2 páginas, no en " + pages.size());

		System.out.println("checkGuideLayout: OK, el libro escrito parte las páginas largas y el índice"
			+ " enlaza a la página que es.");
	}

	/**
	 * <p>{@code "ai": true} deja viva la IA de la entidad base al invocarla, para que las entidades de un
	 * mod de NPC sirvan de algo (patrullar, seguir al grupo) en vez de aparecer congeladas. Tres cosas
	 * tienen que seguir siendo verdad a la vez, y cada una se rompe sola:</p>
	 *
	 * <ul>
	 *   <li>que el campo se lea y se escriba, y que <b>por defecto sea false</b> — un pack ya escrito no
	 *       puede cambiar de comportamiento por añadir un campo (invariante 8);</li>
	 *   <li>que {@code spawnAt} pregunte por él en vez del {@code setNoAi(true)} fijo de siempre;</li>
	 *   <li>que {@code TurnManager.freeze} congele por "¿tiene la IA encendida?" y no por "¿le falta
	 *       bloque de estadísticas?". Si eso se revierte, un PNJ con IA se pasea por el combate durante
	 *       los turnos de los demás — y no falla nada, solo se juega mal.</li>
	 * </ul>
	 */
	private static void checkKeepsOwnAi() throws Exception {
		JsonObject plain = JsonParser.parseString("{\"id\":\"test:guardia\",\"hp\":10}").getAsJsonObject();
		assertTrue(!MonsterRegistry.parse(plain).keepsOwnAi(),
			"sin campo \"ai\" el monstruo tiene que salir congelado, como siempre");

		JsonObject withAi = JsonParser.parseString("{\"id\":\"test:guardia\",\"hp\":10,\"ai\":true}").getAsJsonObject();
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.parse(withAi);
		assertTrue(block.keepsOwnAi(), "\"ai\": true tiene que conservar la IA de la entidad base");

		//Ida y vuelta: el DM que capture ese monstruo desde el juego no puede perder el campo por el camino.
		JsonObject written = MonsterRegistry.toJson(block);
		assertTrue(written.has("ai") && written.get("ai").getAsBoolean(), "toJson pierde el campo \"ai\"");
		assertTrue(MonsterRegistry.parse(written).keepsOwnAi(), "el campo \"ai\" no sobrevive ida y vuelta");
		assertTrue(!MonsterRegistry.toJson(MonsterRegistry.parse(plain)).has("ai"),
			"toJson escribe \"ai\": false en packs que no lo piden, y eso es ruido en el JSON del DM");

		String spawner = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets",
			"MonsterRegistry.java"));
		assertTrue(spawner.contains("mob.setNoAi(!block.keepsOwnAi() && !block.ownClock())"),
			"spawnAt ha vuelto a congelar todo sin preguntar: \"ai\": true (o un jefe con reloj propio, que"
				+ " la necesita por definición) dejaría de hacer nada");

		String turns = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets",
			"TurnManager.java"));
		assertTrue(turns.contains("entity instanceof Mob mob && !mob.isNoAi()"),
			"freeze ha vuelto a mirar el bloque de estadísticas en vez de la IA: un PNJ con IA se movería"
				+ " durante los turnos de los demás");

		System.out.println("checkKeepsOwnAi: OK, la IA propia se declara, sobrevive ida y vuelta, y el modo"
			+ " turnos la sigue apagando mientras dura el combate.");
	}

	/**
	 * <p>La Forma Salvaje <b>escribe encima</b> de la hoja del druida (características físicas y CA) y
	 * guarda debajo lo que había para devolverlo. Ese es el trato entero, y si la vuelta se rompe no falla
	 * nada: queda un druida con la Fuerza de un oso y una CA que no es la suya, en una ficha que se
	 * persiste. Se comprueban las dos direcciones y, sobre todo, <b>que volver deje la hoja como estaba</b>
	 * — comparando el JSON completo, no campo a campo, para que también se note un residuo que nadie
	 * pensó en mirar.</p>
	 */
	private static void checkWildShape() {
		MonsterRegistry.MonsterStatBlock oso = new MonsterRegistry.MonsterStatBlock(
			"test:oso", "Oso pardo", "minecraft:polar_bear", 11, 34,
			Map.of("str", 19, "dex", 10, "con", 16, "int", 2, "wis", 13, "cha", 7), 2,
			List.of(), List.of(), Map.of(), Map.of(),
			CreatureType.BEAST, 0, 0, 1, null, false, false);

		//Caso 1: un druida normal, sin CA fijada a mano por el DM.
		JsonObject sheet = JsonParser.parseString(
			"{\"strength\":\"10\",\"dexterity\":\"14\",\"constitution\":\"12\",\"intelligence\":\"13\"}").getAsJsonObject();
		String antes = sheet.toString();

		DruidWildShapeManager.writeShape(sheet, oso, 7);
		assertTrue(DruidWildShapeManager.shapeOf(sheet) != null, "tras transformarse la hoja tiene que decir en qué está");
		assertTrue(sheet.get("strength").getAsString().equals("19"), "la Fuerza tiene que ser la de la bestia");
		assertTrue(sheet.get("armorClassOverride").getAsInt() == 11, "la CA tiene que ser la de la bestia");
		assertTrue(sheet.get("intelligence").getAsString().equals("13"),
			"la Inteligencia NO cambia: en 5e la bestia no te vuelve tonto");

		int back = DruidWildShapeManager.clearShape(sheet, 99);
		assertTrue(back == 7, "tiene que volver con los PG que tenía al transformarse, no con " + back);
		assertTrue(sheet.toString().equals(antes),
			"volver no dejó la hoja como estaba.\n  antes: " + antes + "\n  después: " + sheet);

		//Caso 2, el que de verdad corrompe: un druida al que el DM ya le había fijado la CA a mano. Si la
		//forma no distingue "no había override" de "había uno", vuelve con la CA de la bestia clavada.
		JsonObject conOverride = JsonParser.parseString(
			"{\"strength\":\"10\",\"dexterity\":\"14\",\"constitution\":\"12\",\"armorClassOverride\":18}").getAsJsonObject();
		String antesOverride = conOverride.toString();
		DruidWildShapeManager.writeShape(conOverride, oso, 5);
		assertTrue(conOverride.get("armorClassOverride").getAsInt() == 11, "mientras dura la forma manda la CA de la bestia");
		DruidWildShapeManager.clearShape(conOverride, 99);
		assertTrue(conOverride.toString().equals(antesOverride),
			"la CA que el DM había fijado no volvió: " + conOverride);

		//Y sin PG guardados (hoja de una versión anterior) cae al valor de respaldo en vez de a cero.
		assertTrue(DruidWildShapeManager.clearShape(new JsonObject(), 42) == 42,
			"sin PG anotados tiene que volver con el respaldo, no con 0");

		System.out.println("checkWildShape: OK, la forma escribe los números de la bestia y volver deja la"
			+ " hoja exactamente como estaba.");
	}

	/**
	 * <p>Un jefe con {@code "ownClock"} sale del ORDEN de turnos sin salir del combate. Todo lo que sostiene
	 * esa frase se rompe en silencio, así que se fija aquí:</p>
	 *
	 * <ul>
	 *   <li>que el campo se lea, se escriba y por defecto sea falso;</li>
	 *   <li>que el salto en {@code advance} pase por {@code step}, que es quien descuenta el asalto — si
	 *       alguien lo cambia por un {@code currentIndex++} suelto, una ronda con el jefe al final deja de
	 *       contar y todo lo que dure asaltos (muros, buffs, invocaciones) se congela;</li>
	 *   <li>que NO pase por {@code tryAct}, que programa el auto-avance: desde fuera del orden, le pasaría
	 *       el turno a otro cada seis segundos;</li>
	 *   <li>que siga contando para el fin del combate, o el encuentro terminaría con el dragón vivo;</li>
	 *   <li>y que las acciones legendarias queden a cero, para no darle dos economías de acción.</li>
	 * </ul>
	 */
	private static void checkOwnClock() throws Exception {
		JsonObject plain = JsonParser.parseString("{\"id\":\"test:x\",\"hp\":10}").getAsJsonObject();
		assertTrue(!MonsterRegistry.parse(plain).ownClock(), "sin el campo, un monstruo espera su turno como todos");
		JsonObject boss = JsonParser.parseString("{\"id\":\"test:x\",\"hp\":10,\"ownClock\":true}").getAsJsonObject();
		assertTrue(MonsterRegistry.parse(boss).ownClock(), "\"ownClock\": true tiene que sacarlo del orden");
		assertTrue(MonsterRegistry.toJson(MonsterRegistry.parse(boss)).get("ownClock").getAsBoolean(),
			"toJson pierde \"ownClock\": un jefe capturado por el DM volvería a hacer cola");
		assertTrue(!MonsterRegistry.toJson(MonsterRegistry.parse(plain)).has("ownClock"),
			"toJson escribe \"ownClock\": false donde nadie lo pidió, y eso es ruido en el JSON del DM");

		String turns = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets", "TurnManager.java"));
		assertTrue(turns.contains("isOffClock(level, current()); skipped++) step(level)"),
			"el salto del jefe tiene que pasar por step(): es quien cierra el asalto");
		assertTrue(turns.contains("if (MonsterRegistry.isOffClock(entity)) return;"),
			"freeze ha vuelto a anclar al jefe con reloj propio: dejaría de moverse por su cuenta");

		String actions = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets", "MonsterActionManager.java"));
		assertTrue(actions.contains("TurnManager.canActIgnoringTurn(monsterEntity)"),
			"autoAct tiene que preguntar por las condiciones, no por el turno, cuando el bloque lleva reloj propio");

		String legendary = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets", "LegendaryActionManager.java"));
		assertTrue(legendary.contains("if (block.ownClock()) return 0;"),
			"un jefe con reloj propio no puede tener ADEMAS acciones legendarias");

		//Y que el bestiario traiga jefes marcados de verdad: sin esto la funcion entera es config muerta.
		int apex = 0;
		for (JsonElement el : readShippedPack("monsters.json")) {
			JsonObject monster = el.getAsJsonObject();
			if (!monster.has("ownClock") || !monster.get("ownClock").getAsBoolean()) continue;
			apex++;
			assertTrue(monster.has("legendaryActions"),
				monster.get("id").getAsString() + " lleva reloj propio sin ser legendario: esto es para jefes");
		}
		assertTrue(apex >= 10, "el bestiario deberia traer jefes con reloj propio ya marcados, encontre " + apex);

		System.out.println("checkOwnClock: OK, " + apex + " jefes fuera del orden de turnos, dentro del combate.");
	}

	private static void checkItemLooks() throws Exception {
		Path textures = Path.of("src", "main", "resources", "assets", "dndsheets", "textures", "item");
		Path models = Path.of("src", "main", "resources", "assets", "dndsheets", "models", "item");
		String token = Files.readString(models.resolve("token.json"));

		for (ItemLook look : ItemLook.values()) {
			String name = look.textureName();
			assertTrue(Files.exists(textures.resolve(name + ".png")), "falta la textura de " + look + " (" + name + ".png)");
			assertTrue(Files.exists(models.resolve(name + ".json")), "falta el modelo de " + look);
			assertTrue(token.contains("\"custom_model_data\": " + look.customModelData() + " }, \"model\": \"dndsheets:item/" + name + "\""),
				"token.json no manda el custom_model_data " + look.customModelData() + " al modelo de " + look);
		}
		//La textura por defecto: la que se ve si un ItemStack viejo no trae CustomModelData.
		assertTrue(Files.exists(textures.resolve("token.png")), "falta el icono base de la ficha");

		//El orden, clavado. Cambiarlo es cambiarle el icono a lo ya repartido.
		assertTrue(ItemLook.DM_WAND.customModelData() == 1 && ItemLook.RAGE.customModelData() == 7
				&& ItemLook.SUMMON_CARD.customModelData() == 19,
			"el orden de ItemLook ha cambiado: los ítems que ya estén en el mundo de alguien cambiarían de icono");

		//Ningún ítem del mod debería seguir siendo un ítem de vanilla renombrado: era justo el problema.
		String abilityItem = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets", "AbilityItem.java"));
		assertTrue(abilityItem.contains("look.applyTo"), "AbilityItem debería construir sobre la ficha del mod, con su aspecto");
		System.out.println("checkItemLooks: OK, " + ItemLook.values().length + " ítems con textura y modelo propios.");
	}

	private static void checkMonsterSkins() throws Exception {
		java.util.Set<String> bestiary = new java.util.HashSet<>();
		for (JsonElement el : readShippedPack("monsters.json")) bestiary.add(el.getAsJsonObject().get("id").getAsString());

		Path dir = Path.of("src", "main", "resources", "dndsheets", "skins");
		java.util.List<Path> packs;
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			packs = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		assertTrue(!packs.isEmpty(), "no hay ningún pack de aspecto en " + dir);

		//La lista está escrita a mano en MonsterSkins; se compara con lo que hay en la carpeta.
		String source = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets", "MonsterSkins.java"));
		int entries = 0;
		for (Path pack : packs) {
			String fileName = pack.getFileName().toString();
			String modId = fileName.replace(".json", "");
			assertTrue(source.contains("\"" + modId + "\""), fileName + " no está en la lista SHIPPED de MonsterSkins: no se cargaría nunca");

			JsonObject json = JsonParser.parseString(Files.readString(pack)).getAsJsonObject();
			assertTrue(modId.equals(json.get("mod").getAsString()),
				"el campo \"mod\" de " + fileName + " no coincide con el nombre del archivo");
			assertTrue(json.has("name") && json.has("url"), fileName + " debería decir de qué mod es y dónde está");

			for (Map.Entry<String, JsonElement> skin : json.getAsJsonObject("skins").entrySet()) {
				assertTrue(bestiary.contains(skin.getKey()),
					"\"" + skin.getKey() + "\" (en " + fileName + ") no es un monstruo del bestiario");
				String entity = skin.getValue().getAsString();
				assertTrue(entity.startsWith(modId + ":"),
					"\"" + entity + "\" no es una entidad de " + modId + " (en " + fileName + ")");
				entries++;
			}
		}
		System.out.println("checkMonsterSkins: OK, " + packs.size() + " packs de aspecto y " + entries + " monstruos cubiertos.");
	}

	private static JsonObject monsterJson(JsonArray bestiary, String id) {
		for (JsonElement el : bestiary) {
			if (id.equals(el.getAsJsonObject().get("id").getAsString())) return el.getAsJsonObject();
		}
		throw new AssertionError("no está en el bestiario: " + id);
	}

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
	//arriba) — cierra el hallazgo F26. "1d1" tira siempre 1: da un total determinista sin depender
	//de aleatoriedad para poder comprobar la sustitución de $str/$prof/$hprof con un assert exacto.
	/**
	 * <p>Cierra F26, lo ultimo que quedaba abierto del ledger: {@code checkDice()} cubria {@code roll()} a
	 * fondo pero no {@code rollAttack}/{@code rollDamage}, que son las dos que de verdad deciden un combate.</p>
	 *
	 * <p>Determinista con {@code 1d1}, el mismo truco que ya usa checkDice: un d1 siempre saca 1, asi que el
	 * total es fijo y ademas es un 1 natural, que es la pifia. El critico no se puede forzar con dados (haria
	 * falta un 20 natural), pero la regla que lo gobierna, {@code criticalFrom}, si se comprueba directa —el
	 * self-test vive en el mismo paquete.</p>
	 */
	private static void checkAttackAndDamageRolls() {
		JsonObject sheet = new JsonObject();
		sheet.addProperty("strength", "16"); //modificador +3

		//Un 1 natural es pifia pase lo que pase, y nunca es critico a la vez.
		DiceManager.AttackRoll pifia = DiceManager.rollAttack(sheet, "1d1 + $str", DiceManager.Advantage.NORMAL);
		assertTrue(pifia.outcome().result() != null && pifia.outcome().result().getValue() == 4,
			"1d1 + $str (Fue 16) como ataque deberia dar total 4");
		assertTrue(pifia.criticalMiss(), "un 1 natural deberia ser pifia");
		assertTrue(!pifia.criticalHit(), "un 1 natural NO deberia ser critico");

		//Ventaja/desventaja tiran dos veces y anotan cual se descarta; con 1d1 las dos valen igual, asi que lo
		//comprobable es que el total no cambia y que la narracion dice cual fue.
		DiceManager.AttackRoll conVentaja = DiceManager.rollAttack(sheet, "1d1 + 5", DiceManager.Advantage.ADVANTAGE);
		assertTrue(conVentaja.outcome().result().getValue() == 6, "con ventaja sobre 1d1 + 5 el total sigue siendo 6");
		assertTrue(conVentaja.outcome().formatted().contains("ventaja")
			&& conVentaja.outcome().formatted().contains("se descarta"),
			"la tirada con ventaja deberia decir que descarta la otra: " + conVentaja.outcome().formatted());

		DiceManager.AttackRoll conDesventaja = DiceManager.rollAttack(sheet, "1d1 + 5", DiceManager.Advantage.DISADVANTAGE);
		assertTrue(conDesventaja.outcome().formatted().contains("desventaja"),
			"la tirada con desventaja deberia decirlo: " + conDesventaja.outcome().formatted());

		//Expresion invalida: ni critico ni pifia, y sin resultado. Si esto devolviera true en cualquiera de los
		//dos, un ataque con un dado mal escrito acertaria o fallaria solo.
		DiceManager.AttackRoll rota = DiceManager.rollAttack(sheet, "999999d20", DiceManager.Advantage.NORMAL);
		assertTrue(rota.outcome().result() == null && !rota.criticalHit() && !rota.criticalMiss(),
			"una tirada que el guard de dados absurdos rechaza no deberia ser ni critico ni pifia");

		//LA regla del critico en 5e: se doblan los DADOS, no el modificador. 2d1 + 3 normal = 5; critico = 7
		//(los dos dados otra vez), no 10. Doblar el total entero es el error clasico y aqui queda pinchado.
		DiceManager.DamageResult normal = DiceManager.rollDamage(sheet, "2d1 + 3", false);
		assertTrue(normal.amount() == 5, "2d1 + 3 sin critico deberia ser 5, fue " + normal.amount());

		DiceManager.DamageResult critico = DiceManager.rollDamage(sheet, "2d1 + 3", true);
		assertTrue(critico.amount() == 7,
			"2d1 + 3 critico deberia ser 7 (dados doblados, modificador una vez), fue " + critico.amount());
		assertTrue(critico.formatted() != null && critico.formatted().contains("TICO!"),
			"el daño critico deberia anunciarse: " + critico.formatted());

		DiceManager.DamageResult danoRoto = DiceManager.rollDamage(sheet, "999999d20", false);
		assertTrue(danoRoto.amount() == 0 && danoRoto.formatted() == null,
			"una expresion de daño invalida deberia dar 0 y sin texto, no un daño inventado");

		//criticalFrom: 20 salvo que la ficha lo baje, y con cortafuegos. Un 2 escrito en un JSON convertiria
		//CADA ataque en critico, y eso se descubriria en mitad de un combate.
		assertTrue(DiceManager.criticalFrom(null) == 20, "sin ficha se critica en 20");
		assertTrue(DiceManager.criticalFrom(new JsonObject()) == 20, "sin el campo se critica en 20");
		JsonObject campeon = new JsonObject();
		campeon.addProperty("criticalFrom", "19");
		assertTrue(DiceManager.criticalFrom(campeon) == 19, "el Campeon del guerrero critica en 19");
		JsonObject absurdo = new JsonObject();
		absurdo.addProperty("criticalFrom", "2");
		assertTrue(DiceManager.criticalFrom(absurdo) == 15, "un 2 deberia toparse en 15, no dejar criticar siempre");
		JsonObject basura = new JsonObject();
		basura.addProperty("criticalFrom", "no es un numero");
		assertTrue(DiceManager.criticalFrom(basura) == 20, "un valor no numerico deberia caer al 20 de siempre");

		//Un dado mal escrito en un pack de contenido no debe poder dar critico. La libreria no rechaza una
		//expresion que no entiende: le saca un numero igual, y ese numero entraba como si fuera el d20.
		//
		//Hay DOS filtros y cubren cosas distintas. El de rango (1..20) atrapa lo que se ve aqui: "20" sale
		//como "20 = 20", sin corchetes, asi que firstDieValue devuelve -1 y no hay ni critico ni pifia. El
		//de notacion (rollAttack exige que la expresion TENGA un dado) es el que cubre el caso peligroso de
		//verdad, "no soy un dado", que la libreria convierte en "99 = 99[99]" con un valor ALEATORIO — ese
		//no se puede fijar en una prueba, porque acertaria unas veces si y otras no, y una prueba
		//intermitente se acaba ignorando. Aqui se pincha lo determinista; el aleatorio lo cierra el filtro.
		DiceManager.AttackRoll sinDado = DiceManager.rollAttack(sheet, "20", DiceManager.Advantage.NORMAL);
		assertTrue(!sinDado.criticalHit(), "una expresion sin dados que vale 20 NO es un 20 natural");
		assertTrue(!sinDado.criticalMiss(), "y tampoco puede ser pifia");

		DiceManager.AttackRoll unoPlano = DiceManager.rollAttack(sheet, "1", DiceManager.Advantage.NORMAL);
		assertTrue(!unoPlano.criticalMiss(), "una expresion sin dados que vale 1 tampoco es una pifia");

		//Y con dado de verdad la deteccion sigue viva: 1d1 saca 1, que es pifia.
		assertTrue(DiceManager.rollAttack(sheet, "1d1", DiceManager.Advantage.NORMAL).criticalMiss(),
			"con un dado real, el 1 natural tiene que seguir siendo pifia");

		System.out.println("checkAttackAndDamageRolls: OK, pifia, ventaja/desventaja, dados doblados sin doblar el modificador y umbral de critico.");
	}

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
			CreatureType.HUMANOID, 0, 0, 1, null, false, false);  //Un licántropo es humanoide en 5e, también en forma de bestia.
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

		//Y el MÁXIMO también. Es un valor derivado (clase y nivel) que el servidor recalcula al guardar la
		//hoja, sin que el cliente se entere: mandando solo el actual, el cliente se quedaba con un máximo
		//viejo para siempre y el HUD acababa enseñando más espacios de los que caben — reportado jugando
		//como "Conjuros: 4/2".
		assertTrue(patch.has("spellSlotsMax") && patch.has("spellSlotsMaxByLevel"),
			"el parche debería llevar también el máximo, que cambia solo y el cliente no puede recalcular");
		for (String field : List.of("spellSlotsByLevel", "spellSlotsMaxByLevel", "spellSlotsCurrent", "spellSlotsMax")) {
			assertTrue(patch.get(field).toString().equals(sube3.get(field).toString()),
				"el parche debería mandar " + field + " tal y como quedó en la hoja");
		}
		//La comprobación que de verdad cierra el fallo: lo que el cliente reconstruye con el parche no puede
		//quedar en un estado imposible.
		assertTrue(patch.get("spellSlotsCurrent").getAsInt() <= patch.get("spellSlotsMax").getAsInt(),
			"el cliente nunca debería poder enseñar más espacios disponibles que el máximo");

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
	 * <p>A quién puede afectar un conjuro. Inmovilizar Persona sobre un esqueleto y Marchitar sobre un
	 * no-muerto funcionaban igual que sobre cualquier otra cosa: el conjuro tenía el nombre de la regla
	 * pero no la regla.</p>
	 */
	private static void checkSpellTargeting() throws Exception {
		SpellRegistry.Spell holdPerson = spellFromPack("dndsheets:hold_person");
		assertTrue(holdPerson.affects(CreatureType.HUMANOID), "Inmovilizar Persona sí afecta a un humanoide");
		assertTrue(!holdPerson.affects(CreatureType.UNDEAD), "pero no a un esqueleto");
		assertTrue(!holdPerson.affects(CreatureType.BEAST), "ni a un lobo");

		//La lista negra es la otra mitad, y no se puede escribir con la blanca sin listar trece tipos.
		SpellRegistry.Spell blight = spellFromPack("dndsheets:blight");
		assertTrue(blight.affects(CreatureType.HUMANOID) && blight.affects(CreatureType.DRAGON),
			"Marchitar afecta a casi todo");
		assertTrue(!blight.affects(CreatureType.UNDEAD) && !blight.affects(CreatureType.CONSTRUCT),
			"salvo a no-muertos y autómatas, que es lo que dice el SRD");

		//Un conjuro sin restricción afecta a todo, que es como se comportaban TODOS hasta ahora.
		SpellRegistry.Spell fireball = spellFromPack("dndsheets:fireball");
		for (CreatureType type : CreatureType.values()) {
			assertTrue(fireball.affects(type), "Bola de Fuego no debería excluir a nadie, y excluye a " + type);
		}

		//Un tipo desconocido NUNCA se filtra: un mob de otro mod sin bloque de estadísticas se comporta
		//como siempre en vez de volverse inmune a media lista de conjuros por no estar clasificado.
		assertTrue(holdPerson.affects(CreatureType.UNKNOWN) && blight.affects(CreatureType.UNKNOWN),
			"sin saber qué hay delante, la restricción no se aplica");

		System.out.println("checkSpellTargeting: OK, los conjuros con objetivo restringido distinguen a quién afectan.");
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

	/**
	 * <p>El que ataca abre el orden de turnos. Sin ello, el golpe que arranca el combate se perdía: se
	 * creaba el encuentro, se tiraba iniciativa, y si el atacante no ganaba su propia tirada su ataque se
	 * rechazaba por "no es tu turno" — el mismo clic funcionaba o desaparecía según un d20 que nadie había
	 * pedido tirar.</p>
	 */
	private static void checkInitiatorGoesFirst() {
		List<Integer> orden = new java.util.ArrayList<>(List.of(50, 40, 30, 20, 10));

		TurnManager.moveToFront(orden, id -> id, 30);
		assertTrue(orden.equals(List.of(30, 50, 40, 20, 10)),
			"el iniciador debería ir primero y el resto conservar su orden, y quedó " + orden);

		//La mitad importante: intercambiar con el primero —la implementación que sale sola— mandaría al 50
		//al puesto del 30 y desordenaría la iniciativa de los demás, que sí es sagrada.
		TurnManager.moveToFront(orden, id -> id, 10);
		assertTrue(orden.equals(List.of(10, 30, 50, 40, 20)), "y otra vez, sin barajar a los demás: " + orden);

		//Ya primero: no debería moverse nada.
		TurnManager.moveToFront(orden, id -> id, 10);
		assertTrue(orden.equals(List.of(10, 30, 50, 40, 20)), "mover al que ya era primero no debería cambiar nada");

		//Un iniciador que no está en la lista (fuera del radio del encuentro) deja el orden intacto en vez
		//de reventar el arranque del combate.
		TurnManager.moveToFront(orden, id -> id, 999);
		assertTrue(orden.equals(List.of(10, 30, 50, 40, 20)), "un iniciador que no está en el orden no debería tocarlo");

		System.out.println("checkInitiatorGoesFirst: OK, quien ataca abre el orden y no desordena al resto.");
	}

	/**
	 * <p>La tabla de cobertura. Es la mitad de la regla que se puede equivocar en silencio: un umbral
	 * corrido convierte un parapeto en una pared, o deja a alguien tapado hasta el cuello sin más CA que
	 * si estuviera en campo abierto.</p>
	 */
	private static void checkCover() {
		assertTrue(Cover.fromBlocked(0, 5) == Cover.NONE, "sin nada tapado no hay cobertura");
		assertTrue(Cover.fromBlocked(1, 5) == Cover.HALF, "un poco tapado es media cobertura");
		assertTrue(Cover.fromBlocked(2, 5) == Cover.HALF, "hasta la mitad, sigue siendo media");
		assertTrue(Cover.fromBlocked(3, 5) == Cover.THREE_QUARTERS, "pasada la mitad, tres cuartos");
		assertTrue(Cover.fromBlocked(4, 5) == Cover.THREE_QUARTERS, "casi todo tapado, tres cuartos");
		assertTrue(Cover.fromBlocked(5, 5) == Cover.TOTAL, "tapado del todo es cobertura total");

		//Los bonificadores son los del SRD, y son la mitad que de verdad se nota en la mesa.
		assertTrue(Cover.NONE.bonus() == 0 && Cover.HALF.bonus() == 2 && Cover.THREE_QUARTERS.bonus() == 5,
			"media cobertura da +2 y tres cuartos +5");
		//La total no vale infinito: hay una ruta donde llega igual (una flecha que YA impactó), y sumar un
		//infinito a la CA haría imposible un golpe que el mundo acaba de permitir.
		assertTrue(Cover.TOTAL.bonus() == 5 && Cover.TOTAL.blocksTargeting(),
			"la cobertura total impide apuntar, pero su bono tiene que ser un número usable");

		//La regla se compara en fracciones, no en un número fijo de rayos: cambiar el muestreo no debería
		//reescribirla. Con 4 muestras el umbral sigue cayendo en la mitad.
		assertTrue(Cover.fromBlocked(2, 4) == Cover.HALF && Cover.fromBlocked(3, 4) == Cover.THREE_QUARTERS,
			"la tabla debería depender de la fracción tapada, no del número de rayos");

		System.out.println("checkCover: OK, la tabla de cobertura y sus bonificadores son los del SRD.");
	}

	/**
	 * <p>Que las DOS rutas de ataque —un jugador ataca, un monstruo ataca— resuelvan por el mismo sitio.</p>
	 *
	 * <p>Esta comprobación existe porque la divergencia ya pasó tres veces seguidas, siempre en la misma
	 * dirección: la regla nueva se escribía donde ataca el jugador y la del monstruo se quedaba atrás. Un
	 * monstruo tiraba plano contra un objetivo derribado (media definición de cinco condiciones), la
	 * cobertura solo valía cuando atacaba un jugador, y Esquivar iba a repetir la historia. Fijar cada
	 * llamada por separado, que es lo que hacía la primera versión de esto, es fijar los síntomas: lo que
	 * hay que sostener es que no haya dos copias de la regla.</p>
	 */
	private static void checkAttackPathsShareRules() throws Exception {
		Path dir = Path.of("src", "main", "java", "net", "hawthorn", "dndsheets");

		for (String archivo : List.of("CombatManager.java", "MonsterActionManager.java")) {
			String cuerpo = methodBody(Files.readString(dir.resolve(archivo)), "resolveAttack(");
			assertTrue(cuerpo.contains("AttackRules.against("),
				archivo + ": resolveAttack debería resolver cobertura, CA, acierto y crítico por AttackRules");
			assertTrue(cuerpo.contains("AttackRules.advantageAgainst("),
				archivo + ": resolveAttack debería sacar la ventaja del objetivo de AttackRules");
		}

		//Y que AttackRules siga teniendo las tres reglas dentro, no solo que la llamen.
		String reglas = Files.readString(dir.resolve("AttackRules.java"));
		for (String regla : List.of("advantageAgainst(", "Cover.between(", "isDodging(", "reactiveArmorClass(", "autoCritInMelee(")) {
			assertTrue(reglas.contains(regla), "AttackRules debería aplicar " + regla + " y no lo hace");
		}

		//Las salvaciones son la otra mitad, y tenían la misma duplicación: quien lanza es distinto, la regla
		//no. Se comprueba igual, contra SaveRules.
		String castSaveSpell = methodBody(readSource("SpellCastManager.java"), "castSaveSpell(");
		String resolveSpell = methodBody(readSource("MonsterActionManager.java"), "resolveSpell(");
		for (String cuerpo : List.of(castSaveSpell, resolveSpell)) {
			assertTrue(cuerpo.contains("SaveRules.resolve("),
				"la salvación debería resolverse por SaveRules: cobertura, CD real, éxito y daño final");
		}
		String reglasSalvacion = readSource("SaveRules.java");
		for (String regla : List.of("Cover.between(", "rollSave(", "halfOnSave")) {
			assertTrue(reglasSalvacion.contains(regla), "SaveRules debería aplicar " + regla + " y no lo hace");
		}
		//Se comprueba que USE el nombre del personaje, en vez de que no aparezca el otro: la primera versión
		//buscaba la ausencia de "target.getName()" y saltaba por el comentario que explica justo eso.
		assertTrue(resolveSpell.contains("targetCombatant.name()"),
			"debería anunciar el nombre del personaje, no el de la cuenta de Minecraft");

		checkAdvantageSourcesArePooled();
		System.out.println("checkAttackPathsShareRules: OK, jugador y monstruo resuelven ataques y salvaciones con las mismas reglas y el mismo código.");
	}

	/**
	 * <p>Que TODAS las fuentes de ventaja se junten de una vez y no por partes.</p>
	 *
	 * <p>Casi se cuela al unificar las dos rutas: {@code combineAdvantage} colapsa a "normal" cuando hay
	 * ventaja y desventaja a la vez, que es la regla correcta de 5e, pero por eso mismo <b>no se puede
	 * anidar</b> — un "normal" que salió de dos fuentes anulándose es indistinguible de "ninguna fuente", y
	 * la siguiente combinación deja ganar sola a la ventaja del atacante.</p>
	 */
	private static void checkAdvantageSourcesArePooled() throws Exception {
		//Objetivo derribado: ventaja de cerca, desventaja de lejos (Combatant.advantageAgainst). Se reusa el
		//FakeCombatant de checkCombatantRules, que existe justo para probar esto sin un mundo detrás.
		Combatant derribado = new FakeCombatant(0);
		derribado.addCondition(Condition.DERRIBADO);

		//A distancia, un derribado da DESVENTAJA. Con un atacante que trae VENTAJA, las dos fuentes se
		//anulan: la respuesta de 5e es normal, "sin importar cuántas haya de cada".
		assertTrue(AttackRules.advantageAgainst(derribado, false, DiceManager.Advantage.ADVANTAGE) == DiceManager.Advantage.NORMAL,
			"ventaja del atacante contra la desventaja de disparar a alguien derribado debería anularse");
		//Y de cerca, las dos son ventaja: no hay nada que anular.
		assertTrue(AttackRules.advantageAgainst(derribado, true, DiceManager.Advantage.ADVANTAGE) == DiceManager.Advantage.ADVANTAGE,
			"de cerca, un derribado da ventaja y se suma a la del atacante");
		//Sin nada del atacante, manda el estado del objetivo tal cual.
		assertTrue(AttackRules.advantageAgainst(derribado, true) == DiceManager.Advantage.ADVANTAGE,
			"sin fuentes del atacante debería quedar lo que dé el objetivo");

		//Y la propiedad que las tres afirmaciones de arriba NO pueden ver: que las fuentes entren en UNA
		//sola combinación. El caso que se rompe al anidar es que la ventaja y la desventaja que se anulan
		//caigan las dos del mismo lado (objetivo derribado que además Esquiva, atacado con ventaja), y ese
		//depende de estado de turno que no existe fuera del juego. Se sostiene por estructura: probé a
		//anidar la combinación y ninguna de las tres afirmaciones se enteró.
		String cuerpo = methodBody(readSource("AttackRules.java"), "advantageAgainst(");
		int combinaciones = cuerpo.split("combineAdvantage\\(", -1).length - 1;
		assertTrue(combinaciones == 1,
			"advantageAgainst debería combinar TODAS las fuentes de una vez y hace " + combinaciones
				+ " combinaciones: anidarlas convierte una ventaja y una desventaja que se anulaban en un "
				+ "\"normal\" indistinguible de \"ninguna fuente\"");
	}

	/**
	 * <p>La lista de pasos para dejar una ficha jugable (raza, clase por preset, trasfondo y competencias).
	 * Es una pantalla, así que casi todo lo suyo solo se ve jugando; lo que sí se puede sujetar son las dos
	 * formas en que dejaría de funcionar sin que nada se queje.</p>
	 *
	 * <p>La primera es que el selector de opciones vuelva <b>a la hoja</b> en vez de a quien lo pidió: la
	 * lista de pasos pide razas y trasfondos, y si el servidor la devuelve a la hoja, el jugador sale
	 * expulsado de la configuración a media faena y no hay error en ningún sitio. La segunda es que la
	 * pantalla exista y no la abra nadie.</p>
	 */
	private static void checkCharacterSetup() throws Exception {
		String handler = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets",
			"network", "CharacterOptionsListMessage.java"));
		assertTrue(!handler.contains("instanceof CharacterSheetScreen"),
			"la lista de opciones tiene que volver a la pantalla que la pidió, sea cual sea: si vuelve solo "
				+ "a la hoja, elegir una raza desde la lista de pasos echa al jugador de la configuración.");

		String list = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets",
			"client", "gui", "CharacterListScreen.java"));
		assertTrue(list.contains("CharacterSetupScreen.open"),
			"nadie abre la lista de pasos: una pantalla que no se puede abrir es lo mismo que no tenerla.");

		System.out.println("checkCharacterSetup: OK, los pasos se abren desde algún sitio y los selectores vuelven a ellos.");
	}

	/**
	 * <p>Multiclase. Es lo último del roadmap por una razón: rehace tres tablas que ya estaban fijadas nivel
	 * por nivel, y las tres fallan callando. Lo que se comprueba aquí es justo eso — el reparto de dados de
	 * golpe, el nivel de lanzador y que una hoja SIN reparto siga comportándose exactamente como antes.</p>
	 *
	 * <p>El caso que más importa es el redondeo del semilanzador, porque hay dos reglas distintas con el
	 * mismo aspecto: de una sola clase, la mitad hacia <b>arriba</b> (un paladín de nivel 2 ya lanza); en
	 * multiclase, la mitad hacia <b>abajo</b> (un paladín 2 aporta 1). Escribir las dos igual da una tabla
	 * que acierta en la mitad de los casos, que es peor que una que falle siempre.</p>
	 */
	private static void checkMulticlass() throws Exception {
		assertTrue(ClassLevels.total(mix("fighter", 3, "wizard", 2)) == 5, "los niveles se suman");

		assertTrue(ClassLevels.casterLevel(mix("fighter", 3, "wizard", 2)) == 2,
			"solo los niveles de lanzador cuentan para la tabla");
		//La trampa: dos semilanzadores de nivel 5 aportan 2 + 2, no 3 + 3.
		assertTrue(ClassLevels.casterLevel(mix("paladin", 5, "ranger", 5)) == 4,
			"en multiclase el semilanzador redondea hacia ABAJO");
		assertTrue(ClassLevels.casterLevel(mix("paladin", 1, "wizard", 1)) == 1,
			"un semilanzador de nivel 1 no aporta nada todavía");
		//El brujo va por libre: sus espacios son otra reserva, así que no suman a esta tabla.
		assertTrue(ClassLevels.casterLevel(mix("warlock", 5, "wizard", 3)) == 3,
			"los niveles de brujo no entran en el nivel de lanzador");
		assertTrue(ClassLevels.casterLevel(mix("fighter", 5, "rogue", 5)) == 0,
			"dos clases que no lanzan no lanzan");

		//PG: el dado entero es el de la PRIMERA clase, y por eso el orden cambia el resultado.
		assertTrue(ClassLevels.maxHitPoints(mix("fighter", 1, "wizard", 1), 14) == 18,
			"guerrero primero: 10+2 del d10 entero, y 4+2 del d6 medio");
		assertTrue(ClassLevels.maxHitPoints(mix("wizard", 1, "fighter", 1), 14) == 16,
			"mago primero: 6+2 y luego 6+2 del d10 medio — el orden importa y es el de la hoja");
		//Constitución penosa: cada nivel da al menos 1 PG, igual que en la ruta de una sola clase.
		assertTrue(ClassLevels.maxHitPoints(mix("wizard", 3), 3) == 4,
			"con Constitución 3 cada nivel después del primero da 1 PG, nunca menos");

		//Sembrar el reparto: multiclasar a un guerrero de nivel 5 tiene que dejarlo en 5 + 1, no en 0 + 1.
		JsonObject sheet = new JsonObject();
		sheet.addProperty("appliedPresetId", "fighter");
		sheet.addProperty("characterLevel", "5");
		java.util.Map<String, Integer> levels = ClassLevels.addLevel(sheet, "wizard", "fighter", 5);
		assertTrue(levels.get("fighter") == 5 && levels.get("wizard") == 1, "el nivel que ya tenía no se pierde");
		assertTrue(sheet.get("characterLevel").getAsString().equals("6"), "y el total se reescribe");
		assertTrue(sheet.get("characterClass").getAsString().equals("Guerrero 5 / Mago 1"),
			"la clase se lee como el reparto que es, con los nombres de los presets");

		//La cadena entera: reparto -> nivel de lanzador -> tabla de lanzador COMPLETO -> hoja.
		JsonObject caster = new JsonObject();
		JsonObject classLevels = new JsonObject();
		classLevels.addProperty("fighter", 3);
		classLevels.addProperty("wizard", 3);
		caster.add("classLevels", classLevels);
		SpellSlots.applyProgression(caster, "Guerrero 3 / Mago 3", 6);
		JsonObject max = caster.getAsJsonObject("spellSlotsMaxByLevel");
		assertTrue(max.get("1").getAsInt() == 4 && max.get("2").getAsInt() == 2,
			"un guerrero 3 / mago 3 lanza como un lanzador completo de nivel 3, no de nivel 6");
		assertTrue(!max.has("3") || max.get("3").getAsInt() == 0, "y no llega a los de nivel 3");

		//Y lo que NO puede cambiar: una hoja sin reparto es exactamente la de siempre.
		JsonObject single = new JsonObject();
		single.addProperty("characterClass", "Mago");
		single.addProperty("constitution", "14");
		assertTrue(!ClassLevels.isMulticlass(single), "sin campo no hay multiclase");
		assertTrue(CharacterRules.maxHitPointsFor(single, 3) == ClassLevels.maxHitPoints(mix("wizard", 3), 14),
			"y la ruta de siempre da lo mismo que el reparto de una sola clase: si no, multiclasar y "
				+ "volver atrás cambiaría los PG de un personaje sin tocarle el nivel");

		System.out.println("checkMulticlass: OK, dados por clase, nivel de lanzador y la hoja sin reparto intacta.");
	}

	private static java.util.Map<String, Integer> mix(Object... pairs) {
		java.util.Map<String, Integer> levels = new java.util.LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) levels.put((String) pairs[i], (Integer) pairs[i + 1]);
		return levels;
	}

	/**
	 * <p>Dotes. El SRD trae <b>una</b> (Luchador) y no es un descuido de la importación: las demás están en
	 * el Manual del Jugador. Así que lo que hay que comprobar no es el catálogo sino el mecanismo, que es lo
	 * que una mesa o un addon van a usar para meter las suyas.</p>
	 *
	 * <p>Lo único con mecánica real de una dote es que sube características, y ahí hay dos formas de
	 * romperla en silencio: pasarse del tope de 20 —lo que la haría mejor que la mejora que sustituye— y
	 * dejar que se coja dos veces.</p>
	 */
	private static void checkFeats() throws Exception {
		int loaded = 0;
		for (JsonElement el : readShippedPack("feats.json")) {
			FeatRegistry.Feat feat = FeatRegistry.parse(el.getAsJsonObject());
			FeatRegistry.register(feat);
			assertTrue(!feat.name().isBlank(), "la dote " + feat.id() + " no tiene nombre");
			assertTrue(!feat.description().isBlank(),
				"la dote " + feat.id() + " no explica lo que hace: casi ninguna tiene mecánica en este motor, "
					+ "así que el texto es LO que la dote es.");
			loaded++;
		}
		assertTrue(loaded > 0, "el pack de dotes que se envía está vacío");
		assertTrue(FeatRegistry.get("dndsheets:grappler") != null, "falta Luchador");

		//Nivel mínimo. Lo trajo el SRD 5.2 y no es decoración: un Don Épico es de nivel 19, y sin puerta
		//aparece en la mejora del nivel 4 como una opción más.
		FeatRegistry.Feat boon = FeatRegistry.get("dndsheets:boon_of_truesight");
		assertTrue(boon != null && boon.minLevel() == 19,
			"los Dones Épicos del SRD 5.2 tienen que llegar con su nivel 19 puesto");
		assertTrue(FeatRegistry.get("dndsheets:archery").minLevel() == 1,
			"y un estilo de combate es de nivel 1: si TODO lo importado quedara con el nivel del último tipo "
				+ "leído, la lista de un nivel 4 se vaciaría o se llenaría entera");

		JsonObject young = new JsonObject();
		assertTrue(!FeatRegistry.grant(young, "dndsheets:boon_of_truesight", 20, 4),
			"un nivel 4 no puede coger un Don Épico");
		assertTrue(FeatRegistry.grant(young, "dndsheets:boon_of_truesight", 20, 19),
			"y a nivel 19 sí");
		assertTrue(!FeatRegistry.availableAt(boon, 18) && FeatRegistry.availableAt(boon, 19),
			"la puerta es >= y no >, o el nivel 19 exacto se quedaría fuera");

		//La Mejora de Característica es una dote en el SRD 5.2, pero aquí ES el recurso que las dotes gastan.
		//Importarla la habría dejado como alternativa a sí misma: coges la mejora para coger la mejora.
		assertTrue(FeatRegistry.get("dndsheets:ability_score_improvement") == null,
			"la Mejora de Característica no puede entrar como dote: es lo que las dotes gastan");

		//Una dote de prueba que sí sube características: es la mitad con mecánica y la que se puede romper.
		JsonObject json = new JsonObject();
		json.addProperty("id", "test:forzudo");
		json.addProperty("name", "Forzudo");
		json.addProperty("description", "Sube Fuerza.");
		JsonObject abilities = new JsonObject();
		abilities.addProperty("str", 2);
		json.add("abilities", abilities);
		FeatRegistry.register(FeatRegistry.parse(json));

		JsonObject sheet = new JsonObject();
		sheet.addProperty("strength", "16");
		assertTrue(FeatRegistry.grant(sheet, "test:forzudo", 20, 4), "una dote nueva se puede coger");
		assertTrue(sheet.get("strength").getAsString().equals("18"), "y sube la característica que dice");
		assertTrue(FeatRegistry.takenBy(sheet).contains("test:forzudo"), "y queda anotada en la hoja");
		assertTrue(!FeatRegistry.grant(sheet, "test:forzudo", 20, 4), "la misma dote no se coge dos veces");
		assertTrue(!FeatRegistry.grant(sheet, "test:no_existe", 20, 4), "y una que no existe tampoco");

		//El tope es el mismo que el de la mejora: una dote que lo saltara sería estrictamente mejor que ella.
		JsonObject strong = new JsonObject();
		strong.addProperty("strength", "19");
		FeatRegistry.grant(strong, "test:forzudo", 20, 4);
		assertTrue(strong.get("strength").getAsString().equals("20"), "el bono se recorta en 20");

		assertTrue("strength".equals(CharacterRules.abilityFieldFor("str"))
			&& "strength".equals(CharacterRules.abilityFieldFor("Strength")),
			"el contenido escribe \"str\" y la hoja \"strength\": las dos formas tienen que llegar al mismo campo");
		assertTrue(CharacterRules.abilityFieldFor("fuerza") == null,
			"y lo que no se reconoce no puede escribir en un campo inventado");

		//Gastar la mejora pendiente necesita un jugador, así que lo que se sujeta es que siga escrito: sin la
		//comprobación, un nivel 1 coge dotes; sin el descuento, una mejora pendiente da dotes infinitas.
		String manager = readSource("LevelUpManager.java");
		String applyFeat = manager.substring(manager.indexOf("public static boolean applyFeat("));
		applyFeat = applyFeat.substring(0, applyFeat.indexOf("\n\t}"));
		assertTrue(applyFeat.contains("pendingOf(sheet) <= 0"),
			"applyFeat tiene que exigir una mejora pendiente: si no, cualquiera coge dotes a nivel 1");
		assertTrue(applyFeat.contains("PENDING, pendingOf(sheet) - 1"),
			"y tiene que gastarla: si no, una sola mejora da todas las dotes del pack");

		System.out.println("checkFeats: OK, " + loaded + " dote(s) del SRD, el tope de característica y la mejora que gastan.");
	}

	/**
	 * <p>Subclases: la segunda mitad de lo que es un personaje en 5e. Se comprueban tres cosas distintas —
	 * los datos que se envían, la puerta del nivel y el único gancho de motor que tienen.</p>
	 *
	 * <p>La puerta del nivel es la que importa: {@code applySubclass} tiene que negarse a aplicar una
	 * subclase que el personaje todavía no puede elegir. Filtrar solo la lista que se pinta no es filtrar —
	 * un cliente modificado manda el id que quiera, y sin esta comprobación un guerrero de nivel 1 se lleva
	 * el rango de crítico del Campeón.</p>
	 */
	private static void checkSubclasses() throws Exception {
		java.util.Set<String> spells = new java.util.HashSet<>();
		for (JsonElement el : readShippedPack("spells.json")) spells.add(el.getAsJsonObject().get("id").getAsString());
		java.util.Set<String> traits = new java.util.HashSet<>();
		for (JsonElement el : readShippedPack("traits.json")) traits.add(el.getAsJsonObject().get("id").getAsString());

		java.util.Set<String> seen = new java.util.HashSet<>();
		int checked = 0;
		for (JsonElement el : readShippedPack("presets.json")) {
			JsonObject preset = el.getAsJsonObject();
			String presetId = preset.get("id").getAsString();
			assertTrue(preset.has("subclasses"),
				"el preset " + presetId + " no ofrece ninguna subclase: en 5e toda clase tiene arquetipo");
			for (JsonElement sub : preset.getAsJsonArray("subclasses")) {
				JsonObject subclass = sub.getAsJsonObject();
				String id = subclass.get("id").getAsString();
				assertTrue(seen.add(id), "la subclase " + id + " está repetida: el id es lo que se elige");

				int level = subclass.has("level") ? subclass.get("level").getAsInt() : 3;
				assertTrue(level >= 1 && level <= 20, id + " se elige al nivel " + level + ", que no existe");
				//El rango de crítico del motor está acotado: un dato fuera de ese rango se aplicaría
				//recortado, o sea que el JSON diría una cosa y la mesa jugaría otra.
				if (subclass.has("criticalFrom")) {
					int from = subclass.get("criticalFrom").getAsInt();
					assertTrue(from >= 15 && from <= 20, id + " critica desde " + from + ", fuera de lo que el motor acepta");
				}
				if (subclass.has("spells")) {
					for (JsonElement spell : subclass.getAsJsonArray("spells")) {
						assertTrue(spells.contains(spell.getAsString()),
							id + " concede \"" + spell.getAsString() + "\", que no está en el pack de hechizos");
					}
				}
				if (subclass.has("traits")) {
					for (JsonElement trait : subclass.getAsJsonArray("traits")) {
						assertTrue(traits.contains(trait.getAsString()),
							id + " concede el rasgo \"" + trait.getAsString() + "\", que no existe");
					}
				}
				checked++;
			}
		}

		//La puerta del nivel, sobre el preset de guerrero que checkPresets() acaba de registrar.
		JsonObject sheet = new JsonObject();
		sheet.addProperty("appliedPresetId", "fighter");
		sheet.addProperty("characterLevel", "2");
		assertTrue(PresetRegistry.availableSubclasses(sheet).isEmpty(),
			"a nivel 2 un guerrero todavía no elige arquetipo");
		assertTrue(!PresetRegistry.applySubclass(sheet, "fighter:champion"),
			"y pedirlo igualmente tiene que rebotar en el servidor, no solo faltar en la lista");
		assertTrue(!sheet.has("criticalFrom"), "y no dejar nada escrito en la hoja al rebotar");

		sheet.addProperty("characterLevel", "3");
		assertTrue(PresetRegistry.availableSubclasses(sheet).size() == 1, "a nivel 3 ya hay una que elegir");
		assertTrue(PresetRegistry.applySubclass(sheet, "fighter:champion"), "y se puede elegir");
		assertTrue(sheet.get("characterSubclass").getAsString().equals("Campeón")
			&& sheet.get("appliedSubclassId").getAsString().equals("fighter:champion"),
			"la hoja guarda el id para las reglas y el nombre para la pantalla, como ya hace con el preset");
		//Una subclase de otra clase no se cuela ni con el nivel correcto.
		assertTrue(!PresetRegistry.applySubclass(sheet, "wizard:evocation"),
			"un guerrero no puede elegir la escuela de un mago");

		//El gancho de motor, que es lo único que la subclase cambia en una tirada.
		assertTrue(DiceManager.criticalFrom(new JsonObject()) == 20, "sin nada escrito se critica con 20");
		assertTrue(DiceManager.criticalFrom(sheet) == 19, "el Campeón critica con 19");
		JsonObject absurd = new JsonObject();
		absurd.addProperty("criticalFrom", "2");
		assertTrue(DiceManager.criticalFrom(absurd) == 15,
			"un número absurdo en un JSON se recorta: si no, cada ataque sería crítico y se descubriría jugando");
		JsonObject garbage = new JsonObject();
		garbage.addProperty("criticalFrom", "diecinueve");
		assertTrue(DiceManager.criticalFrom(garbage) == 20, "y lo que no es un número se ignora");

		System.out.println("checkSubclasses: OK, " + checked + " subclases, la puerta del nivel y el rango de crítico.");
	}

	/**
	 * <p>Competencias de habilidad. La regla es de texto —añadir o quitar {@code + $prof} en la expresión de
	 * la tirada— y por eso se puede fijar aquí entera: lo que no se puede probar sin servidor es quién tiene
	 * permiso para escribirla, y de eso se ocupa el mensaje.</p>
	 *
	 * <p>Lo que de verdad protege esta comprobación es el <b>orden</b>. El índice es lo único que liga una
	 * fila de la pantalla con una casilla del array {@code skills}, así que si la lista de etiquetas de la
	 * hoja y la de {@link RollIndex} se desordenan entre sí, nada falla: simplemente marcas Atletismo y te
	 * llevas competencia en Sigilo.</p>
	 */
	private static void checkSkillProficiency() throws Exception {
		assertTrue(RollIndex.withProficiency("1d20 + $dex", true).equals("1d20 + $dex + $prof"),
			"marcar competencia añade el término");
		assertTrue(RollIndex.withProficiency("1d20 + $dex + $prof", true).equals("1d20 + $dex + $prof"),
			"marcarla dos veces no lo duplica");
		assertTrue(RollIndex.withProficiency("1d20 + $dex + $prof", false).equals("1d20 + $dex"),
			"desmarcarla lo quita entero, sin dejar el + suelto");
		//Reescribir la expresión desde la característica sería más corto y borraría el +2 de un objeto
		//mágico que alguien puso a mano. El editor de tiradas existe justo para poder ponerlo.
		assertTrue(RollIndex.withProficiency("1d20 + $dex + 2", true).equals("1d20 + $dex + 2 + $prof"),
			"lo que ya había en la expresión sigue ahí");
		assertTrue(RollIndex.withProficiency("1d20 + $dex + 2 + $prof", false).equals("1d20 + $dex + 2"),
			"y sigue ahí al desmarcarla");
		//$hprof es MEDIA competencia (bardo, pícaro experto): quitar competencia no puede comérselo.
		assertTrue(RollIndex.withProficiency("1d20 + $hprof", false).equals("1d20 + $hprof"),
			"media competencia no es competencia y no se toca");
		assertTrue(!RollIndex.isProficient("1d20 + $hprof"), "y tampoco cuenta como marcada");
		assertTrue(RollIndex.isProficient("1d20 + $dex + $prof"), "una expresión con el término está marcada");
		//Escrito al principio no lo produce la pantalla, pero un DM puede haberlo escrito así a mano: si se
		//detecta como marcada, tiene que poder desmarcarse, o la casilla dice una cosa y la tirada otra.
		assertTrue(RollIndex.withProficiency("$prof + 1d20", false).equals("1d20"),
			"también se quita si estaba escrito delante");

		assertTrue(RollIndex.skillAbility(0).equals("str"), "Atletismo es de Fuerza");
		assertTrue(RollIndex.skillAbility(3).equals("dex") && RollIndex.skillAbility(1).equals("dex"),
			"Acrobacias y Sigilo son de Destreza");
		assertTrue(RollIndex.skillAbility(4).equals("int") && RollIndex.skillAbility(8).equals("int"),
			"las cinco de conocimiento son de Inteligencia");
		assertTrue(RollIndex.skillAbility(9).equals("wis") && RollIndex.skillAbility(13).equals("wis"),
			"las cinco de percepción son de Sabiduría");
		assertTrue(RollIndex.skillAbility(14).equals("cha") && RollIndex.skillAbility(17).equals("cha"),
			"las cuatro sociales son de Carisma");
		assertTrue(RollIndex.basicNames(RollIndex.Category.SKILLS).size() == RollIndex.SKILL_COUNT,
			"las dos listas de habilidades de RollIndex tienen que medir lo mismo");

		//El orden de las etiquetas de la hoja contra el de RollIndex, que es el del array "skills".
		String sheetScreen = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets",
			"client", "gui", "CharacterSheetScreen.java"));
		java.util.regex.Matcher labels = java.util.regex.Pattern
			.compile("gui[.]dndsheets[.]character_sheet[.]label_skill_([a-z]+)").matcher(sheetScreen);
		List<String> inScreen = new java.util.ArrayList<>();
		while (labels.find()) {
			if (!inScreen.contains(labels.group(1))) inScreen.add(labels.group(1));
		}
		assertTrue(inScreen.size() == RollIndex.SKILL_COUNT,
			"la hoja debería nombrar las " + RollIndex.SKILL_COUNT + " habilidades, encontré " + inScreen.size());
		for (int index = 0; index < RollIndex.SKILL_COUNT; index++) {
			assertTrue(RollIndex.skillLangKey(index).endsWith("_" + inScreen.get(index)),
				"la habilidad " + index + " es \"" + inScreen.get(index) + "\" en la hoja y \""
					+ RollIndex.skillLangKey(index) + "\" en RollIndex: con las dos listas desordenadas entre "
					+ "sí, marcar una habilidad da competencia en otra y nada falla.");
		}

		System.out.println("checkSkillProficiency: OK, el término de competencia y las 18 habilidades en el mismo orden.");
	}

	/**
	 * <p>Traer una construcción de fuera: el nombre del archivo tiene que acabar siendo una ruta válida de
	 * {@code ResourceLocation}, y los archivos que uno se descarga se llaman "Casa Grande (v2).nbt".</p>
	 *
	 * <p>Es el mismo fallo que dio {@code npc-capit-n} en su día, y por eso se comprueba igual: los acentos
	 * se quitan ANTES de filtrar caracteres, porque descomponer después convierte la letra en separador. En
	 * un mod en español eso no es un caso raro, es la mitad de los nombres.</p>
	 */
	private static void checkStructureImport() {
		assertTrue(DungeonManager.structureNameFor("Casa Grande (v2)").equals("casa_grande_v2"),
			"un nombre de archivo normal tiene que salir como ruta válida");
		assertTrue(DungeonManager.structureNameFor("Capitán").equals("capitan"),
			"los acentos se quitan, no se convierten en separador");
		assertTrue(DungeonManager.structureNameFor("torre").equals("torre"), "lo que ya vale no se toca");
		assertTrue(DungeonManager.structureNameFor("___torre___").equals("torre"),
			"un separador suelto al principio o al final no es parte del nombre");
		//Un nombre entero en caracteres no latinos no puede dar una ruta vacía: eso sería un id inválido.
		for (String odd : List.of("", "   ", "!!!", "日本")) {
			assertTrue(DungeonManager.structureNameFor(odd).equals("estructura"),
				"un nombre del que no queda nada usable tiene que caer en uno por defecto");
		}
		assertTrue(DungeonManager.structureNameFor(null).equals("estructura"), "y sin nombre, tampoco vale null");

		for (String name : List.of("Casa Grande (v2)", "Capitán", "___torre___", "MAYÚSCULAS Y ESPACIOS")) {
			assertTrue(DungeonManager.structureNameFor(name).matches("[a-z0-9_]+"),
				"\"" + name + "\" no da una ruta que ResourceLocation acepte");
		}

		System.out.println("checkStructureImport: OK, cualquier nombre de archivo acaba siendo una ruta válida.");
	}

	/**
	 * <p>Encuentros: un grupo de monstruos guardado antes de la sesión y soltado de una vez. Lo que se fija
	 * aquí es la sintaxis de la composición —la misma en el JSON y en el formulario del creador in-game— y
	 * que los monstruos del pack que se envía existan de verdad.</p>
	 *
	 * <p>Esto último es el que importa: un id mal escrito en un encuentro no revienta nada, simplemente
	 * invoca menos monstruos de los que el DM preparó, y eso se descubre en mitad del combate.</p>
	 */
	private static void checkEncounters() throws Exception {
		assertTrue(EncounterRegistry.parseMember("dndsheets:goblin x4").count() == 4, "x4 son cuatro");
		assertTrue(EncounterRegistry.parseMember("dndsheets:goblin x4").monsterId().equals("dndsheets:goblin"),
			"y el id se queda sin la cola");
		assertTrue(EncounterRegistry.parseMember("dndsheets:goblin").count() == 1, "sin cola, uno");
		assertTrue(EncounterRegistry.parseMember("  dndsheets:goblin x2  ").monsterId().equals("dndsheets:goblin"),
			"los espacios de alrededor no forman parte del id");
		//Una "x" dentro del nombre no es una cuenta: solo cuenta la última, y solo si detrás hay un número.
		assertTrue(EncounterRegistry.parseMember("mod:xorn").monsterId().equals("mod:xorn"),
			"un id que empieza por x sigue siendo un id");
		assertTrue(EncounterRegistry.parseMember("mod:dragon x viejo").count() == 1,
			"lo que no es un número no es una cuenta");
		//Cero no es "ninguno", es una errata: un encuentro con una línea que no invoca nada se lee como roto.
		assertTrue(EncounterRegistry.parseMember("mod:goblin x0").count() == 1, "cero se trata como uno");
		assertTrue(EncounterRegistry.parseMember("") == null && EncounterRegistry.parseMember(null) == null,
			"una línea vacía se salta en vez de tumbar el encuentro");

		java.util.Set<String> bestiary = new java.util.HashSet<>();
		for (JsonElement el : readShippedPack("monsters.json")) bestiary.add(el.getAsJsonObject().get("id").getAsString());

		int checked = 0;
		for (JsonElement el : readShippedPack("encounters.json")) {
			EncounterRegistry.Encounter encounter = EncounterRegistry.parse(el.getAsJsonObject());
			assertTrue(!encounter.members().isEmpty(), "el encuentro " + encounter.id() + " no invoca nada");
			for (EncounterRegistry.Member member : encounter.members()) {
				assertTrue(bestiary.contains(member.monsterId()),
					"el encuentro " + encounter.id() + " pide [" + member.monsterId() + "], que no está en el bestiario");
				checked++;
			}
		}
		assertTrue(checked > 0, "el pack de encuentros que se envía está vacío");

		System.out.println("checkEncounters: OK, la sintaxis de composición y " + checked
			+ " monstruos de los encuentros que se envían existen.");
	}

	/**
	 * <p>La luz, que es la otra mitad del entorno después de la cobertura: en oscuridad se está "muy
	 * oscurecido" y eso en 5e es estar ciego. Aquí se fija lo que es puro —dónde caen los cortes entre luz,
	 * penumbra y oscuridad, qué cambia la visión en la oscuridad, y qué razas la tienen— porque lo demás
	 * necesita un mundo con bloques.</p>
	 *
	 * <p>Los umbrales se comprueban <b>en su frontera exacta</b>: el error natural aquí es el de siempre,
	 * un {@code >=} escrito como {@code >}, y desplaza la regla entera un nivel de luz sin que nada más
	 * cambie.</p>
	 */
	private static void checkVision() throws Exception {
		assertTrue(Light.fromLightLevel(0) == Light.DARK && Light.fromLightLevel(3) == Light.DARK,
			"por debajo de 4 es oscuridad");
		assertTrue(Light.fromLightLevel(4) == Light.DIM && Light.fromLightLevel(7) == Light.DIM,
			"de 4 a 7 es penumbra: es donde cae la noche a cielo abierto, que en el SRD es luz de luna");
		assertTrue(Light.fromLightLevel(8) == Light.BRIGHT && Light.fromLightLevel(15) == Light.BRIGHT,
			"de 8 en adelante es luz brillante");
		assertTrue(Light.DARK.blinds() && !Light.DIM.blinds() && !Light.BRIGHT.blinds(),
			"solo la oscuridad ciega: la penumbra estorba, no impide ver");

		//La visión en la oscuridad convierte oscuridad en penumbra y no en luz brillante — quien la tiene
		//deja de estar ciego, no deja de estar a oscuras.
		assertTrue(Light.DARK.withDarkvision(true) == Light.DIM, "con visión en la oscuridad, la oscuridad es penumbra");
		assertTrue(!Light.DARK.withDarkvision(true).blinds(), "y por tanto ya no ciega");
		assertTrue(Light.DARK.withDarkvision(false).blinds(), "sin ella, la oscuridad sigue cegando");
		assertTrue(Light.DIM.withDarkvision(true) == Light.DIM && Light.BRIGHT.withDarkvision(true) == Light.BRIGHT,
			"el rasgo no mejora lo que ya se ve");

		for (String race : List.of("Enano", "Elfo", "Semielfo", "Gnomo", "Semiorco", "Tiefling", "Dwarf", "elfo del bosque")) {
			assertTrue(CharacterRules.darkvisionFeetFor(race) == 60, race + " ve en la oscuridad en el SRD");
		}
		for (String race : List.of("Humano", "Mediano", "Dracónido", "")) {
			assertTrue(CharacterRules.darkvisionFeetFor(race) == 0, "la raza [" + race + "] no ve en la oscuridad");
		}
		//Una raza de la casa no concede el rasgo por su cuenta: las razas son texto libre que un pack puede
		//reemplazar entero, así que "no la reconozco" no puede leerse como "sí la tiene".
		assertTrue(CharacterRules.darkvisionFeetFor("Aarakocra") == 0, "una raza desconocida no concede el rasgo");
		assertTrue(CharacterRules.darkvisionFeetFor((String) null) == 0, "una hoja sin raza tampoco");

		JsonObject dwarf = new JsonObject();
		dwarf.addProperty("characterRace", "Enano");
		assertTrue(CharacterRules.darkvisionFeetFor(dwarf) == 60, "la raza de la hoja decide si no hay campo explícito");
		//El campo explícito tiene que poder QUITARLO, no solo darlo: por eso el centinela es -1 y no 0. Con 0
		//como "sin valor", un enano cegado por una maldición no se podría escribir en la ficha.
		dwarf.addProperty("darkvision", "0");
		assertTrue(CharacterRules.darkvisionFeetFor(dwarf) == 0, "el campo de la ficha manda sobre la raza, también para quitarlo");
		JsonObject custom = new JsonObject();
		custom.addProperty("characterRace", "Aarakocra");
		custom.addProperty("darkvision", "120");
		assertTrue(CharacterRules.darkvisionFeetFor(custom) == 120, "y es la salida para una raza de la casa");

		//Quitar la ceguera sin mirar quién la puso borraría también la de un conjuro o la de un DM en cuanto
		//el jugador saliera a la luz. Eso no se puede probar sin mundo, así que se sujeta por estructura.
		String manager = readSource("VisionManager.java");
		String lift = manager.substring(manager.indexOf("private static void lift("));
		lift = lift.substring(0, lift.indexOf("\n\t}"));
		assertTrue(lift.contains("sourceOf(Condition.CEGADO) != DARKNESS_SOURCE"),
			"lift() tiene que comprobar la fuente antes de quitar la ceguera: si no, salir a la luz cura "
				+ "también la ceguera que acaba de echarte un conjuro o un DM.");

		System.out.println("checkVision: OK, los cortes de luz, la visión en la oscuridad y de quién es cada ceguera.");
	}

	/**
	 * <p>El mod apunta a 1.20.1 y la puerta a una versión futura se deja abierta a propósito. Lo que decide
	 * si esa puerta sigue abierta no es una promesa en el README: es el <b>acoplamiento</b> con vanilla, y
	 * ese crece en silencio — nada falla hoy por meter un mixin o por leer el NBT de un objeto en un sitio
	 * nuevo. Se paga entero el día que alguien porte.</p>
	 *
	 * <p><b>Mixins y access transformers</b> parchean Minecraft por dentro, así que hay que reescribirlos en
	 * cada versión. Hoy no hay ninguno: el mod entero funciona con eventos y API pública de Forge, que es la
	 * razón principal de que portarlo sea siquiera discutible.</p>
	 *
	 * <p><b>El NBT de los objetos</b> desaparece en 1.20.5 y pasa a componentes. Todo lo que este mod escribe
	 * en un objeto vive en un único compuesto {@code "dndsheets"}, así que la migración es <i>un</i> componente
	 * y no una por tipo de objeto — pero eso solo es verdad mientras el acceso siga concentrado en los ficheros
	 * anotados aquí. La lista no prohíbe tocar NBT: obliga a que dispersarlo sea una decisión.</p>
	 *
	 * <p>Ver "Portability to future Minecraft versions" en {@code PROJECT_CONTEXT.md}.</p>
	 */
	private static void checkPortabilityCoupling() throws Exception {
		//Los ficheros que hoy tocan el NBT de un ItemStack. Si añades uno, añádelo aquí y comprueba que lo
		//que escribes va dentro del compuesto "dndsheets", como todo lo demás.
		Set<String> allowed = Set.of(
			"AbilityItem.java", "AbilityItemDispatcher.java", "Config.java", "ItemLook.java",
			"JournalManager.java", "MagicItemRegistry.java", "MonsterCommand.java", "MonsterRegistry.java",
			"PresetManager.java", "SpellCommand.java", "SpellRegistry.java");

		List<Path> files;
		try (Stream<Path> walk = Files.walk(Path.of("src", "main"))) {
			files = walk.filter(Files::isRegularFile).toList();
		}

		Set<String> stray = new java.util.TreeSet<>();
		for (Path file : files) {
			String name = file.getFileName().toString();
			assertTrue(!name.endsWith("mixins.json") && !name.equals("accesstransformer.cfg"),
				"apareció " + file + ": un mixin o un access transformer parchea Minecraft por dentro y hay que "
					+ "reescribirlo en cada versión. Hoy no hay ninguno, y eso es la mitad de lo que mantiene "
					+ "barato un port futuro (PROJECT_CONTEXT.md, \"Portability to future Minecraft versions\").");
			if (!name.endsWith(".java")) continue;

			String source = Files.readString(file);
			assertTrue(!source.contains("setAccessible(") && !source.contains("getDeclaredField("),
				name + " usa reflexión sobre las tripas de vanilla: se rompe en cuanto cambian los mapeos y no "
					+ "avisa al compilar. Si hace falta llegar a algo interno, un evento de Forge o un PR a Forge.");
			if (source.contains("getOrCreateTag()") || source.contains(".getTag()") || source.contains("addTagElement"))
				stray.add(name);
		}

		stray.removeAll(allowed);
		assertTrue(stray.isEmpty(),
			"tocan el NBT de un objeto fuera de la lista anotada: " + stray + ". El NBT de objetos deja de existir "
				+ "en 1.20.5 (pasa a componentes); hoy esa migración es UN componente porque todo lo que el mod "
				+ "escribe vive en el compuesto \"dndsheets\" y solo se toca desde " + allowed.size() + " ficheros. "
				+ "Si el acceso se dispersa, el coste del port crece sin que nada se queje. Si es deliberado, "
				+ "añade el fichero a la lista de checkPortabilityCoupling.");

		System.out.println("checkPortabilityCoupling: OK, sin mixins ni access transformers y el NBT de objetos "
			+ "sigue concentrado en " + allowed.size() + " ficheros.");
	}

	/**
	 * <p>Contenido traído de fuera: lo que el SRD 5.2 añadió y, sobre todo, <b>lo que hace falta para que un
	 * pack escrito en otro idioma no mienta</b>.</p>
	 *
	 * <p>Un tipo de daño no es una etiqueta que se imprime: es la CLAVE con la que se busca la resistencia
	 * del objetivo. Mientras se comparó tal cual venía escrito, un pack de la comunidad en inglés
	 * ({@code "damageType": "fire"}) atravesaba la resistencia al fuego de un personaje
	 * ({@code "fuego": "resistant"}) sin que saltara nada: el daño salía, y salía mal. Eso no es un fallo
	 * que una mesa pueda ver — por eso se comprueba de punta a punta y no solo la función.</p>
	 *
	 * <p>La segunda mitad es de licencia, no de mecánica: el SRD 5.2 es CC-BY-4.0 y <b>obliga</b> a citarlo.
	 * Enviar el contenido sin la atribución sería redistribuirlo mal, así que la atribución se comprueba
	 * como cualquier otra cosa que se puede olvidar.</p>
	 */
	private static void checkImportedContent() throws Exception {
		assertTrue(DamageTypes.normalize("fire").equals("fuego")
			&& DamageTypes.normalize("Fire").equals("fuego")
			&& DamageTypes.normalize(" FUEGO ").equals("fuego")
			&& DamageTypes.normalize("fuego").equals("fuego"),
			"\"fire\", \"Fire\" y \"fuego\" son el mismo tipo de daño");
		assertTrue(DamageTypes.normalize("necrótico").equals("necrotico"),
			"y el acento tampoco puede hacer dos tipos de uno");
		//Un tipo que no está en la tabla no se descarta: se normaliza. Una mesa que se invente "sangrado"
		//tiene que poder resistirlo, y para eso basta con que las dos puntas lo escriban igual.
		assertTrue(DamageTypes.normalize("Sangrado").equals(DamageTypes.normalize("sangrado")),
			"un tipo casero se normaliza igual en las dos puntas de la comparación");
		assertTrue(DamageTypes.normalize(null).equals("fisico") && DamageTypes.normalize("  ").equals("fisico"),
			"sin tipo declarado el golpe es físico, como siempre");

		//De punta a punta: un hechizo importado en inglés contra una hoja con la resistencia escrita en
		//español. Es la comprobación que importa — las dos anteriores pueden pasar y esta fallar igual si
		//el registro se salta la normalización al parsear.
		JsonObject imported = new JsonObject();
		imported.addProperty("id", "test:llamarada");
		imported.addProperty("name", "Llamarada");
		imported.addProperty("level", 1);
		imported.addProperty("mode", "attack");
		imported.addProperty("castingAbility", "int");
		imported.addProperty("dice", "2d6");
		imported.addProperty("damageType", "Fire");
		SpellRegistry.Spell spell = SpellRegistry.parse(imported);
		assertTrue(spell.damageType().equals("fuego"),
			"el registro tiene que normalizar al parsear: si no, cada sitio que compare lo hará a su manera");

		JsonObject sheet = new JsonObject();
		JsonObject affinities = new JsonObject();
		affinities.addProperty("fuego", "resistant");
		sheet.add("damageAffinities", affinities);
		assertTrue(DamageTypes.multiplierFor(null, sheet, spell.damageType()) == 0.5,
			"una resistencia escrita en español tiene que frenar un conjuro importado en inglés");

		//Y el bloque de monstruo por el otro lado: la resistencia declarada en inglés frente al golpe que
		//llega en español. Las claves del pack se normalizan al cargarlo por esta misma razón.
		JsonObject beast = new JsonObject();
		beast.addProperty("id", "test:elemental");
		beast.addProperty("name", "Elemental");
		beast.addProperty("baseEntity", "minecraft:blaze");
		beast.addProperty("ac", 13);
		beast.addProperty("hp", 20);
		JsonObject scores = new JsonObject();
		for (String ability : new String[]{"str", "dex", "con", "int", "wis", "cha"}) scores.addProperty(ability, 10);
		beast.add("abilities", scores);
		JsonObject resist = new JsonObject();
		resist.addProperty("Fire", "immune");
		beast.add("damageAffinities", resist);
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.parse(beast);
		assertTrue(block.damageAffinities().containsKey("fuego"),
			"la resistencia de un bestiario importado en inglés tiene que quedar guardada con la clave que "
				+ "luego se le va a preguntar");

		//El importador y su licencia. El SRD 5.2 es CC-BY-4.0: citarlo no es cortesía, es la condición.
		assertTrue(java.nio.file.Files.exists(Path.of("tools", "import_srd.py")),
			"falta tools/import_srd.py: sin él, ampliar el contenido vuelve a ser trabajo a mano sin rastro");
		//Sin saltos de linea ni ">" de cita: la frase que exige la licencia ocupa dos lineas del Markdown, y
		//buscarla tal cual ataba la comprobacion a donde cae el corte de linea.
		String attribution = Files.readString(Path.of("PROJECT_CONTEXT.md"))
			.replaceAll("(?m)^>", " ").replaceAll("\\s+", " ");
		//Lo que se exige es la frase EXACTA que pide la licencia del 5.2. Buscar "SRD 5.2" a secas daba por
		//buena una atribución borrada (la sigla sale en los párrafos que la explican), y buscar "Creative
		//Commons Attribution 4.0" también, porque el bloque del SRD 5.1 ya la lleva. Una comprobación que
		//aprueba el archivo sin la cita no comprueba la cita.
		assertTrue(attribution.contains("SRD 5.2 is licensed under the Creative Commons Attribution 4.0"),
			"falta la cita literal que exige la licencia del SRD 5.2 en PROJECT_CONTEXT.md: sin ella, enviar "
				+ "feats.json es redistribuir material CC-BY sin atribuir");
		assertTrue(attribution.contains("feats.json"),
			"y a decir qué archivos son: una atribución que no señala el archivo no señala nada");

		System.out.println("checkImportedContent: OK, un pack en inglés compara igual que uno en español y el "
			+ "SRD 5.2 va citado.");
	}

	private static String readSource(String fileName) throws Exception {
		//Acepta "Algo.java" y "command/Algo.java": los dos sitios que deciden un nivel viven en paquetes
		//distintos, y partir la ruta aquí evita un segundo helper que haga lo mismo.
		Path path = Path.of("src", "main", "java", "net", "hawthorn", "dndsheets");
		for (String part : fileName.split("/")) path = path.resolve(part);
		return Files.readString(path);
	}

	/**
	 * <p>Cuerpo de un método, buscando su DECLARACIÓN y no la primera vez que aparece su nombre: en
	 * {@code CombatManager} hay una llamada a {@code resolveAttack(...)} antes de declararlo, y cortar
	 * desde ahí devolvía el método equivocado — la comprobación fallaba dando a entender que faltaba una
	 * llamada que sí estaba.</p>
	 */
	private static String methodBody(String source, String signature) {
		//Una declaración empieza en una línea con UN tabulador; una llamada al mismo método siempre está más
		//adentro. Es lo que distingue las dos sin escribir un parser de Java.
		java.util.regex.Matcher declaracion = java.util.regex.Pattern
			.compile("(?m)^\\t[\\w .<>\\[\\],]*\\b" + java.util.regex.Pattern.quote(signature))
			.matcher(source);
		assertTrue(declaracion.find(), "no encontré la declaración de " + signature);
		//El final del método: la primera llave de cierre a ese mismo nivel de sangría.
		return source.substring(declaracion.start(), source.indexOf("\n\t}", declaracion.start()));
	}

	/**
	 * <p>La Mejora de Puntuación de Característica: los niveles que la conceden y cuántas toca al saltar
	 * varios de golpe. Es lo único que un nivel NO puede derivar, porque es una decisión, y por eso también
	 * lo único que se puede perder sin que nada falle.</p>
	 */
	private static void checkAbilityImprovements() throws Exception {
		//Los cinco niveles del SRD, y los bordes de cada uno: el fallo natural aquí es una lista mal copiada.
		for (int level = 1; level <= 20; level++) {
			boolean esperado = level == 4 || level == 8 || level == 12 || level == 16 || level == 19;
			assertTrue(LevelUpManager.isImprovementLevel(level) == esperado,
				"nivel " + level + ": mejora esperada=" + esperado + " y dice " + LevelUpManager.isImprovementLevel(level));
		}
		//El 20 NO da mejora en 5e, y es el error más fácil de cometer ("el último nivel dará algo").
		assertTrue(!LevelUpManager.isImprovementLevel(20), "el nivel 20 no concede mejora en 5e");

		//Subir de uno en uno y de golpe tienen que dar lo mismo: un DM que pone /dndsheet setlevel 8 sobre un
		//personaje de nivel 1 no debería costarle al jugador la mejora del 4.
		assertTrue(LevelUpManager.improvementsBetween(1, 8) == 2, "del 1 al 8 son dos mejoras: la del 4 y la del 8");
		assertTrue(LevelUpManager.improvementsBetween(1, 20) == 5, "del 1 al 20 son las cinco");
		int unaAUna = 0;
		for (int level = 1; level < 20; level++) unaAUna += LevelUpManager.improvementsBetween(level, level + 1);
		assertTrue(unaAUna == LevelUpManager.improvementsBetween(1, 20),
			"subir de uno en uno debería dar lo mismo que subir de golpe, y da " + unaAUna);

		//Ni el nivel de llegada ni el de salida se cuentan dos veces.
		assertTrue(LevelUpManager.improvementsBetween(4, 8) == 1, "del 4 al 8 solo cuenta la del 8: la del 4 ya se dio");
		assertTrue(LevelUpManager.improvementsBetween(8, 8) == 0, "quedarse igual no concede nada");
		//Bajar de nivel no las quita ni las da: quitarlas obligaría a deshacer puntos ya gastados.
		assertTrue(LevelUpManager.improvementsBetween(12, 3) == 0, "bajar de nivel no debería conceder ni quitar");

		//Se anotan en la hoja y se acumulan, para que sobrevivan a cerrar la pantalla o desconectarse.
		JsonObject hoja = new JsonObject();
		LevelUpManager.grantImprovementsFor(hoja, 1, 4);
		LevelUpManager.grantImprovementsFor(hoja, 4, 8);
		assertTrue(LevelUpManager.pendingOf(hoja) == 2, "dos saltos deberían dejar dos mejoras pendientes");

		//El nivel del que se parte tiene que ser el EXPLÍCITO. La sobrecarga con jugador cae al nivel de XP
		//de Minecraft mientras el DM no fije uno —bien para mostrar un número, veneno para decidir el
		//siguiente— y contar desde ahí le regala o le quita Mejoras a alguien por lo que haya minado.
		JsonObject sinNivel = new JsonObject();
		assertTrue(CharacterRules.levelOf(sinNivel) == 1, "un personaje al que nadie ha subido de nivel es de nivel 1");
		assertTrue(LevelUpManager.improvementsBetween(CharacterRules.levelOf(sinNivel), 8) == 2,
			"subirlo del 1 al 8 son dos Mejoras; contando desde un nivel de XP alto se perderían");

		//Y que los dos sitios que DECIDEN un nivel no llamen a la sobrecarga con jugador. Esto se sostiene por
		//estructura porque el caso que se rompe necesita un ServerPlayer con XP, que fuera del juego no
		//existe: la afirmación de arriba pasa igual con la versión mala.
		for (String archivo : List.of("LevelUpManager.java", "command/SheetCommand.java")) {
			String cuerpo = readSource(archivo);
			int desde = cuerpo.indexOf(archivo.endsWith("SheetCommand.java") ? "public static void applyLevel(" : "public static void levelUp(");
			assertTrue(desde > 0, "no encontré el método que decide el nivel en " + archivo);
			String metodo = cuerpo.substring(desde, cuerpo.indexOf("\n\t}", desde));
			assertTrue(!metodo.contains("characterLevelOf(sheet, "),
				archivo + ": decidir un nivel con la sobrecarga que cae al XP de Minecraft le regala o le "
					+ "quita niveles y Mejoras al jugador según lo que haya minado");
		}

		System.out.println("checkAbilityImprovements: OK, las mejoras se conceden en los niveles del SRD, se cuentan desde el nivel explícito y no se pierden al saltar varios.");
	}

	/**
	 * <p>Que los archivos de idioma parseen y digan las dos lo mismo.</p>
	 *
	 * <p>Esta comprobación existe porque acabo de romper {@code es_es.json} metiendo comillas sin escapar
	 * dentro de un texto ({@code "Personaje "%1$s" borrado"}) y <b>el build siguió en verde</b>: nada lee
	 * estos archivos hasta que el juego arranca, y entonces lo que se ve no es un error sino la clave cruda
	 * en pantalla. Una clave que existe en un idioma y no en el otro falla igual de silenciosamente, solo
	 * que para la mitad de la gente.</p>
	 */
	/**
	 * <p>Toda clave que el codigo pide con {@code Component.translatable} tiene que existir en los ficheros
	 * de idioma. Si no, Minecraft no falla: pinta la clave cruda —"gui.dndsheets.dm_panel.title"— en el
	 * boton, y eso solo se descubre abriendo esa pantalla concreta en el idioma concreto.</p>
	 *
	 * <p>{@code checkLanguageFiles} ya comprueba que los dos idiomas tengan las MISMAS claves, pero no que
	 * las que se usan esten en ninguno de los dos: con una errata en el codigo, ambos ficheros siguen
	 * cuadrando entre si y el fallo pasa igual. Esta comprobacion mira el otro lado, el del uso.</p>
	 */
	/**
	 * <p>Un mensaje traducido lleva huecos ({@code %s}) que se rellenan con los argumentos que le pasa el
	 * codigo. Si dos idiomas no declaran los MISMOS huecos, el que tenga de mas ensena un "%s" pelado al
	 * jugador y el que tenga de menos se come el dato — un "Invocado Goblin (CA , PG)."</p>
	 *
	 * <p>{@code checkLanguageFiles} compara que existan las mismas claves, no que digan la misma forma, asi
	 * que este agujero le pasaba por debajo. Aparecio de verdad al traducir los 63 mensajes de chat: uno de
	 * los avisos de mazmorra tiene cuatro huecos y es facil escribir la version inglesa con tres.</p>
	 */
	private static void checkPlaceholderParity() throws Exception {
		Path dir = Path.of("src", "main", "resources", "assets", "dndsheets", "lang");
		JsonObject es = JsonParser.parseString(Files.readString(dir.resolve("es_es.json"))).getAsJsonObject();
		JsonObject en = JsonParser.parseString(Files.readString(dir.resolve("en_us.json"))).getAsJsonObject();

		Set<String> desalineadas = new java.util.TreeSet<>();
		int conHuecos = 0;
		for (String clave : es.keySet()) {
			if (!en.has(clave)) continue; //Eso ya lo caza checkLanguageFiles.
			int huecosEs = contarHuecos(es.get(clave).getAsString());
			int huecosEn = contarHuecos(en.get(clave).getAsString());
			if (huecosEs > 0) conHuecos++;
			if (huecosEs != huecosEn) desalineadas.add(clave + " (es=" + huecosEs + ", en=" + huecosEn + ")");
		}

		assertTrue(desalineadas.isEmpty(),
			"estas claves no declaran los mismos huecos en los dos idiomas: " + desalineadas
				+ ".\n  Los %s se rellenan por posicion con lo que pasa el codigo: sobrarle uno a un idioma pinta"
				+ " un %s crudo en pantalla, y faltarle uno se traga el dato sin avisar.");

		System.out.println("checkPlaceholderParity: OK, " + conHuecos + " mensajes con huecos y los mismos en ambos idiomas.");
	}

	private static int contarHuecos(String texto) {
		int total = 0;
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("%s").matcher(texto);
		while (m.find()) total++;
		return total;
	}

	/**
	 * <p>Nada de {@code sendSystemMessage(Component.literal("texto"))}: eso es un mensaje que solo existe en
	 * un idioma. Se permite {@code Component.literal(variable)}, donde el texto ya viene resuelto de otro
	 * sitio, porque ahi no hay nada que traducir aqui.</p>
	 */
	private static void checkChatMessagesAreTranslatable() throws Exception {
		List<Path> fuentes;
		try (Stream<Path> walk = Files.walk(Path.of("src", "main", "java"))) {
			fuentes = walk.filter(f -> f.toString().endsWith(".java")).sorted().toList();
		}

		Set<String> culpables = new java.util.TreeSet<>();
		for (Path fuente : fuentes) {
			java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("sendSystemMessage\\(Component\\.literal\\(\"")
				.matcher(Files.readString(fuente));
			if (m.find()) culpables.add(fuente.getFileName().toString());
		}

		assertTrue(culpables.isEmpty(),
			"estos archivos mandan al chat un texto fijo sin pasar por los ficheros de idioma: " + culpables
				+ ".\n  Usa Component.translatable(\"chat.dndsheets....\", args...) y anade la clave a en_us.json"
				+ " Y a es_es.json. Component.literal(variable) si vale: ahi el texto ya viene hecho.");

		//sendSystemMessage no era la unica puerta. Los nombres y descripciones de los items de clase
		//(Totem de Furia, Ayudar, Vara de DM...) se construian con Component.literal, asi que un jugador
		//en ingles leia el mod en espanol y no habia forma de saberlo mirando los ficheros de idioma: la
		//cadena no estaba en ellos. Aqui se busca cualquier literal con prosa dentro (una minuscula
		//seguida de un espacio) — un separador " - " o un formato "%s / %s" no la tienen y no molestan.
		java.util.regex.Pattern prosa = java.util.regex.Pattern.compile("Component\\.literal\\(\"[^\"]*[a-z] [^\"]*\"");
		java.util.Set<String> conProsa = new java.util.TreeSet<>();
		int enComandos = 0;
		for (Path fuente : fuentes) {
			java.util.regex.Matcher m = prosa.matcher(Files.readString(fuente));
			int veces = 0;
			while (m.find()) veces++;
			if (veces == 0) continue;
			//La respuesta de un comando la lee QUIEN LO ESCRIBE, en su propio chat, y casi siempre lleva
			//variables interpoladas ("Pieza \"X\" capturada en el pool \"Y\"") — pasarlas a claves con
			//huecos es otra tanda, y una que checkPlaceholderParity tendra que vigilar entera.
			//ponytail: 86 respuestas de comando siguen en espanol fijo. El tope de abajo impide que
			//crezcan; bajarlo a 0 es la tanda que falta.
			if (fuente.toString().replace('\\', '/').contains("/command/")) enComandos += veces;
			else conProsa.add(fuente.getFileName().toString());
		}

		assertTrue(conProsa.isEmpty(),
			"estos archivos ensenan texto fijo al jugador (nombre o descripcion de item, etiqueta de"
				+ " pantalla) sin pasar por los ficheros de idioma: " + conProsa
				+ ".\n  Es el mismo fallo que arriba y no se ve en ninguna pantalla hasta que alguien juega"
				+ " en el otro idioma. Component.translatable(\"chat.dndsheets....\", args...).");
		assertTrue(enComandos <= 86, "las respuestas de comando con texto fijo han subido a " + enComandos
			+ " (eran 86): no anadas mas, pasalas a Component.translatable con su clave");

		System.out.println("checkChatMessagesAreTranslatable: OK, nada de lo que ve un jugador lleva texto"
			+ " fijo; quedan " + enComandos + " respuestas de comando (solo las ve quien las escribe).");
	}

	private static void checkTranslationKeysExist() throws Exception {
		String lang = Files.readString(Path.of("src", "main", "resources", "assets", "dndsheets", "lang", "en_us.json"));
		java.util.Set<String> declaradas = new java.util.HashSet<>();
		//Mayúsculas incluidas: las claves de vanilla no son todas minúsculas ("itemGroup.dndsheets.dnd_tab").
		java.util.regex.Matcher declara = java.util.regex.Pattern.compile("\"([A-Za-z0-9_.]+)\"\\s*:").matcher(lang);
		while (declara.find()) declaradas.add(declara.group(1));

		List<Path> fuentes;
		try (Stream<Path> walk = Files.walk(Path.of("src", "main", "java"))) {
			fuentes = walk.filter(f -> f.toString().endsWith(".java")).sorted().toList();
		}

		java.util.Set<String> faltan = new java.util.TreeSet<>();
		int usadas = 0;
		for (Path fuente : fuentes) {
			String fuenteTexto = Files.readString(fuente);
			java.util.regex.Matcher usa = java.util.regex.Pattern
				.compile("Component\\.translatable\\(\"([^\"]+)\"").matcher(fuenteTexto);
			while (usa.find()) {
				usadas++;
				String clave = usa.group(1);
				//Las de vanilla (item.minecraft.*, entity.*) no viven en nuestros ficheros.
				if (!clave.contains("dndsheets")) continue;
				//Clave construida a trozos ("...button_" + type): el sufijo solo se sabe en ejecucion, asi
				//que aqui no hay nada que comprobar. Se reconoce porque tras la comilla viene un "+".
				if (fuenteTexto.startsWith(" +", usa.end())) continue;
				if (!declaradas.contains(clave)) faltan.add(clave + "  (" + fuente.getFileName() + ")");
			}
		}

		assertTrue(faltan.isEmpty(),
			"el codigo pide claves de traduccion que no existen en los ficheros de idioma: " + faltan
				+ ".\n  Minecraft no falla por esto: pinta la clave cruda en pantalla, asi que solo se ve"
				+ " abriendo esa pantalla. Anade la clave a en_us.json Y a es_es.json.");

		System.out.println("checkTranslationKeysExist: OK, " + usadas + " usos de translatable y todos con clave declarada.");
	}

	/**
	 * <p>Las variantes regionales de espanol que hay que publicar ademas de {@code es_es}. Esta lista es la
	 * UNICA: {@code tools/sync_lang_variants.py} la lee de aqui, para que no puedan separarse.</p>
	 *
	 * <p><b>La regla general, por si algun dia se traduce a otro idioma:</b> Minecraft carga {@code en_us} y
	 * encima el codigo EXACTO elegido, sin ningun respaldo por region. Publicar solo una variante de un
	 * idioma con varias deja al resto en INGLES, sin error en el log y sin nada raro en los ficheros de
	 * idioma. La misma trampa espera a {@code pt_br}/{@code pt_pt} y a {@code zh_cn}/{@code zh_tw}: quien
	 * anada uno de esos, que anada tambien sus hermanos aqui. Lo que no sea variante del mismo idioma
	 * (asturiano {@code esan}, p.ej.) NO va: es otro idioma y su sitio es el respaldo a {@code en_us}.</p>
	 */
	private static final String[] SPANISH_VARIANTS = { "es_ar", "es_cl", "es_ec", "es_mx", "es_uy", "es_ve" };

	private static void checkLanguageFiles() throws Exception {
		Path dir = Path.of("src", "main", "resources", "assets", "dndsheets", "lang");
		Map<String, Set<String>> keysByLang = new java.util.LinkedHashMap<>();

		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
				String name = file.getFileName().toString();
				JsonObject json;
				try {
					json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				} catch (RuntimeException e) {
					throw new AssertionError(name + " no es JSON válido: " + e.getMessage());
				}
				keysByLang.put(name, json.keySet());
			}
		}
		assertTrue(keysByLang.size() >= 2, "esperaba al menos dos idiomas y encontré " + keysByLang.size());

		//Minecraft NO tiene respaldo por región: carga en_us y encima el idioma EXACTO elegido. 1.20.1
		//ofrece SIETE españoles (es_es, es_ar, es_cl, es_ec, es_mx, es_uy, es_ve), así que un jugador con
		//"Español (México)" recibía es_mx de vanilla y en_us del mod: el juego en español y el mod entero
		//en inglés, sin ningún error en el log y sin nada que mirar en los ficheros de idioma, porque el
		//que fallaba era el que no existía. No hay forma de declarar "es_* usa es_es" — la única salida es
		//que el archivo esté con cada nombre, y que las copias no se separen del original.
		String spanish = Files.readString(dir.resolve("es_es.json"));
		for (String variant : SPANISH_VARIANTS) {
			Path copy = dir.resolve(variant + ".json");
			assertTrue(Files.exists(copy), "falta " + variant + ".json: corre tools/sync_lang_variants.py");
			assertTrue(Files.readString(copy).equals(spanish), variant + ".json se ha separado de es_es.json"
				+ " — quien juegue en ese español leería otra cosa. Corre tools/sync_lang_variants.py");
		}

		//Todas contra la primera: con dos idiomas es lo mismo que compararlas entre sí, y con cinco sigue
		//dando un mensaje que dice qué falta y dónde.
		String reference = keysByLang.keySet().iterator().next();
		Set<String> referenceKeys = keysByLang.get(reference);
		for (Map.Entry<String, Set<String>> entry : keysByLang.entrySet()) {
			if (entry.getKey().equals(reference)) continue;
			for (String key : referenceKeys) {
				assertTrue(entry.getValue().contains(key),
					entry.getKey() + " no tiene la clave \"" + key + "\", que sí está en " + reference
						+ ": quien juegue en ese idioma verá la clave cruda en pantalla");
			}
			for (String key : entry.getValue()) {
				assertTrue(referenceKeys.contains(key), reference + " no tiene la clave \"" + key + "\", que sí está en " + entry.getKey());
			}
		}

		//Las páginas de la guía se declaran en Java y se traducen aquí: una registrada sin traducir sale en
		//pantalla como su propia clave. Es el mismo fallo callado que arriba, con un paso más — nada falla
		//al compilar, y la guía es justo lo que lee quien no sabe todavía cómo funciona nada.
		String guide = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets",
			"client", "gui", "GuideBook.java"));
		java.util.regex.Matcher pages = java.util.regex.Pattern
			.compile("\"(gui[.]dndsheets[.]guide[.]page[.]\\w+)\"").matcher(guide);
		int pageCount = 0;
		while (pages.find()) {
			pageCount++;
			assertTrue(referenceKeys.contains(pages.group(1)),
				"la guía registra la página \"" + pages.group(1) + "\" y no está traducida: saldría la clave cruda");
		}
		assertTrue(pageCount >= 20, "esperaba al menos 20 páginas de guía y encontré " + pageCount);

		System.out.println("checkLanguageFiles: OK, " + keysByLang.size() + " idiomas con las mismas "
			+ referenceKeys.size() + " claves, " + pageCount + " páginas de guía traducidas y JSON válido.");
	}

	/**
	 * <p>Resolver un personaje por su NOMBRE. Los ids salen del UUID del jugador, así que pedir uno para
	 * cambiar de personaje es pedir que se copie una cadena que no significa nada; el nombre es lo que la
	 * persona sabe.</p>
	 */
	private static void checkCharacterLookup() {
		Map<String, JsonObject> sheets = new java.util.LinkedHashMap<>();
		sheets.put("uuid-1", named("Elara la Gris"));
		sheets.put("uuid-2", named("Elandra"));
		sheets.put("uuid-3", named("Borin"));
		sheets.put("uuid-4", named("uuid-2")); //Un personaje llamado igual que el id de otro.
		List<String> ids = List.of("uuid-1", "uuid-2", "uuid-3", "uuid-4");

		assertTrue("uuid-3".equals(CharacterRules.resolveCharacter(sheets, ids, "Borin")), "un nombre exacto debería bastar");
		assertTrue("uuid-3".equals(CharacterRules.resolveCharacter(sheets, ids, "borin")), "y sin importar mayúsculas");
		assertTrue("uuid-3".equals(CharacterRules.resolveCharacter(sheets, ids, "  Borin ")), "ni espacios de sobra");
		assertTrue("uuid-1".equals(CharacterRules.resolveCharacter(sheets, ids, "Elara la Gris")), "un nombre con espacios también");
		assertTrue("uuid-1".equals(CharacterRules.resolveCharacter(sheets, ids, "Elara")), "un prefijo único debería valer");

		//Prefijo ambiguo: "Ela" vale para Elara y Elandra. Ambiguo es tan "no" como no encontrarlo — elegir
		//por el jugador sería elegir mal la mitad de las veces.
		assertTrue(CharacterRules.resolveCharacter(sheets, ids, "Ela") == null, "un prefijo que vale para dos no debería elegir uno");
		assertTrue(CharacterRules.resolveCharacter(sheets, ids, "Nadie") == null, "lo que no existe no resuelve");
		assertTrue(CharacterRules.resolveCharacter(sheets, ids, "  ") == null, "ni una cadena vacía");
		assertTrue(CharacterRules.resolveCharacter(sheets, ids, null) == null, "ni null");

		//El id exacto manda sobre todo: si no, un personaje llamado como el id de otro se lo quedaría.
		assertTrue("uuid-2".equals(CharacterRules.resolveCharacter(sheets, ids, "uuid-2")),
			"un id exacto debería ganar al personaje que se llama así");

		//Y un nombre exacto gana a un prefijo: con "Ana" y "Anabel" delante, "Ana" es Ana.
		Map<String, JsonObject> dos = new java.util.LinkedHashMap<>();
		dos.put("a", named("Ana"));
		dos.put("b", named("Anabel"));
		assertTrue("a".equals(CharacterRules.resolveCharacter(dos, List.of("a", "b"), "Ana")),
			"un nombre exacto no debería perder contra el prefijo de otro");

		//Dos personajes con el MISMO nombre: no hay forma honesta de elegir, así que no se elige.
		Map<String, JsonObject> repes = new java.util.LinkedHashMap<>();
		repes.put("a", named("Bruno"));
		repes.put("b", named("Bruno"));
		assertTrue(CharacterRules.resolveCharacter(repes, List.of("a", "b"), "Bruno") == null,
			"dos personajes con el mismo nombre no se pueden distinguir por nombre");

		//Reportado jugando: dos personajes llamados "Test" daban dos sugerencias IDÉNTICAS que el comando
		//rechazaba después por ambiguas — el autocompletado ofrecía una opción que no funcionaba. El rótulo
		//lleva el id solo cuando hace falta, y con él la elección vuelve a ser posible.
		assertTrue("Borin".equals(CharacterRules.suggestionLabelFor(sheets, ids, "uuid-3")),
			"un nombre único se ofrece a secas, sin id que nadie necesita leer");
		assertTrue("Bruno [a]".equals(CharacterRules.suggestionLabelFor(repes, List.of("a", "b"), "a")),
			"dos nombres iguales se distinguen con el id, y solo entonces");
		assertTrue(!CharacterRules.suggestionLabelFor(repes, List.of("a", "b"), "a")
				.equals(CharacterRules.suggestionLabelFor(repes, List.of("a", "b"), "b")),
			"y las dos sugerencias tienen que ser distintas entre sí");

		//Y lo que se sugiere tiene que poder resolverse: es el fallo entero en una línea.
		for (String id : List.of("a", "b")) {
			String label = CharacterRules.suggestionLabelFor(repes, List.of("a", "b"), id);
			assertTrue(id.equals(CharacterRules.resolveCharacter(repes, List.of("a", "b"), label)),
				"la sugerencia \"" + label + "\" debería resolver al personaje que la generó");
		}
		//Un nombre que de verdad lleva corchetes no se confunde con la forma "Nombre [id]".
		Map<String, JsonObject> corchetes = new java.util.LinkedHashMap<>();
		corchetes.put("x", named("Bruno [el Bravo]"));
		assertTrue("x".equals(CharacterRules.resolveCharacter(corchetes, List.of("x"), "Bruno [el Bravo]")),
			"un nombre con corchetes debería seguir encontrándose por su nombre");

		System.out.println("checkCharacterLookup: OK, los personajes se encuentran por nombre, lo ambiguo se distingue con el id y lo que se sugiere se puede elegir.");
	}

	/**
	 * <p>Qué personaje queda puesto después de borrar uno. Reportado jugando: borrar el personaje que
	 * llevabas puesto no limpiaba nada, así que la hoja abierta con H seguía siendo la del borrado y al
	 * guardar volvía a escribirse en disco — el personaje resucitaba.</p>
	 *
	 * <p>La causa era la pregunta, no el borrado: {@code activeCharacterOf} cae al propio UUID del jugador
	 * cuando no lleva ninguno puesto (es lo que hace que sigan funcionando las hojas anteriores a los
	 * personajes), así que preguntarle "¿le queda personaje?" contestaba que sí en cuanto existiera un
	 * archivo con ese id, aunque no estuviera puesto.</p>
	 */
	private static void checkCharacterAfterDelete() {
		Set<String> existing = Set.of("uuid", "uuid-2", "uuid-3");

		assertTrue("uuid-2".equals(CharacterRules.characterToWearAfter(existing, "uuid-2", List.of("uuid", "uuid-2"))),
			"si el que llevaba puesto sigue existiendo, se queda con él");
		assertTrue("uuid".equals(CharacterRules.characterToWearAfter(existing, "uuid-9", List.of("uuid", "uuid-2"))),
			"si el que llevaba ya no existe, se le pone el primero que le quede");

		//EL FALLO: sin binding, no hay personaje puesto. Contestar "uuid" porque exista un archivo con el id
		//del jugador es justo lo que dejaba al cliente con la hoja borrada en la mano.
		assertTrue("uuid".equals(CharacterRules.characterToWearAfter(existing, null, List.of("uuid", "uuid-2"))),
			"sin binding hay que ELEGIRLE uno de los suyos, no dar por hecho que ya lleva alguno");
		assertTrue(CharacterRules.characterToWearAfter(existing, null, List.of()) == null,
			"y si no le queda ninguno, hay que decirlo para crearle una hoja en blanco");
		//Un id que ya no está en disco no vale como respuesta ni aunque siga en su lista.
		assertTrue("uuid-3".equals(CharacterRules.characterToWearAfter(existing, null, List.of("borrado", "uuid-3"))),
			"un personaje que ya no existe no puede ser el que se le ponga");
		assertTrue(CharacterRules.characterToWearAfter(Set.of(), null, List.of("borrado")) == null,
			"si nada de lo suyo existe ya, se queda sin ninguno");

		System.out.println("checkCharacterAfterDelete: OK, tras borrar se elige un personaje que existe de verdad, o ninguno.");
	}

	/**
	 * <p>Que el nivel sea del PERSONAJE y no del jugador. Reportado jugando: un personaje recién creado
	 * nacía con el nivel de XP de Minecraft de quien lo creaba —PG, competencia y espacios de conjuro de
	 * nivel 12 por haber picado piedra— y todos los personajes de la misma persona salían iguales, porque
	 * los tres sacaban el número del mismo sitio.</p>
	 */
	private static void checkCharacterLevelIsPerCharacter() throws Exception {
		//Lo que se puede comprobar sin juego: la creación estampa un nivel explícito, y con él la regla pura
		//devuelve 1 en vez de caer al XP de nadie.
		String loader = readSource("SheetLoader.java");
		for (String creator : List.of("public static String createCharacter(", "public static String createNpc(")) {
			int from = loader.indexOf(creator);
			assertTrue(from > 0, "no encontré " + creator);
			String body = loader.substring(from, loader.indexOf("\n\t}", from));
			assertTrue(body.contains("\"characterLevel\""),
				creator + " debería dejar un nivel explícito: sin él, el personaje nace con el nivel de XP de quien lo crea");
		}

		JsonObject recienCreado = new JsonObject();
		recienCreado.addProperty("characterLevel", 1);
		assertTrue(CharacterRules.levelOf(recienCreado) == 1, "un personaje nuevo es de nivel 1");
		assertTrue(LevelUpManager.improvementsBetween(CharacterRules.levelOf(recienCreado), 4) == 1,
			"y sube desde el 1, así que la primera Mejora le toca al llegar al 4");

		//Y que ponerse una hoja vieja le congele SU nivel, para que dejen de compartirlo.
		int from = loader.indexOf("public static boolean switchCharacter(");
		String switchBody = loader.substring(from, loader.indexOf("\n\t}", from));
		assertTrue(switchBody.contains("characterLevel"),
			"al ponerse un personaje sin nivel propio hay que estampárselo, o dos personajes lo comparten para siempre");

		//Y la VIDA ACTUAL, por lo mismo: vivía solo en la salud de la entidad, que es del jugador. Cambiar de
		//personaje te dejaba con las heridas del anterior y volver te encontraba las del nuevo.
		assertTrue(switchBody.contains("hitPoints"),
			"cambiar de personaje debería guardar la vida del que se quita y devolverle la suya al que entra");
		assertTrue(loader.contains("restoreHitPoints("),
			"y restaurarla DESPUÉS de fijar el máximo, o quedaría acotada contra el máximo del personaje anterior");

		//Y los recursos de una vez por descanso. Vivían en un Set<UUID> por jugador, así que gastar el Segundo
		//Aliento con un personaje se lo gastaba al otro, y un reinicio del servidor se los devolvía a todos
		//sin haber descansado. Es la misma familia: un valor del JUGADOR haciendo de valor del PERSONAJE.
		JsonObject hoja = new JsonObject();
		assertTrue(!RestResource.isSpent(hoja, RestResource.SECOND_WIND), "una hoja nueva no tiene nada gastado");
		hoja.addProperty(RestResource.SECOND_WIND, true);
		assertTrue(RestResource.isSpent(hoja, RestResource.SECOND_WIND), "y una vez gastado, lo recuerda");
		//Cada recurso es su propia clave: si compartieran una, descansar devolvería tres cosas de golpe.
		assertTrue(!RestResource.isSpent(hoja, RestResource.CHANNEL_DIVINITY)
				&& !RestResource.isSpent(hoja, RestResource.ARCANE_RECOVERY),
			"gastar uno no debería gastar los otros dos");
		for (String manager : List.of("FighterSecondWindManager.java", "ClericTurnUndeadManager.java",
				"WizardArcaneRecoveryManager.java")) {
			assertTrue(!readSource(manager).contains("Set<UUID>"),
				manager + " guarda un recurso por descanso por JUGADOR: con dos personajes se comparte, y un reinicio lo devuelve gratis");
		}

		//Y el inventario. Aquí lo que se fija es el ORDEN, no la existencia: guardar-y-persistir ANTES de
		//vaciar es la diferencia entre "te has quedado con el equipo del otro personaje" (molesto, y
		//reversible cambiando otra vez) y "se han borrado tus objetos" (irreversible). Invertir esas dos
		//líneas compila igual de bien y no falla en ninguna otra comprobación.
		String inventory = readSource("CharacterInventory.java");
		int saved = inventory.indexOf("saveCharacterSheet(");
		int cleared = inventory.indexOf("clearContent()");
		assertTrue(saved > 0 && cleared > 0, "no encontré el guardado y el vaciado del inventario");
		assertTrue(saved < cleared,
			"hay que PERSISTIR el inventario del personaje que sale antes de vaciarle las manos al cuerpo: "
				+ "al revés, un fallo a mitad borra los objetos en vez de dejarlos donde estaban");
		//Y vaciar antes de restaurar: load() escribe encima de las ranuras que trae, no vacía las demás, así
		//que sin el clear el personaje nuevo heredaría las ranuras que él no usa.
		assertTrue(cleared < inventory.indexOf("restore(player,"),
			"hay que vaciar antes de restaurar, o las ranuras que el personaje nuevo no use conservan las del viejo");

		//Y lo que el personaje anterior estaba HACIENDO se acaba con él. Estos cuatro viven indexados por
		//jugador porque son estados vivos y no datos de hoja, así que sin cortarlos el personaje nuevo
		//heredaba la concentración, la furia, la forma salvaje y la marca: seguía enfurecido sin haber
		//entrado en furia.
		for (String manager : List.of("ConcentrationManager", "BarbarianRageManager", "DruidWildShapeManager",
				"RangerHunterMarkManager")) {
			assertTrue(switchBody.contains(manager + "."),
				"cambiar de personaje debería cortar lo que el anterior tenía en marcha en " + manager);
		}

		//Reportado jugando: el inventario no se veía cambiado hasta abrirlo a mano. Sustituir las ranuras en
		//el servidor no repinta la barra rápida por sí solo, y quien mira la pantalla no tiene forma de saber
		//que sus datos ya cambiaron. Los demás estados vanilla que toca el mod —vida, atributo de vida
		//máxima, posición, efectos— los sincroniza Minecraft solo; el inventario es el único que se
		//sustituye ENTERO fuera de una interacción con el menú.
		assertTrue(inventory.contains("broadcastFullState()"),
			"cambiar el inventario en el servidor hay que anunciarlo, o el jugador ve el viejo hasta que abre la mochila");

		System.out.println("checkCharacterLevelIsPerCharacter: OK, nivel, vida, recursos, equipo y efectos vivos son del personaje, y el equipo se ve al cambiar.");
	}

	private static JsonObject named(String characterName) {
		JsonObject sheet = new JsonObject();
		sheet.addProperty("characterName", characterName);
		return sheet;
	}

	/**
	 * <p>Cable trampa para las dos invariantes que más caras salen en este proyecto: el id de un mensaje de
	 * red es su orden de registro, y los enums que cruzan el cable viajan por ordinal. Las dos fallan en
	 * <b>silencio</b> — todo compila, cliente y servidor se dan la mano igual, y se desalinean después.</p>
	 *
	 * <p>Cuenta las piezas que cruzan el cable y las compara con el número anotado a mano junto a
	 * {@code PROTOCOL_VERSION}. No impide el error: obliga a que subir (o no subir) la versión sea una
	 * decisión y no un olvido. Existe porque ya pasó: {@code BrowseActionMessage.Action} ganó {@code DELETE}
	 * y la versión de protocolo se quedó donde estaba, sin que nada se quejara.</p>
	 */
	/**
	 * <p>Invariante 4: lo que muta una hoja tiene que llegar a {@code SheetLoader.saveServer}. El autosave de
	 * 5 minutos es la red de seguridad, no la ruta de escritura.</p>
	 *
	 * <p>Se rompia en nueve sitios a la vez, y todos con la misma forma: mutar el JsonObject y mandar solo el
	 * parche al cliente. El jugador veia el cambio, el disco no se enteraba, y apagar el servidor antes del
	 * autosave devolvia el espacio de conjuro gastado, el escudo, el castigo o —lo peor— levantaba a un
	 * personaje que estaba tirando salvaciones de muerte.</p>
	 *
	 * <p><b>Granularidad: por ARCHIVO, no por sitio.</b> Un archivo que guarde en algun sitio pasa aunque
 * otra ruta suya mute sin guardar — le paso a {@code SpellCastManager}, que persistia el espacio de
 * conjuro en spendSlot pero no el gasto del Hechizo Gemelo. Comprobarlo por sitio pediria seguir el flujo
 * del metodo, que es mucho mas maquinaria de la que esto merece; sirve como red contra el descuido
 * completo, no como prueba de que cada linea persiste.</p>
 *
 * <p><b>Sin lista de excepciones a mano.</b> El criterio se mantiene solo: un archivo que no menciona
	 * {@code ServerPlayer} no PUEDE guardar (saveServer pide el uuid del jugador), asi que es un helper puro
	 * sobre el JsonObject y persiste quien lo llama —{@code SpellSlots}, {@code WeaponBuffManager},
	 * {@code ClassLevels}, {@code FeatRegistry}, {@code PresetRegistry}. En cuanto alguien le pase un
	 * ServerPlayer a uno de esos, esta comprobacion empieza a exigirle el guardado sola.</p>
	 */
	private static void checkSheetWritesArePersisted() throws Exception {
		Path root = Path.of("src", "main", "java", "net", "hawthorn", "dndsheets");
		List<Path> files;
		try (Stream<Path> walk = Files.walk(root, 1)) {
			files = walk.filter(f -> f.toString().endsWith(".java")).sorted().toList();
		}

		Set<String> offenders = new java.util.TreeSet<>();
		int pureHelpers = 0;
		for (Path file : files) {
			String source = Files.readString(file);
			boolean mutates = source.contains("sheet.addProperty(") || source.contains("sheet.remove(")
				|| source.contains("Sheet.addProperty(") || source.contains("Sheet.remove(");
			if (!mutates) continue;

			if (!source.contains("ServerPlayer")) { pureHelpers++; continue; }

			boolean saves = source.contains("saveServer(") || source.contains("saveAndSync(")
				|| source.contains("saveCharacter(");
			if (!saves) offenders.add(file.getFileName().toString());
		}

		assertTrue(offenders.isEmpty(),
			"estos archivos mutan una hoja y tienen el ServerPlayer a mano, pero nunca la guardan: " + offenders
				+ ".\n  Es la invariante 4: manda el cambio por SheetLoader.saveAndSync (guarda + sincroniza) o por"
				+ " saveServer si ya mandas un parche por campo. Sin eso el cambio solo vive en RAM hasta el autosave"
				+ " de 5 minutos, y un cierre antes de que salte lo deshace sin avisar a nadie.");

		System.out.println("checkSheetWritesArePersisted: OK, toda mutacion de hoja con jugador delante persiste ("
			+ pureHelpers + " helpers puros exentos, persiste quien los llama).");
	}

	/**
	 * <p>El TERCER angulo del cable, tras la cuenta ({@code NETWORK_SHAPE}) y el orden
	 * ({@code NETWORK_ORDER}): que tipo tiene cada campo. Se hashea la secuencia de {@code writeX}/
	 * {@code readX} de todas las clases de {@code network/}.</p>
	 *
	 * <p>Existe porque el hueco se demostro solo: cambiar las etiquetas de {@code BrowseListMessage} de
	 * texto plano a {@code Component} —para que el compendio lo traduzca el cliente— no movio ni la cuenta
	 * ni el orden, asi que las otras dos comprobaciones dieron OK mientras la compatibilidad ya estaba
	 * rota. Un cliente viejo lee un String donde el servidor escribe un Component y se desincroniza a
	 * mitad del paquete, en silencio.</p>
	 */
	/**
	 * <p>El candado de DM no se copia: se pide por {@code NetworkUtil.handleOnServerAsDm}.</p>
	 *
	 * <p>Esto no es orden por gusto. El guard estaba escrito a mano, palabra por palabra, en <b>22</b>
	 * mensajes, y es una comprobacion de PERMISOS. Un mensaje nuevo que se olvide de el no falla ni avisa:
	 * el cliente puede mandar el paquete sin tener el menu abierto, asi que cualquier jugador podria borrar
	 * piezas de mazmorra, invocar monstruos o editar el contenido. Con el helper no se puede olvidar — o
	 * pides el jugador por esa puerta, o no lo tienes.</p>
	 *
	 * <p><b>Alcance honesto:</b> esto atrapa la copia LITERAL volviendo, que es lo que se acaba de quitar.
	 * No entiende Java, asi que una redaccion distinta del mismo candado se le escapa. Es una red contra el
	 * copiar-pegar, no una prueba de que todo mensaje de DM este protegido.</p>
	 */
	private static void checkDmGuardIsShared() throws Exception {
		Path networkDir = Path.of("src", "main", "java", "net", "hawthorn", "dndsheets", "network");
		List<Path> mensajes;
		try (Stream<Path> files = Files.list(networkDir)) {
			mensajes = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
		}

		Set<String> copiado = new java.util.TreeSet<>();
		for (Path mensaje : mensajes) {
			//NetworkUtil ES el sitio donde vive el candado; ahi tiene que estar.
			if (mensaje.getFileName().toString().equals("NetworkUtil.java")) continue;
			if (Files.readString(mensaje).contains("!dm.hasPermissions(2)")) {
				copiado.add(mensaje.getFileName().toString());
			}
		}

		assertTrue(copiado.isEmpty(),
			"estos mensajes vuelven a llevar el candado de DM copiado a mano: " + copiado
				+ ".\n  Usa NetworkUtil.handleOnServerAsDm(context, dm -> { ... }): resuelve el emisor y"
				+ " comprueba el permiso en un solo sitio. Copiarlo es como se olvida, y olvidarlo deja el"
				+ " mensaje abierto a cualquier jugador.");

		System.out.println("checkDmGuardIsShared: OK, el candado de DM vive solo en NetworkUtil.");
	}

	private static void checkNetworkWire() throws Exception {
		Path networkDir = Path.of("src", "main", "java", "net", "hawthorn", "dndsheets", "network");
		List<Path> mensajes;
		try (Stream<Path> files = Files.list(networkDir)) {
			mensajes = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
		}

		StringBuilder cable = new StringBuilder();
		int llamadas = 0;
		for (Path mensaje : mensajes) {
			cable.append(mensaje.getFileName()).append(':');
			java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("\\b(?:buffer|buf)\\.(write|read)([A-Za-z]+)\\(").matcher(Files.readString(mensaje));
			while (m.find()) {
				cable.append(m.group(1)).append(m.group(2)).append(',');
				llamadas++;
			}
		}

		int hash = cable.toString().hashCode();
		assertTrue(hash == DndsheetsMod.NETWORK_WIRE,
			"cambio LO QUE SE ESCRIBE en el cable (hash " + hash + ", anotado " + DndsheetsMod.NETWORK_WIRE
				+ ") con la cuenta y el orden intactos: algun campo cambio de tipo, o se anadio/quito uno"
				+ " dentro de un mensaje que ya existia."
				+ "\n  Eso rompe la compatibilidad igual que anadir un mensaje: el cliente viejo lee un tipo"
				+ " donde el servidor nuevo escribe otro y se desincroniza a mitad del paquete. Sube"
				+ " PROTOCOL_VERSION y anota aqui el hash nuevo.");

		System.out.println("checkNetworkWire: OK, " + llamadas + " lecturas/escrituras en el cable, hash " + hash + ".");
	}

	private static void checkNetworkShape() throws Exception {
		String mod = readSource("DndsheetsMod.java");
		int messages = mod.split("addNetworkMessage" + java.util.regex.Pattern.quote("("), -1).length - 1;

		//La cuenta de arriba no ve un intercambio: mover dos entradas ya registradas deja el total igual y
		//renumera los ids en silencio, que es justo lo que la invariante 1 prohíbe. Así que además del cuánto
		//se anota el ORDEN: los nombres, en la secuencia en que salen de la fuente, resumidos en un hash.
		StringBuilder order = new StringBuilder();
		java.util.regex.Matcher registered = java.util.regex.Pattern
			.compile("addNetworkMessage\\(\\s*(\\w+)\\.class").matcher(mod);
		int orderedMessages = 0;
		while (registered.find()) {
			order.append(registered.group(1)).append(',');
			orderedMessages++;
		}
		assertTrue(orderedMessages > 0, "no encontré ninguna llamada a addNetworkMessage(X.class), ¿cambió su forma?");

		int enumConstants = 0;
		StringBuilder detail = new StringBuilder();
		Path networkDir = Path.of("src", "main", "java", "net", "hawthorn", "dndsheets", "network");
		try (Stream<Path> files = Files.list(networkDir)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
				java.util.regex.Matcher enums = java.util.regex.Pattern
					.compile("enum\\s+(\\w+)\\s*[{]([^}]*)[}]").matcher(Files.readString(file));
				while (enums.find()) {
					int count = enums.group(2).split(",").length;
					enumConstants += count;
					//Mismo motivo que con los mensajes: un enum que viaja por ordinal se desalinea al reordenarlo
					//sin que la cuenta se entere. Se guardan los nombres tal y como aparecen.
					order.append(enums.group(1)).append(':');
					for (String constant : enums.group(2).split(",")) order.append(constant.trim()).append(',');
					detail.append("\n    ").append(file.getFileName()).append(' ').append(enums.group(1)).append(": ").append(count);
				}
			}
		}

		//ContentType vive fuera de network/ y cruza el cable igual (ContentEntry*Message lo escriben con
		//writeEnum, o sea por ordinal). La primera versión de esta comprobación solo miraba la carpeta
		//network/, así que añadirle una constante no movía el número: justo el agujero que esto existe para
		//tapar. Se cuenta por su fuente porque el enum no se puede cargar aquí — sus constantes resuelven
		//DndPaths, y eso pide una instancia de Forge arrancada.
		java.util.regex.Matcher contentTypes = java.util.regex.Pattern
			.compile("(?m)^\\t([A-Z_]+)\\(DndPaths\\.").matcher(readSource("ContentType.java"));
		int wireEnumsOutsideNetwork = 0;
		order.append("ContentType:");
		while (contentTypes.find()) {
			order.append(contentTypes.group(1)).append(',');
			wireEnumsOutsideNetwork++;
		}
		assertTrue(wireEnumsOutsideNetwork > 0, "no encontré las constantes de ContentType, ¿cambió su forma?");
		detail.append("\n    ContentType.java ContentType: ").append(wireEnumsOutsideNetwork);

		int shape = messages + enumConstants + wireEnumsOutsideNetwork;
		assertTrue(shape == DndsheetsMod.NETWORK_SHAPE,
			"la forma de la red cambió (" + messages + " mensajes + " + enumConstants + " constantes en network/ + "
				+ wireEnumsOutsideNetwork + " fuera = " + shape
				+ ", anotado " + DndsheetsMod.NETWORK_SHAPE + ")." + detail
				+ "\n  Si añadiste un mensaje o una constante de enum que cruza el cable: añádelo al FINAL,"
				+ " sube PROTOCOL_VERSION y actualiza NETWORK_SHAPE. Si no subes la versión, un cliente nuevo"
				+ " y un servidor viejo se dan la mano y se desalinean después, en silencio.");

		int orderHash = order.toString().hashCode();
		assertTrue(orderHash == DndsheetsMod.NETWORK_ORDER,
			"el ORDEN de la red cambió (hash " + orderHash + ", anotado " + DndsheetsMod.NETWORK_ORDER + ") con la"
				+ " cuenta intacta: se movió algo de sitio en vez de añadirse al final." + detail
				+ "\n  El id de un mensaje es su orden de registro y los enums viajan por ordinal, así que"
				+ " intercambiar dos entradas renumera a las dos sin romper nada al compilar. Si el movimiento es"
				+ " a propósito, sube PROTOCOL_VERSION y anota este hash en NETWORK_ORDER; si no, devuélvelo a su"
				+ " sitio y añade lo nuevo al FINAL.");

		System.out.println("checkNetworkShape: OK, " + shape + " piezas en el cable (" + messages + " mensajes, " + enumConstants
			+ " constantes en network/ y " + wireEnumsOutsideNetwork + " fuera), orden " + orderHash + ".");
	}

	/**
	 * <p>La hoja de personaje dibuja en DOS espacios de coordenadas: {@code renderLabels} corre ya trasladado
	 * a la esquina de la hoja, y {@code render} corre en coordenadas de pantalla. Las constantes de la
	 * retícula ({@code PANEL_X}, {@code ATTACK_TOP}...) son de la hoja, así que usarlas desde {@code render}
	 * pinta el texto en la esquina de la pantalla, encima del panel lateral de características.</p>
	 *
	 * <p>Reportado jugando exactamente así: el aviso de "sin ataques" salía superpuesto a las
	 * características, ilegible. No falla al compilar ni deja rastro en el log — solo se ve.</p>
	 */
	private static void checkSheetCoordinateSpaces() throws Exception {
		String source = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets",
			"client", "gui", "CharacterSheetScreen.java"));
		int from = source.indexOf("public void render(GuiGraphics");
		assertTrue(from > 0, "no encontré render(GuiGraphics...) en CharacterSheetScreen");
		String render = source.substring(from, source.indexOf("\n\t}", from));

		//Dibujar texto de la retícula desde render() es el error; los widgets se colocan con leftPos/topPos
		//al construirlos y se pintan solos, así que render() no debería nombrar la retícula en absoluto.
		for (String grid : List.of("PANEL_X", "PANEL_RIGHT", "ATTACK_TOP", "SEC1_Y", "section(guiGraphics")) {
			assertTrue(!render.contains(grid),
				"render() usa \"" + grid + "\", que es una coordenada de la HOJA: sin la traslación de "
					+ "leftPos/topPos eso se dibuja en la esquina de la pantalla, encima del panel lateral. "
					+ "Va en renderLabels, que ya corre trasladado.");
		}

		System.out.println("checkSheetCoordinateSpaces: OK, la retícula de la hoja solo se usa donde está trasladada.");
	}

	/**
	 * <p>"Una criatura incapacitada no puede realizar acciones ni reacciones" (5e). Hay <b>tres</b> formas de
	 * hacer algo en este mod —acción, reacción y acción legendaria— y la regla estaba escrita en una sola,
	 * así que un monstruo paralizado seguía haciendo ataques de oportunidad y un dragón dormido seguía
	 * repartiendo tres ataques por asalto.</p>
	 *
	 * <p>Se sostiene por estructura porque las tres rutas necesitan entidades de un mundo. Lo que sí se puede
	 * comprobar sin juego es la parte pura: qué condiciones cuentan como incapacitar.</p>
	 */
	private static void checkIncapacitatedCannotAct() throws Exception {
		//La lista de 5e: paralizado, aturdido, petrificado e inconsciente incapacitan; envenenado o
		//derribado, no. Confundirlas convierte una condición dura en una molestia o al revés.
		for (Condition blocking : List.of(Condition.PARALIZADO, Condition.ATURDIDO, Condition.PETRIFICADO,
				Condition.INCONSCIENTE, Condition.INCAPACITADO)) {
			FakeCombatant victim = new FakeCombatant(0);
			victim.addCondition(blocking);
			assertTrue(victim.cannotAct(), blocking + " debería impedir actuar");
		}
		for (Condition harmless : List.of(Condition.ENVENENADO, Condition.DERRIBADO, Condition.CEGADO)) {
			FakeCombatant victim = new FakeCombatant(0);
			victim.addCondition(harmless);
			assertTrue(!victim.cannotAct(), harmless + " estorba, pero no impide actuar");
		}

		//Y que las tres puertas pregunten. tryAct ya lo hacía; tryReact y las acciones legendarias no.
		String turnManager = readSource("TurnManager.java");
		for (String method : List.of("public static boolean tryAct(", "public static boolean tryReact(")) {
			int from = turnManager.indexOf(method);
			assertTrue(from > 0, "no encontré " + method);
			String body = turnManager.substring(from, turnManager.indexOf("\n\t}", from));
			assertTrue(body.contains("isIncapacitated("),
				method + " debería rechazar a quien está incapacitado: en 5e no puede ni actuar ni reaccionar");
		}
		assertTrue(readSource("LegendaryActionManager.java").contains("isIncapacitated("),
			"un jefe incapacitado no puede tomar acciones legendarias, y la regla lo dice explícitamente");

		//Lo mismo para MOVERSE. Los jugadores y los mobs de compatibilidad pasan por MovementAnchorTracker,
		//pero los monstruos propios se mueven con un teleport en MonsterActionManager, así que la regla no les
		//llegaba: un monstruo dentro de un Enmarañar salía andando, que es lo que ese conjuro existe para
		//impedir. Se comprueba qué condiciones paran (agarrado y apresado paran sin incapacitar, que es
		//justo el par que distingue "no puedo moverme" de "no puedo actuar").
		for (Condition stopping : List.of(Condition.AGARRADO, Condition.APRESADO, Condition.PARALIZADO,
				Condition.PETRIFICADO, Condition.INCONSCIENTE)) {
			FakeCombatant stuck = new FakeCombatant(0);
			stuck.addCondition(stopping);
			assertTrue(stuck.cannotMove(), stopping + " debería dejar la velocidad a 0");
		}
		FakeCombatant grappled = new FakeCombatant(0);
		grappled.addCondition(Condition.AGARRADO);
		assertTrue(grappled.cannotMove() && !grappled.cannotAct(),
			"agarrado para el movimiento pero NO la acción: son dos reglas distintas y por eso hacen falta dos comprobaciones");

		assertTrue(readSource("MonsterActionManager.java").contains("cannotMove()"),
			"el movimiento de un monstruo propio debería respetar la velocidad 0, no solo el de jugadores y mobs vanilla");
		//Y que un monstruo ataque con SUS propias condiciones: invisible con ventaja, asustado con desventaja.
		assertTrue(readSource("MonsterActionManager.java").contains("ownAttackAdvantage()"),
			"un monstruo debería atacar con la ventaja que le den sus propias condiciones, no solo con la del objetivo");

		//Y que quien las sufre pueda VERLAS. Media docena de reglas del motor cuelgan de las condiciones, y
		//estaban solo en el Panel de DM: un jugador paralizado veía sus clics no hacer nada, que se lee como
		//un mod roto y no como la regla que es. El punto único de escritura tiene que avisar al cliente.
		String combatant = readSource("Combatant.java");
		int from = combatant.indexOf("default void setConditionSources(");
		assertTrue(from > 0, "no encontré el punto de escritura de condiciones");
		String writePoint = combatant.substring(from, combatant.indexOf("\n\t\t}", from));
		assertTrue(writePoint.contains("sendSheetFieldUpdate("),
			"cambiar las condiciones de un jugador debería avisarle: si no, su copia se queda con las de antes");

		//Y lo mismo para lo que el jugador LLEVA ENCIMA y decide su próxima tirada: concentración, dado de
		//Inspiración y castigo armado vivían solo en el servidor. Un modificador que no se ve no se puede
		//jugar, se descubre después en el resultado.
		for (String manager : List.of("ConcentrationManager.java", "BardInspirationManager.java", "PaladinSmiteManager.java")) {
			assertTrue(readSource(manager).contains("sendSheetFieldUpdate("),
				manager + " cambia algo que decide la próxima tirada del jugador y debería avisarle");
		}
		//El HUD tiene que leerlos: sin esto, los parches llegarían y no se vería nada igualmente.
		String hud = Files.readString(Path.of("src", "main", "java", "net", "hawthorn", "dndsheets",
			"client", "ResourceHudOverlay.java"));
		for (String field : List.of("concentratingOn", "bardicInspiration", "smitePending", "conditions")) {
			assertTrue(hud.contains(field), "el HUD debería enseñar \"" + field + "\"");
		}

		System.out.println("checkIncapacitatedCannotAct: OK, las condiciones se respetan en las dos direcciones y el jugador ve lo que le pasa y lo que lleva encima.");
	}

	/**
	 * <p>Que un addon pueda añadir contenido <b>sin escribir Java</b>: un JSON en
	 * {@code data/&lt;loquesea&gt;/dndsheets/&lt;tipo&gt;/} y ya. Es la diferencia entre tener ecosistema y no
	 * tenerlo — los mods con cientos de addons lo son porque extenderlos es poner datos en una carpeta.</p>
	 *
	 * <p>Se comprueba contra el datapack de ejemplo del propio repo, parseándolo con los MISMOS parsers que
	 * usa el juego. Lo que no puede comprobarse aquí es el enganche con el gestor de recursos de Minecraft
	 * (necesita un servidor), así que eso se sostiene por estructura.</p>
	 */
	private static void checkAddonContentLoads() throws Exception {
		Path addon = Path.of("src", "test", "resources", "addon_example");
		assertTrue(Files.isDirectory(addon), "falta el datapack de ejemplo: es la documentación ejecutable de cómo se escribe un addon");
		assertTrue(Files.exists(addon.resolve("pack.mcmeta")), "un datapack sin pack.mcmeta no lo carga Minecraft");

		//Una entrada suelta por archivo, que es la convención de datapack — y el caso que NO existía antes,
		//porque los packs escritos a mano son arrays.
		JsonObject spell = JsonParser.parseString(Files.readString(
			addon.resolve("data/miaddon/dndsheets/spells/rayo_de_ejemplo.json"))).getAsJsonObject();
		assertTrue(!spell.isJsonArray() && spell.has("id"), "el ejemplo debería ser un objeto suelto, no un array");
		//Por el CARGADOR, no por el parser a secas: lo que hay que demostrar es que un archivo con una entrada
		//suelta —la convención de datapack— se carga, y ese caso no existía antes porque los packs escritos a
		//mano son arrays. Parsear el objeto a pelo pasaría igual con el cargador roto.
		java.util.List<String> loadedIds = new java.util.ArrayList<>();
		int count = SpellRegistry.loadJson(spell, "ejemplo", loadedIds::add);
		assertTrue(count == 1 && loadedIds.contains("miaddon:rayo_de_ejemplo"),
			"un archivo con UNA entrada suelta debería cargar una entrada, y avisar de su id");
		SpellRegistry.Spell parsed = SpellRegistry.get("miaddon:rayo_de_ejemplo");
		assertTrue(parsed != null && parsed.scalesWithSlot(),
			"y quedar registrado de verdad, con su subida de nivel");

		JsonObject monster = JsonParser.parseString(Files.readString(
			addon.resolve("data/miaddon/dndsheets/monsters/centinela_de_ejemplo.json"))).getAsJsonObject();
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.parse(monster);
		assertTrue(block.type() == CreatureType.CONSTRUCT && block.attacksPerTurn() == 2 && !block.attacks().isEmpty(),
			"el monstruo del addon debería traer tipo, multiataque y ataques");

		//Y que el cargador siga enganchado a la recarga de datapacks: sin esto, los archivos están bien
		//escritos y no los lee nadie.
		String loader = readSource("ContentDatapackLoader.java");
		assertTrue(loader.contains("AddReloadListenerEvent"),
			"el contenido de datapacks tiene que engancharse a la recarga, o un addon no se carga nunca");
		for (String folder : List.of("weapons", "spells", "monsters", "presets", "traits", "items", "encounters", "feats")) {
			assertTrue(loader.contains("\"" + folder + "\""),
				"un addon debería poder traer " + folder + " igual que el resto");
		}

		System.out.println("checkAddonContentLoads: OK, un addon añade contenido con solo poner JSON en su carpeta.");
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
		//El dado de golpe sale de Config.hitDiceByClass, que ahora está SEMBRADO con los valores por defecto
		//en vez de vacío hasta que Forge parsea el .toml (ver Config). Antes de eso, aquí —y en el juego,
		//durante todo el arranque anterior a la carga de la config— un guerrero era d8 como cualquier otra
		//clase. Así que estas cifras son las de un d10 de verdad.
		JsonObject hero = new JsonObject();
		hero.addProperty("characterClass", "fighter");
		hero.addProperty("constitution", "14"); //+2

		//Nivel 1 = dado completo + mod → 10 + 2.
		assertTrue(CharacterRules.maxHitPointsFor(hero, 1) == 12,
			"a d10 con CON 14 deberían salir 12 PG a nivel 1, dio " + CharacterRules.maxHitPointsFor(hero, 1));
		//Cada nivel siguiente suma media+1 (6) + mod (2) = 8.
		assertTrue(CharacterRules.maxHitPointsFor(hero, 2) == 20,
			"debería subir a 20 PG a nivel 2, dio " + CharacterRules.maxHitPointsFor(hero, 2));
		assertTrue(CharacterRules.maxHitPointsFor(hero, 3) == 28,
			"debería escalar de forma lineal por nivel, dio " + CharacterRules.maxHitPointsFor(hero, 3));
		//Nivel 0 o negativo se trata como 1: en 5e ningún personaje es de nivel 0.
		assertTrue(CharacterRules.maxHitPointsFor(hero, 0) == 12, "el nivel 0 debería tratarse como nivel 1");
		//Y el d8 sigue siendo el valor por defecto de una clase que la tabla no conoce.
		JsonObject homebrew = new JsonObject();
		homebrew.addProperty("characterClass", "Cazarrecompensas");
		homebrew.addProperty("constitution", "14");
		assertTrue(CharacterRules.maxHitPointsFor(homebrew, 1) == 10,
			"una clase de la casa cae al d8 documentado, dio " + CharacterRules.maxHitPointsFor(homebrew, 1));

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
