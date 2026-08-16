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
		CreatureType type
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
	public static CreatureType typeOf(Entity entity) {
		MonsterStatBlock block = statBlockOf(entity);
		return block != null ? block.type() : CreatureType.UNKNOWN;
	}

	private static final NamedRegistry<MonsterStatBlock> REGISTRY = new NamedRegistry<>("monstruo", MonsterStatBlock::id);

	public static void register(MonsterStatBlock block) {
		REGISTRY.register(block);
	}

	public static MonsterStatBlock get(String id) {
		return REGISTRY.get(id);
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	public static boolean remove(String id) {
		return REGISTRY.remove(id);
	}

	//Público: usado por MonsterCommand (/dndmonsters load) y por DndPaths para precargar solo todos los
	//.json de la carpeta al arrancar el servidor, sin que DndPaths tenga que depender de la capa de
	//comandos — ver AUDIT_TECHNICAL.md M-ARQ-1. Antes, un monstruo malformado a mitad del archivo abortaba
	//el resto (visible solo como un WARN de carga, invisible para el DM en el chat) — JsonRegistryLoader
	//ya salta por elemento, no por archivo.
	private static final JsonRegistryLoader<MonsterStatBlock> LOADER = new JsonRegistryLoader<>("monstruo", MonsterRegistry::parse, MonsterRegistry::register);

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	public static MonsterStatBlock parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.has("name") ? json.get("name").getAsString() : id;
		String baseEntity = json.has("baseEntity") ? json.get("baseEntity").getAsString() : "minecraft:zombie";
		int ac = json.has("ac") ? json.get("ac").getAsInt() : 10;
		int hp = json.has("hp") ? json.get("hp").getAsInt() : 1;
		int prof = json.has("proficiencyBonus") ? json.get("proficiencyBonus").getAsInt() : 2;

		Map<String, Integer> abilities = new LinkedHashMap<>();
		JsonObject abilitiesJson = json.has("abilities") ? json.getAsJsonObject("abilities") : null;
		for (String key : new String[]{"str", "dex", "con", "int", "wis", "cha"}) {
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
					s.has("damageType") ? s.get("damageType").getAsString().toLowerCase(Locale.ROOT) : "fisico",
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

		return new MonsterStatBlock(id, name, baseEntity, ac, hp, abilities, prof, attacks, spells, damageAffinities, nonmagicalAffinities, type);
	}

	private static Map<String, String> readAffinities(JsonObject json, String field) {
		Map<String, String> result = new HashMap<>();
		if (!json.has(field)) return result;
		JsonObject affinities = json.getAsJsonObject(field);
		for (String type : affinities.keySet()) {
			result.put(type.toLowerCase(Locale.ROOT), affinities.get(type).getAsString().toLowerCase(Locale.ROOT));
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
			a.has("damageType") ? a.get("damageType").getAsString().toLowerCase(Locale.ROOT) : "fisico",
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
		json.addProperty("baseEntity", block.baseEntityId());
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
	private static final Map<Integer, List<MonsterAttack>> customAttacksCache = new HashMap<>();

	public static List<MonsterAttack> customAttacksOf(Entity entity) {
		List<MonsterAttack> cached = customAttacksCache.get(entity.getId());
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
		customAttacksCache.put(entity.getId(), result);
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

	public static void clearCustomAttacks(Entity entity) {
		CompoundTag data = entity.getPersistentData();
		if (data.contains("dndsheets")) data.getCompound("dndsheets").remove("customAttacks");
		customAttacksCache.remove(entity.getId());
	}

	private static void saveCustomAttacks(Entity entity, List<MonsterAttack> attacks) {
		JsonArray array = new JsonArray();
		for (MonsterAttack attack : attacks) array.add(attackToJson(attack));

		CompoundTag data = entity.getPersistentData();
		CompoundTag tag = data.getCompound("dndsheets"); //Vacío si no existía; solo pasa si el objetivo no era un monstruo tageado.
		tag.putString("customAttacks", array.toString());
		data.put("dndsheets", tag);

		customAttacksCache.put(entity.getId(), List.copyOf(attacks)); //Refresca el caché con lo que ya tenemos en memoria, en vez de invalidar y reparsear el JSON que se acaba de escribir.
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
		ItemStack stack = new ItemStack(net.minecraft.world.item.Items.ZOMBIE_SPAWN_EGG);
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
		for (String key : new String[]{"str", "dex", "con", "int", "wis", "cha"}) abilities.put(key, 10);

		register(new MonsterStatBlock(id, name, baseEntityId, Math.max(0, ac), Math.max(1, hp), abilities, 2, new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new HashMap<>(), CreatureType.UNKNOWN));
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
		if (type == null) return null;

		Entity entity = type.create(level);
		if (entity == null) return null;

		entity.moveTo(x, y, z, 0, 0);
		entity.setCustomName(Component.literal(block.name()));
		entity.setCustomNameVisible(true);
		if (entity instanceof Mob mob) mob.setNoAi(true);
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

	//Público: CombatManager lo llama cada vez que un jugador golpea a un monstruo, para que gire a verlo
	//en vez de quedarse mirando a quien tenía más cerca al invocarse (o al suelo, si se usa la posición
	//de los pies del objetivo en vez de sus ojos).
	public static void faceTarget(Entity monster, Entity target) {
		monster.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
	}
}
