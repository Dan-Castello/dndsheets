package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <p>Bloques de estadísticas de monstruo cargados en caliente por {@code /dndmonsters load}, en memoria
 * (se pierden al reiniciar el servidor a menos que se recargue el mismo archivo). Los monstruos spawneados
 * son mobs vanilla reales con {@code NoAI:1} y una etiqueta NBT persistente {@code {dndsheets:{monster:"id",
 * currentHp:N}}} que los liga a su bloque de estadísticas y trackea su vida real de D&amp;D.</p>
 */
//Interno: no forma parte de la API pública versionada del mod (ver net.hawthorn.dndsheets.api.DndSheetsApi
//y su API_VERSION). Un mod externo que llame estos métodos directo en vez de a través de la fachada se
//expone a que cambien de firma sin aviso.
public class MonsterRegistry {
	/**
	 * <p>Cómo se VE un monstruo, con piezas de Minecraft y sin traer ningún modelo nuevo.</p>
	 *
	 * <p>El bestiario usa 41 modelos vanilla para 330 monstruos, y muy mal repartidos: 52 criaturas son un
	 * devastador, los 43 dragones entre ellas. Para quien juega, eso es "todo es lo mismo con otro nombre" —
	 * y es la ventaja más clara que le sacan los VTT con bibliotecas de fichas y arte.</p>
	 *
	 * <p>Sin poder enviar arte de terceros (licencias) ni inventar modelos, lo que sí se puede hacer es
	 * usar las piezas que Minecraft ya tiene: <b>equipo visible</b> (un esqueleto con yelmo de hierro y
	 * espada no se lee como el mismo bicho que uno pelado), <b>tamaño de cría</b> y <b>brillo</b>. Es
	 * gratis, no pesa nada, y diferencia sobre todo a los 51 humanoides, que es donde más se notaba.</p>
	 *
	 * <p>La respuesta de fondo, la que da un ecosistema de verdad, está en {@code baseEntity}: acepta
	 * CUALQUIER entidad registrada, también la de otro mod. Un addon con dragones de verdad se enchufa
	 * escribiendo su id ahí, sin tocar este mod.</p>
	 */
	public record Appearance(String mainHand, String offHand, String helmet, String chestplate, String leggings,
			String boots, boolean baby, boolean glowing) {

		static final Appearance DEFAULT = new Appearance(null, null, null, null, null, null, false, false);
		static final Appearance GLOWING = new Appearance(null, null, null, null, null, null, false, true);

		public boolean isDefault() {
			return this == DEFAULT || (mainHand == null && offHand == null && helmet == null && chestplate == null
				&& leggings == null && boots == null && !baby && !glowing);
		}
	}

	public record MonsterAttack(String name, String toHitAbility, String dice, String damageAbility, String damageType, String effectName, String effectDice, int effectTurns) {
		public boolean appliesEffect() { return effectName != null; }
	}
	public record MonsterSpell(String name, String saveAbility, int saveDc, String dice, boolean halfOnSave, String damageType, String effectName, String effectDice, int effectTurns) {
		public boolean appliesEffect() { return effectName != null; }
	}

	/**
	 * @param nonmagicalAffinities igual que {@code damageAffinities}, pero solo frente a ataques NO mágicos.
	 *                             Es la forma mas comun del SRD ("contundente, perforante y cortante de
	 *                             ataques no magicos") y afecta a licantropos, demonios, diablos y buena
	 *                             parte del bestiario de VD medio. Las variantes con plata o adamantina se
	 *                             tratan aqui como simplemente "no magico": el mod no tiene esos materiales,
	 *                             y la alternativa era ignorar la resistencia entera.
	 * @param damageAffinities tipo de daño → {@code resistant}/{@code vulnerable}/{@code immune}, mismo
	 *                         vocabulario que {@code damageAffinities} en la hoja de un jugador (ver
	 *                         {@link DamageTypes#multiplierForLabel}). Vacío = sin afinidades, que es como
	 *                         se comportaban todos los monstruos hasta ahora.
	 */
	public record MonsterStatBlock(
		String id, String name, String baseEntityId, int ac, int maxHp,
		Map<String, Integer> abilities, int proficiencyBonus,
		List<MonsterAttack> attacks, List<MonsterSpell> spells,
		Map<String, String> damageAffinities, Map<String, String> nonmagicalAffinities,
		CreatureType type, int legendaryResistances, int legendaryActions, int attacksPerTurn,
		Appearance appearance,
		//"ai": true deja viva la IA de la entidad base en vez de invocarla congelada. Existe para las
		//entidades de mods de NPC (EasyNPC y compañía), que traen sus propios objetivos —patrullar, seguir
		//al grupo, quedarse en su puesto— y son el motivo por el que baseEntityId acepta cualquier entidad
		//instalada: sin esto, el setNoAi de spawnAt los mataba en el instante de aparecer y quedaban de
		//adorno. En COMBATE sigue mandando el mod: TurnManager.freeze apaga esa IA mientras dura el
		//encuentro y la devuelve al acabar, así que el monstruo resuelve su turno con las reglas de 5e
		//(MonsterActionManager.autoAct) y no con la IA de vanilla. Es decir: la IA es para FUERA del
		//combate, que es donde hoy no había nada.
		boolean keepsOwnAi
	) {
		public int abilityModifier(String key) {
			Integer score = abilities.get(key.toLowerCase(Locale.ROOT));
			return score == null ? 0 : Math.floorDiv(score - 10, 2);
		}
	}

