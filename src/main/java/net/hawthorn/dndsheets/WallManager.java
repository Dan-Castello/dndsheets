package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * <p>Muros persistentes (Muro de Fuego, de Hielo, de Espinas, Barrera de Hojas). Es una capacidad
 * distinta de las formas de área de {@link SpellCastManager}, aunque se parezcan: una línea o un cono
 * se emiten desde el lanzador y se resuelven <em>una vez</em>, mientras que un muro es una superficie
 * que se <em>coloca</em> y sigue ahí durante asaltos, dañando a quien empiece su turno dentro.</p>
 *
 * <p>El muro no coloca bloques reales: cambiar el mundo obligaría a limpiarlo después y a decidir qué
 * pasa si alguien lo pica o si el chunk se descarga. Se guarda como una región y se comprueba al empezar
 * cada turno, que es exactamente cuando 5e dice que hay que tirar la salvación. La geometría es la misma
 * {@code inShape("wall", ...)} que ya se comprueba en el self-test.</p>
 *
 * <p>Estado en memoria y por encuentro, igual que el orden de turnos: un muro no debería sobrevivir a un
 * reinicio del servidor, porque tampoco sobrevive el combate en el que se lanzó.</p>
 */
public class WallManager {

	/**
	 * @param origin      base del muro, a la altura de los pies del lanzador (la altura se cuenta hacia
	 *                    arriba desde ahí, ver {@code SpellCastManager.WALL_HEIGHT}).
	 * @param casterId    de quién es: hace falta para retirarlo si pierde la concentración.
	 */
	public record Wall(UUID casterId, String spellName, Vec3 origin, Vec3 direction, double length,
	                   String dice, String damageType, String saveAbility, int saveDc, boolean halfOnSave,
	                   int roundsRemaining) {

		Wall tick() {
			return new Wall(casterId, spellName, origin, direction, length, dice, damageType,
				saveAbility, saveDc, halfOnSave, roundsRemaining - 1);
		}
	}

	private static final List<Wall> active = new ArrayList<>();

	//Duración por defecto: 1 minuto de 5e = 10 asaltos, que es lo que dura la mayoría de los muros.
	public static final int DEFAULT_ROUNDS = 10;

	public static void place(ServerPlayer caster, SpellRegistry.Spell spell, int saveDc) {
		//Base a la altura de los pies y no de los ojos: el muro nace del suelo, y con la altura contada
		//desde los ojos su mitad inferior quedaría enterrada.
		Vec3 origin = caster.position();
		Vec3 direction = caster.getViewVector(1.0f).normalize();
		active.add(new Wall(caster.getUUID(), spell.name(), origin, direction, spell.aoeRadius(),
			spell.dice(), spell.damageType(), spell.saveAbility(), saveDc, spell.halfOnSave(), DEFAULT_ROUNDS));

		if (caster.level() instanceof ServerLevel level) draw(level, active.get(active.size() - 1));
		ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.wall_placed",
			SheetLoader.characterNameOf(SheetLoader.getServerSheet(caster.getStringUUID()), caster), spell.name())
			.withStyle(ChatFormatting.DARK_PURPLE));
	}

	/**
	 * <p>Llamado al empezar el turno de un combatiente: si está dentro de un muro, tira su salvación y
	 * recibe el daño. Es el momento exacto en el que 5e lo pide, y por eso engancha en
	 * {@code TurnManager.beginTurn} junto a los efectos de estado en vez de en un tick propio.</p>
	 */
	public static void onTurnStart(ServerLevel level, Entity entity) {
		if (active.isEmpty()) return;
		Combatant combatant = Combatant.of(entity);
		if (combatant == null) return;

		for (Wall wall : new ArrayList<>(active)) {
			if (!SpellCastManager.inShape("wall", wall.origin(), wall.direction(), wall.length(),
					entity.getBoundingBox().getCenter())) {
				continue;
			}

			DiceManager.RollOutcome damageRoll = DiceManager.roll(new com.google.gson.JsonObject(), wall.dice());
			if (damageRoll.result() == null) continue;

			Combatant.SaveRoll save = combatant.rollSave(wall.saveAbility());
			boolean saved = save.succeeds(wall.saveDc());
			int amount = saved ? (wall.halfOnSave() ? damageRoll.result().getValue() / 2 : 0) : damageRoll.result().getValue();
			//Un conjuro siempre cuenta como mágico, igual que en SpellCastManager y MonsterActionManager.
			amount = DamageTypes.applyMultiplier(amount, combatant.effectiveDamageMultiplier(wall.damageType(), true));

			CombatFx.spellImpact(entity, saved, wall.damageType());
			broadcast(level, Component.translatable("chat.dndsheets.spell.wall_tick",
				combatant.name(), wall.spellName(), save.formatted(), wall.saveDc(), amount).withStyle(ChatFormatting.DARK_RED));
			if (amount > 0) combatant.takeDamage(amount);
		}
	}

	/** Llamado al cerrarse un asalto completo: descuenta duración y retira lo que expira. */
	public static void endRound(ServerLevel level) {
		Iterator<Wall> it = active.iterator();
		List<Wall> renewed = new ArrayList<>();
		while (it.hasNext()) {
			Wall wall = it.next().tick();
			it.remove();
			if (wall.roundsRemaining() > 0) {
				renewed.add(wall);
				draw(level, wall); //Se redibuja cada asalto: sin esto el muro es invisible salvo el instante en que se lanzó.
			} else {
				broadcast(level, Component.translatable("chat.dndsheets.spell.wall_faded", wall.spellName()).withStyle(ChatFormatting.GRAY));
			}
		}
		active.addAll(renewed);
	}

	/**
	 * <p>Retira los muros de ese lanzador. Los muros son de concentración, así que perderla los apaga —
	 * sin esto, fallar la salvación de Constitución dejaba el muro ardiendo igualmente, que es justo el
	 * fallo que ya se corrigió una vez para los efectos de estado.</p>
	 */
	public static void removeFor(UUID casterId) {
		active.removeIf(wall -> wall.casterId().equals(casterId));
	}

	/** El combate terminó: sin orden de turnos no hay asaltos que contar, así que no hay muro que mantener. */
	public static void clear() {
		active.clear();
	}

	//Partículas a lo largo del muro y en toda su altura, para que se vea dónde está: sin representación
	//visual, un muro persistente es una trampa invisible en vez de una decisión táctica.
	private static void draw(ServerLevel level, Wall wall) {
		int samplesAlong = Math.max(4, (int) (wall.length() * 2));
		for (int i = 0; i <= samplesAlong; i++) {
			Vec3 base = wall.origin().add(wall.direction().scale(wall.length() * i / samplesAlong));
			for (double y = 0; y <= SpellCastManager.WALL_HEIGHT; y += 0.5) {
				level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME,
					base.x, base.y + y, base.z, 1, 0, 0, 0, 0);
			}
		}
	}

	private static void broadcast(ServerLevel level, Component message) {
		for (ServerPlayer player : level.players()) player.sendSystemMessage(message);
	}
}
