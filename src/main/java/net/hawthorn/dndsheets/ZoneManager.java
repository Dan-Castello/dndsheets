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
 * <p>Zonas persistentes: un área que se <em>coloca</em> y sigue ahí durante asaltos, dañando a quien
 * empiece su turno dentro. Cubre tanto los muros (Muro de Fuego, Barrera de Hojas) como los efectos de
 * área que duran (Rayo de Luna, Nube Mortal, Guardianes Espirituales).</p>
 *
 * <p>Es una capacidad distinta de las formas de área de {@link SpellCastManager}, aunque compartan la
 * geometría: una línea o un cono se emiten desde el lanzador y se resuelven <em>una vez</em>. Lo que
 * distingue a una zona es la <b>persistencia</b>, no la forma — por eso la forma es un campo más y no
 * una clase aparte, y por eso {@code inShape} se reutiliza tal cual.</p>
 *
 * <p>El muro no coloca bloques reales: cambiar el mundo obligaría a limpiarlo después y a decidir qué
 * pasa si alguien lo pica o si el chunk se descarga. Se guarda como una región y se comprueba al empezar
 * cada turno, que es exactamente cuando 5e dice que hay que tirar la salvación. La geometría es la misma
 * {@code inShape("wall", ...)} que ya se comprueba en el self-test.</p>
 *
 * <p>Estado en memoria y por encuentro, igual que el orden de turnos: un muro no debería sobrevivir a un
 * reinicio del servidor, porque tampoco sobrevive el combate en el que se lanzó.</p>
 */
public class ZoneManager {

	/**
	 * @param origin      base del muro, a la altura de los pies del lanzador (la altura se cuenta hacia
	 *                    arriba desde ahí, ver {@code SpellCastManager.WALL_HEIGHT}).
	 * @param casterId    de quién es: hace falta para retirarlo si pierde la concentración.
	 */
	/**
	 * @param shape          geometría, la misma que entiende {@code SpellCastManager.inShape}:
	 *                       {@code wall}, {@code sphere}, {@code cone}, {@code line}.
	 * @param followsCaster  la zona se recentra sobre el lanzador cada asalto (Guardianes Espirituales).
	 *                       Sin esto habría que elegir entre no tener ese hechizo o mentir sobre él.
	 */
	public record Zone(UUID casterId, String spellName, Vec3 origin, Vec3 direction, double size,
	                   String shape, boolean followsCaster,
	                   String dice, String damageType, String saveAbility, int saveDc, boolean halfOnSave,
	                   int roundsRemaining) {

		Zone tick() {
			return new Zone(casterId, spellName, origin, direction, size, shape, followsCaster,
				dice, damageType, saveAbility, saveDc, halfOnSave, roundsRemaining - 1);
		}

		Zone movedTo(Vec3 newOrigin) {
			return new Zone(casterId, spellName, newOrigin, direction, size, shape, followsCaster,
				dice, damageType, saveAbility, saveDc, halfOnSave, roundsRemaining);
		}
	}

	private static final List<Zone> active = new ArrayList<>();

	//Duración por defecto: 1 minuto de 5e = 10 asaltos, que es lo que dura la mayoría de los muros.
	public static final int DEFAULT_ROUNDS = 10;

