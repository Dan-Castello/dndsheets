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
		boolean concentration, int aoeRadius, String aoeShape, String summonEntityId, boolean followsCasterFlag,
		String effectName, String effectDice, int effectTurns, String upcastDice,
		java.util.Set<CreatureType> affectsTypes, java.util.Set<CreatureType> immuneTypes
	) {
		/**
		 * <p>Forma del área: {@code sphere} (por defecto), {@code line} o {@code cone}. La diferencia no es
		 * cosmética — una esfera nace en el punto de impacto, mientras que una línea y un cono nacen en el
		 * LANZADOR y salen hacia donde mira. Tratar un cono como radio golpearía a todo lo que tiene detrás,
		 * que es exactamente por qué Rayo y Cono de Frío no se pudieron importar hasta ahora.</p>
		 */
		public boolean originatesAtCaster() { return "line".equals(aoeShape) || "cone".equals(aoeShape) || isZone(); }

		/**
		 * <p>Zona persistente: no se resuelve al lanzarla, se coloca y daña a quien empiece su turno dentro
		 * durante varios asaltos (ver {@link ZoneManager}). Lo que la define es la PERSISTENCIA, no la
		 * forma: un Muro de Fuego y un Rayo de Luna son la misma capacidad con geometría distinta.</p>
		 *
		 * <p>{@code aoeShape:"wall"} sigue implicando zona por compatibilidad: los muros existían antes de
		 * que la persistencia fuera un campo propio, y un pack que ya los tuviera escritos debe seguir
		 * funcionando sin tocarlo.</p>
		 */
		public boolean isZone() { return "zone".equals(mode) || "wall".equals(aoeShape); }

		/** La zona se recentra en el lanzador cada asalto (Guardianes Espirituales). */
		public boolean followsCaster() { return followsCasterFlag; }

		/**
		 * <p>Modos que actúan sobre el propio lanzador y no necesitan a nadie delante: {@code buff} (dados
		 * extra a cada golpe con arma mientras dure, ver {@link WeaponBuffManager}) y {@code temphp} (puntos
		 * de golpe temporales, ver {@link Combatant#grantTemporaryHp}).</p>
		 */
		public boolean isSelfTargeted() { return "buff".equals(mode) || "temphp".equals(mode); }

		/** Invocación que entra en la iniciativa y ataca sola en sus turnos — ver {@link SummonManager}. */
		public boolean isSummon() { return "summon".equals(mode); }
		//Mismo patrón que MonsterRegistry.MonsterAttack/MonsterSpell: un hechizo de concentración
		//(Guardianes Espirituales, Rayo de Luna...) puede dejar un efecto de estado corriendo mientras dura
		//la concentración (ver ConcentrationManager/TurnManager.applyEffect), que se revierte solo si se
		//pierde la concentración — antes eso no existía, "perder concentración" solo tiraba el dado.
		public boolean appliesEffect() { return effectName != null; }

		/**
		 * <p>¿Le hace algo este conjuro a una criatura de este tipo? Inmovilizar Persona solo afecta a
		 * humanoides; Marchitar no le hace nada a no-muertos ni autómatas. Hasta ahora los dos afectaban a
		 * cualquier cosa, que es la diferencia entre un conjuro y su nombre.</p>
		 *
		 * <p><b>Un tipo desconocido nunca se filtra.</b> La restricción solo se aplica cuando de verdad se
		 * sabe qué hay delante: un mob de otro mod sin bloque de estadísticas se sigue comportando como
		 * siempre, en vez de volverse inmune a media lista de conjuros por no estar clasificado. Es la misma
		 * regla que en {@link CreatureType}: nada se dispara —ni se bloquea— por adivinar.</p>
		 */
		public boolean affects(CreatureType type) {
			if (type == CreatureType.UNKNOWN) return true;
			if (!affectsTypes.isEmpty() && !affectsTypes.contains(type)) return false;
			return !immuneTypes.contains(type);
		}

		/** ¿Gana algo por lanzarlo con un espacio superior? Ver {@link #upcastTo}. */
		public boolean scalesWithSlot() { return upcastDice != null && !upcastDice.isEmpty(); }

		/**
		 * <p><b>Lanzar a nivel superior.</b> Devuelve el mismo conjuro resuelto con un espacio de nivel
		 * {@code slotLevel}: {@code upcastDice} extra por cada nivel por encima del suyo (Bola de Fuego,
		 * 8d6 de base y +1d6 por nivel, sale a 10d6 con un espacio de 5º).</p>
		 *
		 * <p>Devuelve una COPIA en vez de un dado suelto a propósito: {@code dice} se lee desde ocho sitios
		 * distintos (ataque, salvación, curación, PG temporales, mejora de arma, zona, invocación y el
		 * gemelado), y pasarles a todos un parámetro nuevo habría sido ocho firmas cambiadas para la misma
		 * idea. Así la subida de nivel vale para todos los modos de golpe, incluidos los que aún no existen.</p>
		 *
		 * <p>El nombre lleva el nivel usado porque va derecho al chat: sin eso, dos Bolas de Fuego con daños
		 * distintos se leen como un fallo del mod y no como la decisión que fue.</p>
		 */
		/**
		 * <p><b>Trucos que crecen con quien los lanza.</b> Un truco de daño suma un dado a los niveles de
		 * personaje 5, 11 y 17: el Rayo de Fuego de un mago de nivel 10 hace 2d10, no 1d10.</p>
		 *
		 * <p>Es la contraparte de {@link #upcastTo} para lo único que no puede subirse de nivel gastando un
		 * espacio. Sin ella, el ataque a voluntad de un lanzador se quedaba clavado en el daño de nivel 1
		 * mientras todo lo demás escalaba — el mismo defecto que tenía el bono de competencia, en el ataque
		 * que un lanzador usa más veces por partida.</p>
		 *
		 * <p>No hace falta declarar nada en el JSON: la progresión es la misma para todos los trucos de daño
		 * del SRD, así que se deduce del nivel en vez de repetirse once veces a mano.</p>
		 */
		public Spell atCasterLevel(int characterLevel) {
			if (level != 0 || dice == null || "0".equals(dice.trim())) return this;

			int dice5eCount = 1 + (characterLevel >= 5 ? 1 : 0) + (characterLevel >= 11 ? 1 : 0) + (characterLevel >= 17 ? 1 : 0);
			if (dice5eCount == 1) return this;

			return new Spell(id, name, level, mode, castingAbility, saveAbility, repeatDice(dice, dice5eCount),
				halfOnSave, damageType, concentration, aoeRadius, aoeShape, summonEntityId, followsCasterFlag,
				effectName, effectDice, effectTurns, upcastDice, affectsTypes, immuneTypes);
		}

		public Spell upcastTo(int slotLevel) {
			int extraLevels = slotLevel - level;
			if (extraLevels <= 0 || !scalesWithSlot()) return this;

			String added = repeatDice(upcastDice, extraLevels);
			//"0" es el dado por defecto de un conjuro que no hace daño ninguno; sumarle nada delante deja
			//un "0 + 2d6" que se tira igual pero se lee en el chat como un error de escritura.
			String scaled = "0".equals(dice.trim()) ? added : dice + " + " + added;
			return new Spell(id, name + " (nv. " + slotLevel + ")", level, mode, castingAbility, saveAbility,
				scaled, halfOnSave, damageType, concentration, aoeRadius, aoeShape, summonEntityId,
				followsCasterFlag, effectName, effectDice, effectTurns, upcastDice, affectsTypes, immuneTypes);
		}
	}

	//Un "1d6" repetido 3 veces se junta en "3d6" en vez de encadenarse: se tira igual, pero el chat enseña
	//una tirada y no tres sumandos del mismo dado. Lo que no encaje en NdM (Proyectil Mágico sube "1d4 + 1"
	//por nivel, dardo a dardo) se repite tal cual, que sigue siendo correcto aunque se lea más largo.
	private static final java.util.regex.Pattern SIMPLE_DICE = java.util.regex.Pattern.compile("(\\d*)d(\\d+)");

	static String repeatDice(String dice, int times) {
		java.util.regex.Matcher m = SIMPLE_DICE.matcher(dice.trim());
		if (m.matches()) {
			int count = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
			return (count * times) + "d" + m.group(2);
		}
		StringBuilder sb = new StringBuilder(dice);
		for (int i = 1; i < times; i++) sb.append(" + ").append(dice);
		return sb.toString();
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
	//de la carpeta al arrancar el servidor, sin que DndPaths tenga que depender de la capa de comandos.
	private static final JsonRegistryLoader<Spell> LOADER = new JsonRegistryLoader<>("hechizo", SpellRegistry::parse, SpellRegistry::register);

	/** Carga desde un JSON ya leído (datapack o jar de otro mod) — ver ContentDatapackLoader. */
	public static int loadJson(com.google.gson.JsonElement root, String source, java.util.function.Consumer<String> onId) {
		return LOADER.loadJson(root, source, onId);
	}

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
		String damageType = json.has("damageType") ? DamageTypes.normalize(json.get("damageType").getAsString()) : "fisico";
		boolean concentration = json.has("concentration") && json.get("concentration").getAsBoolean();
		//Techo defensivo: sin esto, un radio absurdo en el JSON (a propósito o por error de tipeo) hace que
		//SpellCastManager.findAoeTargets escanee todas las entidades cargadas del servidor en cada lanzado,
		//sin límite superior — un vector de lag real, no solo un valor raro.
		int aoeRadius = json.has("aoeRadius") ? Math.max(0, Math.min(json.get("aoeRadius").getAsInt(), 40)) : 0;
		//Esfera por defecto: cualquier hechizo escrito antes de que existieran las formas se comporta igual
		//que siempre. Un valor desconocido cae también a esfera en vez de descartar el hechizo entero.
		String aoeShape = json.has("aoeShape") ? json.get("aoeShape").getAsString().toLowerCase(Locale.ROOT) : "sphere";
		if (!aoeShape.equals("line") && !aoeShape.equals("cone") && !aoeShape.equals("wall")) aoeShape = "sphere";
		//Cuerpo vanilla de una invocación. Vex por defecto: flota, es pequeño y no se parece a ningún mob
		//hostil concreto, que es lo más cerca de "un arma espiritual" que hay sin modelo propio.
		String summonEntityId = json.has("summonEntity") ? json.get("summonEntity").getAsString() : "minecraft:vex";
		boolean followsCaster = json.has("followsCaster") && json.get("followsCaster").getAsBoolean();

		//Mismo formato anidado que MonsterRegistry.parse/parseAttack usan para sus propios monstruos:
		//"appliesEffect": {"name": "...", "dice": "...", "turns": N}.
		JsonObject effect = json.has("appliesEffect") ? json.getAsJsonObject("appliesEffect") : null;
		String effectName = effect != null ? effect.get("name").getAsString() : null;
		String effectDice = effect != null ? effect.get("dice").getAsString() : null;
		int effectTurns = effect != null && effect.has("turns") ? effect.get("turns").getAsInt() : 0;

		//Lo que se suma por cada nivel de espacio por encima del suyo (ver Spell.upcastTo). Ausente = el
		//conjuro no mejora al subirlo de nivel, que en el SRD es la mitad de ellos: Palabra de Poder o
		//Enjambre de Meteoros no ganan nada por gastar un espacio más alto, y fingir que sí los rompería.
		String upcastDice = json.has("upcastDice") ? json.get("upcastDice").getAsString() : null;

		//A quién puede afectar. Vacíos = a todo el mundo, que es como se comportaban todos los conjuros
		//hasta ahora. Son dos campos y no uno porque las dos formas existen en el SRD y cada una escrita
		//con la otra queda ilegible: Inmovilizar Persona es "solo humanoides" (uno), e Inmovilizar Monstruo
		//es "todo menos no-muertos" (uno también, pero al revés — como lista blanca serían trece).
		java.util.Set<CreatureType> affectsTypes = CreatureType.parseAll(json.has("affectsTypes") ? json.getAsJsonArray("affectsTypes") : null);
		java.util.Set<CreatureType> immuneTypes = CreatureType.parseAll(json.has("immuneTypes") ? json.getAsJsonArray("immuneTypes") : null);

		return new Spell(id, name, level, mode, castingAbility, saveAbility, dice, halfOnSave, damageType, concentration, aoeRadius, aoeShape, summonEntityId, followsCaster,
			effectName, effectDice, effectTurns, upcastDice, affectsTypes, immuneTypes);
	}

	//--- Báculo de lanzado rápido: cualquier ítem etiquetado {dndsheets:{quickSpell:"id"}} (mismo patrón que las armas personalizadas) ---

	public static String quickSpellIdOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets")) return null;
		CompoundTag dndTag = tag.getCompound("dndsheets");
		return dndTag.contains("quickSpell") ? dndTag.getString("quickSpell") : null;
	}
}
