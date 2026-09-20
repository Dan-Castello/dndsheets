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
 * <p>Native Minecraft effects (particles, sounds, on-screen text) to go alongside the textual
 * {@link ChatFeedback} — reuses particles and sounds the game already ships (crit, totem, evoker casting
 * a spell, etc.) instead of inventing new assets, and uses vanilla title/action-bar packets for the
 * important moments that deserve more than a chat line.</p>
 */
public class CombatFx {

	//Spark on landing a hit (weapons, attack spells, monsters). A critical (natural 20) looks and sounds
	//different from a normal graze — before this a natural 20 that tripled damage felt identical to a
	//scratch hit.
	public static void hit(Entity target, boolean critical) {
		hit(target, critical, null);
	}

	//Same as above, but with particles/sound based on the hit's real damage type (fire, cold, poison...)
	//instead of the same generic CRIT sparks for everything — before this a poisonous bite and a normal
	//sword swing looked and sounded exactly the same, with no visual clue of WHAT damage type it was (had
	//to read the chat). A null or unmapped damageType (mundane physical damage: slashing, piercing,
	//bludgeoning, or a fist on the training dummy) falls back to the usual generic spark.
	public static void hit(Entity target, boolean critical, String damageType) {
		HitFx fx = FX_BY_DAMAGE_TYPE.getOrDefault(damageType, DEFAULT_FX);
		playCombo(target, fx, critical ? 2.0 : 1.0, 1.0f, critical ? 1.2f : 1.0f);
		if (critical) {
			particles(target, ParticleTypes.END_ROD, 10, 0.3);
			sound(target, SoundEvents.PLAYER_ATTACK_CRIT, 1.0f, 1.0f);
		}
	}

	//Core (the usual particle/count/spread/sound) + a SMALLER, MORE spread-out accent on top — the same
	//trick the critical already used (CRIT + END_ROD), generalized to the ten damage types: a single
	//spark read flat, two layers (tight center + loose halo) give depth without turning into fireworks.
	//accent==null (DEFAULT_FX) deliberately stays with a single layer: the good old physical hit doesn't
	//need reinventing.
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

	//Keys = damageType exactly as it appears in weapons/monsters/spells.json (see DamageTypes). No entry
	//of its own for physical/slashing/piercing/bludgeoning: that's the good old hit, it stays with
	//DEFAULT_FX instead of repeating the same row thirteen times. Each combo pairs a core particle +
	//accent that ALREADY exist in vanilla and genuinely read as the element (flame + ash, snowflake +
	//frosty mist, spark + flash, cloud + spark, toxic + ink, slime + bubbling, blue soul + rising souls,
	//glow + beam of light, portal + reverse portal, explosion + sweep), instead of just one lone particle.
	private static final Map<String, HitFx> FX_BY_DAMAGE_TYPE = Map.ofEntries(
		Map.entry("fire", new HitFx(ParticleTypes.FLAME, 16, 0.35, ParticleTypes.ASH, 8, SoundEvents.FIRECHARGE_USE)),
		Map.entry("cold", new HitFx(ParticleTypes.SNOWFLAKE, 20, 0.4, ParticleTypes.CLOUD, 6, SoundEvents.GLASS_BREAK)),
		Map.entry("lightning", new HitFx(ParticleTypes.ELECTRIC_SPARK, 25, 0.4, ParticleTypes.FLASH, 1, SoundEvents.TRIDENT_THUNDER)),
		Map.entry("thunder", new HitFx(ParticleTypes.CLOUD, 20, 0.4, ParticleTypes.ELECTRIC_SPARK, 10, SoundEvents.GENERIC_EXPLODE)),
		Map.entry("poison", new HitFx(ParticleTypes.SNEEZE, 20, 0.35, ParticleTypes.SQUID_INK, 6, SoundEvents.SPIDER_HURT)),
		Map.entry("acid", new HitFx(ParticleTypes.ITEM_SLIME, 16, 0.35, ParticleTypes.BUBBLE_POP, 10, SoundEvents.GENERIC_EXTINGUISH_FIRE)),
		Map.entry("necrotic", new HitFx(ParticleTypes.SOUL_FIRE_FLAME, 16, 0.35, ParticleTypes.SOUL, 10, SoundEvents.SOUL_ESCAPE)),
		Map.entry("radiant", new HitFx(ParticleTypes.GLOW, 20, 0.4, ParticleTypes.END_ROD, 8, SoundEvents.AMETHYST_BLOCK_CHIME)),
		Map.entry("psychic", new HitFx(ParticleTypes.PORTAL, 25, 0.4, ParticleTypes.REVERSE_PORTAL, 12, SoundEvents.ENDERMAN_TELEPORT)),
		Map.entry("force", new HitFx(ParticleTypes.EXPLOSION, 6, 0.3, ParticleTypes.SWEEP_ATTACK, 2, SoundEvents.ANVIL_LAND))
	);

