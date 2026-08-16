package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <p>Un participante en las reglas de 5e, sea un jugador (con hoja) o un monstruo (con bloque de
 * estadísticas). Antes no existía tal cosa: el estado vivía en dos sitios incompatibles — {@code JsonObject}
 * + atributo de salud de Minecraft para el jugador, {@link MonsterRegistry.MonsterStatBlock} + NBT de la
 * entidad para el monstruo — y cada regla que necesitaba "la CA del objetivo" o "quítale N puntos de
 * golpe" se escribía dos veces, con un {@code boolean isMonster} decidiendo cuál.</p>
 *
 * <p>El coste de ese corte era medible y no era intencionado: el monstruo no tenía resistencias
 * ({@link DamageTypes#multiplierFor} exigía una hoja), ni reacción de Escudo
 * ({@link ShieldManager#effectiveAc} exigía un {@code ServerPlayer}), ni concentración
 * ({@link ConcentrationManager#onDamageTaken} hacía un cast duro). Ninguna de las tres ausencias era una
 * decisión de diseño. Al pasar por esta interfaz, cada una se arregla en un solo sitio.</p>
 *
 * <p>Las implementaciones NO guardan estado propio: leen y escriben donde ese estado ya vivía (la hoja
 * JSON del jugador, el NBT del monstruo), así que se pueden crear y tirar en cada llamada sin cachear
 * nada, y las condiciones sobreviven a reinicios y reconexiones por el mismo camino que ya usaban los PG.</p>
 */
public interface Combatant {

	Entity entity();

	String name();

	/** CA base, sin contar reacciones. */
	int armorClass();

	int currentHp();

	int maxHp();

	/** Modificador de característica por clave corta: {@code str}, {@code dex}, {@code con}, {@code int}, {@code wis}, {@code cha}. */
	int abilityModifier(String ability);

	int proficiencyBonus();

	/**
	 * <p>Multiplicador de daño por resistencias/vulnerabilidades/inmunidades. Petrificado (resistencia a
	 * todo) lo aplica {@link #effectiveDamageMultiplier}, común a los dos lados.</p>
	 *
	 * @param magical si el golpe cuenta como mágico. Media docena de resistencias del SRD dependen de ello
	 *                ("contundente, perforante y cortante de ataques no mágicos"), y sin el dato el
	 *                bestiario entero resultaba más blando de lo que dice su bloque de estadísticas.
	 */
	double damageMultiplier(String damageType, boolean magical);

	/**
	 * <p>Aplica daño a los puntos de golpe reales, ya multiplicado y ya descontados los temporales. No se
	 * llama directamente desde las reglas: el punto de entrada es {@link #takeDamage}, que es quien aplica
	 * la absorción de PG temporales antes de llegar aquí.</p>
	 */
	void applyRealDamage(int amount);

	/**
	 * <p>Puntos de golpe temporales: una reserva que absorbe daño ANTES que los reales y que no se cura ni
	 * se apila — un montón nuevo reemplaza al viejo, se queda el mayor. En 5e los dan Falsa Vida, Heroísmo,
	 * Palabra de Ánimo y varios rasgos de clase.</p>
	 */
	int temporaryHp();

	void setTemporaryHp(int amount);

	/**
	 * <p>Punto de entrada del daño para TODAS las reglas. Los PG temporales se descuentan primero y solo
	 * el resto llega a los PG reales. Vive aquí y no en cada implementación porque la regla es idéntica
	 * para jugador, PNJ y monstruo — repetirla tres veces es exactamente lo que {@link Combatant} vino a
	 * evitar.</p>
	 */
	default void takeDamage(int amount) {
		applyRealDamage(absorbWithTemporaryHp(amount));
	}

	/**
	 * <p>Gasta los PG temporales contra ese daño y devuelve lo que queda por aplicar. Público porque hay
	 * un camino que NO puede usar {@link #takeDamage}: el PvP con arma vive dentro del
	 * {@code LivingHurtEvent} de Minecraft y entrega el daño con {@code setAmount}, así que necesita
	 * descontar la reserva y quedarse con el resto en vez de aplicarlo él. Sin esto, los PG temporales
	 * absorbían conjuros y golpes de monstruo pero no un espadazo de otro jugador.</p>
	 */
	default int absorbWithTemporaryHp(int amount) {
		if (amount <= 0) return 0;
		int temporary = temporaryHp();
		if (temporary <= 0) return amount;
		int absorbed = Math.min(temporary, amount);
		setTemporaryHp(temporary - absorbed);
		return amount - absorbed;
	}

	/**
	 * <p>Concede PG temporales. No se suman a los que ya haya: en 5e se elige uno de los dos montones, y
	 * quedarse con el mayor es la lectura estándar y la que no castiga por relanzar el conjuro.</p>
	 */
	default void grantTemporaryHp(int amount) {
		if (amount > temporaryHp()) setTemporaryHp(amount);
	}

	boolean isDefeated();

	/**
	 * <p>Condiciones activas y, para cada una, el id de entidad que la causó, o {@link #NO_SOURCE} si no se
	 * sabe. La fuente hace falta para las dos condiciones de 5e cuyo efecto depende de <em>quién</em> las
	 * provocó: hechizado (no puedes atacar a quien te hechizó) y asustado (desventaja solo mientras veas la
	 * fuente). Para las otras doce sobra, y por eso se admite que no la haya.</p>
	 */
	Map<Condition, Integer> conditionSources();

	/** Único punto de escritura de condiciones: cada implementación persiste donde ya guarda lo demás. */
	void setConditionSources(Map<Condition, Integer> sources);

	/** Fuente desconocida: la condición se aplicó sin decir quién la causó (p.ej. a mano por el DM). */
	int NO_SOURCE = -1;

	default Set<Condition> conditions() {
		Set<Condition> result = EnumSet.noneOf(Condition.class);
		result.addAll(conditionSources().keySet());
		return result;
	}

	default int sourceOf(Condition condition) {
		Integer source = conditionSources().get(condition);
		return source == null ? NO_SOURCE : source;
	}

	/**
	 * CA efectiva tras reacciones defensivas (Escudo). Por defecto, la CA base: un combatiente que aún no
	 * sepa reaccionar simplemente no cambia nada, y el día que un monstruo tenga reacciones se sobreescribe
	 * aquí sin tocar ninguna ruta de combate.
	 */
	default int reactiveArmorClass(int attackRollValue) {
		return armorClass();
	}

	default boolean hasCondition(Condition condition) {
		return conditions().contains(condition);
	}

	default void addCondition(Condition condition) {
		addCondition(condition, NO_SOURCE);
	}

	default void addCondition(Condition condition, int sourceEntityId) {
		Map<Condition, Integer> updated = new EnumMap<>(Condition.class);
		updated.putAll(conditionSources()); //putAll y no el constructor de copia: EnumMap(Map) lanza si el mapa viene vacío y no es un EnumMap.
		//Se reescribe aunque ya estuviera: volver a aplicar la misma condición desde otra fuente debe
		//actualizarla (te asusta el dragón, no ya el goblin del turno pasado).
		Integer previous = updated.put(condition, sourceEntityId);
		if (previous == null || previous != sourceEntityId) setConditionSources(updated);
	}

	default void removeCondition(Condition condition) {
		Map<Condition, Integer> updated = new EnumMap<>(Condition.class);
		updated.putAll(conditionSources()); //putAll y no el constructor de copia: EnumMap(Map) lanza si el mapa viene vacío y no es un EnumMap.
		if (updated.remove(condition) != null) setConditionSources(updated);
	}

	/**
	 * <p>Si ve la fuente de esa condición. Sin fuente registrada devuelve {@code true}: la aproximación
	 * conservadora, aplicar el efecto igual, que es lo que hacía el mod antes de rastrear fuentes.</p>
	 */
	default boolean seesSourceOf(Condition condition) {
		int sourceId = sourceOf(condition);
		if (sourceId == NO_SOURCE) return true;
		Entity source = entity().level().getEntity(sourceId);
		if (source == null) return false; //La fuente ya no está en el mundo: dejó de darte miedo.
		return !(entity() instanceof LivingEntity living) || living.hasLineOfSight(source);
	}

	/**
	 * <p>Hechizado: no puedes atacar a quien te hechizó. El resto de objetivos siguen siendo válidos, así
	 * que esto depende del objetivo concreto y no se puede resolver mirando solo las condiciones.</p>
	 */
	default boolean cannotAttack(Entity target) {
		if (target == null || !hasCondition(Condition.HECHIZADO)) return false;
		return sourceOf(Condition.HECHIZADO) == target.getId();
	}

	/** No puede actuar (incapacitado, paralizado, petrificado, aturdido, inconsciente). */
	default boolean cannotAct() {
		return conditions().stream().anyMatch(Condition::preventsActions);
	}

	/** Velocidad 0 (agarrado, apresado, paralizado, petrificado, inconsciente). */
	default boolean cannotMove() {
		return conditions().stream().anyMatch(Condition::preventsMovement);
	}

	/** Ventaja/desventaja de las tiradas de ataque que hace ESTE combatiente, por sus propias condiciones. */
	default DiceManager.Advantage ownAttackAdvantage() {
		//Asustado es la única cuya desventaja depende de ver la fuente; el resto aplican siempre.
		boolean disadvantage = conditions().stream()
			.filter(condition -> condition != Condition.ASUSTADO || seesSourceOf(Condition.ASUSTADO))
			.anyMatch(Condition::selfAttackDisadvantage);
		return DiceManager.combineAdvantage(
			conditions().stream().anyMatch(Condition::selfAttackAdvantage) ? DiceManager.Advantage.ADVANTAGE : DiceManager.Advantage.NORMAL,
			disadvantage ? DiceManager.Advantage.DISADVANTAGE : DiceManager.Advantage.NORMAL);
	}

	/**
	 * Ventaja/desventaja de quien ataca a ESTE combatiente. {@code melee} decide el caso de derribado, la
	 * única condición cuyo efecto cambia con la distancia (ventaja a 5 pies, desventaja a distancia).
	 */
	default DiceManager.Advantage advantageAgainst(boolean melee) {
		boolean advantage = conditions().stream().anyMatch(Condition::attackersAdvantage);
		boolean disadvantage = conditions().stream().anyMatch(Condition::attackersDisadvantage);
		if (hasCondition(Condition.DERRIBADO)) {
			if (melee) advantage = true;
			else disadvantage = true;
		}
		return DiceManager.combineAdvantage(
			advantage ? DiceManager.Advantage.ADVANTAGE : DiceManager.Advantage.NORMAL,
			disadvantage ? DiceManager.Advantage.DISADVANTAGE : DiceManager.Advantage.NORMAL);
	}

	/** Un impacto cuerpo a cuerpo contra él es crítico automático (paralizado, inconsciente). */
	default boolean autoCritInMelee() {
		return conditions().stream().anyMatch(Condition::autoCritInMelee);
	}

	/** Falla automáticamente salvaciones de Fuerza y Destreza. */
	default boolean autoFailsStrDexSaves() {
		return conditions().stream().anyMatch(Condition::autoFailsStrDexSaves);
	}

	/**
	 * <p>Resultado de una salvación. Cuando una condición la hace fallar sin tirar —paralizado,
	 * petrificado, aturdido e inconsciente fallan automáticamente las de Fuerza y Destreza en 5e—
	 * {@code blockedBy} dice cuál y {@code outcome} es {@code null}: no se tira nada, así que no hay
	 * número que enseñar.</p>
	 */
	record SaveRoll(DiceManager.RollOutcome outcome, Condition blockedBy) {

		public boolean succeeds(int dc) {
			return blockedBy == null && outcome != null && outcome.result() != null && outcome.result().getValue() >= dc;
		}

		/** Texto para el chat, o {@code null} si la expresión ni siquiera se pudo tirar. */
		public String formatted() {
			if (blockedBy != null) return "auto (" + blockedBy.label() + ")";
			return outcome == null || outcome.result() == null ? null : outcome.formatted();
		}
	}

	/**
	 * Salvación de característica, por clave corta o larga ({@code dex} o {@code dexterity}). Antes esto
	 * se resolvía con un {@code if (target instanceof Player)} en cada sitio que necesitaba una salvación;
	 * al pasar por aquí, la regla de fallo automático se aplica a jugadores y monstruos por igual y en un
	 * solo lugar.
	 */
	default SaveRoll rollSave(String ability) {
		String key = ability == null ? "" : ability.toLowerCase(Locale.ROOT);
		if ((key.startsWith("str") || key.startsWith("dex")) && autoFailsStrDexSaves()) {
			Condition blocking = conditions().stream().filter(Condition::autoFailsStrDexSaves).findFirst().orElse(null);
			return new SaveRoll(null, blocking);
		}
		//Expresión con el modificador ya resuelto en vez de "$dex": el objetivo puede ser un monstruo, que
		//no tiene hoja de la que DiceManager pueda sacar la característica.
		return new SaveRoll(DiceManager.roll(new JsonObject(), "1d20 + " + abilityModifier(key)), null);
	}

	/** Resistencias propias más la resistencia a todo el daño de petrificado, que aplica a ambos lados. */
	default double effectiveDamageMultiplier(String damageType, boolean magical) {
		double multiplier = damageMultiplier(damageType, magical);
		if (conditions().stream().anyMatch(Condition::resistsAllDamage)) multiplier = Math.min(multiplier, 0.5);
		return multiplier;
	}

	/**
	 * {@code null} si la entidad no participa en las reglas de 5e (un mob de otro mod sin bloque de
	 * estadísticas, un armor stand de pruebas, un jugador sin hoja cargada): quien llame debe caer al
	 * comportamiento normal de Minecraft, exactamente como hacía antes.
	 */
	static Combatant of(Entity entity) {
		if (entity instanceof Player player) {
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			return sheet == null ? null : new PlayerCombatant(player, sheet);
		}
		//El PNJ se comprueba ANTES que el bloque de monstruo: una entidad con ficha de personaje juega con
		//las reglas completas de un PJ, y esas mandan sobre cualquier estadística de monstruo que arrastre.
		String characterId = characterIdOf(entity);
		if (characterId != null) {
			JsonObject sheet = SheetLoader.getCharacterSheet(characterId);
			if (sheet != null) return new NpcCombatant(entity, sheet, characterId);
			//Ficha borrada con su cuerpo todavía en el mundo: cae a monstruo/vanilla en vez de reventar.
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(entity);
		return block == null ? null : new MonsterCombatant(entity, block);
	}

	/**
	 * <p>Liga una entidad del mundo a una ficha de personaje. Mismo compartimento NBT persistente que ya
	 * usa {@code MonsterRegistry.tagAsMonster} —Minecraft lo guarda y lo carga solo— para no inventar un
	 * segundo mecanismo de etiquetado que se comporte distinto al recargar el chunk.</p>
	 */
	static void tagAsCharacter(Entity entity, String characterId) {
		CompoundTag data = entity.getPersistentData();
		CompoundTag tag = data.getCompound("dndsheets"); //Vacío si no existía, igual que MonsterRegistry.
		tag.putString("character", characterId);
		data.put("dndsheets", tag);
	}

	/** Id de personaje ligado a esa entidad, o {@code null} si no lleva ficha. */
	static String characterIdOf(Entity entity) {
		CompoundTag data = entity.getPersistentData();
		if (!data.contains("dndsheets")) return null;
		CompoundTag tag = data.getCompound("dndsheets");
		String characterId = tag.contains("character") ? tag.getString("character") : null;
		return characterId == null || characterId.isEmpty() ? null : characterId;
	}

	//--- Respaldado por una hoja -----------------------------------------------------------------------

	/**
	 * <p>Lo que comparten un jugador y un PNJ: los dos llevan una hoja de personaje, así que las
	 * características, la competencia, las afinidades de daño y las condiciones se leen igual en ambos.
	 * Existe para no reintroducir por la puerta de atrás el mismo corte que {@link Combatant} vino a
	 * borrar — un PNJ con hoja no es "un monstruo raro", es un personaje sin nadie sentado detrás.</p>
	 *
	 * <p>Lo que NO comparten queda fuera y lo pone cada implementación: de dónde salen los PG (el atributo
	 * de salud de Minecraft para el jugador, la propia hoja para el PNJ), si la armadura y el escudo reales
	 * equipados cuentan para la CA, y si sabe reaccionar.</p>
	 */
	interface SheetBacked extends Combatant {

		JsonObject sheet();

		/** Id con el que se persiste la hoja: UUID del jugador, o id del personaje para un PNJ. */
		String saveId();

		//La hoja usa nombres largos ("dexterity"); las expresiones de tirada y los bloques de monstruo usan
		//los cortos ("dex"). La interfaz habla en cortos, así que la traducción vive aquí una sola vez.
		Map<String, String> LONG_ABILITY_KEYS = Map.of(
			"str", "strength", "dex", "dexterity", "con", "constitution",
			"int", "intelligence", "wis", "wisdom", "cha", "charisma");

		@Override default int abilityModifier(String ability) {
			String key = LONG_ABILITY_KEYS.getOrDefault(ability.toLowerCase(Locale.ROOT), ability);
			return CombatManager.abilityModifier(sheet(), key);
		}

		@Override default int proficiencyBonus() {
			if (!sheet().has("proficiencyBonus")) return 2;
			try {
				return Integer.parseInt(sheet().get("proficiencyBonus").getAsString());
			} catch (RuntimeException e) {
				return 2; //Mismo criterio que CombatManager.abilityModifier: una hoja vieja corrupta no debe tumbar el combate.
			}
		}

		//Una hoja de personaje solo tiene afinidades incondicionales ("damageAffinities"), asi que ignora
		//"magical" a proposito: el dia que un objeto magico conceda una condicional, se lee aqui y ya.
		@Override default double damageMultiplier(String damageType, boolean magical) {
			double multiplier = DamageTypes.multiplierFor(entity(), sheet(), damageType);
			//Resistencia temporal de una poción: se combina quedándose con la más protectora, igual que las
			//de objeto — beber dos pociones del mismo tipo no da inmunidad.
			String temporary = ConsumableManager.activeAffinity(sheet(), damageType == null ? null : damageType.toLowerCase(Locale.ROOT));
			if (temporary != null) multiplier = Math.min(multiplier, DamageTypes.multiplierForLabel(temporary));
			//Las resistencias de objetos mágicos se combinan con las de la hoja quedándose con la MÁS
			//protectora, no sumándose: en 5e dos fuentes de resistencia al fuego siguen siendo resistencia
			//al fuego, no inmunidad.
			if (entity() instanceof Player wearer && damageType != null) {
				for (MagicItemRegistry.MagicItem item : MagicItemRegistry.activeFor(wearer, sheet())) {
					String declared = item.affinities().get(damageType.toLowerCase(Locale.ROOT));
					if (declared != null) multiplier = Math.min(multiplier, DamageTypes.multiplierForLabel(declared));
				}
			}
			return multiplier;
		}

		//En la hoja, igual que las condiciones: es lo que persiste y lo que el jugador ve al abrirla.
		@Override default int temporaryHp() {
			return sheet().has("temporaryHp") ? sheet().get("temporaryHp").getAsInt() : 0;
		}

		@Override default void setTemporaryHp(int amount) {
			sheet().addProperty("temporaryHp", Math.max(0, amount));
			SheetLoader.saveServer(sheet(), saveId());
		}

		@Override default Map<Condition, Integer> conditionSources() {
			Map<Condition, Integer> result = new EnumMap<>(Condition.class);
			if (!sheet().has("conditions")) return result;
			for (var element : sheet().getAsJsonArray("conditions")) {
				parseEntry(element.getAsString(), result);
			}
			return result;
		}

		@Override default void setConditionSources(Map<Condition, Integer> sources) {
			JsonArray array = new JsonArray();
			for (Map.Entry<Condition, Integer> entry : sources.entrySet()) array.add(formatEntry(entry));
			sheet().add("conditions", array);
			//A disco de inmediato, sin confiar en el autoguardado de 5 minutos: mismo fallo que ya costó
			//perder cambios de oro/espacios hechos por el DM (ver PROJECT_CONTEXT.md, bug #5).
			SheetLoader.saveServer(sheet(), saveId());

			//Y al cliente. Este es el ÚNICO punto de escritura de condiciones, así que es el único sitio
			//donde hace falta: sin él, la copia del jugador se quedaba con las de hace un rato y el HUD no
			//podía enseñar nada fiable. Un parche corto, no la hoja entera — llega a mitad de combate.
			if (entity() instanceof net.minecraft.server.level.ServerPlayer player) {
				JsonObject patch = new JsonObject();
				patch.add("conditions", array);
				DndsheetsMod.sendSheetFieldUpdate(player, patch);
			}
		}
	}

	//--- Jugador ---------------------------------------------------------------------------------------

	record PlayerCombatant(Player player, JsonObject sheet) implements SheetBacked {

		@Override public Entity entity() { return player; }

		@Override public String saveId() { return player.getStringUUID(); }

		@Override public String name() { return SheetLoader.characterNameOf(sheet, player); }

		/** Incluye la armadura y el escudo REALES equipados, cosa que solo un jugador tiene. */
		@Override public int armorClass() {
			int base = CombatManager.armorClassOf(player, sheet);
			//Los objetos mágicos suman aquí y no en CombatManager porque este ES el punto único por el que
			//pasa toda pregunta de "¿cuál es su CA?" — incluida la del Panel de DM y la de un monstruo
			//decidiendo si acierta.
			for (MagicItemRegistry.MagicItem item : MagicItemRegistry.activeFor(player, sheet)) base += item.acBonus();
			return base;
		}

		@Override public Combatant.SaveRoll rollSave(String ability) {
			Combatant.SaveRoll roll = SheetBacked.super.rollSave(ability);
			int bonus = 0;
			for (MagicItemRegistry.MagicItem item : MagicItemRegistry.activeFor(player, sheet)) bonus += item.saveBonus();
			//Solo si de verdad se tiró: una salvación que falla sola por condición no mejora por llevar un
			//anillo, y sumarle el bono la convertiría en un número que no significa nada.
			if (bonus == 0 || roll.blockedBy() != null || roll.outcome() == null || roll.outcome().result() == null) return roll;
			return new Combatant.SaveRoll(DiceManager.roll(sheet, "1d20 + " + (abilityModifier(ability) + bonus)), null);
		}

		//PG del atributo de salud real de Minecraft, no de la hoja: para un jugador esa ES su vida, y la
		//hoja solo la refleja.
		@Override public int currentHp() { return (int) Math.ceil(player.getHealth()); }

		@Override public int maxHp() { return (int) Math.ceil(player.getMaxHealth()); }

		@Override public int reactiveArmorClass(int attackRollValue) {
			if (!(player instanceof ServerPlayer serverPlayer)) return armorClass();
			return ShieldManager.effectiveAc(serverPlayer, attackRollValue, armorClass());
		}

		/**
		 * OJO: no llamar desde dentro de un {@code LivingHurtEvent} — ahí el daño ya está en vuelo y lo
		 * aplica el propio evento con {@code setAmount}; llamar a esto allí recurriría. Este camino existe
		 * para el daño que NO nace de un golpe vanilla (hechizos, ataques de monstruo, efectos por turno).
		 */
		@Override public void applyRealDamage(int amount) {
			if (amount <= 0) return;
			player.hurt(player.damageSources().generic(), amount);
			if (player instanceof ServerPlayer serverPlayer) ConcentrationManager.onDamageTaken(serverPlayer, amount);
		}

		@Override public boolean isDefeated() { return player.getHealth() <= 0; }
	}

	//--- PNJ (personaje con hoja, sin jugador detrás) ---------------------------------------------------

	/**
	 * <p>Una ficha de personaje que el DM lleva sobre una entidad del mundo: aliados, secundarios,
	 * enemigos con nivel de clase. Juega con exactamente las mismas reglas que un PJ —de ahí que comparta
	 * {@link SheetBacked}— en vez de tener que degradarse a un bloque de estadísticas de monstruo.</p>
	 *
	 * <p>Sus PG viven en la hoja, no en el atributo de salud de Minecraft: la hoja es lo que persiste
	 * entre sesiones y sobrevive a que la entidad se descargue o se vuelva a invocar. La entidad es el
	 * cuerpo, no el personaje.</p>
	 */
	record NpcCombatant(Entity npc, JsonObject sheet, String characterId) implements SheetBacked {

		@Override public Entity entity() { return npc; }

		@Override public String saveId() { return characterId; }

		@Override public String name() {
			return sheet.has("characterName") ? sheet.get("characterName").getAsString() : characterId;
		}

		/**
		 * Sin armadura ni escudo reales que consultar (un mob no equipa como un jugador): CA del override
		 * manual del DM si lo hay, y si no la base de 5e, 10 + mod. Destreza.
		 */
		@Override public int armorClass() {
			if (sheet.has("armorClassOverride")) return sheet.get("armorClassOverride").getAsInt();
			return 10 + abilityModifier("dex");
		}

		@Override public int maxHp() {
			return SheetLoader.maxHitPointsFor(sheet, SheetLoader.characterLevelOf(sheet));
		}

		//Al invocarlo la hoja aún no trae "currentHp"; empieza a PG completos en vez de a 0, que lo mataría
		//en el primer golpe.
		@Override public int currentHp() {
			return sheet.has("currentHp") ? sheet.get("currentHp").getAsInt() : maxHp();
		}

		@Override public void applyRealDamage(int amount) {
			if (amount <= 0) return;
			int remaining = Math.max(0, currentHp() - amount);
			sheet.addProperty("currentHp", remaining);
			SheetLoader.saveServer(sheet, characterId);
			if (remaining > 0) return;

			CombatFx.defeated(npc);
			TurnManager.markDefeated(npc.getId());
			//Mismo baile que un monstruo: nuestra salud de 5e vive aparte de la de Minecraft, así que die()
			//no puede inferir la muerte solo y setHealth(0) antes es imprescindible para que isDeadOrDying()
			//deje de devolver false — sin eso el cuerpo se queda tirado sin desaparecer nunca.
			if (npc instanceof LivingEntity living) {
				living.setHealth(0.0F);
				living.die(npc.damageSources().generic());
			} else {
				npc.remove(Entity.RemovalReason.KILLED);
			}
		}

		@Override public boolean isDefeated() { return currentHp() <= 0; }
	}

	//--- Monstruo --------------------------------------------------------------------------------------

	record MonsterCombatant(Entity monster, MonsterRegistry.MonsterStatBlock block) implements Combatant {

		private static final String CONDITIONS_KEY = "conditions";

		@Override public Entity entity() { return monster; }

		@Override public String name() { return block.name(); }

		@Override public int armorClass() { return block.ac(); }

		@Override public int currentHp() { return MonsterRegistry.currentHpOf(monster); }

		@Override public int maxHp() { return block.maxHp(); }

		@Override public int abilityModifier(String ability) { return block.abilityModifier(ability); }

		@Override public int proficiencyBonus() { return block.proficiencyBonus(); }

		/**
		 * Mismo vocabulario que las afinidades de la hoja de un jugador — ver
		 * {@link DamageTypes#multiplierForLabel}. Se queda con la MAS protectora de las dos cuando ambas
		 * aplican: una inmunidad incondicional no debe empeorar porque el golpe sea mágico.
		 */
		@Override public double damageMultiplier(String damageType, boolean magical) {
			if (damageType == null) return 1.0;
			String type = damageType.toLowerCase(Locale.ROOT);
			double multiplier = DamageTypes.multiplierForLabel(block.damageAffinities().get(type));
			//Solo si HAY entrada condicional para ese tipo: comparar contra el 1.0 que devuelve una ausente
			//aplastaba cualquier resistencia incondicional a "daño normal", que es lo contrario de lo que se
			//pretende. Cuando existen las dos gana la más protectora — una inmunidad no debe empeorar porque
			//el golpe encima sea no mágico.
			String conditional = magical ? null : block.nonmagicalAffinities().get(type);
			if (conditional != null) {
				multiplier = Math.min(multiplier, DamageTypes.multiplierForLabel(conditional));
			}
			return multiplier;
		}

		//En el mismo compartimento NBT que los PG y las condiciones — ver MonsterRegistry.setCurrentHp.
		@Override public int temporaryHp() {
			CompoundTag data = monster.getPersistentData();
			return data.contains("dndsheets") ? data.getCompound("dndsheets").getInt("temporaryHp") : 0;
		}

		@Override public void setTemporaryHp(int amount) {
			CompoundTag data = monster.getPersistentData();
			CompoundTag tag = data.getCompound("dndsheets");
			tag.putInt("temporaryHp", Math.max(0, amount));
			data.put("dndsheets", tag);
		}

		@Override public void applyRealDamage(int amount) {
			int remaining = currentHp() - amount;
			if (remaining > 0) {
				MonsterRegistry.setCurrentHp(monster, remaining);
				return;
			}
			MonsterRegistry.setCurrentHp(monster, 0);
			CombatFx.defeated(monster);
			TurnManager.markDefeated(monster.getId());
			//die(), no remove(): un remove() a secas nunca pasa por el camino de muerte vanilla (loot table,
			//XP...). Nuestra salud real de Minecraft nunca baja (el PG de 5e se trackea aparte), así que die()
			//no puede inferir la muerte solo; setHealth(0) antes es imprescindible porque isDeadOrDying()
			//sigue devolviendo false con la salud llena y el mob se quedaría tirado sin desaparecer nunca.
			if (monster instanceof LivingEntity living) {
				living.setHealth(0.0F);
				living.die(monster.damageSources().generic());
			} else {
				monster.remove(Entity.RemovalReason.KILLED);
			}
		}

		@Override public boolean isDefeated() { return currentHp() <= 0; }

		@Override public Map<Condition, Integer> conditionSources() {
			Map<Condition, Integer> result = new EnumMap<>(Condition.class);
			CompoundTag data = monster.getPersistentData();
			if (!data.contains("dndsheets")) return result;
			String joined = data.getCompound("dndsheets").getString(CONDITIONS_KEY);
			if (joined.isEmpty()) return result;
			for (String entry : joined.split(",")) {
				parseEntry(entry, result);
			}
			return result;
		}

		@Override public void setConditionSources(Map<Condition, Integer> sources) {
			StringBuilder joined = new StringBuilder();
			for (Map.Entry<Condition, Integer> entry : sources.entrySet()) {
				if (joined.length() > 0) joined.append(',');
				joined.append(formatEntry(entry));
			}
			CompoundTag data = monster.getPersistentData();
			CompoundTag tag = data.getCompound("dndsheets"); //Vacío si no existía, igual que MonsterRegistry.setCurrentHp.
			tag.putString(CONDITIONS_KEY, joined.toString());
			data.put("dndsheets", tag);
		}
	}

	//--- Formato en disco -------------------------------------------------------------------------------

	/**
	 * <p>Una condición se guarda como {@code etiqueta} o {@code etiqueta@idFuente}, en el array JSON de la
	 * hoja y en la cadena NBT del monstruo por igual. El sufijo es opcional a propósito: lo guardado antes
	 * de que existieran las fuentes se sigue leyendo tal cual, como condición sin fuente conocida.</p>
	 *
	 * <p>El id de entidad no sobrevive a un reinicio del servidor (Minecraft los reasigna), así que tras
	 * reiniciar una condición conserva su efecto pero pierde a quién señalaba. Es aceptable: las dos que
	 * usan la fuente ya tratan "no la veo" como "no aplica", que es el lado seguro.</p>
	 */
	//Público, no privado, pese a que solo lo usan las dos implementaciones de aquí abajo: es el formato en
	//disco, y romperlo hace que las condiciones dejen de sobrevivir a un reinicio SIN que falle nada
	//visible. Expuesto para que JsonContentSelfTest pueda fijar la ida y vuelta.
	public static void parseEntry(String entry, Map<Condition, Integer> into) {
		int separator = entry.indexOf('@');
		String label = separator < 0 ? entry : entry.substring(0, separator);
		Condition condition = Condition.fromLabel(label);
		if (condition == null) return;
		int source = NO_SOURCE;
		if (separator >= 0) {
			try {
				source = Integer.parseInt(entry.substring(separator + 1));
			} catch (NumberFormatException e) {
				source = NO_SOURCE; //Etiqueta manipulada a mano: se queda sin fuente en vez de tumbar la carga.
			}
		}
		into.put(condition, source);
	}

	public static String formatEntry(Map.Entry<Condition, Integer> entry) {
		int source = entry.getValue() == null ? NO_SOURCE : entry.getValue();
		return source == NO_SOURCE ? entry.getKey().label() : entry.getKey().label() + "@" + source;
	}
}
