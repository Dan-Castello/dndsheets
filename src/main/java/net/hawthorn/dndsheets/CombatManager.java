package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;

/**
 * <p>Two uses: (1) armor stands act as training dummies, hitting them (melee or ranged) with a
 * configured weapon rolls damage on its own and announces it in chat, without destroying the dummy;
 * (2) in PvP, a hit between players with a configured weapon is resolved as a real 5e attack: an attack
 * roll (1d20 + ability + proficiency) against the target's real AC (10 + Dex mod + real equipped armor)
 * and, on a hit, the real damage is replaced by the configured damage roll. If the weapon isn't
 * configured, nothing is touched and Minecraft behaves as always.</p>
 */
@Mod.EventBusSubscriber
public class CombatManager {

	private record Roll(int amount, String formatted, String weaponName, String characterName) {}
	//autoDetected: non-null only when the item isn't registered by hand (JSON/.toml) but declares real
	//attack damage via a vanilla attribute (see Config.autoDetectWeapon) — compatibility with weapons
	//from other mods (Tinkers' Construct and any other) without needing a JSON entry per item.
	private record IdentifiedWeapon(String id, String name, int enchantBonus, Config.WeaponDefault autoDetected) {}
	private record ResolvedWeapon(String dice, String ability, String damageType) {}

	//Virtual id for the bare-handed hit when a trait grants it a real die (see TraitRegistry): it's not a
	//weapon configured in Config, so it's resolved separately in resolveWeapon/findWeaponExpression.
	private static final String UNARMED_ID = "dndsheets:unarmed";

	//Advantage/disadvantage is set with /dndsheet advantage and consumes itself (reverts to "normal") on
	//that sheet's next attack roll, whether with a weapon, a spell (see SpellCastManager), or the sheet's
	//own attack button (see procedures.RollAnnouncerProcedure) — public for that reason, not just package-private.
	public static DiceManager.Advantage consumeAdvantage(JsonObject sheet) {
		DiceManager.Advantage advantage = DiceManager.advantageFromLabel(sheet.has("nextAttackAdvantage") ? sheet.get("nextAttackAdvantage").getAsString() : "normal");
		sheet.addProperty("nextAttackAdvantage", "normal");
		return advantage;
	}

	@SubscribeEvent
	public static void onAttackEntity(AttackEntityEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!Config.auto(Config.Rule.COMBAT)) return; //Manual: the table rolls and applies everything by hand.
		Player player = event.getEntity();
		Entity target = event.getTarget();

		if (target instanceof ArmorStand) {
			event.setCanceled(true); //Training dummy: doesn't get destroyed or disarmed when hit.
			ItemStack heldItem = player.getMainHandItem();
			if (heldItem.isEmpty()) return;
			Roll roll = computeDamageRoll(player, identifyWeapon(player, heldItem));
			if (roll != null) announce(target, player, roll);
			return;
		}