	/**
	 * <p>Tipo de criatura de un monstruo del mundo, o {@link CreatureType#UNKNOWN} si no lo tiene (un mob
	 * de compatibilidad, un PNJ genérico o un pack escrito antes de que el campo existiera).</p>
	 */
	//--- Resistencia Legendaria: usos que le quedan a ESTE monstruo concreto, no a su especie. Van en su
	//etiqueta NBT junto a los PG, igual que todo lo demás que es de la instancia: dos dragones del mismo id
	//gastan las suyas por separado, y Minecraft ya guarda y carga ese compartimento solo.

	private static final String LEGENDARY_LEFT = "legendaryLeft";

	/**
	 * <p>Usos de Resistencia Legendaria que le quedan. Sin la etiqueta puesta todavía (un monstruo invocado
	 * antes de que existiera la regla, o recién aparecido) devuelve los de su bloque: el valor por defecto
	 * es "las tiene todas", no "no tiene ninguna".</p>
	 */
	public static int legendaryResistancesLeft(Entity entity) {
		MonsterStatBlock block = statBlockOf(entity);
		if (block == null || block.legendaryResistances() <= 0) return 0;
		CompoundTag tag = entity.getPersistentData().getCompound("dndsheets");
		return tag.contains(LEGENDARY_LEFT) ? Math.max(0, tag.getInt(LEGENDARY_LEFT)) : block.legendaryResistances();
	}

	/** Gasta una. Devuelve false si no le quedaba ninguna. */
	public static boolean spendLegendaryResistance(Entity entity) {
		int left = legendaryResistancesLeft(entity);
		if (left <= 0) return false;
		CompoundTag data = entity.getPersistentData();
		CompoundTag tag = data.getCompound("dndsheets");
		tag.putInt(LEGENDARY_LEFT, left - 1);
		data.put("dndsheets", tag);
		return true;
	}

	public static CreatureType typeOf(Entity entity) {
		//Un jugador es humanoide, y esto no es un detalle: sin ello, Inmovilizar Persona no funcionaría
		//sobre un PJ —el caso más común del conjuro en la mesa— porque un jugador no tiene bloque de
		//estadísticas del que sacar el tipo. Todas las razas jugables del SRD son humanoides.
		if (entity instanceof Player) return CreatureType.HUMANOID;
		MonsterStatBlock block = statBlockOf(entity);
		return block != null ? block.type() : CreatureType.UNKNOWN;
	}

	private static final NamedRegistry<MonsterStatBlock> REGISTRY = new NamedRegistry<>("monstruo", MonsterStatBlock::id);

	public static void register(MonsterStatBlock block) {
		REGISTRY.register(block);
	}

	/** Reescribe sin avisar: ver {@link NamedRegistry#replace}. Lo usa SummonManager en cada invocación. */
	public static void replace(MonsterStatBlock block) {
		REGISTRY.replace(block);
	}

	public static MonsterStatBlock get(String id) {
		return REGISTRY.get(id);
	}

