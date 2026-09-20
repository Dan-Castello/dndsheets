package net.hawthorn.dndsheets;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Resolves a {@code SpellCastMessage} request: targets whatever the caster is looking at (same raycast
 * Minecraft uses for arrows) and resolves the spell with the same attack-vs-AC or save-vs-DC mechanic
 * already used by weapons and monsters, spending a spell slot.</p>
 */
@Mod.EventBusSubscriber
public class SpellCastManager {
	private static final double RANGE = 30.0;
	private static final Map<String, String> ABILITY_SHEET_KEY = Map.of(
		"str", "strength", "dex", "dexterity", "con", "constitution",
		"int", "intelligence", "wis", "wisdom", "cha", "charisma"
	);

	//ponytail: some cast paths (e.g. the wand pointed at an entity) make Minecraft fire more than one
	//interaction event for the same click, duplicating the cast request. Instead of chasing down the
	//exact event that repeats, a second request from the same player within the same server tick is ignored.
	private static final Map<UUID, Long> lastCastTick = new HashMap<>();

	//Unlike rage/second wind/etc., this entry has no expiration timer of its own (it's only used to
	//deduplicate within the same tick), so without this it stays in the map forever if the player never
	//reconnects.
	@SubscribeEvent
	public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		lastCastTick.remove(event.getEntity().getUUID());
	}

	private static boolean isAoe(SpellRegistry.Spell spell) {
		//A persistent zone has aoeRadius but is NOT resolved as an area on cast: it gets placed (see ZoneManager).
		return "save".equals(spell.mode()) && spell.aoeRadius() > 0 && !spell.isZone();
	}

	//Sneaking + clicking with an area or zone staff: shows where it would land WITHOUT casting the spell
	//(spends no spell slot or turn action), before committing to a normal click.
	public static void previewAoe(ServerPlayer caster, String spellId) {
		SpellRegistry.Spell spell = SpellRegistry.get(spellId);
		if (spell == null) return;
		//A zone is the one that MOST needs a preview and was the only one without one: isAoe() excludes it
		//on purpose (it isn't resolved on cast, it gets placed), so the sneak-click drew nothing and the
		//wall got placed blind — for ten rounds and with the slot already spent, with no way to reposition it.
		if (spell.isZone()) {
			ZoneManager.preview(caster, spell, spell.followsCaster() ? null : findImpactPoint(caster));
			return;
		}
		if (!isAoe(spell)) return;
		//Each shape is previewed with ITS OWN geometry. This used to render with the cone and the line (a
		//ring at the impact point would lie about who it reaches), so the two shapes that MOST need to see
		//the area — your own group is right behind you — were precisely the only ones without a preview.
		if (spell.originatesAtCaster()) CombatFx.shapeOutline(caster, spell.aoeShape(), spell.aoeRadius(), spell.damageType());
		else CombatFx.aoeRing(caster.level(), findImpactPoint(caster), spell.aoeRadius());
	}

	/**
	 * <p>Spends the slot and notifies the client. With no level requested it takes the lowest one that
	 * works (burning a high slot when a low one would do wastes the expensive resource); with
	 * {@code minSlotLevel} it respects the player's choice to upcast. Returns the level actually spent.</p>
	 */
	private static int spendSlot(ServerPlayer caster, JsonObject casterSheet, int spellLevel, int minSlotLevel) {
		int spent = SpellSlots.spend(casterSheet, spellLevel, minSlotLevel);
		//Say it, don't just do it. The slot used to be deducted silently: the only trace was a HUD number
		//going down, and with a cantrip (which by rule spends NOTHING) the reasonable outside conclusion is
		//"this ignores spell slots". Naming the level spent and what's left OF THAT LEVEL is what turns the
		//resource into something felt. Only to the caster: it's their own accounting.
		if (spent > 0) {
			caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.slot_spent",
				spent, SpellSlots.currentSlots(casterSheet)[spent]).withStyle(ChatFormatting.DARK_AQUA));
		}
		//Persisted, not just announced: the spent slot is sheet state (invariant 4). It used to only send
		//the patch to the client, so shutting down the server before the autosave restored the already-spent slot.
		//Saved with saveServer and not saveAndSync because the field patch below already syncs, and it's
		//much cheaper than sending the whole sheet on every cast.
		SheetLoader.saveServer(casterSheet, caster.getStringUUID());
		sendSlotsUpdate(caster, casterSheet);
		return spent;
	}

	/** Cast without choosing a level: the quick-cast staff and any other path that doesn't ask. */
	public static void handleCastRequest(ServerPlayer caster, String spellId) {
		handleCastRequest(caster, spellId, 0);
	}

	/**
	 * <p>A cast is two halves: {@link #prepare} validates and <b>charges</b> (target, spell slot, turn,
	 * counterspell), and {@link #resolve} applies the effect. They used to be fused into a single ~170-line
	 * method until cast time (see {@link CastingManager}) forced real time to pass between the two. The
	 * split isn't cosmetic: it's what lets a spell that takes time to go off be interrupted AFTER it's
	 * already cost its action and its slot, which is exactly what happens at the table.</p>
	 *
	 * @param slotLevel slot level chosen by the player, or 0 for the lowest one that works.
	 */
	public static void handleCastRequest(ServerPlayer caster, String spellId, int slotLevel) {
		CastRequest request = prepare(caster, spellId, slotLevel);
		if (request == null) return;

		//castTicks 0 (the default value, and the one for every pack written before the field existed)
		//resolves right here, in the same tick: exactly the original behavior.
		int castTicks = request.spell().castTicksAt(Config.castTicksPerLevel(), Config.castTicksMax());
		if (castTicks <= 0) {
			resolve(caster, request);
			return;
		}
		CastingManager.begin(caster, request, castTicks);
	}

	/**
	 * <p>What's needed to resolve a spell whose cost has ALREADY been paid. It travels whole through
	 * {@link CastingManager} for the duration of the cast, so the target and impact point are the ones
	 * from when it was aimed — re-aiming on resolve would let a spell correct its aim just by the caster
	 * turning the camera.</p>
	 */
	record CastRequest(SpellRegistry.Spell spell, Entity target, List<Entity> aoeTargets, Vec3 impactPoint,
		boolean isAoe, int proficiency, int abilityMod, String casterName) {}

	/** @return null if the cast is rejected; in that case nothing has been charged beyond what the comment says. */
	private static CastRequest prepare(ServerPlayer caster, String spellId, int slotLevel) {
		long now = caster.level().getGameTime();
		Long last = lastCastTick.put(caster.getUUID(), now);
		if (last != null && last == now) return null;

		SpellRegistry.Spell spell = SpellRegistry.get(spellId);
		if (spell == null) return null;

		JsonObject casterSheet = SheetLoader.getServerSheet(caster.getStringUUID());
		if (casterSheet == null) return null;

		//Cantrips (level 0) are at-will in 5e: they neither require nor spend a slot. Spell.level() existed
		//from the start and wasn't checked here, so a cantrip consumed a slot like any other AND got
		//blocked once they ran out — meaning the caster's basic attack ran dry, which is exactly what a
		//cantrip must NOT do. Computed once and used in the three spots that touched the counter.
		boolean needsSlot = spell.level() > 0;

		//A cantrip can't be upcast (it spends no slot at all), so the choice is ignored instead of turning
		//into an expenditure the rule doesn't allow.
		int requestedLevel = needsSlot ? Math.max(spell.level(), Math.min(slotLevel, SpellSlots.MAX_SPELL_LEVEL)) : 0;

		//Not prepared means not cast, and it's checked first of all: costs no slot, no action, not even a
		//target search. Same criterion as the wrong-target-type case below — punishing with a resource for
		//a rule the mod knows and the player can't see would be the worst possible outcome. Three things
		//always pass through: a cantrip (never needs preparation), a sheet with no field (invariant 8), and
		//a spell the sheet doesn't know — that last one is the staff, which casts without having learned anything.
		if (needsSlot && !SpellRegistry.preparationAllows(casterSheet, spellId)) {
			caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.not_prepared", ContentNames.of(spell.name()))
				.withStyle(ChatFormatting.GRAY));
			return null;
		}

		//Checked here without spending, and spent further down: if the spell is rejected for lack of a
		//target or because it isn't your turn, the slot can't have already been charged.
		if (!SpellSlots.hasSlotFor(casterSheet, requestedLevel)) {
			//The level, not just "you have no slots left": with 4 level-1 slots and none at level 2, the old
			//message contradicted the HUD, which showed 4 available. What's missing is LEVEL 2 or higher,
			//and saying so is the difference between a rule and an apparent bug.
			caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.no_slots_of_level", requestedLevel)
				.withStyle(ChatFormatting.RED));
			return null;
		}

		//Fireball and similar (mode:"save" + aoeRadius>0): there's no need to be looking straight at an
		//entity, the impact point can be empty terrain and it still hits everything within the radius.
		boolean isAoe = isAoe(spell);
		Entity target = null;
		List<Entity> aoeTargets = null;
		Vec3 impactPoint = null;

		if (spell.isZone() || spell.isSelfTargeted() || spell.isSummon()) {
			//No target, but a zone DOES have a point: it's placed where aimed (see ZoneManager.place).
			//buff/temphp/summon target the self and need neither.
			if (spell.isZone() && !spell.followsCaster()) impactPoint = findImpactPoint(caster);
		} else if (isAoe) {
			impactPoint = findImpactPoint(caster);
			aoeTargets = findAoeTargets(caster, impactPoint, spell.aoeRadius(), spell.aoeShape());
			//In an area, whoever the spell can't affect simply falls off the list: the blast passes over
			//them. The cast itself isn't rejected, which is what does happen with a single target.
			//Local copy because "spell" gets reassigned further down when upcasting it, and a lambda can't
			//capture a variable that changes.
			SpellRegistry.Spell cast = spell;
			aoeTargets.removeIf(entity -> !cast.affects(MonsterRegistry.typeOf(entity)));
			//An empty area still gets cast. Rejecting it would require having someone inside the radius just
			//to throw a Fireball, meaning you couldn't torch a forest, blow a hole in a wall, or cover a
			//retreat — things that happen constantly at the table. The slot is spent and the world reacts
			//(see SurfaceManager on resolve); there's simply no one to damage.
		} else {
			target = findTarget(caster);
			if (target == null) {
				//A healing spell with no one in sight is cast on the caster themself (Cure Wounds on the
				//caster is the most common case).
				//Everything else is cast AT THE GROUND OR THE AIR instead of being rejected. Requiring a
				//creature in the crosshair turned the spell into a guided weapon: you couldn't fire a warning
				//shot, light a door on fire, illuminate a room, or miss on purpose, and aiming at the ground
				//gave the same "no target" as not aiming at anything. At the table, aiming is free and the
				//spell still goes off.
				if ("heal".equals(spell.mode())) target = caster;
				else impactPoint = findImpactPoint(caster);
			}
			//Wrong target type: a warning is sent and the slot is NOT charged. Charging it would punish for
			//a rule the mod knows and the player can't see: at the table, the DM would say "that's not a
			//humanoid" before you spend anything.
			CreatureType targetType = target != null ? MonsterRegistry.typeOf(target) : null;
			if (targetType != null && !spell.affects(targetType)) {
				caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.wrong_target_type",
					ContentNames.of(spell.name()), nameOf(target), targetType.label()).withStyle(ChatFormatting.GRAY));
				return null;
			}
		}

		//Previously, casting an attack/save spell against a monster NEVER started turn mode by itself
		//(unlike a weapon hit, see CombatManager.autoStartCombatIfNeeded) — tryAct below lets anything
		//through while no combat is active, so the spell resolved "for free", with no turn or freezing for
		//anyone. Healing doesn't count: healing someone isn't an aggression.
		//A cast that reaches no one isn't aggressing anyone either, so it doesn't start turn mode: there's
		//no one to add to initiative. There are three such cases — a wall (which the first creature to
		//start their turn inside it will trigger), an empty area, and a spell cast into the air or at
		//terrain — and this is also the guard that avoids a null dereference in all three.
		Entity aggressed = isAoe ? (aoeTargets.isEmpty() ? null : aoeTargets.get(0)) : target;
		if (aggressed != null && !"heal".equals(spell.mode()) && !spell.isZone() && !spell.isSelfTargeted() && !spell.isSummon()) {
			CombatManager.autoStartCombatIfNeeded(aggressed, caster);
		}

		//The turn check happens last, with everything else already validated (there's a target, there are
		//slots): that way, if rejected for turn reasons, no resource was charged for an action that wasn't
		//even genuinely attempted.
		if (!TurnManager.tryAct(caster)) {
			TurnManager.notifyCantAct(caster);
			return null;
		}

		String casterName = SheetLoader.characterNameOf(casterSheet, caster);

		//Counterspell: checked before resolving anything. The original caster's slot is spent regardless
		//(in real 5e the spell is considered "used" even if countered), but there's no effect, no
		//concentration, and no twinned second target.
		String counterer = CounterspellManager.findCounterer(caster.level(), caster.position(), caster);
		if (counterer != null) {
			//The slot is spent even if countered (in 5e the spell counts as used), but a cantrip has no slot
			//to spend. The requested one is spent: getting countered doesn't refund the 5th-level slot.
			if (needsSlot) spendSlot(caster, casterSheet, spell.level(), requestedLevel);
			ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.counterspelled", casterName, ContentNames.of(spell.name()), counterer).withStyle(ChatFormatting.DARK_PURPLE));
			return null;
		}

		int proficiency = casterSheet.has("proficiencyBonus") ? safeInt(casterSheet.get("proficiencyBonus").getAsString()) : 2;
		int abilityMod = CombatManager.abilityModifier(casterSheet, ABILITY_SHEET_KEY.getOrDefault(spell.castingAbility(), "intelligence"));

		//A cantrip is at-will and spends no slot: said explicitly because silence reads as the mod not
		//tracking it at all. This is the other half of spendSlot's message.
		if (!needsSlot) {
			caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.cantrip_free", ContentNames.of(spell.name()))
				.withStyle(ChatFormatting.DARK_AQUA));
		}
		//The slot's level isn't known until it's spent: a 3rd-level one was requested, but if those were
		//exhausted it went out at 4th level and the spell upcasts with it. Hence upcasting is applied AFTER
		//spending, not before, using the actual level and not the requested one.
		if (needsSlot) spell = spell.upcastTo(spendSlot(caster, casterSheet, spell.level(), requestedLevel));
		//And whatever can't be upcast by spending a slot scales with the caster instead: a damage cantrip
		//gains a die at levels 5, 11, and 17. No if needed — for everything else it returns the same spell.
		spell = spell.atCasterLevel(SheetLoader.characterLevelOf(casterSheet, caster));

		return new CastRequest(spell, target, aoeTargets, impactPoint, isAoe, proficiency, abilityMod, casterName);
	}

	/**
	 * <p>Applies the effect of an already-paid-for spell. Runs in the same tick as {@link #prepare} when
	 * the spell is instantaneous, and {@code castTicks} later when it isn't.</p>
	 */
	static void resolve(ServerPlayer caster, CastRequest request) {
		SpellRegistry.Spell spell = request.spell();
		Entity target = request.target();
		List<Entity> aoeTargets = request.aoeTargets();
		Vec3 impactPoint = request.impactPoint();
		boolean isAoe = request.isAoe();
		int proficiency = request.proficiency();
		int abilityMod = request.abilityMod();
		String casterName = request.casterName();

		//Re-fetched instead of traveling inside the CastRequest: between prepare and resolve a whole second
		//can have passed, and in that second the player may have switched characters (SheetLoader.sheets is
		//indexed by character id, not by player). Writing onto the old JsonObject would save the temp HP or
		//the weapon buff to the wrong sheet.
		JsonObject casterSheet = SheetLoader.getServerSheet(caster.getStringUUID());
		if (casterSheet == null) return;

		//The cast flash and the trail fire on RESOLVE, not on start. They used to be in prepare(), i.e.
		//BEFORE the charge-up: with cast time, the player saw burst → charge-up helix → impact, the sequence
		//backward with no payoff at the end. An instantaneous spell (castTicks 0) resolves in this same
		//tick, so nothing changes for it.
		CombatFx.spellCast(caster, spell.school());
		//Zone/self-cast/summon have no single point to travel to (see CombatFx.spellTravel).
		if (target != null) CombatFx.spellTravel(caster, target, spell.damageType());
		else if (impactPoint != null) CombatFx.spellTravel(caster, impactPoint, spell.damageType());

		if (spell.concentration()) ConcentrationManager.startConcentrating(caster, spell.name());

		if (spell.isSummon()) {
			SummonManager.summon(caster, spell, proficiency, abilityMod);
		} else if ("buff".equals(spell.mode())) {
			//Granted to the caster themself: it's a self-targeted spell, doesn't need a target in front.
			WeaponBuffManager.grant(casterSheet, spell.name(), spell.dice(), spell.damageType(), ZoneManager.DEFAULT_ROUNDS);
			//grant() only touches the JsonObject, and spendSlot's save already happened before this.
			SheetLoader.saveServer(casterSheet, caster.getStringUUID());
			ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.buff_granted",
				casterName, ContentNames.of(spell.name()), spell.dice()).withStyle(ChatFormatting.GOLD));
		} else if ("temphp".equals(spell.mode())) {
			Combatant self = Combatant.of(caster);
			DiceManager.RollOutcome roll = DiceManager.roll(casterSheet, spell.dice());
			if (self != null && roll.result() != null) {
				self.grantTemporaryHp(roll.result().getValue());
				ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.temp_hp_granted",
					casterName, ContentNames.of(spell.name()), roll.result().getValue()).withStyle(ChatFormatting.GOLD));
			}
		} else if (spell.isZone()) {
			ZoneManager.place(caster, spell, 8 + proficiency + abilityMod, impactPoint);
		} else if (isAoe) {
			//There used to be no visual representation of the radius at all: you found out who it hit by
			//reading chat, after the fact — a particle ring at the actual radius used shows the reach of the
			//blast, not just the impact point (see CombatFx.aoeRing).
			//Sphere: ring at the impact point. Cone and line: their actual outline from the caster, which
			//until now drew nothing (see CombatFx.shapeOutline).
			if (spell.originatesAtCaster()) CombatFx.shapeOutline(caster, spell.aoeShape(), spell.aoeRadius(), spell.damageType());
			else CombatFx.aoeRing(caster.level(), impactPoint, spell.aoeRadius());
			//Twinning a spell that already deals damage across a whole radius wouldn't make sense (5e
			//doesn't allow it either); the pending flag is ignored instead of consumed, so it isn't spent on
			//a cast where it doesn't apply.
			for (Entity aoeTarget : aoeTargets) castSaveSpell(caster, casterName, spell, aoeTarget, proficiency, abilityMod);
			//Surfaces (see SurfaceManager): fire that leaves the ground burning, real water that puts it out
			//or conducts lightning. After resolving the hit, not before — it needs to know who was already
			//fully hit so as not to charge the water+lightning spark to the same target twice.
			if (caster.level() instanceof ServerLevel surfaceLevel && !spell.originatesAtCaster()) {
				SurfaceManager.onAoeImpact(surfaceLevel, impactPoint, spell.damageType(), aoeTargets);
			}
		} else if (target == null) {
			castAtPoint(caster, casterName, spell, impactPoint);
		} else if ("save".equals(spell.mode())) {
			castSaveSpell(caster, casterName, spell, target, proficiency, abilityMod);
			castTwinnedIfPending(caster, casterName, spell, target, proficiency, abilityMod);
		} else if ("heal".equals(spell.mode())) {
			castHealSpell(caster, casterName, spell, target);
			castTwinnedIfPending(caster, casterName, spell, target, proficiency, abilityMod);
		} else {
			castAttackSpell(caster, casterName, spell, target, proficiency, abilityMod);
			castTwinnedIfPending(caster, casterName, spell, target, proficiency, abilityMod);
		}
	}

	/**
	 * <p>The spell went out and reached no one: there's no attack roll or save to resolve, but it
	 * <b>did</b> happen — the slot is already spent and the world reacts the same as under an area. Fire
	 * ignites the ground, lightning electrifies the water ({@link SurfaceManager}), which is what turns
	 * "missing" into a play rather than an error message.</p>
	 */
	private static void castAtPoint(ServerPlayer caster, String casterName, SpellRegistry.Spell spell, Vec3 impactPoint) {
		if (impactPoint == null) return;
		//Radius 1: the ring marks where it landed, without lying about an area of effect this spell doesn't have.
		CombatFx.aoeRing(caster.level(), impactPoint, 1.0);
		ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.hits_ground",
			casterName, ContentNames.of(spell.name())).withStyle(ChatFormatting.GRAY));
		if (caster.level() instanceof ServerLevel level) {
			SurfaceManager.onAoeImpact(level, impactPoint, spell.damageType(), List.of());
		}
	}

	//Metamagic: Twinned Spell (see SorcererMetamagicManager) — if the sorcerer activated it before
	//casting, the SAME spell resolves again against a second valid target nearby, without spending an
	//extra spell slot (the real cost in 5e is sorcery points, which this mod doesn't model).
	private static void castTwinnedIfPending(ServerPlayer caster, String casterName, SpellRegistry.Spell spell, Entity firstTarget, int proficiency, int abilityMod) {
		JsonObject casterSheet = SheetLoader.getServerSheet(caster.getStringUUID());
		if (!SorcererMetamagicManager.consumePending(casterSheet)) return;
		//consumePending only removes the flag from the JsonObject. Without this, the expenditure lived only
		//in RAM until the autosave: restarting before it fired restored Twinned Spell armed and it could be
		//twinned for free again (invariant 4). Arming it got persisted; spending it didn't.
		SheetLoader.saveServer(casterSheet, caster.getStringUUID());

		Entity secondTarget = findNearestOther(caster, firstTarget);
		if (secondTarget == null) {
			caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.twin_no_target").withStyle(ChatFormatting.GRAY));
			return;
		}

		if ("save".equals(spell.mode())) {
			castSaveSpell(caster, casterName, spell, secondTarget, proficiency, abilityMod);
		} else if ("heal".equals(spell.mode())) {
			castHealSpell(caster, casterName, spell, secondTarget);
		} else {
			castAttackSpell(caster, casterName, spell, secondTarget, proficiency, abilityMod);
		}
	}

	//Same valid-target criterion as findAoeTargets (player or spawned monster, alive), but by proximity to
	//the caster instead of raycast — a second target doesn't have to be in the crosshair.
	private static Entity findNearestOther(ServerPlayer caster, Entity excluding) {
		AABB box = new AABB(caster.position(), caster.position()).inflate(RANGE);
		Entity best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (Entity candidate : caster.level().getEntities((Entity) null, box,
				e -> e != excluding && isSpellTarget(caster, e))) {
			double distSq = candidate.position().distanceToSqr(caster.position());
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				best = candidate;
			}
		}
		return best;
	}

	//No attack roll or save: the target (the self if no one was in sight, see above) recovers real HP,
	//just as "real" as the damage from an attack or an attack/save spell. Unlike damage (a plain die roll,
	//no ability score), healing DOES add the casting ability score in real 5e (e.g. "1d8 + $wis" for Cure
	//Wounds) — that's why it's rolled with the CASTER's sheet, not an empty one like attack/save damage does.
	private static void castHealSpell(ServerPlayer caster, String casterName, SpellRegistry.Spell spell, Entity target) {
		JsonObject casterSheet = SheetLoader.getServerSheet(caster.getStringUUID());
		DiceManager.RollOutcome healRoll = DiceManager.roll(casterSheet != null ? casterSheet : new JsonObject(), spell.dice());
		if (healRoll.result() == null) return;

		int amount = healRoll.result().getValue();
		healTarget(target, amount);
		CombatFx.heal(target);
		ChatFeedback.broadcast(caster, ChatFeedback.healResult(casterName, nameOf(target), spell.name(), healRoll.formatted()));
	}

	private static void healTarget(Entity target, int amount) {
		if (target instanceof ServerPlayer player) {
			player.heal(amount);
			return;
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(target);
		if (block == null) {
			//Compatibility mob (an Enemy from another mod or vanilla, with no stat block of its own, see
			//TurnManager.isMonster): unlike one of our own monsters (HP tracked separately in NBT), its real
			//HP IS its vanilla Minecraft health — this used to do nothing (block==null, and that's it), so
			//healing a compatibility mob had no effect at all.
			if (target instanceof LivingEntity living) living.heal(amount);
			return;
		}
		int newHp = Math.min(block.maxHp(), MonsterRegistry.currentHpOf(target) + amount);
		MonsterRegistry.setCurrentHp(target, newHp);
	}

	//Same raycast as findTarget but including blocks: if no entity is in the crosshair, the impact point
	//is where the ray hits terrain (or the end of the range if it hits nothing).
	private static Vec3 findImpactPoint(ServerPlayer caster) {
		Entity direct = findTarget(caster);
		if (direct != null) return direct.position();

		Vec3 eyePos = caster.getEyePosition(1.0f);
		Vec3 endPos = eyePos.add(caster.getViewVector(1.0f).scale(RANGE));
		BlockHitResult blockHit = caster.level().clip(new ClipContext(eyePos, endPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
		return blockHit.getLocation();
	}

	/**
	 * <p>Targets inside the area, with real terrain occlusion: something inside the shape only counts if
	 * there's a clear line of sight free of solid blocks from the effect's origin to it (a wall protects
	 * it, same as in 5e).</p>
	 *
	 * <p>The sphere originates at the impact point; the line and the cone originate at the CASTER and
	 * extend toward where they're looking. That difference is why they exist as their own shapes instead
	 * of being approximated with a radius: a cone turned into a radius would hit everything behind the
	 * caster, including their own party.</p>
	 */
	private static List<Entity> findAoeTargets(ServerPlayer caster, Vec3 center, double radius, String shape) {
		boolean fromCaster = "line".equals(shape) || "cone".equals(shape);
		Vec3 origin = fromCaster ? caster.getEyePosition(1.0f) : center;
		Vec3 direction = caster.getViewVector(1.0f).normalize();

		//The search box covers the shape's maximum reach in any direction; the fine filtering is done by
		//inShape below. Overshooting the search here is cheap and avoids oriented-box geometry.
		AABB box = new AABB(origin, origin).inflate(radius);
		List<Entity> candidates = caster.level().getEntities((Entity) null, box,
			entity -> isSpellTarget(caster, entity));

		List<Entity> hit = new ArrayList<>();
		for (Entity entity : candidates) {
			Vec3 point = entity.getBoundingBox().getCenter();
			if (!inShape(shape, origin, direction, radius, point)) continue;
			if (hasClearPath(caster, origin, point)) hit.add(entity);
		}
		return hit;
	}

	//A 5e cone is as wide as it is long at any point along its length, which gives a half-angle of
	//atan(0.5) ≈ 26.57°. Its cosine is stored for comparing against a dot product and skipping the
	//arccosine for every candidate target of every cast.
	private static final double CONE_COS_HALF_ANGLE = Math.cos(Math.atan(0.5));

	//A 5e line is 5 feet wide = 1 block, so half a block on each side of the axis.
	private static final double LINE_HALF_WIDTH = 0.5;

	//Wall of Fire and friends: 20 feet tall (4 blocks) and 1 foot thick, rounded up to half a block on
	//each side so a hitbox fits and it doesn't need to be crossed with pixel precision.
	static final double WALL_HEIGHT = 4.0;
	static final double WALL_HALF_THICKNESS = 0.5;

	//Package-private, not private: this is pure geometry (no world, no entities) and it's exactly the
	//kind of logic worth pinning down in JsonContentSelfTest — a cone that opens backward doesn't fail
	//anywhere, it just hits your own party instead of the enemy.
	static boolean inShape(String shape, Vec3 origin, Vec3 direction, double length, Vec3 point) {
		Vec3 offset = point.subtract(origin);
		double along = offset.dot(direction); //Projection onto the axis: negative = behind the caster.

		return switch (shape) {
			//Perpendicular distance to the axis, clamping the projection to the segment so a target past
			//the end of the ray doesn't count just for being near the infinite line.
			case "line" -> along >= 0 && along <= length
				&& offset.subtract(direction.scale(along)).length() <= LINE_HALF_WIDTH;
			//Within range AND within the angle. The zero-length case is already ruled out by along >= 0.
			case "cone" -> along > 0 && offset.length() <= length
				&& along / offset.length() >= CONE_COS_HALF_ANGLE;
			//A wall is a SURFACE, not a cylinder: distance to the axis is measured horizontally only, and
			//height is checked separately. Measuring it in 3D like the line would give a tube, and someone
			//standing right above or below the wall would take damage without ever touching it.
			case "wall" -> {
				if (along < 0 || along > length) yield false;
				double vertical = point.y - origin.y;
				if (vertical < 0 || vertical > WALL_HEIGHT) yield false;
				Vec3 flatOffset = new Vec3(offset.x, 0, offset.z);
				Vec3 flatAxis = new Vec3(direction.x, 0, direction.z).normalize();
				double flatAlong = flatOffset.dot(flatAxis);
				yield flatOffset.subtract(flatAxis.scale(flatAlong)).length() <= WALL_HALF_THICKNESS;
			}
			default -> offset.length() <= length;
		};
	}

	//ponytail: a single ray to the center of the target's hitbox, not several points across its volume or
	//a partial-cover calculation — enough to decide "a full wall blocks it, a gap in the wall doesn't",
	//which is all an area needs to decide. PARTIAL cover is already computed by Cover with its five rays
	//for attack rolls and saves; here the only question is whether the effect reaches or not.
	private static boolean hasClearPath(ServerPlayer caster, Vec3 from, Vec3 to) {
		return !Cover.isBlocked(caster.level(), from, to, caster);
	}

	/**
	 * <p>A spell's valid target: <b>any living creature</b>. It used to be only players and enemies, and
	 * that left half the table untouchable — a cow, a villager, a horse, or a tamed wolf couldn't be
	 * burned, healed, or put to sleep, and the spell was rejected as if no one were there. In 5e, aiming
	 * is free: the rule decides what happens to the target, not whether it can be targeted.</p>
	 *
	 * <p>The rest of the path already knew how to handle a creature with no sheet or stat block (AC 10 in
	 * {@link #armorClassOfEntity}, a bare d20 save in {@code SaveRules}, vanilla health in
	 * {@link #applyDamage} and {@link #healTarget}), so nothing more was needed than letting it in.</p>
	 */
	private static boolean isSpellTarget(ServerPlayer caster, Entity entity) {
		return entity != caster && entity.isAlive() && entity instanceof LivingEntity;
	}

	//Same raycast Minecraft uses internally to know what an arrow hit, reused to aim the spell at whatever
	//the caster has in front of them.
	private static Entity findTarget(ServerPlayer caster) {
		Vec3 eyePos = caster.getEyePosition(1.0f);
		Vec3 viewVec = caster.getViewVector(1.0f);
		Vec3 endPos = eyePos.add(viewVec.scale(RANGE));
		AABB searchBox = caster.getBoundingBox().expandTowards(viewVec.scale(RANGE)).inflate(1.0);

		EntityHitResult hit = ProjectileUtil.getEntityHitResult(caster.level(), caster, eyePos, endPos, searchBox,
			entity -> isSpellTarget(caster, entity));
		return hit != null ? hit.getEntity() : null;
	}

	private static void castAttackSpell(ServerPlayer caster, String casterName, SpellRegistry.Spell spell, Entity target, int proficiency, int abilityMod) {
		if (TurnManager.isMonster(target)) MonsterRegistry.faceTarget(target, caster);
		JsonObject casterSheet = SheetLoader.getServerSheet(caster.getStringUUID());
		DiceManager.Advantage advantage = casterSheet != null ? CombatManager.consumeAdvantage(casterSheet) : DiceManager.Advantage.NORMAL;
		int inspiration = BardInspirationManager.consumeAttackBonus(casterSheet);
		DiceManager.AttackRoll attackRoll = DiceManager.rollAttack(new JsonObject(), "1d20 + " + (abilityMod + proficiency + inspiration), advantage);
		if (attackRoll.outcome().result() == null) return;
		if (casterSheet != null) sendAdvantageAndInspirationUpdate(caster);

		//An attack spell aims the same way an arrow does, so cover counts the same way (see Cover).
		Cover cover = Cover.between(caster, target);
		int targetAc = armorClassOfEntity(target) + cover.bonus();
		String targetName = nameOf(target);

		if (attackRoll.criticalMiss() || (!attackRoll.criticalHit() && attackRoll.outcome().result().getValue() < targetAc)) {
			ChatFeedback.broadcast(caster, ChatFeedback.withCover(ChatFeedback.attackResult(casterName, targetName, spell.name(), attackRoll.outcome().formatted(), targetAc, false, null, inspiration), cover));
			return;
		}

		DiceManager.DamageResult damageRoll = DiceManager.rollDamage(new JsonObject(), spell.dice(), attackRoll.criticalHit());
		if (damageRoll.formatted() == null) return;

		applyDamage(target, damageRoll.amount(), spell.damageType());
		CombatFx.hit(target, attackRoll.criticalHit(), spell.damageType());
		ChatFeedback.broadcast(caster, ChatFeedback.withCover(ChatFeedback.attackResult(casterName, targetName, spell.name(), attackRoll.outcome().formatted(), targetAc, true, damageRoll.formatted(), inspiration), cover));
		applySpellEffect(caster, spell, target);
	}

	private static void castSaveSpell(ServerPlayer caster, String casterName, SpellRegistry.Spell spell, Entity target, int proficiency, int abilityMod) {
		if (TurnManager.isMonster(target)) MonsterRegistry.faceTarget(target, caster);
		//Cover, real DC, save, and final damage: same rules, and the same code, as when the caster is a
		//monster (see SaveRules).
		SaveRules.Outcome save = SaveRules.resolve(caster, target, spell.saveAbility(),
			8 + proficiency + abilityMod, spell.dice(), spell.halfOnSave());
		if (save == null) return;
		boolean saved = save.saved();

		CombatFx.spellImpact(target, saved, spell.damageType());
		ChatFeedback.broadcast(caster, ChatFeedback.withLegendaryResistance(
			ChatFeedback.withCover(ChatFeedback.saveResult(casterName, nameOf(target), spell.name(),
				save.roll().formatted(), save.dc(), saved, save.label(), save.damageFormatted()), save.cover()),
			save.legendaryResistance(), MonsterRegistry.legendaryResistancesLeft(target)));

		if (save.finalDamage() > 0) applyDamage(target, save.finalDamage(), spell.damageType());
		//The effect depends on the SAVE, not the damage: in 5e whether a condition sticks is decided by
		//whether the target beat the roll, and there are whole spells that deal no damage at all (Hold
		//Person, Sleep). This used to hang off "finalDamage > 0", so a condition-only spell applied nothing
		//and one that did deal damage imposed its condition even on someone who had beaten the save.
		if (!saved) applySpellEffect(caster, spell, target);
	}

	//Same pattern as MonsterActionManager.applyEffectFromHit: if the spell carries appliesEffect (see
	//SpellRegistry.Spell) and it actually connected (called only when there was already damage > 0), it
	//attaches the status effect to the target. If it was also a concentration spell, the target/effect
	//pair is added to ConcentrationManager's registry so it only reverts if the caster loses concentration.
	private static void applySpellEffect(ServerPlayer caster, SpellRegistry.Spell spell, Entity target) {
		if (!spell.appliesEffect()) return;
		TurnManager.applyEffect(target, spell.effectName(), spell.effectDice(), spell.effectTurns(), caster);
		ChatFeedback.broadcast(target, Component.translatable("chat.dndsheets.monster.effect_applied", nameOf(target), spell.effectName(), spell.effectTurns()).withStyle(ChatFormatting.DARK_PURPLE));
		if (spell.concentration()) ConcentrationManager.attachEffect(caster, target.getId(), spell.effectName());
	}

	//Public: also used by TurnManager for status-effect damage (poison, etc.) at the start of a turn.
	public static void applyDamage(Entity target, int amount) {
		applyDamage(target, amount, null);
	}

	public static void applyDamage(Entity target, int amount, String damageType) {
		//This used to be ~35 lines with an instanceof Player deciding how to apply damage, who tracks HP,
		//and how to kill — exactly the duplication Combatant came to erase, which also left monsters with
		//no resistances against spell damage. A spell always counts as magical.
		Combatant combatant = Combatant.of(target);
		if (combatant != null) {
			combatant.takeDamage(DamageTypes.applyMultiplier(amount, combatant.effectiveDamageMultiplier(damageType, true)));
			return;
		}
		//Compatibility mob (an Enemy from another mod or vanilla, see TurnManager.isMonster): has no stat
		//block or sheet, so its real HP IS its vanilla health — hurt() already fires the vanilla death path
		//on its own (loot, XP, sound), with nothing manual needed.
		if (target instanceof LivingEntity living) living.hurt(target.damageSources().generic(), amount);
	}

	private static int armorClassOfEntity(Entity target) {
		Combatant combatant = Combatant.of(target);
		//10 is 5e's reference AC for something with no armor and no Dexterity bonus: it's what's left for a
		//compatibility mob, which has neither a sheet nor a stat block to pull it from.
		return combatant != null ? combatant.armorClass() : 10;
	}

	private static String nameOf(Entity target) {
		if (target instanceof Player player) {
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			return SheetLoader.characterNameOf(sheet, player);
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(target);
		return block != null ? block.name() : target.getName().getString();
	}

	private static int safeInt(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 2;
		}
	}

	//It used to resend the whole sheet on every spell cast — now only the fields that actually changed.
	//There are TWO, not one: the per-level table and the total that comes from it (see
	//SpellSlots.clientPatch); sending only the total left the Spellbook showing stale columns and offering
	//levels that were already spent.
	private static void sendSlotsUpdate(ServerPlayer player, JsonObject sheet) {
		DndsheetsMod.sendSheetFieldUpdate(player, SpellSlots.clientPatch(sheet));
	}

	//Called right after CombatManager.consumeAdvantage/BardInspirationManager.consumeAttackBonus: sends
	//only the two fields those two methods just touched, same pattern as CombatManager.
	private static void sendAdvantageAndInspirationUpdate(ServerPlayer player) {
		JsonObject patch = new JsonObject();
		patch.addProperty("nextAttackAdvantage", "normal");
		patch.add("bardicInspiration", JsonNull.INSTANCE);
		DndsheetsMod.sendSheetFieldUpdate(player, patch);
	}
}
