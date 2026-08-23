package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * <p>Superficies de terreno, la otra mitad del combo de altura+empujar que se trajo de Baldur's Gate 3:
 * el agua de VERDAD del mundo (bloques reales, no una zona abstracta) apaga el fuego y conduce el rayo, y
 * un impacto de fuego en área deja el suelo ardiendo unos asaltos, no solo el instante del golpe.</p>
 *
 * <p><b>Por qué no es una {@link ZoneManager.Zone}.</b> Una zona es DE alguien —tiene lanzador, tiene
 * concentración, se apaga si la pierde—; un charco de fuego que dejó una Bola de Fuego no es de nadie, y
 * cualquier otro golpe de área que caiga encima lo puede reavivar. Comparten el mismo gancho de
 * {@code TurnManager.beginTurn} porque la PREGUNTA es la misma (¿hay algo en el suelo bajo mis pies al
 * empezar mi turno?), pero el DUEÑO de la respuesta es distinto.</p>
 *
 * <p><b>Por qué el agua es de verdad y no otra superficie trackeada.</b> Minecraft ya sabe dónde hay agua;
 * inventar una segunda "superficie de agua" encima habría sido mantener dos verdades para la misma
 * pregunta. Apagar fuego y conducir rayo consultan el bloque real del mundo — ni un hechizo nuevo, ni
 * contenido que cargar, ni estado que limpiar cuando el chunk se descarga.</p>
 *
 * <p><b>Simplificación deliberada</b>, {@code ponytail}: el charco de fuego no se dibuja como bloques de
 * fuego reales (podría prender la base de un jugador y no hay quien lo limpie después) — es una zona
 * abstracta con partículas, como ya hace {@link ZoneManager} con sus muros. El chispazo de rayo+agua es
 * daño fijo, no una tirada completa con salvación: es un efecto secundario del impacto, no un segundo
 * hechizo, y complicarlo no aporta nada que el jugador vaya a notar.</p>
 */
class SurfaceManager {

	private record Fire(Vec3 origin, double radius, int roundsRemaining) {
		Fire tick() {
			return new Fire(origin, radius, roundsRemaining - 1);
		}
	}

	private static final List<Fire> fires = new ArrayList<>();

	private static final int FIRE_ROUNDS = 3;
	private static final String FIRE_DICE = "1d4";
	//Bloque de impacto y el de al lado: un radio de agua de sobra sin tener que barrer un área entera por
	//fotograma cada vez que cae un hechizo.
	private static final int WATER_CHECK_RADIUS = 2;
	//"Charco eléctrico": cualquiera de pie en agua real dentro de este radio del impacto de un rayo se
	//lleva la misma descarga, sin salvación — es agua conduciendo electricidad, no una regla que se esquive.
	private static final double LIGHTNING_WATER_RADIUS = 6.0;
	private static final String LIGHTNING_SPLASH_DICE = "1d6";

	/**
	 * <p>Llamado justo después de resolver el daño de un hechizo de área (ver {@code SpellCastManager}).
	 * {@code alreadyHit} son las entidades que el hechizo ya golpeó de lleno: el chispazo de agua+rayo las
	 * salta, para no cobrarles el mismo rayo dos veces.</p>
	 */
	static void onAoeImpact(ServerLevel level, Vec3 impactPoint, String damageType, List<Entity> alreadyHit) {
		boolean wet = isNearWater(level, impactPoint);
		if ("fuego".equals(damageType)) {
			if (wet) {
				broadcast(level, Component.translatable("chat.dndsheets.surface.fire_fizzles").withStyle(ChatFormatting.AQUA));
				return;
			}
			ignite(level, impactPoint);
		} else if ("rayo".equals(damageType) && wet) {
			shockWater(level, impactPoint, alreadyHit);
		}
	}

	//Si ya había fuego ahí, se le da cuerda de nuevo en vez de apilar un segundo charco encima del primero
	//(dos Bolas de Fuego seguidas en el mismo sitio no deberían doblar el daño por turno, solo mantenerlo).
	private static void ignite(ServerLevel level, Vec3 point) {
		for (int i = 0; i < fires.size(); i++) {
			Fire existing = fires.get(i);
			if (existing.origin().distanceTo(point) <= existing.radius()) {
				fires.set(i, new Fire(existing.origin(), existing.radius(), FIRE_ROUNDS));
				return;
			}
		}
		fires.add(new Fire(point, 2.5, FIRE_ROUNDS));
		broadcast(level, Component.translatable("chat.dndsheets.surface.fire_ignites").withStyle(ChatFormatting.GOLD));
	}

