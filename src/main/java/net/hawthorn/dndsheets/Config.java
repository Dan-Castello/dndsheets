package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <p>Tabla de dados de golpe por clase, en config/dndsheets-common.toml, para poder
 * editarla (o traducirla, o ampliarla a mano) en bloque sin tocar el código ni recompilar.</p>
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
//Interno: no forma parte de la API pública versionada del mod (ver net.hawthorn.dndsheets.api.DndSheetsApi
//y su API_VERSION). Un mod externo que llame estos métodos directo en vez de a través de la fachada se
//expone a que cambien de firma sin aviso.
public class Config {
	public static final ForgeConfigSpec SPEC;
	private static final ForgeConfigSpec.ConfigValue<List<? extends String>> HIT_DICE_ENTRIES;
	private static final ForgeConfigSpec.ConfigValue<List<? extends String>> WEAPON_DAMAGE_ENTRIES;
	private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ENCHANT_BONUS_ENTRIES;

	static {
		ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
		builder.comment(
			"Dado de golpe por clase, usado para calcular la vida máxima real a partir de clase + nivel + constitución.",
			"Formato: una entrada por línea como \"nombre:dado\" (p.ej. \"guerrero:10\").",
			"La coincidencia es por subcadena e insensible a mayúsculas contra el campo 'Clase y Nivel' de la hoja.",
			"Añade aquí los nombres de clase que uses en tu mesa (en cualquier idioma) sin tocar el código."
		);
		HIT_DICE_ENTRIES = builder.defineList("hitDice", defaultHitDice(), Config::isValidEntry);

		builder.comment(
			"Daño por defecto de cada arma, usado para precargar la pestaña de Ataques con las armas",
			"que el jugador lleve en el inventario, y para la tirada automática al golpear un armor stand.",
			"Formato: una entrada por línea como \"id_de_item;dado;característica\" (p.ej. \"minecraft:iron_sword;1d6;str\").",
			"La característica es str o dex. Cada jugador puede sobrescribir su propia tirada editando la entrada en su hoja.",
			"",
			"También admite armas personalizadas (dagas, lanzas, dardos...) sobre CUALQUIER ítem base:",
			"dale al ítem una etiqueta NBT {dndsheets:{weapon:\"tu_id\"}} (por /give, loot table con",
			"set_nbt, etc.) y usa ese mismo \"tu_id\" como clave aquí, p.ej. \"dndsheets:dagger;1d4;dex\".",
			"Esa etiqueta manda sobre el id del ítem base, así puedes repartirlas como loot."
		);
		WEAPON_DAMAGE_ENTRIES = builder.defineList("weaponDamage", defaultWeaponDamage(), Config::isValidWeaponEntry);

		builder.comment(
			"Bono de daño por nivel de encantamiento del arma, sumado como número plano a la tirada",
			"(equivalente al +1/+2/+3 de un arma mágica en 5e), no como dados extra.",
			"Formato: una entrada por línea como \"id_de_encantamiento;bono_por_nivel\" (p.ej. \"minecraft:sharpness;1\").",
			"Un Sharpness III con bono 1 suma +3. Añade aquí cualquier otro encantamiento que quieras que cuente."
		);
		ENCHANT_BONUS_ENTRIES = builder.defineList("enchantmentDamageBonus", defaultEnchantBonus(), Config::isValidEnchantEntry);

		SPEC = builder.build();
	}

	private static List<String> defaultHitDice() {
		return List.of(
			"barbarian:12", "bárbaro:12", "barbaro:12",
			"fighter:10", "guerrero:10",
			"paladin:10", "paladín:10",
			"ranger:10", "explorador:10",
			"bard:8", "bardo:8",
			"cleric:8", "clérigo:8", "clerigo:8",
			"druid:8", "druida:8",
			"monk:8", "monje:8",
			"rogue:8", "pícaro:8", "picaro:8",
			"warlock:8", "brujo:8",
			"sorcerer:6", "hechicero:6",
			"wizard:6", "mago:6"
		);
	}