	/**
	 * <p>Cambia SOLO el modelo de un monstruo ya registrado, dejando intactas sus reglas. Lo usa
	 * {@link MonsterSkins} para que un dragón pase a ser el dragón de otro mod sin tocar su ficha.</p>
	 *
	 * <p>Comprueba que la entidad exista antes de cambiar nada, y esa comprobación es toda la seguridad de
	 * la idea: un id equivocado en un pack de aspecto deja el modelo vanilla como estaba en vez de degradar
	 * a un monstruo que funcionaba. Sin ella, una errata convertiría un devastador en un zombi.</p>
	 *
	 * @return {@code true} si se aplicó.
	 */
	public static boolean reskin(String id, String entityId) {
		MonsterStatBlock block = REGISTRY.get(id);
		if (block == null) return false;
		ResourceLocation loc = ResourceLocation.tryParse(entityId);
		if (loc == null || !ForgeRegistries.ENTITY_TYPES.containsKey(loc)) return false;

		REGISTRY.replace(new MonsterStatBlock(block.id(), block.name(), entityId, block.ac(), block.maxHp(),
			block.abilities(), block.proficiencyBonus(), block.attacks(), block.spells(), block.damageAffinities(),
			block.nonmagicalAffinities(), block.type(), block.legendaryResistances(), block.legendaryActions(),
			block.attacksPerTurn(), block.appearance(), block.keepsOwnAi()));
		return true;
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	public static boolean remove(String id) {
		return REGISTRY.remove(id);
	}

	//Público: usado por MonsterCommand (/dndmonsters load) y por DndPaths para precargar solo todos los
	//.json de la carpeta al arrancar el servidor, sin que DndPaths tenga que depender de la capa de
	//comandos. Antes, un monstruo malformado a mitad del archivo abortaba
	//el resto (visible solo como un WARN de carga, invisible para el DM en el chat) — JsonRegistryLoader
	//ya salta por elemento, no por archivo.
	private static final JsonRegistryLoader<MonsterStatBlock> LOADER = new JsonRegistryLoader<>("monstruo", MonsterRegistry::parse, MonsterRegistry::register);

	/** Carga desde un JSON ya leído (datapack o jar de otro mod) — ver ContentDatapackLoader. */
	public static int loadJson(com.google.gson.JsonElement root, String source, java.util.function.Consumer<String> onId) {
		return LOADER.loadJson(root, source, onId);
	}

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	private static Appearance parseAppearance(JsonObject json) {
		if (json == null) return Appearance.DEFAULT;
		return new Appearance(
			str(json, "mainHand"), str(json, "offHand"), str(json, "helmet"), str(json, "chestplate"),
			str(json, "leggings"), str(json, "boots"),
			json.has("baby") && json.get("baby").getAsBoolean(),
			json.has("glowing") && json.get("glowing").getAsBoolean());
	}

	private static String str(JsonObject json, String key) {
		return json.has(key) ? json.get(key).getAsString() : null;
	}

	public static MonsterStatBlock parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.has("name") ? json.get("name").getAsString() : id;
		String baseEntity = json.has("baseEntity") ? json.get("baseEntity").getAsString() : "minecraft:zombie";
		int ac = json.has("ac") ? json.get("ac").getAsInt() : 10;
		int hp = json.has("hp") ? json.get("hp").getAsInt() : 1;
		int prof = json.has("proficiencyBonus") ? json.get("proficiencyBonus").getAsInt() : 2;
		boolean keepsOwnAi = json.has("ai") && json.get("ai").getAsBoolean();

		Map<String, Integer> abilities = new LinkedHashMap<>();
		JsonObject abilitiesJson = json.has("abilities") ? json.getAsJsonObject("abilities") : null;
		for (String key : Combatant.ABILITIES) {
			abilities.put(key, abilitiesJson != null && abilitiesJson.has(key) ? abilitiesJson.get(key).getAsInt() : 10);
		}

		List<MonsterAttack> attacks = new ArrayList<>();
		if (json.has("attacks")) {
			for (JsonElement el : json.getAsJsonArray("attacks")) {
				attacks.add(parseAttack(el.getAsJsonObject()));
			}
		}

		List<MonsterSpell> spells = new ArrayList<>();
		if (json.has("abilities_special")) {
			for (JsonElement el : json.getAsJsonArray("abilities_special")) {
				JsonObject s = el.getAsJsonObject();
				JsonObject effect = s.has("appliesEffect") ? s.getAsJsonObject("appliesEffect") : null;
				spells.add(new MonsterSpell(
					s.get("name").getAsString(),
					s.has("saveAbility") ? s.get("saveAbility").getAsString().toLowerCase(Locale.ROOT) : "dex",
					s.has("saveDc") ? s.get("saveDc").getAsInt() : 10,
					s.get("dice").getAsString(),
					!s.has("halfOnSave") || s.get("halfOnSave").getAsBoolean(),
					s.has("damageType") ? DamageTypes.normalize(s.get("damageType").getAsString()) : "fisico",
					effect != null ? effect.get("name").getAsString() : null,
					effect != null ? effect.get("dice").getAsString() : null,
					effect != null && effect.has("turns") ? effect.get("turns").getAsInt() : 0
				));
			}
		}

		//Opcionales: un monstruo sin ellas se comporta exactamente como antes, sin resistencias.
		Map<String, String> damageAffinities = readAffinities(json, "damageAffinities");
		Map<String, String> nonmagicalAffinities = readAffinities(json, "nonmagicalAffinities");

		//Opcional tambien: un pack escrito antes de que existiera el campo carga igual, con UNKNOWN, y lo
		//unico que pierde es acceso a las reglas que preguntan por el tipo.
		CreatureType type = CreatureType.parse(json.has("type") ? json.get("type").getAsString() : null);
		//Ausente = 0 = no es un jefe. Es lo correcto por defecto: la Resistencia Legendaria la tiene una
		//docena larga de criaturas del SRD, no el bestiario entero.
		int legendaryResistances = json.has("legendaryResistances") ? Math.max(0, json.get("legendaryResistances").getAsInt()) : 0;
		//Cuántas acciones legendarias puede gastar por asalto (3 en casi todo el SRD). Ausente = 0 = actúa
		//solo en su turno, como cualquier otro monstruo.
		int legendaryActions = json.has("legendaryActions") ? Math.max(0, json.get("legendaryActions").getAsInt()) : 0;
		//Multiataque: cuántos ataques hace en SU turno. 1 por defecto, que es como se comportaba todo el
		//bestiario. El tope de 6 no es una regla de 5e, es un cortafuegos: un número absurdo en un JSON (a
		//propósito o por un dedo) convierte un turno en una ráfaga de mensajes de chat imposible de leer.
		int attacksPerTurn = json.has("multiattack") ? Math.max(1, Math.min(6, json.get("multiattack").getAsInt())) : 1;
		Appearance appearance = parseAppearance(json.has("appearance") ? json.getAsJsonObject("appearance") : null);

		return new MonsterStatBlock(id, name, baseEntity, ac, hp, abilities, prof, attacks, spells, damageAffinities, nonmagicalAffinities, type, legendaryResistances, legendaryActions, attacksPerTurn, appearance, keepsOwnAi);
	}