		if (TurnManager.isCombatTarget(target)) {
			if (Combatant.of(target) == null) {
				//Boss/enemy from another mod, with no representation in the rules (no stat block or
				//sheet): there's no 5e attack/damage roll to resolve, but the hit still hooks into turn
				//mode (starts combat on its own, counts as your action, blocks hitting out of turn) — the
				//real damage is still resolved by Minecraft as-is.
				autoStartCombatIfNeeded(target, player);
				if (!TurnManager.tryAct(player)) {
					event.setCanceled(true);
					TurnManager.notifyCantAct(player);
				}
				return;
			}

			//The weapon is identified BEFORE touching the turn (an unconfigured weapon shouldn't spend
			//anything), but unlike before it no longer just bails out if it's null: a punch with no
			//bare-hand trait (no Martial Arts, no Wild Shape) is still THE player's action this turn —
			//before, it fell completely outside turn mode (never started combat, never spent the turn),
			//so a non-monk player could throw punches indefinitely without "no DM" ever noticing.
			IdentifiedWeapon weapon = identifyWeapon(player, player.getMainHandItem());
			if (weapon != null && blockedByOffhand(weapon, player.getOffhandItem().isEmpty())) {
				event.setCanceled(true); //Really cancels: not even Minecraft's weak punch gets through, it can't be wielded like that.
				player.sendSystemMessage(Component.translatable("chat.dndsheets.combat.needs_both_hands").withStyle(ChatFormatting.RED));
				return; //Wasn't a real attack attempt: doesn't start combat or spend the turn.
			}
			if (weapon != null && blockedByClass(player, weapon)) {
				event.setCanceled(true);
				player.sendSystemMessage(Component.translatable("chat.dndsheets.combat.wrong_class").withStyle(ChatFormatting.RED));
				return;
			}
			if (blockedByCharm(player, target)) {
				event.setCanceled(true);
				player.sendSystemMessage(Component.translatable("chat.dndsheets.condition.charmed_block").withStyle(ChatFormatting.RED));
				return; //Wasn't a valid attack attempt: doesn't start combat or spend the turn.
			}
			autoStartCombatIfNeeded(target, player);
			if (!TurnManager.tryAct(player)) {
				event.setCanceled(true); //Out of turn: not even Minecraft's weak punch gets through.
				TurnManager.notifyCantAct(player);
				return;
			}
			if (weapon == null) return; //No weapon and no bare-hand trait: Minecraft resolves the normal hit, turn already spent.
			event.setCanceled(true); //Resolved as a real encounter, not as Minecraft's hit.
			resolveAttackOnCreature(player, target, weapon, true);
		}
	}

	//With nobody actively running the session, nobody's going to type /dndturns start (or manually add a
	//latecomer): a player's first hit on a monster starts combat if none was active, the same entry point
	//the DM Panel already uses (TurnManager.startAt). Whoever lands that hit goes in as the STARTER and
	//opens the turn order: previously it didn't, and the result was that their attack got lost if they
	//didn't win their own initiative roll — the same click worked or vanished depending on a d20 nobody
	//had asked to roll (see TurnManager.startAt with starter).
	//If combat was ALREADY active but this player never joined the order (arrived after it started),
	//they're added right now — without this they'd never be able to act in that encounter at all. There
	//they're NOT the starter: the encounter already existed and arriving late doesn't earn cutting to the front.
	//Public: also used by SpellCastManager, so attacking with a spell starts combat on its own the same
	//way a weapon hit already does — previously an attack/save spell resolved "for free", with no turn or
	//freeze for anyone, because nothing called it from that side.
	public static void autoStartCombatIfNeeded(Entity target, Player attacker) {
		if (!(target.level() instanceof ServerLevel level)) return;
		if (!TurnManager.isActive()) {
			if (!Config.auto(Config.Rule.TURNS)) return; //Manual: only the DM starts combat.
			TurnManager.startAt(level, target.position(), TurnManager.DEFAULT_RADIUS, attacker);
			return;
		}
		if (attacker instanceof ServerPlayer serverPlayer) TurnManager.addLatePlayerIfMissing(level, serverPlayer);
	}

	//Identifies the ranged weapon by looking at what the player is holding at the moment of impact (fairly
	//reliable, bows and crossbows don't leave the hand when fired). For projectiles that DO leave the hand
	//(e.g. a thrown trident), it falls back to a second attempt via the projectile's entity type: it
	//doesn't carry the original item, so it can't see its enchantments or any custom NBT tag, only the
	//configured default die.
	@SubscribeEvent
	public static void onProjectileImpact(ProjectileImpactEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!Config.auto(Config.Rule.COMBAT)) return; //Manual: the table rolls and applies everything by hand.
		HitResult ray = event.getRayTraceResult();
		if (!(ray instanceof EntityHitResult entityHit)) return;
		Entity target = entityHit.getEntity();
		if (!(event.getEntity() instanceof Projectile projectile) || !(projectile.getOwner() instanceof Player player)) return;

		if (target instanceof ArmorStand) {
			event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY); //Training dummy: doesn't get destroyed when shot.
			IdentifiedWeapon weapon = identifyRangedWeapon(player, projectile);
			if (weapon == null) return;
			Roll roll = computeDamageRoll(player, weapon);
			if (roll != null) announce(target, player, roll);
			return;
		}

		if (TurnManager.isCombatTarget(target)) {
			if (Combatant.of(target) == null) {
				//Same compatibility criterion as onAttackEntity: with no representation in the rules, the
				//shot still hooks into turn mode, but the real damage is resolved by Minecraft as-is.
				autoStartCombatIfNeeded(target, player);
				if (!TurnManager.tryAct(player)) {
					event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
					TurnManager.notifyCantAct(player);
				}
				return;
			}

			//Same order as onAttackEntity: identify the weapon before canceling the event/spending the turn.
			IdentifiedWeapon weapon = identifyRangedWeapon(player, projectile);
			if (weapon == null) return; //Unrecognized projectile: Minecraft behaves as always.
			if (blockedByClass(player, weapon)) {
				player.sendSystemMessage(Component.translatable("chat.dndsheets.combat.wrong_class").withStyle(ChatFormatting.RED));
				return; //Neither resolved as 5e nor is the turn touched: the vanilla shot already left before impact.
			}
			if (blockedByCharm(player, target)) {
				event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
				player.sendSystemMessage(Component.translatable("chat.dndsheets.condition.charmed_block").withStyle(ChatFormatting.RED));
				return;
			}
			event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY); //Resolved as a real encounter, not as Minecraft's impact.
			autoStartCombatIfNeeded(target, player);
			if (!TurnManager.tryAct(player)) { TurnManager.notifyCantAct(player); return; }
			resolveAttackOnCreature(player, target, weapon, false);
		}
	}

	//PvP: resolved as a real 5e attack. If the weapon isn't configured, nothing is touched and Minecraft
	//applies its usual normal damage (none of this interferes with "normal" fights).
	@SubscribeEvent
	public static void onLivingHurt(LivingHurtEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!Config.auto(Config.Rule.COMBAT)) return; //Manual: the table rolls and applies everything by hand.
		if (!(event.getEntity() instanceof Player victim)) return; //The victim must be a player (PvP).

		DamageSource source = event.getSource();
		if (!(source.getEntity() instanceof Player attacker)) return; //Whoever hits must also be a player.

		//The weapon is identified BEFORE touching the turn: if it isn't configured, this isn't even a 5e
		//attack (could be an explosion or a thrown potion attributed to the player before Forge sorts it
		//out) and shouldn't spend their action or get blocked by the turn gating — previously tryAct was
		//called first, so any damage attributed to the player consumed their turn even if it wasn't a real hit.
		boolean melee = !(source.getDirectEntity() instanceof Projectile);
		IdentifiedWeapon weapon = source.getDirectEntity() instanceof Projectile projectile
			? identifyRangedWeapon(attacker, projectile)
			: identifyWeapon(attacker, attacker.getMainHandItem());
		if (weapon == null) return; //Unrecognized weapon: Minecraft's normal damage is left alone, turn untouched.
		if (melee && blockedByOffhand(weapon, attacker.getOffhandItem().isEmpty())) {
			event.setCanceled(true); //Really cancels: it can't be wielded like that, not even Minecraft's normal damage gets through.
			attacker.sendSystemMessage(Component.translatable("chat.dndsheets.combat.needs_both_hands").withStyle(ChatFormatting.RED));
			return;
		}
		if (blockedByCharm(attacker, victim)) {
			if (melee) event.setCanceled(true); //Melee can be canceled in time; an arrow already in flight can't.
			attacker.sendSystemMessage(Component.translatable("chat.dndsheets.condition.charmed_block").withStyle(ChatFormatting.RED));
			return;
		}
		if (blockedByClass(attacker, weapon)) {
			if (melee) event.setCanceled(true); //Melee can be canceled in time; an arrow already in flight can't.
			attacker.sendSystemMessage(Component.translatable("chat.dndsheets.combat.wrong_class").withStyle(ChatFormatting.RED));
			return;
		}

		JsonObject attackerSheet = SheetLoader.getServerSheet(attacker.getStringUUID());
		Combatant target = Combatant.of(victim);
		if (attackerSheet == null || target == null) return;

		//weaponDefault is also checked BEFORE touching the turn: identifyWeapon returns a non-null
		//IdentifiedWeapon for ANY non-empty item in hand, whether or not it's registered in Config — so
		//"holding an unconfigured sword" passed the filter above just the same, and only here was it
		//detected as not a real weapon. Previously tryAct was called first, so that unconfigured hit
		//spent the turn's action for nothing, even though the damage fell back to vanilla anyway.
		ResolvedWeapon weaponDefault = resolveWeapon(attacker, attackerSheet, weapon, SheetLoader.characterLevelOf(attackerSheet, attacker));
		if (weaponDefault == null) return; //Unconfigured weapon: Minecraft's normal damage is left alone, turn untouched.

		//In turn mode, not even PvP with a configured weapon gets a pass: hitting out of your turn (or
		//twice in the same turn) is blocked entirely, not just left unresolved as a 5e attack (otherwise
		//Minecraft would apply its normal damage anyway).
		if (!TurnManager.tryAct(attacker)) {
			event.setCanceled(true);
			TurnManager.notifyCantAct(attacker);
			return;
		}

		AttackOutcome outcome = resolveAttack(attacker, attackerSheet, target, weapon,
			weaponDefault.ability(), weaponDefault.damageType(), melee);
		if (outcome == null) return;
		if (!outcome.hit()) {
			event.setCanceled(true); //Miss: not even Minecraft's armor damage reduction applies, there's no hit.
			ChatFeedback.broadcast(attacker, outcome.message());
			return;
		}

		//Damage is delivered through the event itself, NOT through Combatant.takeDamage: we're already
		//INSIDE Minecraft's damage pipeline and calling it here would recurse. From this point on the
		//target's real armor can still subtract more — Minecraft does that only after this event.
		//Temporary HP is deducted here by hand: this path delivers damage through the event itself, so it
		//can't go through Combatant.takeDamage (it would recurse). See Combatant.absorbWithTemporaryHp.
		int afterTemporary = target.absorbWithTemporaryHp(outcome.damage());
		event.setAmount(afterTemporary);
		ConcentrationManager.onDamageTaken((ServerPlayer) victim, afterTemporary);
		ChatFeedback.broadcast(attacker, outcome.message());
	}

	/**
	 * <p>Pushing someone into lava, or letting them fall, already dealt Minecraft damage — but it never
	 * went through 5e resistances ({@link Combatant#effectiveDamageMultiplier}), the biggest untapped
	 * leverage point in the whole engine: the real physical world (lava, height) and 5e's rules never
	 * talked to each other. Covers player, NPC, and monster alike — unlike {@link #onLivingHurt} above,
	 * which only looks at PvP — because anyone with a non-null {@link Combatant#of} can have a real resistance.</p>
	 *
	 * <p>{@code source.getEntity() == null} is the mark of "no attacker": a hit from another combatant
	 * has already been resolved through its own path ({@link #onLivingHurt}, or a monster's attack in
	 * {@code MonsterActionManager}), and touching it again here would scale it twice.</p>
	 *
	 * <p><b>Deliberate scope:</b> only applies the resistance multiplier to the damage Minecraft already
	 * calculated — it does NOT replace the SRD's fall formula (1d6 per 10 feet) with Minecraft's, which is
	 * more granular and there's no evidence a table would prefer it any less.</p>
	 */
	@SubscribeEvent
	public static void onEnvironmentalDamage(LivingHurtEvent event) {
		if (!Config.auto(Config.Rule.COMBAT)) return; //Manual: the table rolls and applies everything by hand.
		if (event.getEntity().level().isClientSide()) return;
		DamageSource source = event.getSource();
		if (source.getEntity() != null) return; //Has an attacker: not environmental, already resolved through another path.

		Combatant combatant = Combatant.of(event.getEntity());
		if (combatant == null) return; //No sheet, normal Minecraft.

		String damageType = environmentalDamageType(source);
		if (damageType == null) return; //Drowning, void, starvation... nothing the SRD modulates by resistance.

		int scaled = DamageTypes.applyMultiplier((int) event.getAmount(), combatant.effectiveDamageMultiplier(damageType, false));
		event.setAmount(scaled);
	}

	//net.minecraft.world.damagesource.DamageTypes not imported: the same simple name is already taken by
	//net.hawthorn.dndsheets.DamageTypes (same package, used unqualified throughout this file) — importing
	//the vanilla class would clash with that implicit resolution.
	@Nullable
	private static String environmentalDamageType(DamageSource source) {
		if (source.is(net.minecraft.world.damagesource.DamageTypes.LAVA)
			|| source.is(net.minecraft.world.damagesource.DamageTypes.IN_FIRE)
			|| source.is(net.minecraft.world.damagesource.DamageTypes.ON_FIRE)
			|| source.is(net.minecraft.world.damagesource.DamageTypes.HOT_FLOOR)) return "fire";
		if (source.is(net.minecraft.world.damagesource.DamageTypes.FALL)
			|| source.is(net.minecraft.world.damagesource.DamageTypes.FALLING_BLOCK)
			|| source.is(net.minecraft.world.damagesource.DamageTypes.FALLING_ANVIL)
			|| source.is(net.minecraft.world.damagesource.DamageTypes.FALLING_STALACTITE)) return "bludgeoning";
		return null;
	}

	private record AttackOutcome(boolean hit, int damage, MutableComponent message) {}

	/**
	 * <p>Shared core of every player attack roll against a {@link Combatant}, whether another player or a
	 * monster. This used to be two nearly identical methods — this PvP path and
	 * {@code resolveAttackOnCreature} — that only differed in how they read AC, HP, and the target's
	 * name; and that difference had quietly, with nobody deciding it, dropped resistances, the Shield
	 * reaction, and concentration on the monster's side.</p>
	 *
	 * <p>Doesn't apply the damage on purpose: it returns it. The two callers deliver it through paths
	 * that can't be unified without breaking something — PvP is inside Minecraft's {@code LivingHurtEvent}
	 * and uses {@code setAmount}, while a monster tracks its 5e HP separately from the vanilla health attribute.</p>
	 */
	@Nullable
	private static AttackOutcome resolveAttack(Player attacker, JsonObject attackerSheet, Combatant target,
			IdentifiedWeapon weapon, String ability, String damageType, boolean melee) {
		//All sources in ONE single call, never combined piecemeal: see AttackRules.advantageAgainst.
		DiceManager.Advantage advantage = AttackRules.advantageAgainst(attacker, target, melee,
			consumeAdvantage(attackerSheet),
			new Combatant.PlayerCombatant(attacker, attackerSheet).ownAttackAdvantage());

		String expression = "1d20 + $" + ability + " + $prof";
		int inspiration = BardInspirationManager.consumeAttackBonus(attackerSheet);
		if (inspiration > 0) expression = expression + " + " + inspiration;
		DiceManager.AttackRoll attackRoll = DiceManager.rollAttack(attackerSheet, expression, advantage);
		if (attackRoll.outcome().result() == null) return null;
		CombatFx.diceTick(attacker);
		persistAndSendSheetUpdate(attacker);

		String attackerName = SheetLoader.characterNameOf(attackerSheet, attacker);
		//Cover, effective AC, hit, and crit: same rules, and the same code, as when a monster attacks.
		//See AttackRules — being written twice is what left monsters ignoring half a dozen rules, each
		//discovered separately.
		AttackRules.Against result = AttackRules.against(attacker, target, attackRoll, melee);
		int targetAc = result.targetAc();

		if (!result.hit()) {
			return new AttackOutcome(false, 0, ChatFeedback.withCover(ChatFeedback.attackResult(attackerName, target.name(), weapon.name(),
				attackRoll.outcome().formatted(), targetAc, false, null, inspiration), result.cover()));
		}

		boolean critical = result.critical();
		Roll damageRoll = computeDamageRoll(attacker, weapon, critical, advantage, ability, target.entity());
		if (damageRoll == null) return null;

		//A weapon hit counts as magical if the weapon carries any enchantment the mod recognizes (see
		//Config.enchantBonusPerLevelFor). It's the only "magic weapon" signal that exists in Minecraft
		//without inventing a new material, and it's the one that decides half a dozen SRD resistances.
		boolean magical = weapon.enchantBonus() != 0;
		int finalAmount = DamageTypes.applyMultiplier(damageRoll.amount(), target.effectiveDamageMultiplier(damageType, magical));
		CombatFx.hit(target.entity(), critical, damageType);
		return new AttackOutcome(true, finalAmount, ChatFeedback.withCover(ChatFeedback.attackResult(attackerName, target.name(), weapon.name(),
			attackRoll.outcome().formatted(), targetAc, true, damageRoll.formatted(), inspiration), result.cover()));
	}

	//Player attacks a monster spawned by /dndmonsters spawn: same attack-vs-AC as PvP, but the target has
	//no sheet, it has a stat block (MonsterRegistry), and it does carry real HP tracked in its persistent
	//NBT instead of infinite like the armor stand.
	private static void resolveAttackOnCreature(Player attacker, Entity targetEntity, IdentifiedWeapon weapon, boolean melee) {
		if (weapon == null) return;
		Combatant target = Combatant.of(targetEntity);
		if (target == null) return;
		MonsterRegistry.faceTarget(targetEntity, attacker); //Make it clear who it's responding to, hit or miss.

		JsonObject attackerSheet = SheetLoader.getServerSheet(attacker.getStringUUID());
		if (attackerSheet == null) return;

		ResolvedWeapon weaponDefault = resolveWeapon(attacker, attackerSheet, weapon, SheetLoader.characterLevelOf(attackerSheet, attacker));
		String ability = weaponDefault != null ? weaponDefault.ability() : "str";
		String damageType = weaponDefault != null ? weaponDefault.damageType() : "bludgeoning";

		AttackOutcome outcome = resolveAttack(attacker, attackerSheet, target, weapon, ability, damageType, melee);
		if (outcome == null) return;
		if (!outcome.hit()) {
			ChatFeedback.broadcast(attacker, outcome.message());
			return;
		}

		//Remaining HP is calculated BEFORE applying damage, because takeDamage can kill the entity (and
		//then currentHp would no longer say anything useful). The HP suffix stays monster-side only on
		//purpose: in PvP, Minecraft's real armor subtracts more damage AFTER the event, so any number
		//announced there would be a lie.
		int remainingHp = Math.max(0, target.currentHp() - outcome.damage());
		MutableComponent message = outcome.message()
			.append(Component.translatable("chat.dndsheets.combat.hp_suffix", remainingHp, target.maxHp()).withStyle(ChatFormatting.DARK_GRAY));

		target.takeDamage(outcome.damage()); //If it reaches 0, kills the mob and removes it from the turn order — see Combatant.MonsterCombatant.
		if (target.isDefeated()) {
			//Suffix on the SAME line as the hit that was just announced, not a separate second chat line.
			message.append(Component.translatable("chat.dndsheets.combat.defeated_suffix", ContentNames.of(target.name())).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
		}
		ChatFeedback.broadcast(attacker, message);
	}

	//AC bonus for carrying a real shield (+2 in 5e): vanilla player.getArmorValue() NEVER counts it —
	//it only adds up the 4 real armor slots, a shield in the offhand isn't "armor" to Minecraft, vanilla
	//or modded. Detected by class (ShieldItem), not by id, so it works the same with any mod's shield
	//that extends the vanilla class (a common pattern).
	private static final int SHIELD_AC_BONUS = 2;

	//Public: also used by network.SheetSummaryRequestMessage to show the real AC in the DM Panel.
	public static int armorClassOf(Player player, JsonObject sheet) {
		//DM/OP override (/dndsheet setac, see SheetCommand): overrides the normal calculation for a
		//special case (magic item, one-off table rule) without having to lie to Minecraft about the real
		//equipped armor. "auto" (the default case) removes this and returns to the usual calculation.
		if (sheet != null && sheet.has("armorClassOverride")) return sheet.get("armorClassOverride").getAsInt();
		int shieldBonus = player.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem ? SHIELD_AC_BONUS : 0;
		return 10 + abilityModifier(sheet, "dexterity") + (int) player.getArmorValue() + shieldBonus;
	}

	static int abilityModifier(JsonObject sheet, String key) {
		if (!sheet.has(key)) return 0;
		try {
			return Math.floorDiv(Integer.parseInt(sheet.get(key).getAsString()) - 10, 2);
		} catch (RuntimeException e) {
			//RuntimeException, not just NumberFormatException: sheet.get(key) can be a JsonObject/JsonArray
			//if an old sheet was left corrupted before SheetServerMessage started validating types, and
			//.getAsString() on that throws UnsupportedOperationException, not NumberFormatException.
			return 0;
		}
	}

	@Nullable
	private static ItemStack findHeldWeapon(Player player) {
		for (ItemStack candidate : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
			if (candidate.isEmpty()) continue;
			if (Config.weaponDefaultFor(Config.weaponIdOf(candidate)) != null) return candidate;
		}
		return null;
	}

	private static IdentifiedWeapon identifyWeapon(Player player, ItemStack heldItem) {
		if (heldItem == null || heldItem.isEmpty()) return identifyUnarmed(player);
		String itemId = Config.weaponIdOf(heldItem);
		//Only bothers computing auto-detection if the item isn't registered by hand — an explicit
		//registration (JSON/.toml) always wins, so it's not worth reading attributes unless necessary.
		Config.WeaponDefault autoDetected = Config.weaponDefaultFor(itemId) == null ? Config.autoDetectWeapon(heldItem) : null;
		return new IdentifiedWeapon(itemId, heldItem.getHoverName().getString(), enchantmentBonusFor(heldItem), autoDetected);
	}

	//A punch only resolves as a real 5e attack if the character has a trait granting it its own die
	//(e.g. a monk's Martial Arts) or Wild Shape is active; without that, it stays as Minecraft's usual
	//weak hit, same as any unconfigured weapon.
	@Nullable
	private static IdentifiedWeapon identifyUnarmed(Player player) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (unarmedProfileFor(player, sheet, SheetLoader.characterLevelOf(sheet, player)) == null) return null;
		return new IdentifiedWeapon(UNARMED_ID, "content.dndsheets.weapon.unarmed_strike", 0, null);
	}

	//Wild Shape (temporary, see DruidWildShapeManager) overrides any permanent TraitRegistry trait while
	//active — it shouldn't be possible for the two to stack.
	private static TraitRegistry.UnarmedProfile unarmedProfileFor(Player player, JsonObject sheet, int level) {
		if (player instanceof ServerPlayer serverPlayer && DruidWildShapeManager.isShifted(serverPlayer)) {
			return DruidWildShapeManager.unarmedProfile(serverPlayer);
		}
		return TraitRegistry.unarmedProfileFor(sheet, level);
	}

	@Nullable
	private static ResolvedWeapon resolveWeapon(Player player, JsonObject sheet, IdentifiedWeapon weapon, int level) {
		if (UNARMED_ID.equals(weapon.id())) {
			TraitRegistry.UnarmedProfile profile = unarmedProfileFor(player, sheet, level);
			return profile == null ? null : new ResolvedWeapon(profile.dice(), profile.ability(), "bludgeoning");
		}
		Config.WeaponDefault weaponDefault = Config.weaponDefaultFor(weapon.id());
		if (weaponDefault == null) weaponDefault = weapon.autoDetected(); //Compatibility with weapons from other mods, see Config.autoDetectWeapon.
		if (weaponDefault == null) return null;
		//Pact of the Blade (see /dndsheet pact): the warlock uses Charisma to attack and deal damage with
		//their weapon, instead of the weapon's normal ability — the real mechanical difference of this pact.
		String ability = "blade".equals(pactOf(sheet)) ? "cha" : weaponDefault.ability();
		return new ResolvedWeapon(weaponDefault.dice(), ability, weaponDefault.damageType());
	}

	//A "two" weapon (truly two-handed, e.g. a greatsword) can't be wielded with anything else in the
	//other hand — unlike versatile, which just loses the big die and keeps attacking the same, this one
	//simply doesn't attack. Previously this wasn't checked anywhere (a deliberate simplification), and
	//carrying a shield with a greatsword attacked the same, just with the small die.
	private static boolean blockedByOffhand(IdentifiedWeapon weapon, boolean offhandEmpty) {
		if (offhandEmpty || UNARMED_ID.equals(weapon.id())) return false;
		Config.WeaponDefault weaponDefault = Config.weaponDefaultFor(weapon.id());
		if (weaponDefault == null) weaponDefault = weapon.autoDetected();
		return weaponDefault != null && "two".equals(weaponDefault.hands());
	}

	//Weapon restricted by class (optional "classes" field in weapons.json, see Config.WeaponDefault):
	//with no list configured, any class can use it, same as always. Bare hands are never restricted by
	//this — Martial Arts/Wild Shape already decide on their own who can hit unarmed.
	private static boolean blockedByClass(Player player, IdentifiedWeapon weapon) {
		if (UNARMED_ID.equals(weapon.id())) return false;
		Config.WeaponDefault weaponDefault = Config.weaponDefaultFor(weapon.id());
		if (weaponDefault == null) weaponDefault = weapon.autoDetected();
		if (weaponDefault == null) return false;
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		String characterClass = sheet != null && sheet.has("characterClass") ? sheet.get("characterClass").getAsString() : null;
		return !weaponDefault.allowsClass(characterClass);
	}

	/**
	 * <p>Charmed: you can't attack whoever charmed you, but you can attack anyone else. Checked alongside
	 * the rest of this file's wielding/class restrictions, not inside {@code resolveAttack}, because it
	 * has to be decided BEFORE spending the turn or letting vanilla damage through.</p>
	 */
	private static boolean blockedByCharm(Player attacker, Entity target) {
		Combatant combatant = Combatant.of(attacker);
		return combatant != null && combatant.cannotAttack(target);
	}

	@Nullable
	private static String pactOf(JsonObject sheet) {
		if (sheet == null || !sheet.has("warlockPact")) return null;
		//Sheets saved when pacts were Spanish ("cadena"/"hoja"/"vara") still read.
		return switch (sheet.get("warlockPact").getAsString()) {
			case "cadena" -> "chain";
			case "hoja" -> "blade";
			case "vara" -> "tome";
			default -> sheet.get("warlockPact").getAsString();
		};
	}

	//For projectiles no longer in hand (thrown tridents): identifies the weapon by the projectile's
	//entity type, since Minecraft coincidentally uses the same id ("minecraft:trident") for the item and
	//for the thrown entity.
	//Same stats the vanilla bow/crossbow already default to (see dndsheets-common.toml), for any modded
	//bow/crossbow with no explicit entry. Config.autoDetectWeapon (melee) doesn't work here: it reads the
	//attack-damage attribute, which a bow doesn't have — its damage comes from the arrow, not from a
	//weapon attribute.
	private static final Config.WeaponDefault GENERIC_BOW_DEFAULT = new Config.WeaponDefault("1d8", "dex", "physical", "two", null, java.util.List.of());

	//Recognizes a bow/crossbow from ANOTHER mod by its own vanilla class (most bow mods extend
	//BowItem/CrossbowItem to inherit drawing and firing) instead of requiring an exact id in the config —
	//same spirit as Config.autoDetectWeapon, but by item type instead of by attribute.
	@Nullable
	private static ItemStack findGenericBowOrCrossbow(Player player) {
		for (ItemStack candidate : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
			if (candidate.getItem() instanceof net.minecraft.world.item.BowItem || candidate.getItem() instanceof net.minecraft.world.item.CrossbowItem) return candidate;
		}
		return null;
	}

	@Nullable
	private static IdentifiedWeapon identifyRangedWeapon(Player player, Projectile projectile) {
		ItemStack weapon = findHeldWeapon(player);
		if (weapon != null) return identifyWeapon(player, weapon);

		ItemStack genericBow = findGenericBowOrCrossbow(player);
		if (genericBow != null && Config.weaponDefaultFor(Config.weaponIdOf(genericBow)) == null) {
			return new IdentifiedWeapon(Config.weaponIdOf(genericBow), genericBow.getHoverName().getString(), enchantmentBonusFor(genericBow), GENERIC_BOW_DEFAULT);
		}

		String projectileId = ForgeRegistries.ENTITY_TYPES.getKey(projectile.getType()).toString();
		if (Config.weaponDefaultFor(projectileId) == null) return null;
		return new IdentifiedWeapon(projectileId, projectile.getDisplayName().getString(), 0, null);
	}

	private static Roll computeDamageRoll(Player player, IdentifiedWeapon weapon) {
		return computeDamageRoll(player, weapon, false, DiceManager.Advantage.NORMAL, null, null);
	}

	@Nullable
	private static Roll computeDamageRoll(Player player, IdentifiedWeapon weapon, boolean critical, DiceManager.Advantage advantage, String attackAbility, Entity target) {
		if (weapon == null) return null;
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return null;
		int level = SheetLoader.characterLevelOf(sheet, player);

		String expression = findWeaponExpression(player, sheet, weapon, level, player.getOffhandItem().isEmpty());
		if (expression == null) return null; //Not a recognized weapon, nothing to roll.

		if (weapon.enchantBonus() != 0) expression = expression + " + " + weapon.enchantBonus();

		//Barbarian Rage: flat melee damage bonus with Strength (see BarbarianRageManager). A flat number,
		//not a die — safe to concatenate into the same expression (unlike Sneak Attack, which is a die
		//and needs to be rolled separately).
		if ("str".equals(attackAbility) && player instanceof ServerPlayer ragingCandidate && BarbarianRageManager.isRaging(ragingCandidate)) {
			expression = expression + " + " + CharacterRules.rageDamageBonusFor(level);
		}

		DiceManager.DamageResult damage = DiceManager.rollDamage(sheet, expression, critical);
		if (damage.formatted() == null) return null;

		int amount = damage.amount();
		String formatted = damage.formatted();

		//Sneak Attack (and any future "extra die on advantage" trait): rolled SEPARATELY and added in
		//Java, not folded into the same expression — the dice engine (dicebot) doesn't correctly resolve
		//two different dice groups in a single expression (e.g. "1d8 + 2d6"), only group+flat number. The
		//same "critical" is passed to it so it also doubles its own dice on a critical hit, as in 5e.
		if (advantage == DiceManager.Advantage.ADVANTAGE) {
			String sneakAttackDice = TraitRegistry.sneakAttackDiceFor(sheet, level);
			if (sneakAttackDice != null) {
				DiceManager.DamageResult sneak = DiceManager.rollDamage(sheet, sneakAttackDice, critical);
				if (sneak.formatted() != null) {
					amount += sneak.amount();
					formatted = formatted + " + " + Component.translatable("chat.dndsheets.roll.sneak").getString() + " " + sneak.formatted();
				}
			}
		}

		//Hunter's Mark: same "roll separately and add" as Sneak Attack, only if THIS hit lands on the
		//marked target (not just any target).
		if (target != null && player instanceof ServerPlayer possibleRanger && RangerHunterMarkManager.isMarked(possibleRanger, target)) {
			DiceManager.DamageResult mark = DiceManager.rollDamage(sheet, RangerHunterMarkManager.DICE, critical);
			if (mark.formatted() != null) {
				amount += mark.amount();
				formatted = formatted + " + " + Component.translatable("chat.dndsheets.roll.hunters_mark").getString() + " " + mark.formatted();
			}
		}

		//Divine Smite: only on real weapon hits, not bare-handed NOR against a training dummy
		//(target==null is the armor stand path, see announce/the 2-arg computeDamageRoll(player, weapon))
		//— without the target check, a practice swing against the dummy spent a real spell slot exactly
		//like an actual combat hit. It spends the spell slot inside consumeIfPending, so if none are left
		//it simply contributes nothing (and the flag is already cleared, not left pending for the next hit).
		if (target != null && !UNARMED_ID.equals(weapon.id())) {
			String smiteDice = PaladinSmiteManager.consumeIfPending(sheet, target);
			if (smiteDice != null) {
				DiceManager.DamageResult smite = DiceManager.rollDamage(sheet, smiteDice, critical);
				if (smite.formatted() != null) {
					amount += smite.amount();
					formatted = formatted + " + " + Component.translatable("chat.dndsheets.roll.divine_smite").getString() + " " + smite.formatted();
				}
			}
		}

		//Weapon buff with a duration (Divine Favor): unlike Divine Smite it's NOT consumed, it applies to
		//every hit while it lasts. Rolled separately for the same reason as the other extra dice: the
		//dice engine doesn't resolve two different groups in a single expression.
		WeaponBuffManager.Buff buff = WeaponBuffManager.active(sheet);
		if (buff != null) {
			DiceManager.DamageResult extra = DiceManager.rollDamage(sheet, buff.dice(), critical);
			if (extra.formatted() != null) {
				amount += extra.amount();
				//The buff carries the name of the spell that granted it, which since the migration is a
				//language key. It's resolved to plain text here because it goes INSIDE the roll formula,
				//which is a String — see ContentNames.plain and its ceiling.
				formatted = formatted + " + " + ContentNames.plain(buff.name()) + " " + extra.formatted();
			}
		}

		String characterName = SheetLoader.characterNameOf(sheet, player);
		return new Roll(amount, formatted, weapon.name(), characterName);
	}

	private static void announce(Entity target, Player player, Roll roll) {
		//Hit on an armor stand (practice dummy): always hits, with no attack roll involved, so there's
		//never a real crit to announce here.
		CombatFx.hit(target, false);
		CombatFx.diceTick(player);
		ChatFeedback.broadcast(player, ChatFeedback.damageOnly(roll.characterName(), roll.weaponName(), roll.formatted()));
	}

	//Called right after consumeAdvantage/BardInspirationManager.consumeAttackBonus on every attack roll:
	//instead of resending the whole sheet, it sends only the two fields those two methods just touched —
	//previously this happened on EVERY hit of EVERY fight.
	private static void persistAndSendSheetUpdate(Player player) {
		//The three consumptions above mutate the in-memory sheet; without this they only traveled to the
		//client and were left hanging on the 5-minute autosave (invariant 4). This affects the spell slot
		//Divine Smite spends, which is the one that really hurts to lose.
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet != null) SheetLoader.saveServer(sheet, player.getStringUUID());

		JsonObject patch = new JsonObject();
		patch.addProperty("nextAttackAdvantage", "normal");
		patch.add("bardicInspiration", JsonNull.INSTANCE);
		//The smite is consumed on the same hit (see PaladinSmiteManager.consumeIfPending), so it's turned
		//off in the same patch instead of leaving the HUD indicator lit until the next roll.
		patch.add("smitePending", JsonNull.INSTANCE);
		DndsheetsMod.sendSheetFieldUpdate((ServerPlayer) player, patch);
	}

	//Adds the configured bonus (dndsheets-common.toml) for each level of each real enchantment the weapon carries.
	private static int enchantmentBonusFor(ItemStack weapon) {
		int total = 0;
		for (Map.Entry<Enchantment, Integer> entry : EnchantmentHelper.getEnchantments(weapon).entrySet()) {
			String enchantId = ForgeRegistries.ENCHANTMENTS.getKey(entry.getKey()).toString();
			Integer perLevel = Config.enchantBonusPerLevelFor(enchantId);
			if (perLevel != null) total += perLevel * entry.getValue();
		}
		return total;
	}

	//Prefers whatever the player has set for that weapon in their own "attacks" list (so editing it there also changes the auto-roll), falling back to the config default.
	@Nullable
	private static String findWeaponExpression(Player player, JsonObject sheet, IdentifiedWeapon weapon, int level, boolean offhandEmpty) {
		String itemId = weapon.id();
		if (UNARMED_ID.equals(itemId)) {
			TraitRegistry.UnarmedProfile profile = unarmedProfileFor(player, sheet, level);
			return profile == null ? null : profile.dice() + " + $" + profile.ability();
		}

		if (sheet.has("attacks")) {
			JsonArray attacks = sheet.getAsJsonArray("attacks");
			for (int i = 0; i < attacks.size(); i++) {
				JsonObject form = attacks.get(i).getAsJsonObject();
				if (!form.has("itemId") || !form.get("itemId").getAsString().equals(itemId)) continue;

				JsonArray rollSet = form.getAsJsonArray("rolls");
				if (rollSet.isEmpty()) continue;
				JsonArray rollGroup = rollSet.get(0).getAsJsonArray();
				if (rollGroup.isEmpty()) continue;
				return rollGroup.get(0).getAsJsonObject().get("expression").getAsString();
			}
		}

		Config.WeaponDefault weaponDefault = Config.weaponDefaultFor(itemId);
		if (weaponDefault == null) weaponDefault = weapon.autoDetected(); //Compatibility with weapons from other mods, see Config.autoDetectWeapon.
		if (weaponDefault == null) return null;
		//Versatile (e.g. longsword 1d8/1d10): the big die only counts with the other hand truly free —
		//a shield or any other item in it counts as "not free", same as in 5e.
		String dice = weaponDefault.isVersatile() && offhandEmpty ? weaponDefault.versatileDice() : weaponDefault.dice();
		return dice + " + $" + weaponDefault.ability();
	}
}
