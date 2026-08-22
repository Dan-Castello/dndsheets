package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <p>Jefes que <b>no hacen cola</b>. Una criatura con {@code "ownClock": true} en su bloque sale del orden
 * de iniciativa pero no del combate: recibe daño con todas las reglas de 5e, cuenta para que el encuentro
 * termine, y una condición que incapacita la para en seco igual que a cualquiera. Lo único que ignora es
 * esperar su turno.</p>
 *
 * <p><b>El guardarraíl que lo hace justo</b> es {@link #CYCLE_TICKS}: actúa una vez cada seis segundos,
 * que es exactamente lo que dura un asalto de 5e. No tiene más acciones que nadie — las tiene
 * <em>desincronizadas</em>. Quitar ese tope convierte un jefe en un bug.</p>
 *
 * <p><b>Por qué también se le fija el objetivo.</b> Un jefe que huye o pierde el aggro rompe la escena
 * mucho más que uno que espera turno: los jugadores lo ven irse y la sensación es de fallo, no de diseño.
 * Así que al entrar en combate se le quitan las metas de vanilla que lo harían escapar
 * ({@code PanicGoal}, {@code AvoidEntityGoal} — un ravager en llamas huye, y con eso el encuentro se
 * acabó) y en cada ciclo se le vuelve a poner objetivo si lo ha perdido. No hace falta ningún mixin: las
 * metas de un {@code Mob} se pueden quitar por su nombre de clase.</p>
 */
public final class OwnClockManager {

	//Seis segundos: el asalto de 5e. Es el número que impide que esto sea "el jefe hace lo que quiere".
	private static final int CYCLE_TICKS = 120;
	//A cuánto busca objetivo si lo perdió. El mismo alcance con el que autoAct elige a quién pegar.
	private static final double AGGRO_RANGE = 30.0;

	private OwnClockManager() {
	}

	/**
	 * <p>Arranca el reloj de esta criatura y anuncia a la mesa lo que está pasando. Idempotente por
	 * definición del llamador: se invoca al montar la iniciativa y al sumar un monstruo tarde, y las dos
	 * veces sobre criaturas distintas.</p>
	 */
	public static void start(ServerLevel level, Entity boss) {
		if (!MonsterRegistry.isOffClock(boss)) return;
		hardenAggro(boss);
		announce(level, boss);
		schedule(level, boss.getId());
	}

	private static void schedule(ServerLevel level, int entityId) {
		DndsheetsMod.queueServerWork(CYCLE_TICKS, () -> {
			//El combate manda: fuera de él no hay reloj que llevar, y el jefe vuelve a ser un mob normal
			//con su IA. Sin esta salida el ciclo se rearmaría para siempre.
			if (!TurnManager.isActive()) return;
			Entity boss = level.getEntity(entityId);
			if (boss == null || !boss.isAlive() || !MonsterRegistry.isOffClock(boss)) return;

			keepAggro(level, boss);
			//autoAct comprueba por su cuenta que la criatura pueda actuar (paralizada, aturdida...) sin
			//pedir turno, porque el bloque lo marca. Ver MonsterActionManager.
			MonsterActionManager.autoAct(level, boss);
			schedule(level, entityId);
		});
	}

	/**
	 * <p>Le quita las metas de vanilla que lo harían huir. Se recorre la lista de metas y se descartan por
	 * tipo: es la única forma sin mixins, y funciona sobre la entidad base sea de quien sea.</p>
	 */
	private static void hardenAggro(Entity boss) {
		if (!(boss instanceof Mob mob)) return;
		//No se puede quitar mientras se itera: se juntan primero y se quitan después.
		List<net.minecraft.world.entity.ai.goal.Goal> fleeing = new ArrayList<>();
		Set<WrappedGoal> goals = mob.goalSelector.getAvailableGoals();
		for (WrappedGoal wrapped : goals) {
			if (wrapped.getGoal() instanceof PanicGoal || wrapped.getGoal() instanceof AvoidEntityGoal<?>) {
				fleeing.add(wrapped.getGoal());
			}
		}
		for (net.minecraft.world.entity.ai.goal.Goal goal : fleeing) mob.goalSelector.removeGoal(goal);
		//Y que no se descargue por distancia a mitad de la pelea, que es la otra forma de "desaparecer".
		mob.setPersistenceRequired();
	}

	/** Si perdió el objetivo (murió, se alejó, nunca lo tuvo), se le da el jugador vivo más cercano. */
	private static void keepAggro(ServerLevel level, Entity boss) {
		if (!(boss instanceof Mob mob)) return;
		LivingEntity target = mob.getTarget();
		if (target != null && target.isAlive()) return;
		Player nearest = level.getNearestPlayer(boss, AGGRO_RANGE);
		if (nearest instanceof ServerPlayer serverPlayer && serverPlayer.isAlive()) mob.setTarget(serverPlayer);
	}

	/**
	 * <p>El aviso a la mesa, en pantalla y no en el chat. Es la mitad del diseño: sin él, un jugador que ve
	 * al dragón moverse fuera de su turno concluye que el mod está roto. Con él, concluye que ese bicho es
	 * otra cosa — que es exactamente lo que queríamos.</p>
	 *
	 * <p>El subtítulo cambia según el tipo de criatura, porque "no puede ser detenida por los turnos" dicho
	 * de un dragón y dicho de un cieno son dos frases distintas, y la que no encaja se lee como plantilla.</p>
	 */
	private static void announce(ServerLevel level, Entity boss) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(boss);
		if (block == null) return;

		Component title = Component.literal(block.name()).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
		Component subtitle = Component.translatable(subtitleKey(block.type())).withStyle(ChatFormatting.GRAY);

		for (ServerPlayer player : level.players()) {
			//Entrada lenta y salida lenta: un título que aparece de golpe se lee como un error del juego.
			player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
			player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			player.connection.send(new ClientboundSetTitleTextPacket(title));
			//El sonido hace la mitad del trabajo: avisa incluso a quien está mirando su hoja.
			player.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6f, 1.4f);
		}
	}

	/** Una frase por tipo de criatura; el resto comparten la genérica. */
	static String subtitleKey(CreatureType type) {
		return switch (type) {
			case DRAGON, GIANT, MONSTROSITY, ABERRATION, FIEND, CELESTIAL, UNDEAD, ELEMENTAL, CONSTRUCT ->
				"chat.dndsheets.ownclock.subtitle." + type.name().toLowerCase(java.util.Locale.ROOT);
			default -> "chat.dndsheets.ownclock.subtitle.generic";
		};
	}
}