	private static Map<String, String> readAffinities(JsonObject json, String field) {
		Map<String, String> result = new HashMap<>();
		if (!json.has(field)) return result;
		JsonObject affinities = json.getAsJsonObject(field);
		for (String type : affinities.keySet()) {
			//La CLAVE se normaliza como el tipo del golpe que la va a consultar: un bloque escrito en ingles
			//("fire") tiene que casar con el dano que le llega ("fuego") o la resistencia no existe.
			result.put(DamageTypes.normalize(type), affinities.get(type).getAsString().toLowerCase(Locale.ROOT));
		}
		return result;
	}

	//Extraído de parse() para que también lo use el ataque personalizado que un DM añade en vivo a un
	//monstruo ya invocado (ver addCustomAttack) — mismo formato, un objeto de "attacks" suelto.
	private static MonsterAttack parseAttack(JsonObject a) {
		JsonObject effect = a.has("appliesEffect") ? a.getAsJsonObject("appliesEffect") : null;
		return new MonsterAttack(
			a.get("name").getAsString(),
			a.has("toHitAbility") ? a.get("toHitAbility").getAsString().toLowerCase(Locale.ROOT) : "str",
			a.get("dice").getAsString(),
			a.has("damageAbility") ? a.get("damageAbility").getAsString().toLowerCase(Locale.ROOT) : "str",
			a.has("damageType") ? DamageTypes.normalize(a.get("damageType").getAsString()) : "fisico",
			effect != null ? effect.get("name").getAsString() : null,
			effect != null ? effect.get("dice").getAsString() : null,
			effect != null && effect.has("turns") ? effect.get("turns").getAsInt() : 0
		);
	}