	private static void shockWater(ServerLevel level, Vec3 impactPoint, List<Entity> alreadyHit) {
		for (Entity entity : level.getEntitiesOfClass(LivingEntity.class,
				new net.minecraft.world.phys.AABB(impactPoint, impactPoint).inflate(LIGHTNING_WATER_RADIUS))) {
			if (alreadyHit.contains(entity) || !entity.isInWaterOrBubble()) continue;
			Combatant combatant = Combatant.of(entity);
			if (combatant == null) continue;

			DiceManager.RollOutcome roll = DiceManager.roll(new com.google.gson.JsonObject(), LIGHTNING_SPLASH_DICE);
			if (roll.result() == null) continue;
			int amount = DamageTypes.applyMultiplier(roll.result().getValue(), combatant.effectiveDamageMultiplier("rayo", true));
			CombatFx.spellImpact(entity, false, "rayo");
			broadcast(level, Component.translatable("chat.dndsheets.surface.water_shock", combatant.name(), roll.formatted())
				.withStyle(ChatFormatting.AQUA));
			if (amount > 0) combatant.takeDamage(amount);
		}
	}

	/** Llamado al empezar el turno de un combatiente: si está de pie sobre fuego tirado en el suelo, arde. */
	static void onTurnStart(ServerLevel level, Entity entity) {
		if (fires.isEmpty()) return;
		Combatant combatant = Combatant.of(entity);
		if (combatant == null) return;

		for (Fire fire : fires) {
			if (fire.origin().distanceTo(entity.getBoundingBox().getCenter()) > fire.radius()) continue;

			DiceManager.RollOutcome roll = DiceManager.roll(new com.google.gson.JsonObject(), FIRE_DICE);
			if (roll.result() == null) continue;
			int amount = DamageTypes.applyMultiplier(roll.result().getValue(), combatant.effectiveDamageMultiplier("fuego", true));
			CombatFx.spellImpact(entity, false, "fuego");
			broadcast(level, Component.translatable("chat.dndsheets.surface.fire_tick", combatant.name(), roll.formatted())
				.withStyle(ChatFormatting.GOLD));
			if (amount > 0) combatant.takeDamage(amount);
		}
	}

	/** Asalto completo: descuenta duración, apaga lo que expira, y redibuja lo que sigue ardiendo. */
	static void endRound(ServerLevel level) {
		Iterator<Fire> it = fires.iterator();
		List<Fire> renewed = new ArrayList<>();
		while (it.hasNext()) {
			Fire fire = it.next().tick();
			it.remove();
			if (fire.roundsRemaining() > 0) {
				renewed.add(fire);
				draw(level, fire);
			}
		}
		fires.addAll(renewed);
	}

	static void clear() {
		fires.clear();
	}

	private static boolean isNearWater(ServerLevel level, Vec3 point) {
		BlockPos center = BlockPos.containing(point);
		for (BlockPos pos : BlockPos.betweenClosed(
				center.offset(-WATER_CHECK_RADIUS, -1, -WATER_CHECK_RADIUS),
				center.offset(WATER_CHECK_RADIUS, 1, WATER_CHECK_RADIUS))) {
			if (level.getFluidState(pos).is(FluidTags.WATER)) return true;
		}
		return false;
	}

	//Mismo criterio visual que ZoneManager: partículas en el área en vez de nada, para que un charco de
	//fuego sea una decisión táctica visible y no una trampa invisible.
	private static void draw(ServerLevel level, Fire fire) {
		int samples = 10;
		for (int i = 0; i < samples; i++) {
			double angle = 2 * Math.PI * i / samples;
			double x = fire.origin().x + Math.cos(angle) * fire.radius();
			double z = fire.origin().z + Math.sin(angle) * fire.radius();
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, x, fire.origin().y + 0.1, z, 1, 0, 0, 0, 0);
		}
	}

	private static void broadcast(ServerLevel level, Component message) {
		for (ServerPlayer player : level.players()) player.sendSystemMessage(message);
	}
}
