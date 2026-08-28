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

	//Remolino morado + sonido de evocador al lanzar un hechizo sin escuela declarada. Lo siguen usando el
	//lanzado de un monstruo (MonsterActionManager) y el de una poción (ConsumableManager), que no tienen
	//un SpellRegistry.Spell del que sacar la escuela.
	public static void spellCast(Entity caster) {
		spellCast(caster, MagicSchool.UNKNOWN);
	}

	//Lanzar según la ESCUELA del conjuro, no según su tipo de daño. Los dos responden a preguntas
	//distintas: el tipo de daño ya distingue el impacto (ver FX_BY_DAMAGE_TYPE), pero medio SRD no hace
	//daño ninguno —Detectar Magia, Invisibilidad, Disipar Magia— y hasta ahora los 87 conjuros del pack
	//arrancaban con el MISMO remolino morado. El instante en el que un conjuro se ve es el de lanzarlo, y
	//era justo el que no distinguía nada.
	public static void spellCast(Entity caster, MagicSchool school) {
		HitFx fx = FX_BY_SCHOOL.getOrDefault(school, DEFAULT_CAST_FX);
		sound(caster, fx.sound(), 1.0f, castPitch(school));

		if (!(caster.level() instanceof ServerLevel level)) {
			//Sin nivel de servidor no hay partículas dirigidas; queda el sonido, que ya es más que antes.
			return;
		}

		Vec3 look = caster.getLookAngle();
		Vec3 hands = caster.getEyePosition().subtract(0, 0.3, 0).add(look.scale(0.7));
		Vec3[] basis = basisFor(look);

		//1) El núcleo revienta en las manos: la esfera es pequeña y rápida, o sea "esto ha soltado".
		burstShell(level, fx.particle(), hands, 0.15, 14, 0.4);

		//2) Un chorro cónico HACIA DONDE MIRA. Es la capa que faltaba: sin dirección, lanzar un conjuro y
		//recibir uno se veían igual, y no había forma de saber por dónde había salido.
		for (int i = 0; i < 14; i++) {
			Vec3 spread = onCircle(basis, 2 * Math.PI * i / 14, 0.22);
			directed(level, fx.particle(), hands, look.add(spread), 0.6);
		}

		//3) Onda expansiva a los pies, tres anillos que crecen en ticks seguidos. Se queda donde se lanzó
		//(la posición se captura ahora) en vez de seguir al lanzador: una onda que persigue a quien la
		//soltó se lee como un aura, no como un golpe.
		if (fx.accent() != null) {
			Vec3 feet = caster.position().add(0, 0.1, 0);
			ParticleOptions accent = fx.accent();
			for (int step = 1; step <= 3; step++) {
				double radius = 0.55 * step;
				DndsheetsMod.queueServerWork(step, () ->
					burstRing(level, accent, feet, radius, 10 + (int) (radius * 8), 0.03));
			}
		}
	}

	//Vanilla no tiene ocho sonidos que lean como ocho escuelas de magia, así que dos parejas caían en el
	//mismo fichero y sonaban IDÉNTICAS: abjuración y adivinación compartían la campanilla de amatista, y el
	//disparo de encantamiento usaba la misma familia que el bucle de carga (o sea que no se oía como un
	//disparo). El tono es lo que queda para separarlas sin traerse audio propio: adivinación aguda y lejana
	//("saber"), abjuración grave y cerca ("proteger"), nigromancia por debajo de todo.
	//ponytail: separar por tono llega hasta donde llega — dos escuelas siguen siendo el mismo timbre. La
	//salida de verdad son cuatro o cinco .ogg CC0 propios (ver "Atribución > Audio"), no más pitcheo.
	private static float castPitch(MagicSchool school) {
		return switch (school) {
			case DIVINATION -> 1.5f;
			case ABJURATION -> 0.9f;
			case ENCHANTMENT -> 1.25f;
			case NECROMANCY -> 0.8f;
			default -> 1.0f;
		};
	}

	private static final HitFx DEFAULT_CAST_FX = solo(ParticleTypes.WITCH, 15, 0.4, SoundEvents.EVOKER_CAST_SPELL);

	/**
	 * <p><b>La carga.</b> Mientras un conjuro con tiempo de lanzamiento se conjura (ver
	 * {@code CastingManager}), la magia de su escuela se acumula delante de las manos del lanzador. Es la
	 * única parte de la animación que ven los demás jugadores —la pose de brazos es local a quien lanza,
	 * ver {@code client.SpellCastAnimator}—, así que tiene que leerse desde fuera como "ese está conjurando
	 * algo" sin ninguna otra pista.</p>
	 *
	 * <p>Tres capas, y ninguna es un puñado de puntos quietos: una <b>hélice de dos brazos</b> que gira cada
	 * vez más rápido y se cierra sobre las manos según avanza; <b>motas que caen hacia dentro</b> desde
	 * fuera, o sea magia que se recoge en vez de escaparse; y un <b>tono que sube</b>. Lo que hace que se lea
	 * como acumulación y no como parpadeo es que el radio DECRECE — al revés que una explosión.</p>
	 */
	public static void spellCharge(Entity caster, MagicSchool school, float progress) {
		if (!(caster.level() instanceof ServerLevel level)) return;
		HitFx fx = FX_BY_SCHOOL.getOrDefault(school, DEFAULT_CAST_FX);
		ParticleOptions accent = fx.accent() != null ? fx.accent() : fx.particle();

		Vec3 look = caster.getLookAngle();
		Vec3 hands = caster.getEyePosition().subtract(0, 0.35, 0).add(look.scale(0.7));
		Vec3[] basis = basisFor(look);

		//Dos brazos opuestos: con uno solo, desde medio ángulo parece un punto que va y viene.
		double radius = 0.55 - progress * 0.42;
		double turn = progress * Math.PI * 6;
		for (int arm = 0; arm < 2; arm++) {
			double angle = turn + arm * Math.PI;
			Vec3 offset = onCircle(basis, angle, radius);
			//Velocidad tangencial (gira) más un empujón hacia el centro (cae hacia la mano).
			Vec3 tangent = onCircle(basis, angle + Math.PI / 2, 1.0);
			directed(level, fx.particle(), hands.add(offset), tangent.add(offset.normalize().scale(-1.2)), 0.09);
		}

		//Una mota suelta cada dos ticks, desde un punto al azar del entorno hacia las manos.
		if (level.getGameTime() % 2 == 0) {
			Vec3 from = hands.add(new Vec3(
				(level.random.nextDouble() - 0.5) * 2.4,
				(level.random.nextDouble() - 0.5) * 1.6,
				(level.random.nextDouble() - 0.5) * 2.4));
			directed(level, accent, from, hands.subtract(from).normalize(), 0.22);
		}

		//El tono sube con la carga: es lo que avisa de que está a punto de salir sin mirar la barra.
		if (level.getGameTime() % 4 == 0) {
			sound(caster, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.35f, 0.7f + progress * 0.9f);
		}
	}

	//Mismo record y mismo playCombo que los impactos (núcleo compacto + acento suelto): no hace falta una
	//estructura paralela para lo mismo con otra clave. UNKNOWN no está en el mapa a propósito — cae a
	//DEFAULT_CAST_FX, o sea al efecto exacto de antes de que existieran las escuelas (invariante 8).
	private static final Map<MagicSchool, HitFx> FX_BY_SCHOOL = Map.of(
		//Fuego que prende hacia arriba: es la escuela del daño puro y tiene que leerse como la más agresiva.
		MagicSchool.EVOCATION, new HitFx(ParticleTypes.FLAME, 20, 0.35, ParticleTypes.LAVA, 3, SoundEvents.FIRECHARGE_USE),
		//Escudo: brillo de encantamiento cerrándose alrededor, sin nada que salga disparado.
		MagicSchool.ABJURATION, new HitFx(ParticleTypes.ENCHANT, 24, 0.5, ParticleTypes.END_ROD, 6, SoundEvents.AMETHYST_BLOCK_CHIME),
		//Algo llega de otro sitio: la misma nube del huevo de spawn más el sonido con el que el evocador
		//prepara sus vexes, que es literalmente conjurar.
		MagicSchool.CONJURATION, new HitFx(ParticleTypes.POOF, 18, 0.4, ParticleTypes.CLOUD, 8, SoundEvents.EVOKER_PREPARE_SUMMON),
		//Saber, no golpear: destello alto y quieto, y la campanilla de amatista un tono por encima.
		MagicSchool.DIVINATION, new HitFx(ParticleTypes.GLOW, 18, 0.45, ParticleTypes.END_ROD, 8, SoundEvents.AMETHYST_BLOCK_CHIME),
		//Sobre la mente ajena: corazones y notas, el vocabulario que Minecraft ya usa para "te caigo bien".
		MagicSchool.ENCHANTMENT, new HitFx(ParticleTypes.HEART, 10, 0.45, ParticleTypes.NOTE, 8, SoundEvents.AMETHYST_BLOCK_RESONATE),
		//Lo que no está ahí: portal y antiportal a la vez, que es la pareja que ya representa lo irreal.
		MagicSchool.ILLUSION, new HitFx(ParticleTypes.PORTAL, 26, 0.5, ParticleTypes.REVERSE_PORTAL, 12, SoundEvents.ENDERMAN_TELEPORT),
		//Almas y humo bajo. Comparte partícula con el daño necrótico a propósito: ahí sí coinciden.
		MagicSchool.NECROMANCY, new HitFx(ParticleTypes.SOUL, 16, 0.35, ParticleTypes.SMOKE, 10, SoundEvents.SOUL_ESCAPE),
		//Una cosa se vuelve otra: el destello de encerar más el burbujeo del alambique.
		MagicSchool.TRANSMUTATION, new HitFx(ParticleTypes.WAX_ON, 20, 0.4, ParticleTypes.CRIT, 6, SoundEvents.BREWING_STAND_BREW)
	);

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
		HitFx fx = FX_BY_DAMAGE_TYPE.getOrDefault(damageType, solo(ParticleTypes.FLAME, 20, 0.4, SoundEvents.GENERIC_EXPLODE));

		if (saved) {
			//Superar la salvación se ve igual sea cual sea el elemento: es el mismo alivio. Pero ahora es un
			//ESCUDO —una cáscara que se cierra hacia dentro, velocidad negativa— y no una nubecita suelta.
			if (target.level() instanceof ServerLevel level) {
				burstShell(level, ParticleTypes.ENCHANT, center(target), 1.1, 22, -0.25);
			} else {
				particles(target, ParticleTypes.ENCHANT, 10, 0.4);
			}
			return;
		}

		playCombo(target, fx, 1.0, 0.6f, 1.2f);
		if (!(target.level() instanceof ServerLevel level)) return;

		//Cáscara que se expande desde el objetivo + anillo a sus pies. Las dos capas juntas dan el "boom"
		//que faltaba: antes el impacto de un conjuro y un mordisco de araña se veían con la misma densidad
		//de puntos, y un conjuro de nivel 5 no se distinguía de un arañazo.
		burstShell(level, fx.particle(), center(target), 0.3, 18, 0.5);
		burstRing(level, fx.accent() != null ? fx.accent() : fx.particle(),
			target.position().add(0, 0.1, 0), 0.9, 14, 0.06);
	}

	private static Vec3 center(Entity entity) {
		return new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() / 2, entity.getZ());
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
	/**
	 * <p><b>El contorno real de un cono o una línea</b>, naciendo en el lanzador y saliendo hacia donde
	 * mira — la misma geometría exacta que {@code SpellCastManager.inShape} usa para decidir a quién
	 * alcanza, y por eso vale también como previsualización honesta.</p>
	 *
	 * <p>Hasta ahora estas dos formas <b>no pintaban absolutamente nada</b>: {@code aoeRing} solo se llama
	 * para la esfera, porque un anillo en el punto de impacto mentiría sobre a quién alcanza un cono. Así
	 * que Cono de Frío y Relámpago —dos de los conjuros más vistosos del SRD— se resolvían con el chat y
	 * los chispazos en cada objetivo, sin que nadie viera nunca la forma.</p>
	 */
	public static void shapeOutline(Entity caster, String shape, double length, String damageType) {
		if (!(caster.level() instanceof ServerLevel level) || length <= 0) return;
		HitFx fx = FX_BY_DAMAGE_TYPE.getOrDefault(damageType, DEFAULT_FX);

		Vec3 axis = caster.getLookAngle().normalize();
		Vec3 origin = caster.getEyePosition();
		Vec3[] basis = basisFor(axis);

		int steps = (int) Math.max(4, Math.min(20, Math.round(length * 2)));
		for (int i = 1; i <= steps; i++) {
			double along = length * i / steps;
			Vec3 spine = origin.add(axis.scale(along));

			if ("line".equals(shape)) {
				//Una línea de 5e mide un bloque de ancho: se marca el eje y sus dos bordes, no un tubo.
				directed(level, fx.particle(), spine, axis, 0.15);
				for (int side = -1; side <= 1; side += 2) {
					directed(level, fx.accent() != null ? fx.accent() : fx.particle(),
						spine.add(basis[0].scale(0.5 * side)), axis, 0.08);
				}
				continue;
			}

			//Un cono de 5e es tan ancho como largo, o sea radio = mitad de la distancia recorrida. El anillo
			//se dibuja con más puntos cuanto más lejos, para que el borde no se despegue al abrirse.
			double radius = along * 0.5;
			int samples = Math.max(6, (int) (radius * 6));
			for (int k = 0; k < samples; k++) {
				Vec3 offset = onCircle(basis, 2 * Math.PI * k / samples, radius);
				directed(level, fx.particle(), spine.add(offset), axis, 0.1);
			}
		}
	}

	public static void aoeRing(Level world, Vec3 center, double radius) {
		if (!(world instanceof ServerLevel level) || radius <= 0) return;
		Vec3 floor = new Vec3(center.x, center.y + 0.1, center.z);
		int samples = Math.max(12, (int) (radius * 6));

		//El borde real del área, marcado con partículas que salen HACIA FUERA en vez de quedarse quietas:
		//el anillo se lee como una onda que barre hasta ahí, no como una valla pintada en el suelo.
		burstRing(level, ParticleTypes.END_ROD, floor, radius, samples, 0.05);

		//Y una segunda onda que la alcanza desde el centro un instante después, para que se vea de DÓNDE
		//salió. Cuatro pasos: bastante para leerse como movimiento, sin llenar el chunk de partículas.
		for (int step = 1; step <= 4; step++) {
			double wave = radius * step / 4.0;
			DndsheetsMod.queueServerWork(step, () ->
				burstRing(level, ParticleTypes.CRIT, floor, wave, Math.max(8, (int) (wave * 5)), 0.02));
		}
	}

	//Rastro de partículas entre quien lanza y el punto de impacto, siguiendo la misma partícula núcleo que
	//ya usa el tipo de daño en hit()/spellImpact() — antes de esto, lanzar un hechizo se veía como dos
	//chispazos sin conexión (uno al lanzador, otro al objetivo, teletransportados entre sí), sin nada que
	//sugiriera que algo VIAJÓ de uno a otro. No es una entidad de verdad con física propia: el impacto ya
	//lo decide la tirada de dados de SpellCastManager, así que el rastro es puramente cosmético, siempre
	//"acierta" el punto que la regla ya calculó — un proyectil con colisión real competiría con esa tirada
	//en vez de ilustrarla.
	//ponytail: partículas escalonadas por tick en vez de una entidad de proyectil con renderer propio —
	//si algún día hace falta que el rastro esquive obstáculos o se vea desde ángulos raros, esa es la
	//migración natural, pero hoy nadie lo pidió.
	public static void spellTravel(ServerPlayer caster, Entity target, String damageType) {
		spellTravel(caster, target.getX(), target.getY() + target.getBbHeight() / 2, target.getZ(), damageType);
	}

	public static void spellTravel(ServerPlayer caster, Vec3 to, String damageType) {
		spellTravel(caster, to.x, to.y, to.z, damageType);
	}

	private static void spellTravel(ServerPlayer caster, double toX, double toY, double toZ, String damageType) {
		if (!(caster.level() instanceof ServerLevel level)) return;
		HitFx fx = FX_BY_DAMAGE_TYPE.getOrDefault(damageType, DEFAULT_FX);
		Vec3 from = caster.getEyePosition().add(caster.getLookAngle().scale(0.6));
		Vec3 to = new Vec3(toX, toY, toZ);

		//Los pasos se reparten por distancia y se acotan arriba: con 8 fijos, a treinta bloques el rastro
		//salía como cuatro manchas sueltas y a tres bloques como un borrón en el sitio. El techo evita
		//además que el rastro siga viajando mucho después de que el chat ya cantó el resultado.
		double distance = from.distanceTo(to);
		int steps = (int) Math.max(8, Math.min(16, Math.round(distance)));

		//Arco vertical suave en vez de la recta perfecta de antes, que leía como un puntero láser y no como
		//algo lanzado. Proporcional a la distancia (un conjuro a bocajarro no describe una parábola) y con
		//techo, para que a treinta bloques no salga volando por encima del objetivo.
		double arc = Math.min(1.5, distance * 0.08);

		Vec3 axis = to.subtract(from).normalize();
		Vec3[] basis = basisFor(axis);
		ParticleOptions trail = fx.accent() != null ? fx.accent() : fx.particle();

		for (int i = 1; i <= steps; i++) {
			double t = i / (double) steps;
			Vec3 point = from.lerp(to, t).add(0, Math.sin(Math.PI * t) * arc, 0);
			DndsheetsMod.queueServerWork(i, () -> {
				//El núcleo VIAJA hacia el impacto en vez de quedarse quieto donde nació.
				directed(level, fx.particle(), point, axis, 0.3);
				//Y dos hebras enrolladas a su alrededor. Es lo que convierte una fila de puntos en algo con
				//volumen: el ojo lee la rotación aunque cada partícula viva media décima de segundo.
				double angle = t * Math.PI * 8;
				for (int arm = 0; arm < 2; arm++) {
					Vec3 offset = onCircle(basis, angle + arm * Math.PI, 0.28);
					directed(level, trail, point.add(offset), axis, 0.18);
				}
			});
		}
	}

	public static void actionBar(ServerPlayer player, Component message) {
		player.connection.send(new ClientboundSetActionBarTextPacket(message));
	}

	// --- Primitivas de partículas DIRIGIDAS -------------------------------------------------------
	//
	//La diferencia entre "un puñado de puntos" y algo que parece magia, en Minecraft vanilla, no son las
	//partículas que se eligen: es que tengan VELOCIDAD. sendParticles con count=0 no reparte al azar dentro
	//de una caja — manda UNA partícula cuyo dx/dy/dz es su vector de velocidad, escalado por speed. Todo lo
	//de esta clase se pintaba con count>0 (offsets aleatorios alrededor de un punto), y eso es exactamente
	//por qué cada conjuro salía como una nubecita quieta: nada se movía en una dirección concreta.

	private static void directed(ServerLevel level, ParticleOptions type, Vec3 at, Vec3 velocity, double speed) {
		level.sendParticles(type, at.x, at.y, at.z, 0, velocity.x, velocity.y, velocity.z, speed);
	}

	//Dos vectores perpendiculares al eje, para poder dibujar círculos y hélices alrededor de una dirección
	//cualquiera (la mirada del lanzador, el camino de un proyectil) y no solo alrededor de los ejes del mundo.
	private static Vec3[] basisFor(Vec3 axis) {
		Vec3 reference = Math.abs(axis.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 u = axis.cross(reference).normalize();
		return new Vec3[]{u, axis.cross(u).normalize()};
	}

	/** Punto de una circunferencia alrededor de {@code axis}, a {@code radius} del centro. */
	private static Vec3 onCircle(Vec3[] basis, double angle, double radius) {
		return basis[0].scale(Math.cos(angle) * radius).add(basis[1].scale(Math.sin(angle) * radius));
	}

	//Anillo horizontal cuyas partículas salen HACIA FUERA: una onda expansiva, no una guirnalda de puntos.
	private static void burstRing(ServerLevel level, ParticleOptions type, Vec3 center, double radius, int samples, double outwardSpeed) {
		for (int i = 0; i < samples; i++) {
			double angle = 2 * Math.PI * i / samples;
			Vec3 direction = new Vec3(Math.cos(angle), 0, Math.sin(angle));
			directed(level, type, center.add(direction.scale(radius)), direction, outwardSpeed);
		}
	}

	//Cáscara esférica que se expande. Los puntos se reparten con el ángulo áureo (espiral de Fibonacci) en
	//vez de por latitud/longitud: repartir por lat/long amontona la mitad de las partículas en los polos y
	//la esfera se ve como dos borlas.
	private static void burstShell(ServerLevel level, ParticleOptions type, Vec3 center, double radius, int points, double speed) {
		double golden = Math.PI * (3 - Math.sqrt(5));
		for (int i = 0; i < points; i++) {
			double y = points == 1 ? 0 : 1 - (i / (double) (points - 1)) * 2;
			double ringRadius = Math.sqrt(Math.max(0, 1 - y * y));
			double theta = golden * i;
			Vec3 direction = new Vec3(Math.cos(theta) * ringRadius, y, Math.sin(theta) * ringRadius);
			directed(level, type, center.add(direction.scale(radius)), direction, speed);
		}
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