	//Público: usado por el creador de contenido in-game para guardar un monstruo invocado (normalmente un
	//NPC genérico ya armado con ataques en vivo, ver client.gui.MonsterTemplateSaveScreen) como plantilla
	//JSON reusable — mismos nombres de campo que parse() espera, para que cargarlo de vuelta funcione igual
	//que cualquier otro pack de monstruos.
	public static JsonObject toJson(MonsterStatBlock block) {
		JsonObject json = new JsonObject();
		json.addProperty("id", block.id());
		json.addProperty("name", block.name());
		if (block.type() != CreatureType.UNKNOWN) json.addProperty("type", block.type().label());
		if (block.legendaryResistances() > 0) json.addProperty("legendaryResistances", block.legendaryResistances());
		if (block.legendaryActions() > 0) json.addProperty("legendaryActions", block.legendaryActions());
		if (block.attacksPerTurn() > 1) json.addProperty("multiattack", block.attacksPerTurn());
		json.addProperty("baseEntity", block.baseEntityId());
		if (block.keepsOwnAi()) json.addProperty("ai", true);
		json.addProperty("ac", block.ac());
		json.addProperty("hp", block.maxHp());
		json.addProperty("proficiencyBonus", block.proficiencyBonus());

		JsonObject abilities = new JsonObject();
		for (Map.Entry<String, Integer> entry : block.abilities().entrySet()) abilities.addProperty(entry.getKey(), entry.getValue());
		json.add("abilities", abilities);

		if (!block.attacks().isEmpty()) {
			JsonArray attacks = new JsonArray();
			for (MonsterAttack attack : block.attacks()) attacks.add(attackToJson(attack));
			json.add("attacks", attacks);
		}

		//Se omite si está vacío, para no ensuciar cada monstruo guardado con un objeto que no dice nada:
		//parse() ya trata "sin campo" y "vacío" igual.
		writeAffinities(json, "damageAffinities", block.damageAffinities());
		writeAffinities(json, "nonmagicalAffinities", block.nonmagicalAffinities());
		writeAppearance(json, block.appearance());
		return json;
	}

	//Se omite si está vacío, para no ensuciar cada monstruo guardado con un objeto que no dice nada:
	//parse() ya trata "sin campo" y "vacío" igual.
	private static void writeAffinities(JsonObject json, String field, Map<String, String> affinities) {
		if (affinities.isEmpty()) return;
		JsonObject out = new JsonObject();
		for (Map.Entry<String, String> entry : affinities.entrySet()) out.addProperty(entry.getKey(), entry.getValue());
		json.add(field, out);
	}

	private static void writeAppearance(JsonObject json, Appearance look) {
		if (look == null || look.isDefault()) return;
		JsonObject out = new JsonObject();
		if (look.mainHand() != null) out.addProperty("mainHand", look.mainHand());
		if (look.offHand() != null) out.addProperty("offHand", look.offHand());
		if (look.helmet() != null) out.addProperty("helmet", look.helmet());
		if (look.chestplate() != null) out.addProperty("chestplate", look.chestplate());
		if (look.leggings() != null) out.addProperty("leggings", look.leggings());
		if (look.boots() != null) out.addProperty("boots", look.boots());
		if (look.baby()) out.addProperty("baby", true);
		if (look.glowing()) out.addProperty("glowing", true);
		json.add("appearance", out);
	}

	private static JsonObject attackToJson(MonsterAttack attack) {
		JsonObject a = new JsonObject();
		a.addProperty("name", attack.name());
		a.addProperty("toHitAbility", attack.toHitAbility());
		a.addProperty("dice", attack.dice());
		a.addProperty("damageAbility", attack.damageAbility());
		a.addProperty("damageType", attack.damageType());
		return a;
	}

	//--- Etiqueta NBT persistente del mob spawneado (Entity#getPersistentData, la guarda y carga Minecraft solo) ---

	public static void tagAsMonster(Entity entity, String monsterId, int currentHp) {
		CompoundTag tag = new CompoundTag();
		tag.putString("monster", monsterId);
		tag.putInt("currentHp", currentHp);
		entity.getPersistentData().put("dndsheets", tag);
	}

	public static String monsterIdOf(Entity entity) {
		CompoundTag data = entity.getPersistentData();
		if (!data.contains("dndsheets")) return null;
		CompoundTag tag = data.getCompound("dndsheets");
		return tag.contains("monster") ? tag.getString("monster") : null;
	}

	public static MonsterStatBlock statBlockOf(Entity entity) {
		String id = monsterIdOf(entity);
		return id == null ? null : get(id);
	}

	public static int currentHpOf(Entity entity) {
		CompoundTag data = entity.getPersistentData();
		if (!data.contains("dndsheets")) return 0;
		return data.getCompound("dndsheets").getInt("currentHp");
	}