	private static boolean isValidEntry(Object entry) {
		if (!(entry instanceof String s)) return false;
		String[] parts = s.split(":");
		if (parts.length != 2) return false;
		try {
			Integer.parseInt(parts[1].trim());
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static List<String> defaultWeaponDamage() {
		List<String> entries = new java.util.ArrayList<>();
		for (String tier : new String[]{"wooden", "stone", "iron", "golden", "diamond", "netherite"}) {
			entries.add("minecraft:" + tier + "_sword;1d6;str");
			entries.add("minecraft:" + tier + "_axe;1d8;str");
		}
		entries.add("minecraft:bow;1d8;dex");
		entries.add("minecraft:crossbow;1d8;dex");
		entries.add("minecraft:trident;1d8;str");

		//Armas personalizadas de ejemplo (ver etiqueta NBT en el comentario de arriba). Bórralas o cambia
		//el dado libremente; solo son un punto de partida para dagas/lanzas/dardos repartidos como loot.
		entries.add("dndsheets:dagger;1d4;dex");
		entries.add("dndsheets:spear;1d6;str");
		entries.add("dndsheets:dart;1d4;dex");
		return entries;
	}

	private static boolean isValidWeaponEntry(Object entry) {
		if (!(entry instanceof String s)) return false;
		String[] parts = s.split(";");
		if (parts.length != 3) return false;
		String ability = parts[2].trim().toLowerCase(Locale.ROOT);
		return ability.equals("str") || ability.equals("dex");
	}

	private static List<String> defaultEnchantBonus() {
		return List.of(
			"minecraft:sharpness;1",
			"minecraft:smite;1",
			"minecraft:bane_of_arthropods;1",
			"minecraft:power;1",
			"minecraft:impaling;1"
		);
	}

	private static boolean isValidEnchantEntry(Object entry) {
		if (!(entry instanceof String s)) return false;
		String[] parts = s.split(";");
		if (parts.length != 2) return false;
		try {
			Integer.parseInt(parts[1].trim());
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	//"hands" es "one" (por defecto), "two" (a dos manos de verdad, ver CombatManager.blockedByOffhand) o
	//"versatile" (1d8/1d10 tipo espada larga: más daño con las dos manos libres). "versatileDice" solo
	//importa si hands=="versatile" — ver CombatManager, que decide cuál usar mirando si la otra mano está
	//vacía. "classes": lista de subcadenas (en minúscula) contra las que se compara characterClass, mismo
	//patrón que ya usa hitDieFor — vacía/null significa "cualquier clase puede usarla" (el caso por defecto).
	public record WeaponDefault(String dice, String ability, String damageType, String hands, String versatileDice, List<String> classes) {
		public boolean isVersatile() {
			return "versatile".equals(hands) && versatileDice != null;
		}

		public boolean allowsClass(String characterClass) {
			if (classes == null || classes.isEmpty()) return true;
			if (characterClass == null) return false;
			String normalized = characterClass.toLowerCase(Locale.ROOT);
			for (String allowed : classes) if (normalized.contains(allowed)) return true;
			return false;
		}
	}
	//customModelData: opcional, para que un resource pack reskinee un arma personalizada por número en vez
	//de compartir la textura del ítem base — null significa "sin modelo custom" (comportamiento de siempre).
	public record WeaponGiveInfo(String displayName, String baseItemId, Integer customModelData) {}

	private static Map<String, Integer> hitDiceByClass = new LinkedHashMap<>();
	private static Map<String, WeaponDefault> weaponDamageByItem = new LinkedHashMap<>();
	private static Map<String, Integer> enchantBonusPerLevel = new LinkedHashMap<>();

	//Armas cargadas en caliente por /dndweapons load (ver WeaponCommand), no por el toml. Tienen prioridad
	//sobre weaponDamageByItem por si un pack de armas quiere pisar un id ya definido ahí.
	private static final Map<String, WeaponDefault> jsonWeapons = new LinkedHashMap<>();
	private static final Map<String, WeaponGiveInfo> jsonWeaponGiveInfo = new LinkedHashMap<>();

	/**
	 * <p>Registra (o sobrescribe) un arma en memoria, típicamente desde un JSON cargado con
	 * {@code /dndweapons load}. No se guarda en el toml: se pierde al reiniciar el servidor a menos
	 * que se vuelva a cargar el mismo archivo.</p>
	 *
	 * <p>F22 del audit: de los 5 overloads posicionales que había antes (hasta 10 parámetros String),
	 * solo este de 10 parámetros tenía llamadas reales (ver {@link #loadFile} y
	 * {@link net.hawthorn.dndsheets.api.DndSheetsApi#registerWeapon}, que ya resuelve los campos
	 * opcionales con {@link net.hawthorn.dndsheets.api.WeaponRegistration}) — el resto se eliminó.</p>
	 */
	public static void registerWeapon(String id, String dice, String ability, String damageType, String hands, String versatileDice, List<String> classes, String displayName, String baseItemId, Integer customModelData) {
		List<String> normalizedClasses = new java.util.ArrayList<>();
		for (String c : classes) normalizedClasses.add(c.toLowerCase(Locale.ROOT));
		jsonWeapons.put(id, new WeaponDefault(dice, ability.toLowerCase(Locale.ROOT), damageType.toLowerCase(Locale.ROOT), hands.toLowerCase(Locale.ROOT), versatileDice, normalizedClasses));
		jsonWeaponGiveInfo.put(id, new WeaponGiveInfo(displayName, baseItemId, customModelData));
	}

	public static WeaponGiveInfo giveInfoFor(String weaponId) {
		return jsonWeaponGiveInfo.get(weaponId);
	}

	public static java.util.Set<String> loadedWeaponIds() {
		java.util.Set<String> ids = new java.util.LinkedHashSet<>(jsonWeapons.keySet());
		ids.addAll(weaponDamageByItem.keySet());
		return ids;
	}

	//Solo las armas personalizadas cargadas por JSON (con nombre e ítem base propios), para la pestaña
	//creativa: las de weaponDamageByItem ya son ítems reales de Minecraft, no hace falta repetirlas ahí.
	public static java.util.Set<String> customWeaponIds() {
		return jsonWeaponGiveInfo.keySet();
	}

	//Público: usado por WeaponCommand (/dndweapons load) y por DndPaths para precargar solo todos los
	//.json de la carpeta al arrancar el servidor, sin que DndPaths tenga que depender de la capa de
	//comandos — ver AUDIT_TECHNICAL.md M-ARQ-1. No usa JsonRegistryLoader como los demás *Registry: valida
	//varios campos obligatorios a la vez y llama a registerWeapon con parámetros posicionales en vez de un
	//par parse()/register() sobre un registro propio.
	public static int loadFile(Path file) throws IOException {
		String json = Files.readString(file);
		JsonArray weapons = JsonParser.parseString(json).getAsJsonArray();
		int count = 0;
		int index = 0;
		for (JsonElement element : weapons) {
			index++;
			try {
				JsonObject weapon = element.getAsJsonObject();
				if (!weapon.has("id") || !weapon.has("dice") || !weapon.has("ability")) {
					DndsheetsMod.LOGGER.warn("Saltando arma #{} en {}: falta \"id\", \"dice\" o \"ability\".", index, file.getFileName());
					continue;
				}

				String id = weapon.get("id").getAsString();
				String dice = weapon.get("dice").getAsString();
				String ability = weapon.get("ability").getAsString();
				String name = weapon.has("name") ? weapon.get("name").getAsString() : id;
				String baseItem = weapon.has("item") ? weapon.get("item").getAsString() : "minecraft:stick";
				String damageType = weapon.has("damageType") ? weapon.get("damageType").getAsString() : "fisico";
				String hands = weapon.has("hands") ? weapon.get("hands").getAsString() : "one";
				String versatileDice = weapon.has("versatileDice") ? weapon.get("versatileDice").getAsString() : null;

				//Opcional: qué clases pueden usarla (subcadenas comparadas contra "Clase y Nivel" de la hoja,
				//mismo patrón que hitDieFor) — sin este campo (el caso por defecto) cualquier clase
				//puede usar el arma, igual que antes.
				List<String> classes = new java.util.ArrayList<>();
				if (weapon.has("classes")) {
					for (JsonElement el : weapon.getAsJsonArray("classes")) classes.add(el.getAsString());
				}

				Integer customModelData = weapon.has("customModelData") ? weapon.get("customModelData").getAsInt() : null;

				registerWeapon(id, dice, ability, damageType, hands, versatileDice, classes, name, baseItem, customModelData);
				count++;
			} catch (RuntimeException e) {
				DndsheetsMod.LOGGER.warn("Saltando arma #{} en {}: {}", index, file.getFileName(), e.toString());
			}
		}
		return count;
	}

	//Si el id es directamente un ítem real de Minecraft (p.ej. "minecraft:bow"), se entrega tal cual, sin
	//etiqueta NBT. Si es un id personalizado (p.ej. "dndsheets:dagger"), se etiqueta sobre el ítem base
	//configurado (por /dndweapons load) para que el resto del sistema lo reconozca como esa arma.
	//Público: también lo usan WeaponCommand (/dndweapons give), la pestaña creativa
	//(DndsheetsModCreativeTab) y PresetManager (arma inicial de un preset).
	public static ItemStack buildWeaponStack(String weaponId, int count) {
		ResourceLocation directLoc = ResourceLocation.tryParse(weaponId);
		Item directItem = directLoc != null ? ForgeRegistries.ITEMS.getValue(directLoc) : null;
		if (directItem != null && directItem != Items.AIR) {
			return new ItemStack(directItem, count);
		}

		WeaponGiveInfo giveInfo = giveInfoFor(weaponId);
		Item baseItem = Items.STICK;
		if (giveInfo != null) {
			ResourceLocation baseLoc = ResourceLocation.tryParse(giveInfo.baseItemId());
			Item resolved = baseLoc != null ? ForgeRegistries.ITEMS.getValue(baseLoc) : null;
			if (resolved != null) baseItem = resolved;
		}

		ItemStack stack = new ItemStack(baseItem, count);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putString("weapon", weaponId);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		if (giveInfo != null) {
			stack.setHoverName(Component.literal(giveInfo.displayName()));
			//Reskin por resource pack: un modelo custom en assets/minecraft/models/item/<baseItem>.json puede
			//mapear este número a un modelo/textura distinta, sin que el arma tenga que compartir la del
			//ítem base que la representa (p.ej. una "Daga" que ya no se ve como una espada de hierro).
			if (giveInfo.customModelData() != null) stack.getOrCreateTag().putInt("CustomModelData", giveInfo.customModelData());
		}
		addHandsLore(stack, weaponDefaultFor(weaponId));
		return stack;
	}

	//Para "identificar armas de una y dos manos ya que algunas tienen bonificaciones" (feedback de
	//playtesting): una línea de lore visible en el tooltip del ítem, no solo un dato en el JSON que solo
	//lee el código. Las armas de "hands":"one" (el caso por defecto, casi todas) no llevan lore extra —
	//no hay nada especial que señalar.
	private static void addHandsLore(ItemStack stack, WeaponDefault weaponDefault) {
		if (weaponDefault == null) return;

		String text = switch (weaponDefault.hands()) {
			case "two" -> "A dos manos";
			case "versatile" -> weaponDefault.isVersatile()
				? "Versátil (" + weaponDefault.dice() + " a una mano, " + weaponDefault.versatileDice() + " a dos)"
				: null;
			default -> null;
		};
		if (text == null) return;

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(Component.literal(text).withStyle(ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);
	}

	/**
	 * @param characterClass free text from the sheet's "Class &amp; Level" field.
	 * @return the hit die size (6, 8, 10, 12...) for the first configured class name found
	 * as a substring of {@code characterClass}, or 8 (the most common in 5e) if none match.
	 */
	public static int hitDieFor(String characterClass) {
		if (characterClass == null) return 8;
		String normalized = characterClass.toLowerCase(Locale.ROOT);
		for (Map.Entry<String, Integer> entry : hitDiceByClass.entrySet()) {
			if (normalized.contains(entry.getKey())) return entry.getValue();
		}
		return 8;
	}

	/**
	 * @param itemId registry name of the held item, e.g. "minecraft:iron_sword".
	 * @return the configured default damage die + ability for that item, or null if it isn't a recognized weapon.
	 */
	public static WeaponDefault weaponDefaultFor(String itemId) {
		if (itemId == null) return null;
		WeaponDefault fromJson = jsonWeapons.get(itemId);
		if (fromJson != null) return fromJson;
		return weaponDamageByItem.get(itemId);
	}

	/**
	 * <p>Compatibilidad con armas de OTROS mods (Tinkers' Construct y cualquier otro) sin necesitar un JSON
	 * por ítem: si nadie registró este id a mano (ni JSON ni .toml) pero el ítem ya declara daño de ataque
	 * real por el atributo vanilla {@code ATTACK_DAMAGE} — el mismo que hace que el tooltip diga "X de daño
	 * de ataque" y que el combate normal de Minecraft ya sepa hacer más daño con él — se aproxima como un
	 * arma real de 5e con ESE daño, en vez de tratarlo siempre como un arma sin configurar.</p>
	 *
	 * <p>Se lee de la INSTANCIA real del ítem, no de un valor fijo por id: las herramientas de Tinkers'
	 * Construct guardan sus estadísticas por NBT, distintas en cada herramienta forjada, y ese atributo ya
	 * las refleja sin que este mod tenga que saber nada de Tinkers' Construct (ni de ningún otro mod) en
	 * particular — cualquier ítem de cualquier mod que participe del combate vanilla normal ya expone este
	 * mismo atributo, es el mecanismo que usa Minecraft para que el combate modded funcione en absoluto.</p>
	 *
	 * <p><b>Simplificaciones deliberadas</b>: se expresa como UN dado "1dX" con el mismo promedio que el
	 * daño real del ítem (X = 2×daño-1, p.ej. +6 de daño real → 1d11, promedio 6) en vez de un número fijo
	 * sin variación — sigue siendo una tirada de verdad, con su propia varianza, y dobla en un crítico
	 * igual que cualquier otro dado. No es una conversión exacta (no hay un "dado correcto" único para un
	 * número real de Minecraft), pero mantiene el promedio de poder del ítem tal como lo balanceó el mod
	 * que lo añade. Siempre Fuerza y daño físico, sin versatilidad ni tipo de daño especial; un registro a
	 * mano (JSON o .toml) para un ítem concreto sigue mandando sobre esto (ver {@link #weaponDefaultFor},
	 * que se comprueba primero) — por ejemplo, para tratar una daga modded como Destreza en vez de Fuerza.</p>
	 */
	public static WeaponDefault autoDetectWeapon(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		double bonus = 0;
		for (AttributeModifier modifier : stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
			if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) bonus += modifier.getAmount();
		}
		int average = (int) Math.round(bonus);
		if (average <= 0) return null; //Ni siquiera declara más daño que las manos vacías: no lo tratamos como arma.
		int sides = Math.max(1, 2 * average - 1);
		return new WeaponDefault("1d" + sides, "str", "fisico", "one", null, List.of());
	}

	/**
	 * @return the item's custom weapon id from its {@code {dndsheets:{weapon:"..."}}} NBT tag if it has
	 * one (lets any base item be reskinned into a dagger/lanza/dardo/etc. for loot purposes), otherwise
	 * its plain Minecraft registry id (e.g. "minecraft:iron_sword").
	 */
	public static String weaponIdOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag != null && tag.contains("dndsheets")) {
			CompoundTag dndTag = tag.getCompound("dndsheets");
			if (dndTag.contains("weapon")) return dndTag.getString("weapon");
		}
		return ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
	}

	/**
	 * @param enchantId registry name of the enchantment, e.g. "minecraft:sharpness".
	 * @return the configured flat damage bonus per level of that enchantment, or null if it isn't configured.
	 */
	public static Integer enchantBonusPerLevelFor(String enchantId) {
		if (enchantId == null) return null;
		return enchantBonusPerLevel.get(enchantId);
	}

	private static void reload() {
		Map<String, Integer> parsedHitDice = new LinkedHashMap<>();
		for (String entry : HIT_DICE_ENTRIES.get()) {
			String[] parts = entry.split(":");
			try {
				parsedHitDice.put(parts[0].trim().toLowerCase(Locale.ROOT), Integer.parseInt(parts[1].trim()));
			} catch (NumberFormatException ignored) {
				//Entradas inválidas ya se filtran por isValidEntry(), pero por si acaso.
			}
		}
		hitDiceByClass = parsedHitDice;

		Map<String, WeaponDefault> parsedWeapons = new LinkedHashMap<>();
		for (String entry : WEAPON_DAMAGE_ENTRIES.get()) {
			String[] parts = entry.split(";");
			if (parts.length != 3) continue;
			parsedWeapons.put(parts[0].trim(), new WeaponDefault(parts[1].trim(), parts[2].trim().toLowerCase(Locale.ROOT), "fisico", "one", null, List.of()));
		}
		weaponDamageByItem = parsedWeapons;

		Map<String, Integer> parsedEnchants = new LinkedHashMap<>();
		for (String entry : ENCHANT_BONUS_ENTRIES.get()) {
			String[] parts = entry.split(";");
			if (parts.length != 2) continue;
			try {
				parsedEnchants.put(parts[0].trim(), Integer.parseInt(parts[1].trim()));
			} catch (NumberFormatException ignored) {
				//Entradas inválidas ya se filtran por isValidEnchantEntry(), pero por si acaso.
			}
		}
		enchantBonusPerLevel = parsedEnchants;
	}

	@SubscribeEvent
	public static void onLoad(ModConfigEvent.Loading event) {
		reload();
	}

	@SubscribeEvent
	public static void onReload(ModConfigEvent.Reloading event) {
		reload();
	}
}
