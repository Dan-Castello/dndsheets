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

import java.util.Map;

/**
 * <p>Efectos nativos de Minecraft (partículas, sonidos, texto en pantalla) para acompañar al
 * {@link ChatFeedback} textual — reutiliza partículas y sonidos que ya trae el juego (crit, totem,
 * evocador lanzando un hechizo, etc.) en vez de inventar assets nuevos, y usa paquetes de título/barra
 * de acción vanilla para los momentos importantes que merecen algo más que una línea de chat.</p>
 */
public class CombatFx {

	//Chispazo al conectar un golpe (armas, hechizos de ataque, monstruos). Un crítico (natural 20) se ve y
	//suena distinto del roce normal — antes de esto un 20 natural que triplicaba el daño se sentía
	//idéntico a un golpe raspado.
	public static void hit(Entity target, boolean critical) {
		hit(target, critical, null);
	}

	//Igual que arriba, pero con partículas/sonido según el tipo de daño real del golpe (fuego, frío,
	//veneno...) en vez de las mismas chispas CRIT genéricas para todo — antes un mordisco venenoso y un
	//espadazo normal se veían y sonaban exactamente igual, sin ninguna pista visual de QUÉ tipo de daño
	//fue (había que leer el chat). damageType null o sin mapear (daño físico mundano: cortante,
	//perforante, contundente, o el puño en el muñeco de pruebas) cae al chispazo genérico de siempre.
	public static void hit(Entity target, boolean critical, String damageType) {
		HitFx fx = FX_BY_DAMAGE_TYPE.getOrDefault(damageType, DEFAULT_FX);
		playCombo(target, fx, critical ? 2.0 : 1.0, 1.0f, critical ? 1.2f : 1.0f);
		if (critical) {
			particles(target, ParticleTypes.END_ROD, 10, 0.3);
			sound(target, SoundEvents.PLAYER_ATTACK_CRIT, 1.0f, 1.0f);
		}
	}

	//Núcleo (particle/count/spread/sound de siempre) + un acento MÁS chico y MÁS disperso encima — el
	//mismo truco que ya usaba el crítico (CRIT + END_ROD), generalizado a los diez tipos de daño: un
	//único chispazo se leía plano, dos capas (centro compacto + halo suelto) da profundidad sin
	//convertirse en fuegos artificiales. accent==null (DEFAULT_FX) se queda con una sola capa, a
	//propósito: el golpe físico de toda la vida no necesita reinventarse.
	private record HitFx(ParticleOptions particle, int count, double spread, ParticleOptions accent, int accentCount, SoundEvent sound) {}

	private static HitFx solo(ParticleOptions particle, int count, double spread, SoundEvent sound) {
		return new HitFx(particle, count, spread, null, 0, sound);
	}

	private static void playCombo(Entity target, HitFx fx, double scale, float volume, float pitch) {
		particles(target, fx.particle(), (int) Math.round(fx.count() * scale), fx.spread());
		if (fx.accent() != null) particles(target, fx.accent(), (int) Math.round(fx.accentCount() * scale), fx.spread() * 1.6);
		sound(target, fx.sound(), volume, pitch);
	}

	private static final HitFx DEFAULT_FX = solo(ParticleTypes.CRIT, 10, 0.3, SoundEvents.PLAYER_ATTACK_STRONG);

	//Claves = damageType tal cual aparece en weapons/monsters/spells.json (ver DamageTypes). Sin entrada
	//propia para fisico/cortante/perforante/contundente: ese es el golpe "de toda la vida", se queda con
	//DEFAULT_FX en vez de repetir la misma fila trece veces. Cada combo empareja partícula núcleo + acento
	//que YA existen en vanilla y de verdad leen como el elemento (llama + brasa, copo + vaho helado, chispa
	//+ destello, nube + chispa, tóxico + tinta, gel + burbujeo, alma azul + almas subiendo, brillo + rayo
	//de luz, portal + antiportal, explosión + tajo), en vez de una partícula sola y listo.
	private static final Map<String, HitFx> FX_BY_DAMAGE_TYPE = Map.ofEntries(
		Map.entry("fuego", new HitFx(ParticleTypes.FLAME, 16, 0.35, ParticleTypes.ASH, 8, SoundEvents.FIRECHARGE_USE)),
		Map.entry("frio", new HitFx(ParticleTypes.SNOWFLAKE, 20, 0.4, ParticleTypes.CLOUD, 6, SoundEvents.GLASS_BREAK)),
		Map.entry("rayo", new HitFx(ParticleTypes.ELECTRIC_SPARK, 25, 0.4, ParticleTypes.FLASH, 1, SoundEvents.TRIDENT_THUNDER)),
		Map.entry("trueno", new HitFx(ParticleTypes.CLOUD, 20, 0.4, ParticleTypes.ELECTRIC_SPARK, 10, SoundEvents.GENERIC_EXPLODE)),
		Map.entry("veneno", new HitFx(ParticleTypes.SNEEZE, 20, 0.35, ParticleTypes.SQUID_INK, 6, SoundEvents.SPIDER_HURT)),
		Map.entry("ácido", new HitFx(ParticleTypes.ITEM_SLIME, 16, 0.35, ParticleTypes.BUBBLE_POP, 10, SoundEvents.GENERIC_EXTINGUISH_FIRE)),
		Map.entry("necrótico", new HitFx(ParticleTypes.SOUL_FIRE_FLAME, 16, 0.35, ParticleTypes.SOUL, 10, SoundEvents.SOUL_ESCAPE)),
		Map.entry("radiante", new HitFx(ParticleTypes.GLOW, 20, 0.4, ParticleTypes.END_ROD, 8, SoundEvents.AMETHYST_BLOCK_CHIME)),
		Map.entry("psíquico", new HitFx(ParticleTypes.PORTAL, 25, 0.4, ParticleTypes.REVERSE_PORTAL, 12, SoundEvents.ENDERMAN_TELEPORT)),
		Map.entry("fuerza", new HitFx(ParticleTypes.EXPLOSION, 6, 0.3, ParticleTypes.SWEEP_ATTACK, 2, SoundEvents.ANVIL_LAND))
	);

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

	//Impacto de hechizo sin tipo de daño conocido (mantiene el llamazo genérico de siempre): destello suave
	//si supera la salvación, llamas si falla.
	public static void spellImpact(Entity target, boolean saved) {
		spellImpact(target, saved, null);
	}

	//Igual que arriba, pero el fallo de salvación usa las mismas partículas/sonido por tipo de daño que
	//hit() (ver FX_BY_DAMAGE_TYPE) en vez de fuego a secas para CUALQUIER hechizo — un Rayo de Escarcha
	//fallado ya no se ve como una bola de fuego. Superar la salvación se queda con el destello genérico:
	//"resististe" es el mismo alivio visual sin importar el elemento.
	public static void spellImpact(Entity target, boolean saved, String damageType) {
		if (saved) {
			particles(target, ParticleTypes.ENCHANT, 10, 0.4);
			return;
		}
		HitFx fx = FX_BY_DAMAGE_TYPE.getOrDefault(damageType, solo(ParticleTypes.FLAME, 20, 0.4, SoundEvents.GENERIC_EXPLODE));
		playCombo(target, fx, 1.0, 0.6f, 1.2f);
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
	//normal ya sonaba y brillaba más que activar un recurso de clase.
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