	public static void setCurrentHp(Entity entity, int hp) {
		CompoundTag data = entity.getPersistentData();
		CompoundTag tag = data.getCompound("dndsheets"); //Vacío si no existía, ya está puesta por tagAsMonster.
		tag.putInt("currentHp", hp);
		data.put("dndsheets", tag);
	}

	//--- Ataques personalizados por instancia: para poder editar EN VIVO un monstruo ya invocado (species
	//compartida entre todos los de su id) sin tocar JSON ni reiniciar el servidor. Se guardan aparte del
	//bloque de estadísticas compartido, en la propia etiqueta NBT del mob invocado. Simplificación
	//deliberada: sin "appliesEffect" (veneno, etc.) en los personalizados, solo ataque+daño — si hace
	//falta un efecto, se edita/carga el monstruo entero por JSON como hasta ahora.

	//Se llama en cada acción de un monstruo (turno automático, ataque de oportunidad, o el DM abriendo su
	//menú), así que el JSON parseado se cachea por entityId en vez de reparsearse cada vez — invalidado
	//solo en los dos sitios que de verdad cambian el NBT (saveCustomAttacks/clearCustomAttacks).
	//Indexado por UUID y NO por entity.getId(): el id numerico de una entidad se RECICLA cuando la
	//entidad muere o se descarga, asi que un monstruo nuevo podia heredar el id de uno muerto y con el
	//los ataques personalizados del anterior. No era solo una fuga de memoria: devolvia datos de otro.
	//Se desaloja ademas al morir (ver TurnManager.onMonsterDeath) para que no crezca sin cota.
	private static final Map<java.util.UUID, List<MonsterAttack>> customAttacksCache = new HashMap<>();

	public static List<MonsterAttack> customAttacksOf(Entity entity) {
		List<MonsterAttack> cached = customAttacksCache.get(entity.getUUID());
		if (cached != null) return cached;

		CompoundTag data = entity.getPersistentData();
		List<MonsterAttack> result;
		if (!data.contains("dndsheets")) {
			result = List.of();
		} else {
			CompoundTag tag = data.getCompound("dndsheets");
			if (!tag.contains("customAttacks")) {
				result = List.of();
			} else {
				result = new ArrayList<>();
				for (JsonElement el : JsonParser.parseString(tag.getString("customAttacks")).getAsJsonArray()) {
					result.add(parseAttack(el.getAsJsonObject()));
				}
			}
		}
		customAttacksCache.put(entity.getUUID(), result);
		return result;
	}

	public static void addCustomAttack(Entity entity, MonsterAttack attack) {
		List<MonsterAttack> current = new ArrayList<>(customAttacksOf(entity));
		current.removeIf(existing -> existing.name().equalsIgnoreCase(attack.name())); //Reemplaza si ya había uno con ese nombre.
		current.add(attack);
		saveCustomAttacks(entity, current);
	}

	public static boolean removeCustomAttack(Entity entity, String name) {
		List<MonsterAttack> current = new ArrayList<>(customAttacksOf(entity));
		boolean removed = current.removeIf(existing -> existing.name().equalsIgnoreCase(name));
		if (removed) saveCustomAttacks(entity, current);
		return removed;
	}

	/** Desaloja el caché de un monstruo que acaba de morir. Llamado desde TurnManager.onMonsterDeath. */
	public static void forgetCustomAttacks(Entity entity) {
		customAttacksCache.remove(entity.getUUID());
	}

	public static void clearCustomAttacks(Entity entity) {
		CompoundTag data = entity.getPersistentData();
		if (data.contains("dndsheets")) data.getCompound("dndsheets").remove("customAttacks");
		customAttacksCache.remove(entity.getUUID());
	}

	private static void saveCustomAttacks(Entity entity, List<MonsterAttack> attacks) {
		JsonArray array = new JsonArray();
		for (MonsterAttack attack : attacks) array.add(attackToJson(attack));

		CompoundTag data = entity.getPersistentData();
		CompoundTag tag = data.getCompound("dndsheets"); //Vacío si no existía; solo pasa si el objetivo no era un monstruo tageado.
		tag.putString("customAttacks", array.toString());
		data.put("dndsheets", tag);

		customAttacksCache.put(entity.getUUID(), List.copyOf(attacks)); //Refresca el caché con lo que ya tenemos en memoria, en vez de invalidar y reparsear el JSON que se acaba de escribir.
	}

