package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.hawthorn.dndsheets.init.DndsheetsModSounds;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * <p>Efectos nativos de Minecraft (partículas, sonidos, texto en pantalla) para acompañar al
 * {@link ChatFeedback} textual — reutiliza partículas y sonidos que ya trae el juego (crit, totem,
 * evocador lanzando un hechizo, etc.) en vez de inventar assets nuevos, y usa paquetes de título/barra
 * de acción vanilla para los momentos importantes que merecen algo más que una línea de chat.</p>
 */
public class CombatFx {

	//Chispazo al conectar un golpe (armas, hechizos de ataque, monstruos). Un crítico (natural 20) se ve y
	//suena distinto del roce normal — antes de esto un 20 natural que triplicaba el daño se sentía
	//idéntico a un golpe raspado, ver AUDIT_UX.md, Transversal #4.
	public static void hit(Entity target, boolean critical) {
		if (critical) {
			particles(target, ParticleTypes.CRIT, 30, 0.5);
			particles(target, ParticleTypes.END_ROD, 10, 0.3);
			sound(target, SoundEvents.PLAYER_ATTACK_CRIT, 1.0f, 1.0f);
		} else {
			particles(target, ParticleTypes.CRIT, 10, 0.3);
			sound(target, SoundEvents.PLAYER_ATTACK_STRONG, 1.0f, 1.0f);
		}
	}

	//Humo al derrotar a un monstruo invocado (no pasa por LivingEntity#die(), así que Minecraft no lo pone solo).
	public static void defeated(Entity target) {
		particles(target, ParticleTypes.POOF, 20, 0.4);
		sound(target, SoundEvents.GENERIC_EXPLODE, 0.5f, 1.4f);
	}

	//Remolino morado + sonido de evocador al lanzar cualquier hechizo (báculo o Grimorio).
	public static void spellCast(Entity caster) {
		particles(caster, ParticleTypes.WITCH, 15, 0.4);
		sound(caster, SoundEvents.EVOKER_CAST_SPELL, 1.0f, 1.0f);
	}

	//Corazones al recibir un hechizo de curación (mode:"heal" en spells.json, ver SpellCastManager).
	public static void heal(Entity target) {
		particles(target, ParticleTypes.HEART, 10, 0.4);
		sound(target, SoundEvents.PLAYER_LEVELUP, 0.6f, 1.6f);
	}

	//Impacto de hechizo: llamas si falla la salvación (o no hay salvación), destello suave si la supera.
	public static void spellImpact(Entity target, boolean saved) {
		if (saved) {
			particles(target, ParticleTypes.ENCHANT, 10, 0.4);
		} else {
			particles(target, ParticleTypes.FLAME, 20, 0.4);
			sound(target, SoundEvents.GENERIC_EXPLODE, 0.6f, 1.2f);
		}
	}

	//El mismo sonido de dado que ya usan las demás tiradas, para la tirada de salvación de muerte (no pasa por DiceManager).
	public static void diceTick(Entity source) {
		sound(source, DndsheetsModSounds.DICE.get(), 1.0f, 1.0f);
	}

	//Al caer a 0 PG: humo alrededor, sonido de dolor grave, y un título en pantalla solo para ese jugador.
	public static void downed(ServerPlayer player) {
		particles(player, ParticleTypes.SMOKE, 25, 0.5);
		sound(player, SoundEvents.PLAYER_HURT, 1.0f, 0.6f);
		title(player,
			Component.translatable("chat.dndsheets.title.downed").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
			Component.translatable("chat.dndsheets.title.downed_subtitle").withStyle(ChatFormatting.RED)
		);
	}

	//Estabilizarse (3 éxitos, 20 natural, o reanimado): partículas de tótem + su mismo sonido, título verde.
	public static void saved(ServerPlayer player, String titleText) {
		particles(player, ParticleTypes.TOTEM_OF_UNDYING, 30, 0.5);
		sound(player, SoundEvents.TOTEM_USE, 1.0f, 1.0f);
		title(player, Component.literal(titleText).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), null);
	}

	//Humo al invocar un monstruo (mismo efecto que un huevo de spawn vanilla, aquí puesto a mano).
	public static void monsterSpawn(Entity entity) {
		particles(entity, ParticleTypes.POOF, 15, 0.3);
	}

	//Destello + campanilla al activar un recurso de clase (Furia, Segundo Aliento, Inspiración
	//Bárdica...). Antes de esto esos managers solo mandaban una línea de chat: el mismo golpe de espada
	//normal ya sonaba y brillaba más que activar un recurso de clase — ver AUDIT_UX.md, Transversal #1.
	public static void activate(Entity entity) {
		particles(entity, ParticleTypes.END_ROD, 12, 0.4);
		sound(entity, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
	}

	//Anillo de partículas en el radio real de un hechizo de área (Bola de Fuego, Guardianes Espirituales...)
	//además del estallido en cada objetivo que ya hace spellImpact — para que se vea el ALCANCE de la
	//explosión, no solo a quién golpeó (antes solo se sabía leyendo el chat, después del hecho). También
	//lo usa SpellCastManager.previewAoe (agachado + clic con el báculo) para enseñar el radio ANTES de
	//comprometerse al lanzado normal, sin rehacer el clic único en un flujo de apuntar-y-confirmar.
	//ponytail: un anillo horizontal en el punto de impacto, no una esfera 3D.
	public static void aoeRing(Level world, Vec3 center, double radius) {
		if (!(world instanceof ServerLevel level) || radius <= 0) return;
		int samples = Math.max(12, (int) (radius * 6));
		for (int i = 0; i < samples; i++) {
			double angle = 2 * Math.PI * i / samples;
			double x = center.x + radius * Math.cos(angle);
			double z = center.z + radius * Math.sin(angle);
			level.sendParticles(ParticleTypes.END_ROD, x, center.y + 0.1, z, 1, 0, 0, 0, 0);
		}
	}

	public static void actionBar(ServerPlayer player, Component message) {
		player.connection.send(new ClientboundSetActionBarTextPacket(message));
	}

	private static void particles(Entity entity, ParticleOptions type, int count, double spread) {
		if (entity.level() instanceof ServerLevel level) {
			level.sendParticles(type, entity.getX(), entity.getY() + entity.getBbHeight() / 2, entity.getZ(), count, spread, spread, spread, 0.02);
		}
	}

	private static void sound(Entity entity, SoundEvent soundEvent, float volume, float pitch) {
		if (soundEvent == null) return;
		Level level = entity.level();
		if (!level.isClientSide()) {
			level.playSound(null, entity.getX(), entity.getY(), entity.getZ(), soundEvent, SoundSource.NEUTRAL, volume, pitch);
		}
	}

	private static void title(ServerPlayer player, Component titleText, Component subtitleText) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 10));
		player.connection.send(new ClientboundSetTitleTextPacket(titleText));
		if (subtitleText != null) player.connection.send(new ClientboundSetSubtitleTextPacket(subtitleText));
	}
}