	//Smoke on defeating a summoned monster (doesn't go through LivingEntity#die(), so Minecraft doesn't add it on its own).
	public static void defeated(Entity target) {
		particles(target, ParticleTypes.POOF, 20, 0.4);
		sound(target, SoundEvents.GENERIC_EXPLODE, 0.5f, 1.4f);
	}

	//Purple swirl + evoker sound when casting a spell with no declared school. Still used by a monster's
	//cast (MonsterActionManager) and a potion's (ConsumableManager), which have no SpellRegistry.Spell to
	//pull the school from.
	public static void spellCast(Entity caster) {
		spellCast(caster, MagicSchool.UNKNOWN);
	}

	//Cast based on the spell's SCHOOL, not its damage type. The two answer different questions: damage
	//type already distinguishes the impact (see FX_BY_DAMAGE_TYPE), but half the SRD deals no damage at
	//all — Detect Magic, Invisibility, Dispel Magic — and until now all 87 spells in the pack started
	//with the SAME purple swirl. The moment a spell is actually seen is when it's cast, and that was
	//exactly the moment that distinguished nothing.
	public static void spellCast(Entity caster, MagicSchool school) {
		HitFx fx = FX_BY_SCHOOL.getOrDefault(school, DEFAULT_CAST_FX);
		sound(caster, fx.sound(), 1.0f, castPitch(school));

		if (!(caster.level() instanceof ServerLevel level)) {
			//No server level means no directed particles; the sound is left, which is already more than before.
			return;
		}

		Vec3 look = caster.getLookAngle();
		Vec3 hands = caster.getEyePosition().subtract(0, 0.3, 0).add(look.scale(0.7));
		Vec3[] basis = basisFor(look);

		//1) The core bursts in the hands: the sphere is small and fast, i.e. "this has just been released".
		burstShell(level, fx.particle(), hands, 0.15, 14, 0.4);

		//2) A conical jet TOWARD WHERE THEY'RE LOOKING. This is the layer that was missing: with no
		//direction, casting a spell and being hit by one looked the same, and there was no way to tell
		//which way it came out.
		for (int i = 0; i < 14; i++) {
			Vec3 spread = onCircle(basis, 2 * Math.PI * i / 14, 0.22);
			directed(level, fx.particle(), hands, look.add(spread), 0.6);
		}

		//3) A shockwave at the caster's feet, three rings growing over consecutive ticks. It stays where
		//it was cast (the position is captured now) instead of following the caster: a wave chasing
		//whoever released it reads as an aura, not as a blast.
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

	//Vanilla doesn't have eight sounds that read as eight schools of magic, so two pairs landed on the
	//same file and sounded IDENTICAL: abjuration and divination shared the amethyst chime, and the
	//enchantment cast used the same family as the charge-up loop (so it didn't sound like a cast at all).
	//Pitch is what's left to tell them apart without bringing in dedicated audio: divination high and
	//distant ("knowing"), abjuration low and close ("protecting"), necromancy underneath everything.
	//ponytail: separating by pitch only goes so far — two schools still share the same timbre. The real
	//fix is four or five dedicated CC0 .ogg files (see "Attribution > Audio"), not more pitch-shifting.
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
	 * <p><b>The charge-up.</b> While a spell with a casting time is being cast (see {@code CastingManager}),
	 * its school's magic gathers in front of the caster's hands. It's the only part of the animation other
	 * players see — the arm pose is local to the caster, see {@code client.SpellCastAnimator} — so it has
	 * to read from outside as "that one is casting something" with no other clue.</p>
	 *
	 * <p>Three layers, and none of them a handful of still points: a <b>two-armed helix</b> that spins
	 * faster and faster and closes in on the hands as it progresses; <b>motes falling inward</b> from
	 * outside, i.e. magic being gathered rather than escaping; and a <b>rising pitch</b>. What makes it
	 * read as accumulation rather than flicker is that the radius SHRINKS — the opposite of an explosion.</p>
	 */
	public static void spellCharge(Entity caster, MagicSchool school, float progress) {
		if (!(caster.level() instanceof ServerLevel level)) return;
		HitFx fx = FX_BY_SCHOOL.getOrDefault(school, DEFAULT_CAST_FX);
		ParticleOptions accent = fx.accent() != null ? fx.accent() : fx.particle();

		Vec3 look = caster.getLookAngle();
		Vec3 hands = caster.getEyePosition().subtract(0, 0.35, 0).add(look.scale(0.7));
		Vec3[] basis = basisFor(look);

		//Two opposite arms: with just one, from a half angle it looks like a single point going back and forth.
		double radius = 0.55 - progress * 0.42;
		double turn = progress * Math.PI * 6;
		for (int arm = 0; arm < 2; arm++) {
			double angle = turn + arm * Math.PI;
			Vec3 offset = onCircle(basis, angle, radius);
			//Tangential velocity (spins) plus a push toward the center (falls into the hand).
			Vec3 tangent = onCircle(basis, angle + Math.PI / 2, 1.0);
			directed(level, fx.particle(), hands.add(offset), tangent.add(offset.normalize().scale(-1.2)), 0.09);
		}

		//One loose mote every two ticks, from a random point in the surroundings toward the hands.
		if (level.getGameTime() % 2 == 0) {
			Vec3 from = hands.add(new Vec3(
				(level.random.nextDouble() - 0.5) * 2.4,
				(level.random.nextDouble() - 0.5) * 1.6,
				(level.random.nextDouble() - 0.5) * 2.4));
			directed(level, accent, from, hands.subtract(from).normalize(), 0.22);
		}

		//Pitch rises with the charge: that's what warns it's about to go off without looking at the bar.
		if (level.getGameTime() % 4 == 0) {
			sound(caster, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.35f, 0.7f + progress * 0.9f);
		}
	}

	//Same record and same playCombo as impacts (tight core + loose accent): no need for a parallel
	//structure for the same thing under a different key. UNKNOWN is deliberately not in the map — it
	//falls back to DEFAULT_CAST_FX, i.e. the exact effect from before schools existed (invariant 8).
	private static final Map<MagicSchool, HitFx> FX_BY_SCHOOL = Map.of(
		//Fire licking upward: this is the pure-damage school and has to read as the most aggressive.
		MagicSchool.EVOCATION, new HitFx(ParticleTypes.FLAME, 20, 0.35, ParticleTypes.LAVA, 3, SoundEvents.FIRECHARGE_USE),
		//Shield: enchant glow closing around, with nothing flying outward.
		MagicSchool.ABJURATION, new HitFx(ParticleTypes.ENCHANT, 24, 0.5, ParticleTypes.END_ROD, 6, SoundEvents.AMETHYST_BLOCK_CHIME),
		//Something arriving from elsewhere: the same poof cloud as a spawn egg plus the sound the evoker
		//uses to prepare its vexes, which is literally summoning.
		MagicSchool.CONJURATION, new HitFx(ParticleTypes.POOF, 18, 0.4, ParticleTypes.CLOUD, 8, SoundEvents.EVOKER_PREPARE_SUMMON),
		//Knowing, not striking: a tall, still glow, and the amethyst chime a pitch higher.
		MagicSchool.DIVINATION, new HitFx(ParticleTypes.GLOW, 18, 0.45, ParticleTypes.END_ROD, 8, SoundEvents.AMETHYST_BLOCK_CHIME),
		//About someone else's mind: hearts and notes, the vocabulary Minecraft already uses for "I like you".
		MagicSchool.ENCHANTMENT, new HitFx(ParticleTypes.HEART, 10, 0.45, ParticleTypes.NOTE, 8, SoundEvents.AMETHYST_BLOCK_RESONATE),
		//What isn't there: portal and reverse portal at once, the pair that already represents the unreal.
		MagicSchool.ILLUSION, new HitFx(ParticleTypes.PORTAL, 26, 0.5, ParticleTypes.REVERSE_PORTAL, 12, SoundEvents.ENDERMAN_TELEPORT),
		//Souls and low smoke. Shares its particle with necrotic damage on purpose: there they really do overlap.
		MagicSchool.NECROMANCY, new HitFx(ParticleTypes.SOUL, 16, 0.35, ParticleTypes.SMOKE, 10, SoundEvents.SOUL_ESCAPE),
		//One thing becomes another: the waxing glint plus the brewing stand's bubbling.
		MagicSchool.TRANSMUTATION, new HitFx(ParticleTypes.WAX_ON, 20, 0.4, ParticleTypes.CRIT, 6, SoundEvents.BREWING_STAND_BREW)
	);

	//Hearts on receiving a healing spell (mode:"heal" in spells.json, see SpellCastManager).
	public static void heal(Entity target) {
		particles(target, ParticleTypes.HEART, 10, 0.4);
		sound(target, SoundEvents.PLAYER_LEVELUP, 0.6f, 1.6f);
	}

	//Spell impact with no known damage type (keeps the old generic fireball look): a soft glow if the
	//save succeeds, flames if it fails.
	public static void spellImpact(Entity target, boolean saved) {
		spellImpact(target, saved, null);
	}

	//Same as above, but a failed save uses the same per-damage-type particles/sound as hit() (see
	//FX_BY_DAMAGE_TYPE) instead of plain fire for ANY spell — a failed Ray of Frost no longer looks like a
	//fireball. Succeeding the save keeps the generic glow: "you resisted" is the same visual relief no
	//matter the element.
	public static void spellImpact(Entity target, boolean saved, String damageType) {
		HitFx fx = FX_BY_DAMAGE_TYPE.getOrDefault(damageType, solo(ParticleTypes.FLAME, 20, 0.4, SoundEvents.GENERIC_EXPLODE));

		if (saved) {
			//Succeeding the save looks the same regardless of element: it's the same relief. But now it's
			//a SHIELD — a shell closing inward, negative speed — and not a loose little cloud.
			if (target.level() instanceof ServerLevel level) {
				burstShell(level, ParticleTypes.ENCHANT, center(target), 1.1, 22, -0.25);
			} else {
				particles(target, ParticleTypes.ENCHANT, 10, 0.4);
			}
			return;
		}

		playCombo(target, fx, 1.0, 0.6f, 1.2f);
		if (!(target.level() instanceof ServerLevel level)) return;

		//A shell expanding from the target + a ring at its feet. The two layers together give the "boom"
		//that was missing: before, a spell impact and a spider bite looked with the same particle
		//density, and a level-5 spell was indistinguishable from a scratch.
		burstShell(level, fx.particle(), center(target), 0.3, 18, 0.5);
		burstRing(level, fx.accent() != null ? fx.accent() : fx.particle(),
			target.position().add(0, 0.1, 0), 0.9, 14, 0.06);
	}

	private static Vec3 center(Entity entity) {
		return new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() / 2, entity.getZ());
	}