	//--- Vara de DM: cualquier ítem etiquetado {dndsheets:{dmtool:true}} (mismo patrón que las armas personalizadas) ---

	public static boolean isDmTool(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag != null && tag.contains("dndsheets") && tag.getCompound("dndsheets").getBoolean("dmtool");
	}

	//--- Vara de Movimiento: mismo patrón que la Vara de DM, pero para reposicionar un monstruo ya invocado
	//sin pasar por su menú de ataques (ver MonsterActionManager.onSelectMonsterToMove) ---

	public static boolean isMoveTool(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag != null && tag.contains("dndsheets") && tag.getCompound("dndsheets").getBoolean("movetool");
	}

	//--- Carta de invocación: cualquier ítem etiquetado {dndsheets:{monsterSpawn:"id"}} (usada como un huevo de spawn vanilla) ---

	public static String monsterSpawnIdOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets")) return null;
		CompoundTag dndTag = tag.getCompound("dndsheets");
		return dndTag.contains("monsterSpawn") ? dndTag.getString("monsterSpawn") : null;
	}

	//Para la pestaña creativa (DndsheetsModCreativeTab): un huevo de spawn reetiquetado por monstruo cargado.
	public static ItemStack buildSpawnCard(String monsterId) {
		MonsterStatBlock block = get(monsterId);
		ItemStack stack = ItemLook.SUMMON_CARD.applyTo(
			new ItemStack(net.hawthorn.dndsheets.init.DndsheetsModItems.TOKEN.get()));
		CompoundTag dndTag = new CompoundTag();
		dndTag.putString("monsterSpawn", monsterId);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.literal("Invocar: " + (block != null ? block.name() : monsterId)));
		return stack;
	}

	//--- NPC genérico: un bloque de estadísticas en blanco registrado al vuelo, sin JSON de por medio, para
	//que el DM tenga una base que rellenar en vivo con /dndmonsters attack add (ver MonsterCommand). Cada
	//invocación se registra con un id propio, así que dos NPCs genéricos nunca comparten bloque de
	//estadísticas aunque tengan el mismo nombre.

	private static int genericCounter = 0;

	public static Entity spawnGeneric(ServerLevel level, double x, double y, double z, String name, String baseEntityId, int ac, int hp) {
		String id = "dndsheets:npc_" + (++genericCounter);
		Map<String, Integer> abilities = new LinkedHashMap<>();
		for (String key : Combatant.ABILITIES) abilities.put(key, 10);

		register(new MonsterStatBlock(id, name, baseEntityId, Math.max(0, ac), Math.max(1, hp), abilities, 2, new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new HashMap<>(), CreatureType.UNKNOWN, 0, 0, 1, Appearance.DEFAULT, false));
		return spawnAt(level, x, y, z, id);
	}

	/**
	 * <p>Invoca el mob base de un monstruo cargado en la posición dada, igual que hace
	 * {@code /dndmonsters spawn} (y la carta de invocación de la pestaña creativa): sin IA, con su nombre
	 * visible, y con la etiqueta NBT persistente que lo liga a su bloque de estadísticas.</p>
	 * @return la entidad invocada, o null si el monstruo no existe o su ítem base no es válido.
	 */
	public static Entity spawnAt(ServerLevel level, double x, double y, double z, String monsterId) {
		return spawnAt(level, x, y, z, monsterId, null);
	}

	/**
	 * @param configure se ejecuta sobre la entidad recién creada ANTES de que entre al orden de turnos.
	 *                  Existe porque hay estado que decide cómo entra: una invocación se etiqueta con su
	 *                  dueño, y {@code addLateMonster} lee esa etiqueta para saber si es enemigo o aliado.
	 *                  Etiquetarla después la metía en la iniciativa como enemigo, y entonces el combate no
	 *                  terminaba nunca mientras durase.
	 */
	public static Entity spawnAt(ServerLevel level, double x, double y, double z, String monsterId,
			java.util.function.Consumer<Entity> configure) {
		MonsterStatBlock block = get(monsterId);
		if (block == null) return null;

		ResourceLocation entityLoc = ResourceLocation.tryParse(block.baseEntityId());
		EntityType<?> type = entityLoc != null ? ForgeRegistries.ENTITY_TYPES.getValue(entityLoc) : null;
		if (type == null) {
			//Un id desconocido es casi siempre un addon que apunta a una entidad de OTRO mod que no está
			//instalado. Devolver null dejaba al DM con un comando que no hacía nada y sin explicación. Un
			//zombi con el nombre y las estadísticas correctas es una ficha jugable; nada no lo es.
			DndsheetsMod.LOGGER.warn("dndsheets: el monstruo \"{}\" pide la entidad \"{}\", que no existe (¿falta el mod que la trae?). Uso un zombi.",
				monsterId, block.baseEntityId());
			type = EntityType.ZOMBIE;
		}

		Entity entity = type.create(level);
		if (entity == null) return null;

		entity.moveTo(x, y, z, 0, 0);
		entity.setCustomName(Component.literal(block.name()));
		entity.setCustomNameVisible(true);
		//Congelado salvo que el bloque pida lo contrario con "ai": true — ver keepsOwnAi.
		if (entity instanceof Mob mob) mob.setNoAi(!block.keepsOwnAi());
		applyAppearance(entity, block.appearance());
		tagAsMonster(entity, monsterId, block.maxHp());
		if (configure != null) configure.accept(entity);

		level.addFreshEntity(entity);

		//Sin esto, NoAI + yaw 0 hace que todos los monstruos invocados miren siempre al norte.
		//Se orientan hacia el jugador más cercano para que quede claro a quién amenazan.
		Player nearest = level.getNearestPlayer(entity, 30);
		if (nearest != null) faceTarget(entity, nearest);

		//Si se invoca a mitad de un combate ya en marcha, se suma al orden de turnos ya mismo — si no,
		//quedaría incontrolable y podía hacer que el combate se diera por terminado con él todavía vivo.
		TurnManager.addLateMonster(level, entity, block.name());

		return entity;
	}

	/**
	 * <p>Viste al monstruo con lo que diga su bloque {@code appearance}. Todo lo que toca aquí es vanilla:
	 * equipo visible, tamaño de cría y brillo.</p>
	 *
	 * <p>Las probabilidades de soltar el equipo se ponen a 0. Un monstruo de mesa es una ficha, no una
	 * fuente de botín: si el yelmo que le pusiste para que se distinga de sus tres hermanos cae al suelo al
	 * matarlo, has convertido una decisión visual en una recompensa que el DM no había repartido.</p>
	 */
	private static void applyAppearance(Entity entity, Appearance look) {
		if (look == null || look.isDefault()) return;
		if (look.glowing()) entity.setGlowingTag(true);
		if (look.baby()) {
			//Zombie NO es AgeableMob (los no-muertos no crecen), así que hacen falta las dos ramas.
			if (entity instanceof net.minecraft.world.entity.monster.Zombie zombie) zombie.setBaby(true);
			else if (entity instanceof net.minecraft.world.entity.AgeableMob ageable) ageable.setBaby(true);
		}
		if (!(entity instanceof Mob mob)) return;
		equip(mob, net.minecraft.world.entity.EquipmentSlot.MAINHAND, look.mainHand());
		equip(mob, net.minecraft.world.entity.EquipmentSlot.OFFHAND, look.offHand());
		equip(mob, net.minecraft.world.entity.EquipmentSlot.HEAD, look.helmet());
		equip(mob, net.minecraft.world.entity.EquipmentSlot.CHEST, look.chestplate());
		equip(mob, net.minecraft.world.entity.EquipmentSlot.LEGS, look.leggings());
		equip(mob, net.minecraft.world.entity.EquipmentSlot.FEET, look.boots());
	}

	private static void equip(Mob mob, net.minecraft.world.entity.EquipmentSlot slot, String itemId) {
		if (itemId == null || itemId.isBlank()) return;
		ResourceLocation loc = ResourceLocation.tryParse(itemId);
		net.minecraft.world.item.Item item = loc != null ? ForgeRegistries.ITEMS.getValue(loc) : null;
		if (item == null) {
			//Se avisa y se sigue: un objeto que no existe no debe impedir que el monstruo aparezca.
			DndsheetsMod.LOGGER.warn("dndsheets: \"{}\" no es un objeto conocido; no equipo esa ranura.", itemId);
			return;
		}
		mob.setItemSlot(slot, new net.minecraft.world.item.ItemStack(item));
		mob.setDropChance(slot, 0.0f);
	}

	//Público: CombatManager lo llama cada vez que un jugador golpea a un monstruo, para que gire a verlo
	//en vez de quedarse mirando a quien tenía más cerca al invocarse (o al suelo, si se usa la posición
	//de los pies del objetivo en vez de sus ojos).
	public static void faceTarget(Entity monster, Entity target) {
		monster.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
	}
}
