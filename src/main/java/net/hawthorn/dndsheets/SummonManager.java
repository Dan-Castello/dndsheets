package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Invocaciones que actúan por su cuenta en turnos posteriores: Arma Espiritual, Esfera Flamígera,
 * Sabueso Fiel. Es la diferencia con un hechizo de daño normal — no se resuelve una vez y ya, deja algo
 * en el mundo que entra en la iniciativa y ataca solo.</p>
 *
 * <p>Casi toda la maquinaria ya existía y esta clase apenas la conecta: {@link MonsterRegistry#spawnAt}
 * invoca entidades con bloque de estadísticas, {@link TurnManager#addLateMonster} las mete en el orden
 * de turnos a mitad de encuentro, y {@link MonsterActionManager#autoAct} ya hace que un monstruo ataque
 * solo cuando le toca. Lo único que faltaba de verdad era <b>a quién</b> ataca: {@code autoAct} apunta al
 * jugador más cercano, que para un invocado del propio jugador es exactamente el objetivo equivocado.</p>
 *
 * <p>El bloque de estadísticas se genera al vuelo desde el hechizo y se registra con un id sintético, en
 * vez de exigir que el DM dé de alta un monstruo por cada invocación posible.</p>
 */
public class SummonManager {

	private static final String OWNER_KEY = "summonOwner";
	private static final String ROUNDS_KEY = "summonRounds";

	//Un invocado no aguanta golpes: en 5e la mayoría son objetos o efectos que se disipan al recibir daño,
	//y darles PG de verdad los convertiría en un escudo gratis. 1 PG y CA baja: existen para atacar, no
	//para tanquear.
	private static final int SUMMON_HP = 1;
	private static final int SUMMON_AC = 10;

	/**
	 * <p>Invoca la entidad del hechizo delante del lanzador y la mete en la iniciativa. El bloque de
	 * estadísticas hereda la característica de lanzamiento y la competencia del invocador, que es como 5e
	 * calcula el ataque de un arma invocada.</p>
	 */
	public static Entity summon(ServerPlayer caster, SpellRegistry.Spell spell, int proficiency, int abilityMod) {
		if (!(caster.level() instanceof ServerLevel level)) return null;

		String monsterId = "dndsheets:summon_" + spell.id().replace(':', '_');
		//El bloque se re-registra en cada invocación a propósito: así un cambio en el JSON del hechizo se
		//nota en la siguiente sin recargar nada, y no hay que mantener un registro paralelo de invocables.
		MonsterRegistry.replace(new MonsterRegistry.MonsterStatBlock(
			monsterId, spell.name(), spell.summonEntityId(), SUMMON_AC, SUMMON_HP,
			//Las características se fijan para que abilityModifier devuelva exactamente el modificador del
			//invocador: 10 + 2*mod da ese mod, y así el ataque sale con los números del lanzador sin
			//inventar un campo nuevo en el bloque.
			Map.of("str", 10 + 2 * abilityMod, "dex", 10 + 2 * abilityMod, "con", 10,
				"int", 10, "wis", 10, "cha", 10),
			proficiency,
			List.of(new MonsterRegistry.MonsterAttack(spell.name(), "str", spell.dice(), "str",
				spell.damageType(), null, null, 0)),
			List.of(), Map.of(), Map.of(),
			//Un arma espiritual o una esfera de fuego son autómatas: no son criaturas vivas, son magia con
			//forma. Importa poco hoy, pero dejarlo en UNKNOWN sería decir "no lo sé" de algo que sí se sabe.
			//Una invocación no es un jefe: sin Resistencia Legendaria.
			//Brilla: una invocación es tuya y dura poco, y en una mesa de seis jugadores hay que poder
			//distinguirla de un monstruo del DM de un vistazo, incluso a través de una pared.
			//Congelada, como cualquier monstruo del mod: una invocación actúa en su turno por autoAct, no
			//por su cuenta. La IA propia es para los PNJ de ambiente, no para esto.
			CreatureType.CONSTRUCT, 0, 0, 1, MonsterRegistry.Appearance.GLOWING, false, false));

		//Delante del lanzador, no encima: invocarlo dentro de su propia hitbox lo dejaría empujándolo.
		Vec3 spot = caster.position().add(caster.getViewVector(1.0f).scale(2.0));
		//La etiqueta de dueño se pone ANTES de que entre al orden de turnos: addLateMonster la lee para
		//decidir si es enemigo, y hacerlo después la metía como tal — el combate no habría terminado nunca
		//mientras durase la invocación.
		Entity summoned = MonsterRegistry.spawnAt(level, spot.x, spot.y, spot.z, monsterId, entity -> {
			CompoundTag data = entity.getPersistentData();
			CompoundTag tag = data.getCompound("dndsheets"); //Mismo compartimento que el resto del estado NBT.
			tag.putString(OWNER_KEY, caster.getStringUUID());
			tag.putInt(ROUNDS_KEY, ZoneManager.DEFAULT_ROUNDS);
			data.put("dndsheets", tag);
		});
		if (summoned == null) return null;

		ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.summoned",
			SheetLoader.characterNameOf(SheetLoader.getServerSheet(caster.getStringUUID()), caster), spell.name())
			.withStyle(ChatFormatting.GOLD));
		return summoned;
	}

	/** UUID del jugador que la invocó, o {@code null} si esa entidad no es una invocación. */
	public static String ownerOf(Entity entity) {
		CompoundTag data = entity.getPersistentData();
		if (!data.contains("dndsheets")) return null;
		String owner = data.getCompound("dndsheets").getString(OWNER_KEY);
		return owner.isEmpty() ? null : owner;
	}

	/**
	 * <p>A quién ataca una invocación en su turno: al enemigo más cercano de su dueño, no al jugador más
	 * cercano. Sin esto, el Arma Espiritual de un jugador le pegaría a él, que es el objetivo exactamente
	 * contrario al que existe para atacar.</p>
	 */
	public static Entity findEnemyTarget(ServerLevel level, Entity summoned, double range) {
		Entity best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (Entity candidate : level.getEntities(summoned,
				summoned.getBoundingBox().inflate(range),
				e -> e.isAlive() && TurnManager.isMonster(e) && ownerOf(e) == null)) {
			double distSq = candidate.position().distanceToSqr(summoned.position());
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				best = candidate;
			}
		}
		return best;
	}

	//getAllEntities() devuelve un Iterable, no una colección, y además hay que copiarlo antes de recorrer:
	//dismiss() elimina entidades, y hacerlo sobre la vista viva del nivel es una ConcurrentModificationException
	//esperando a que alguien tenga dos invocaciones a la vez.
	private static List<Entity> snapshot(ServerLevel level) {
		List<Entity> all = new ArrayList<>();
		level.getAllEntities().forEach(all::add);
		return all;
	}

	/** Descuenta un asalto a cada invocación y disipa las que expiran. */
	public static void endRound(ServerLevel level) {
		for (Entity entity : snapshot(level)) {
			if (ownerOf(entity) == null) continue;
			CompoundTag tag = entity.getPersistentData().getCompound("dndsheets");
			int left = tag.getInt(ROUNDS_KEY) - 1;
			if (left > 0) {
				tag.putInt(ROUNDS_KEY, left);
				entity.getPersistentData().put("dndsheets", tag);
				continue;
			}
			dismiss(level, entity);
		}
	}

	/** Disipa las invocaciones de ese jugador: estos hechizos son de concentración. */
	public static void removeFor(ServerLevel level, UUID ownerId) {
		String owner = ownerId.toString();
		for (Entity entity : snapshot(level)) {
			if (owner.equals(ownerOf(entity))) dismiss(level, entity);
		}
	}

	//DISCARDED y no KILLED: una invocación que se disipa no "muere", así que no debe soltar botín ni XP ni
	//contar como enemigo abatido. markDefeated sí hace falta para que no bloquee el fin del combate.
	private static void dismiss(ServerLevel level, Entity entity) {
		TurnManager.markDefeated(entity.getId());
		CombatFx.defeated(entity);
		entity.remove(Entity.RemovalReason.DISCARDED);
		for (Player player : level.players()) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.spell.summon_faded",
				entity.getName().getString()).withStyle(ChatFormatting.GRAY));
		}
	}
}