	/**
	 * @param aimPoint dónde apunta el lanzador (choque del rayo con el terreno), o {@code null} para
	 *                 colocarla justo delante. Una zona que sigue al lanzador (Guardianes Espirituales) lo
	 *                 ignora: nace centrada en él y se recentra al cerrar cada asalto.
	 */
	public static void place(ServerPlayer caster, SpellRegistry.Spell spell, int saveDc, Vec3 aimPoint) {
		Zone zone = zoneAt(caster, spell, saveDc, aimPoint);
		active.add(zone);

		if (caster.level() instanceof ServerLevel level) draw(level, zone);
		ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.zone_placed",
			SheetLoader.characterNameOf(SheetLoader.getServerSheet(caster.getStringUUID()), caster), ContentNames.of(spell.name()))
			.withStyle(ChatFormatting.DARK_PURPLE));
	}

	/**
	 * <p>Agachado + clic con un báculo de zona: dibuja dónde caería <b>sin colocarla</b> — no gasta espacio
	 * de conjuro, ni acción, ni entra en {@code active}.</p>
	 *
	 * <p>Sale de {@link #zoneAt} y se pinta con el mismo {@link #draw} que la de verdad, y eso no es un
	 * ahorro de líneas: una previsualización calculada aparte es una que puede mentir. Un muro que se
	 * enseña dos bloques a la izquierda de donde va a caer es peor que no enseñar nada, porque encima te
	 * hace confiar.</p>
	 */
	public static void preview(ServerPlayer caster, SpellRegistry.Spell spell, Vec3 aimPoint) {
		if (caster.level() instanceof ServerLevel level) draw(level, zoneAt(caster, spell, 0, aimPoint));
	}

	//La zona que saldría de este lanzamiento, sin colocarla: lo que place() registra y lo que preview()
	//enseña salen de aquí, para que no puedan discrepar.
	private static Zone zoneAt(ServerPlayer caster, SpellRegistry.Spell spell, int saveDc, Vec3 aimPoint) {
		//El eje de una zona es HORIZONTAL, siempre: la zona se levanta DEL SUELO. Con el vector de vista a
		//secas, apuntar al suelo —que es justamente como se coloca una— inclinaba el eje hacia abajo, así
		//que el muro se hundía en el terreno y su "along" (ver SpellCastManager.inShape) dejaba de cuadrar
		//con nadie: la zona se colocaba, se anunciaba en el chat y no golpeaba a nadie nunca.
		Vec3 flat = new Vec3(caster.getViewVector(1.0f).x, 0, caster.getViewVector(1.0f).z);
		//Mirando en vertical exacta no queda dirección horizontal que normalizar: se cae al giro del cuerpo.
		Vec3 direction = flat.lengthSqr() < 1.0e-6 ? Vec3.directionFromRotation(0, caster.getYRot()) : flat.normalize();

		//Donde se apunta, no un par de bloques por delante. Colocarla siempre a dos pasos convertía el muro
		//en algo que solo se podía poner encima de uno mismo: no había forma de tapar un pasillo a diez
		//bloques ni de cortar el paso por delante de un enemigo, que es para lo que existe el conjuro.
		//La base queda a la altura de los pies y no de los ojos —el punto apuntado ES el choque del rayo con
		//el suelo—, que es lo que un muro necesita para no nacer con su mitad inferior enterrada.
		Vec3 ahead = caster.position().add(direction.scale(2.0));
		Vec3 origin = spell.followsCaster() ? caster.position() : aimPoint != null ? aimPoint : ahead;
		return new Zone(caster.getUUID(), spell.name(), origin, direction, spell.aoeRadius(),
			spell.aoeShape(), spell.followsCaster(),
			spell.dice(), spell.damageType(), spell.saveAbility(), saveDc, spell.halfOnSave(), DEFAULT_ROUNDS);
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

		for (Zone wall : new ArrayList<>(active)) {
			if (!SpellCastManager.inShape(wall.shape(), wall.origin(), wall.direction(), wall.size(),
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
			broadcast(level, Component.translatable("chat.dndsheets.spell.zone_tick",
				combatant.name(), wall.spellName(), save.formatted(), wall.saveDc(), amount).withStyle(ChatFormatting.DARK_RED));
			if (amount > 0) combatant.takeDamage(amount);
		}
	}

	/** Llamado al cerrarse un asalto completo: descuenta duración y retira lo que expira. */
	public static void endRound(ServerLevel level) {
		Iterator<Zone> it = active.iterator();
		List<Zone> renewed = new ArrayList<>();
		while (it.hasNext()) {
			Zone wall = it.next().tick();
			//Guardianes Espirituales y similares: la zona va con su lanzador, así que se recentra en él al
			//cerrar el asalto. Si el lanzador ya no está en el mundo, se queda donde estaba en vez de
			//desaparecer sin avisar.
			if (wall.followsCaster()) {
				ServerPlayer owner = level.getServer().getPlayerList().getPlayer(wall.casterId());
				if (owner != null) wall = wall.movedTo(owner.position());
			}
			it.remove();
			if (wall.roundsRemaining() > 0) {
				renewed.add(wall);
				draw(level, wall); //Se redibuja cada asalto: sin esto el muro es invisible salvo el instante en que se lanzó.
			} else {
				broadcast(level, Component.translatable("chat.dndsheets.spell.zone_faded", wall.spellName()).withStyle(ChatFormatting.GRAY));
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
	private static void draw(ServerLevel level, Zone wall) {
		if ("wall".equals(wall.shape())) {
			int samplesAlong = Math.max(4, (int) (wall.size() * 2));
			for (int i = 0; i <= samplesAlong; i++) {
				Vec3 base = wall.origin().add(wall.direction().scale(wall.size() * i / samplesAlong));
				for (double y = 0; y <= SpellCastManager.WALL_HEIGHT; y += 0.5) {
					level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME,
						base.x, base.y + y, base.z, 1, 0, 0, 0, 0);
				}
			}
			return;
		}
		//Cualquier otra forma se marca con el mismo anillo que ya usa un área instantánea: el jugador ya
		//sabe leerlo, y reinventar un dibujo por forma no aporta nada.
		CombatFx.aoeRing(level, wall.origin(), wall.size());
	}

	private static void broadcast(ServerLevel level, Component message) {
		for (ServerPlayer player : level.players()) player.sendSystemMessage(message);
	}
}