	//The same dice sound the other rolls already use, for the death save roll (doesn't go through DiceManager).
	public static void diceTick(Entity source) {
		sound(source, DndsheetsModSounds.DICE.get(), 1.0f, 1.0f);
	}

	//On dropping to 0 HP: smoke around, a low pain sound, and an on-screen title just for that player.
	public static void downed(ServerPlayer player) {
		particles(player, ParticleTypes.SMOKE, 25, 0.5);
		sound(player, SoundEvents.PLAYER_HURT, 1.0f, 0.6f);
		title(player,
			Component.translatable("chat.dndsheets.title.downed").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
			Component.translatable("chat.dndsheets.title.downed_subtitle").withStyle(ChatFormatting.RED)
		);
	}

	//Stabilizing (3 successes, natural 20, or revived): totem particles + its own sound, green title.
	public static void saved(ServerPlayer player, Component titleText) {
		particles(player, ParticleTypes.TOTEM_OF_UNDYING, 30, 0.5);
		sound(player, SoundEvents.TOTEM_USE, 1.0f, 1.0f);
		title(player, titleText.copy().withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), null);
	}

	//Smoke on summoning a monster (same effect as a vanilla spawn egg, applied here by hand).
	public static void monsterSpawn(Entity entity) {
		particles(entity, ParticleTypes.POOF, 15, 0.3);
	}

	//Glow + chime on activating a class resource (Rage, Second Wind, Bardic Inspiration...). Before this
	//those managers only sent a chat line: a normal sword hit already sounded and glowed more than
	//activating a class resource did.
	public static void activate(Entity entity) {
		particles(entity, ParticleTypes.END_ROD, 12, 0.4);
		sound(entity, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
	}

	//Particle ring at the real radius of an area spell (Fireball, Spiritual Guardians...) in addition to
	//the burst on each target that spellImpact already does — so the REACH of the explosion is visible,
	//not just who it hit (previously the only way to know was reading the chat, after the fact). Also
	//used by SpellCastManager.previewAoe (crouch + click with the staff) to show the radius BEFORE
	//committing to the actual cast, without redoing the single click in an aim-and-confirm flow.
	//ponytail: a horizontal ring at the impact point, not a 3D sphere.
	/**
	 * <p><b>The real outline of a cone or a line</b>, starting at the caster and going out toward where
	 * they're looking — the exact same geometry {@code SpellCastManager.inShape} uses to decide who it
	 * reaches, which is also why it works as an honest preview.</p>
	 *
	 * <p>Until now these two shapes <b>drew absolutely nothing</b>: {@code aoeRing} is only called for
	 * the sphere, because a ring at the impact point would lie about who a cone reaches. So Cone of Cold
	 * and Lightning Bolt — two of the flashiest spells in the SRD — got resolved with chat and sparks on
	 * each target, with nobody ever seeing the shape.</p>
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
				//A 5e line is one block wide: the axis and its two edges are marked, not a tube.
				directed(level, fx.particle(), spine, axis, 0.15);
				for (int side = -1; side <= 1; side += 2) {
					directed(level, fx.accent() != null ? fx.accent() : fx.particle(),
						spine.add(basis[0].scale(0.5 * side)), axis, 0.08);
				}
				continue;
			}

			//A 5e cone is as wide as it is long, i.e. radius = half the distance traveled. The ring is
			//drawn with more points the farther out it is, so the edge doesn't come apart as it widens.
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

		//The area's real edge, marked with particles that move OUTWARD instead of sitting still: the ring
		//reads as a wave sweeping out to that point, not as a fence painted on the ground.
		burstRing(level, ParticleTypes.END_ROD, floor, radius, samples, 0.05);

		//And a second wave catching up to it from the center a moment later, so it's clear WHERE it came
		//from. Four steps: enough to read as motion, without flooding the chunk with particles.
		for (int step = 1; step <= 4; step++) {
			double wave = radius * step / 4.0;
			DndsheetsMod.queueServerWork(step, () ->
				burstRing(level, ParticleTypes.CRIT, floor, wave, Math.max(8, (int) (wave * 5)), 0.02));
		}
	}

	//Particle trail between the caster and the impact point, following the same core particle the damage
	//type already uses in hit()/spellImpact() — before this, casting a spell looked like two disconnected
	//sparks (one at the caster, one at the target, teleported between each other), with nothing
	//suggesting something TRAVELED from one to the other. It's not a real entity with its own physics:
	//the impact is already decided by SpellCastManager's dice roll, so the trail is purely cosmetic and
	//always "hits" the point the rule already calculated — a projectile with real collision would compete
	//with that roll instead of illustrating it.
	//ponytail: particles staggered by tick instead of a projectile entity with its own renderer — if the
	//trail ever needs to dodge obstacles or look right from odd angles, that's the natural migration, but
	//nobody has asked for it today.
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

		//Steps scale with distance and are capped: with a fixed 8, at thirty blocks the trail came out as
		//four loose blobs, and at three blocks as a smear on the spot. The cap also keeps the trail from
		//still traveling long after the chat has already announced the result.
		double distance = from.distanceTo(to);
		int steps = (int) Math.max(8, Math.min(16, Math.round(distance)));

		//A gentle vertical arc instead of the perfectly straight line from before, which read as a laser
		//pointer and not as something thrown. Proportional to distance (a point-blank spell doesn't trace
		//a parabola) and capped, so at thirty blocks it doesn't fly off way above the target.
		double arc = Math.min(1.5, distance * 0.08);

		Vec3 axis = to.subtract(from).normalize();
		Vec3[] basis = basisFor(axis);
		ParticleOptions trail = fx.accent() != null ? fx.accent() : fx.particle();

		for (int i = 1; i <= steps; i++) {
			double t = i / (double) steps;
			Vec3 point = from.lerp(to, t).add(0, Math.sin(Math.PI * t) * arc, 0);
			DndsheetsMod.queueServerWork(i, () -> {
				//The core TRAVELS toward the impact instead of sitting still where it was born.
				directed(level, fx.particle(), point, axis, 0.3);
				//Plus two strands coiled around it. That's what turns a row of points into something with
				//volume: the eye reads the rotation even though each particle lives for half a tenth of a second.
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

	// --- DIRECTED particle primitives -------------------------------------------------------------
	//
	//The difference between "a handful of dots" and something that looks like magic, in vanilla
	//Minecraft, isn't which particles are chosen: it's whether they have VELOCITY. sendParticles with
	//count=0 doesn't scatter randomly inside a box — it sends ONE particle whose dx/dy/dz is its velocity
	//vector, scaled by speed. Everything in this class used to be drawn with count>0 (random offsets
	//around a point), and that's exactly why every spell came out as a still little cloud: nothing moved
	//in a specific direction.

	private static void directed(ServerLevel level, ParticleOptions type, Vec3 at, Vec3 velocity, double speed) {
		level.sendParticles(type, at.x, at.y, at.z, 0, velocity.x, velocity.y, velocity.z, speed);
	}

	//Two vectors perpendicular to the axis, so circles and helixes can be drawn around any direction (the
	//caster's look direction, a projectile's path) and not just around the world's axes.
	private static Vec3[] basisFor(Vec3 axis) {
		Vec3 reference = Math.abs(axis.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 u = axis.cross(reference).normalize();
		return new Vec3[]{u, axis.cross(u).normalize()};
	}

	/** A point on a circle around {@code axis}, at {@code radius} from the center. */
	private static Vec3 onCircle(Vec3[] basis, double angle, double radius) {
		return basis[0].scale(Math.cos(angle) * radius).add(basis[1].scale(Math.sin(angle) * radius));
	}

	//Horizontal ring whose particles move OUTWARD: a shockwave, not a garland of dots.
	private static void burstRing(ServerLevel level, ParticleOptions type, Vec3 center, double radius, int samples, double outwardSpeed) {
		for (int i = 0; i < samples; i++) {
			double angle = 2 * Math.PI * i / samples;
			Vec3 direction = new Vec3(Math.cos(angle), 0, Math.sin(angle));
			directed(level, type, center.add(direction.scale(radius)), direction, outwardSpeed);
		}
	}

	//Expanding spherical shell. Points are distributed using the golden angle (Fibonacci spiral) instead
	//of by latitude/longitude: distributing by lat/long piles up half the particles at the poles and the
	//sphere ends up looking like two tassels.
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
