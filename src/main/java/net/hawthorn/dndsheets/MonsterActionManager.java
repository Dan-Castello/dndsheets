package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.network.MonsterActionOpenMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p>The DM controls AI-less monsters with the DM Wand ({@link MonsterRegistry#isDmTool}): right-clicking
 * one opens a menu with its attacks/spells; picking one resolves it against the player nearest the
 * monster (a real attack/save roll, real damage applied). Sneak + right-click (on a monster OR an armor
 * stand) removes it instantly, to clean up if too many were summoned.</p>
 */
@Mod.EventBusSubscriber
public class MonsterActionManager {

	@SubscribeEvent
	public static void onInteractWithMonster(PlayerInteractEvent.EntityInteract event) {
		handleDmWand(event, event.getTarget());
	}

	/**
	 * <p>An armor stand <b>never</b> arrives through {@code EntityInteract}, so deleting it with the DM
	 * Wand hasn't worked since the day this was written. The cause is in vanilla and doesn't show up
	 * reading this file: {@code ArmorStand.interactAt} returns {@code CONSUME} as soon as it detects it's
	 * on the client — before looking at the item, the slot, or anything else — so it can be equipped with
	 * a right-click. And {@code Minecraft.startUseItem} only retries with {@code interact}
	 * <em>if the first one didn't consume</em> ({@code if (!interactionresult.consumesAction())}), so the
	 * INTERACT packet is never sent and the normal event never fires. The one that does arrive is this
	 * one, the INTERACT_AT.</p>
	 *
	 * <p>It's filtered to armor stands on purpose: any other entity sends BOTH packets (its
	 * {@code interactAt} returns PASS and the client retries), so handling both events with no filter
	 * would run the handler twice per click — the same double-pass bug {@link InteractionEvents}
	 * documents, through a different door.</p>
	 */
	@SubscribeEvent
	public static void onInteractWithArmorStand(PlayerInteractEvent.EntityInteractSpecific event) {
		if (!(event.getTarget() instanceof ArmorStand)) return;
		handleDmWand(event, event.getTarget());
	}

	private static void handleDmWand(PlayerInteractEvent event, Entity target) {
		if (event.getEntity().level().isClientSide()) return;
		Player dm = event.getEntity();
		//event.getItemStack() is the item in THE HAND OF THIS EVENT, not "either hand." The client fires
		//one event per hand (Minecraft.startUseItem walks InteractionHand.values() and retries with the
		//other if the first didn't consume), so checking both hands would make the handler run TWICE per
		//click — the duplicated chat message. It's still fine to carry the wand in the off hand: then the
		//pass that matches is the one for that hand.
		if (!MonsterRegistry.isDmTool(event.getItemStack())) return;
		if (!DndsheetsMod.canActAsDm(dm)) return; //The DM Wand only works in an op's hands (or anyone's in solo mode, see DndsheetsMod.canActAsDm), even if a player gets hold of one.

		//The wand does nothing to a player: they have their own sheet, and the click follows its normal course.
		if (target instanceof Player) return;
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(target);
		boolean isArmorStand = target instanceof ArmorStand;

		InteractionEvents.consume(event);

		//Sneak + right-click with the DM Wand: deletes the monster or armor stand instantly, to clean up
		//if too many were summoned. Without sneaking, it behaves as always (action menu).
		if (dm.isShiftKeyDown()) {
			//Deletion still only works for what the mod summoned (and practice armor stands). A creature
			//from another mod that hasn't been given a sheet yet isn't ours to delete, and an extra
			//sneak + click would take out the NPC someone just built.
			if (block == null && !isArmorStand) return;
			//Two-step: the first sneak-click only arms it; a second on the SAME creature within 5 s deletes.
			long now = dm.level().getGameTime();
			Long armedAt = pendingDelete.get(dm.getUUID() + ":" + target.getId());
			pendingDelete.values().removeIf(t -> now - t > 100); //Stale arms (and other creatures') expire.
			if (armedAt == null || now - armedAt > 100) {
				pendingDelete.put(dm.getUUID() + ":" + target.getId(), now);
				dm.displayClientMessage(Component.translatable("chat.dndsheets.monster.delete_confirm").withStyle(ChatFormatting.RED), true);
				return;
			}
			pendingDelete.remove(dm.getUUID() + ":" + target.getId());
			Component deletedName = block != null ? ContentNames.of(MonsterRegistry.displayNameOf(target, block)) : Component.translatable("chat.dndsheets.monster.the_armor_stand");
			TurnManager.markDefeated(target.getId()); //Deleted by hand by the DM: it's no longer a standing enemy, it counts the same as dead for auto-ending combat.
			target.remove(Entity.RemovalReason.DISCARDED);
			if (dm instanceof ServerPlayer serverDm) {
				serverDm.sendSystemMessage(Component.translatable("chat.dndsheets.monster.deleted", deletedName).withStyle(ChatFormatting.GRAY));
			}
			//If it was the last living enemy, combat ends NOW, not when it's next someone's turn (the only
			//point that used to check this) — see TurnManager.checkAllEnemiesDefeated.
			if (target.level() instanceof ServerLevel level) TurnManager.checkAllEnemiesDefeated(level);
			return;
		}

		if (!(dm instanceof ServerPlayer serverDm)) return;

		//No sheet yet: instead of doing nothing (which is what used to happen), offer to give it one.
		//It's the bridge with any NPC mod — the creature gets built there, with its own tools, and here
		//it's told what it is. See MonsterBindMessage.
		if (block == null) {
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> serverDm),
				new net.hawthorn.dndsheets.network.MonsterBindMessage(target.getId(), new ArrayList<>(MonsterRegistry.ids())));
			return;
		}

		List<String> customAttackNames = new ArrayList<>();
		for (MonsterRegistry.MonsterAttack attack : MonsterRegistry.customAttacksOf(target)) customAttackNames.add(attack.name());

		List<String> actionNames = new ArrayList<>();
		for (MonsterRegistry.MonsterAttack attack : block.attacks()) actionNames.add(attack.name());
		actionNames.addAll(customAttackNames);
		for (MonsterRegistry.MonsterSpell spell : block.spells()) actionNames.add(spell.name());

		//No longer bails here if it's empty: the menu always opens, if only to use
		//"+ Add attack" and give a freshly summoned monster (e.g. a generic NPC) its first action.
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> serverDm), new MonsterActionOpenMessage(target.getId(), actionNames, customAttackNames));
	}

	//Move Wand: reposition an already-summoned monster without going through its attack menu or having to
	//wait for its turn — for staging a scene (or fixing where it landed when summoned) without commands or
	//hand-typed coordinates. Selection kept in memory per player: right-clicking a monster selects it
	//(overwriting any previous selection), right-clicking a block moves it there.
	private static final Map<UUID, Integer> pendingMove = new HashMap<>();

	//"dm uuid:entity id" -> game tick of the first sneak-click, see the two-step delete in onInteractWithMonster.
	private static final Map<String, Long> pendingDelete = new HashMap<>();

	/** A DM who started moving a monster and disconnected without picking a destination left their entry behind. */
	static void clearFor(ServerPlayer player) {
		pendingMove.remove(player.getUUID());
	}

	@SubscribeEvent
	public static void onSelectMonsterToMove(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity().level().isClientSide()) return;
		Player dm = event.getEntity();
		//event.getItemStack() is the item in THE HAND OF THIS EVENT, not "either hand." The client fires
		//one event per hand (Minecraft.startUseItem walks InteractionHand.values() and retries with the
		//other if the first didn't consume), so checking both hands would make the handler run TWICE per
		//click — the duplicated chat message. It's still fine to carry the wand in the off hand: then the
		//pass that matches is the one for that hand.
		if (!MonsterRegistry.isMoveTool(event.getItemStack())) return;
		if (!DndsheetsMod.canActAsDm(dm)) return;

		Entity target = event.getTarget();
		if (MonsterRegistry.statBlockOf(target) == null) return;

		InteractionEvents.consume(event);
		pendingMove.put(dm.getUUID(), target.getId());
		if (dm instanceof ServerPlayer serverDm) {
			serverDm.sendSystemMessage(Component.translatable("chat.dndsheets.monster.move_selected", target.getName().getString()).withStyle(ChatFormatting.GRAY));
		}
	}

	@SubscribeEvent
	public static void onSelectMoveDestination(PlayerInteractEvent.RightClickBlock event) {
		if (event.getEntity().level().isClientSide()) return;
		Player dm = event.getEntity();
		//event.getItemStack() is the item in THE HAND OF THIS EVENT, not "either hand." The client fires
		//one event per hand (Minecraft.startUseItem walks InteractionHand.values() and retries with the
		//other if the first didn't consume), so checking both hands would make the handler run TWICE per
		//click — the duplicated chat message. It's still fine to carry the wand in the off hand: then the
		//pass that matches is the one for that hand.
		if (!MonsterRegistry.isMoveTool(event.getItemStack())) return;

		Integer entityId = pendingMove.get(dm.getUUID());
		if (entityId == null) return;

		InteractionEvents.consume(event);
		pendingMove.remove(dm.getUUID());
		if (!(event.getLevel() instanceof ServerLevel level)) return;

		Entity monster = level.getEntity(entityId);
		if (monster == null || !monster.isAlive()) return; //No longer exists (dead/deleted since it was selected): nothing to move.

		BlockPos destination = event.getPos().relative(event.getFace());
		monster.teleportTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5);
		if (dm instanceof ServerPlayer serverDm) {
			serverDm.sendSystemMessage(Component.translatable("chat.dndsheets.monster.moved", monster.getName().getString()).withStyle(ChatFormatting.GRAY));
		}
	}

	//Summoning card (creative tab, or the analogous /dndspells...): right-clicking a block summons it on
	//top, just like a vanilla spawn egg. Not consumed in creative; consumed in survival.
	@SubscribeEvent
	public static void onUseSpawnCard(PlayerInteractEvent.RightClickBlock event) {
		if (event.getEntity().level().isClientSide()) return;
		ItemStack stack = event.getItemStack();
		String monsterId = MonsterRegistry.monsterSpawnIdOf(stack);
		if (monsterId == null) return;

		InteractionEvents.consume(event);
		if (!(event.getLevel() instanceof ServerLevel level)) return;

		BlockPos spawnPos = event.getPos().relative(event.getFace());
		Entity spawned = MonsterRegistry.spawnAt(level, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, monsterId);
		if (spawned == null) return;
		CombatFx.monsterSpawn(spawned);

		Player player = event.getEntity();
		if (!player.getAbilities().instabuild) stack.shrink(1);
	}

	/**
	 * <p>Called on receiving a {@code MonsterActionChooseMessage}: the DM chose action {@code actionIndex}
	 * (0..N-1 attacks, then 0..M-1 spells) for the monster {@code entityId}, and who to aim it at
	 * ({@code targetUuid}, chosen in {@link net.hawthorn.dndsheets.client.gui.MonsterActionScreen} via
	 * {@link net.hawthorn.dndsheets.client.gui.PlayerPickerScreen}).</p>
	 */
	public static void resolveAction(ServerPlayer dm, int entityId, int actionIndex, String targetUuid) {
		if (actionIndex < 0) return;
		Entity monsterEntity = dm.level().getEntity(entityId);
		if (monsterEntity == null) return;

		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(monsterEntity);
		if (block == null) return;

		Player target = resolveTarget(dm, monsterEntity, targetUuid);
		if (target == null) return;

		//In turn mode, a monster also spends its one action for the turn: if the DM insists on making it
		//act again before its turn comes back around, it's ignored just like it would be for a player.
		if (!TurnManager.tryAct(monsterEntity)) {
			dm.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.dndsheets.monster.cant_act", ContentNames.of(MonsterRegistry.displayNameOf(monsterEntity, block))).withStyle(ChatFormatting.RED));
			return;
		}

		//Same approach autoAct already uses (automatic turn with no DM) — without this, a monster
		//controlled by hand with the DM Wand always attacked from wherever it spawned, never closing
		//distance (NoAI fixed since it's summoned, see MonsterRegistry.spawnAt), even when the player was
		//out of its reach.
		moveTowardIfNeeded(monsterEntity, target);

		//Same order onInteractWithMonster builds the menu in: species attacks first, then this
		//instance's custom ones (see MonsterRegistry.addCustomAttack), then spells.
		List<MonsterRegistry.MonsterAttack> attacks = new ArrayList<>(block.attacks());
		attacks.addAll(MonsterRegistry.customAttacksOf(monsterEntity));

		if (actionIndex < attacks.size()) {
			resolveAttack(block, monsterEntity, attacks.get(actionIndex), target);
			return;
		}

		int spellIndex = actionIndex - attacks.size();
		if (spellIndex < 0 || spellIndex >= block.spells().size()) return;
		resolveSpell(block, monsterEntity, block.spells().get(spellIndex), target);
	}

	//This used to ALWAYS resolve against level.getNearestPlayer, with no way for the DM to pick who they
	//actually wanted to target (or check line of sight) — a DM couldn't make the ogre focus the rogue who
	//just insulted it instead of the nearest tank. It falls back to the nearest player only if the UUID
	//comes in empty/invalid or that player is no longer connected (chosen in the picker, but disconnected
	//before the message arrived) — a reasonable target beats none at all.
	private static Player resolveTarget(ServerPlayer dm, Entity monsterEntity, String targetUuid) {
		if (targetUuid != null && !targetUuid.isEmpty()) {
			try {
				ServerPlayer target = dm.getServer().getPlayerList().getPlayer(java.util.UUID.fromString(targetUuid));
				if (target != null) return target;
			} catch (IllegalArgumentException ignored) {
				//Malformed UUID: should never happen coming from the picker, but any client can send any
				//string — falls back to the nearest player instead of crashing the resolution.
			}
		}
		return monsterEntity.level().getNearestPlayer(monsterEntity, 30);
	}

	//Monster's automatic turn: TurnManager.beginTurn calls this as soon as it's a monster's turn, without
	//waiting for the DM Wand — it's the piece that makes "no DM" actually work. It attacks the nearest
	//player with an attack chosen at random among the available ones (species + custom); if it has no
	//attacks, it tries a random one of its own spells. ponytail: randomness only decides WHICH attack is
	//used, with no tactical target selection (it doesn't pick the weakest target, doesn't flee at low
	//health) — a monster always hits the nearest one. The DM can still step in by hand at any other
	//moment (e.g. between rounds) with the usual DM Wand.
	public static void autoAct(ServerLevel level, Entity monsterEntity) {
		if (!monsterEntity.isAlive()) return;
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(monsterEntity);
		if (block == null) return;

		//tryAct first, ALWAYS, even with nobody to attack: it's what tells TurnManager this combatant
		//already spent its turn and triggers the auto-advance (see TurnManager.scheduleAutoAdvance).
		//Without this, a monster with nobody nearby would be left holding the turn forever — nobody's
		//going to type /dndturns next for it.
		//A boss with its own clock doesn't request a turn (it's never theirs to begin with): it requests
		//to be allowed to act. And it must NOT go through tryAct even indirectly — that method schedules
		//the turn's auto-advance, so calling it out of order would hand the turn to someone else every six seconds.
		if (MonsterRegistry.isOffClock(monsterEntity)) {
			if (!TurnManager.canActIgnoringTurn(monsterEntity)) return;
		} else if (!TurnManager.tryAct(monsterEntity)) return;

		//A player's summon attacks its owner's enemies; a DM monster attacks the nearest player. Without
		//this distinction, Spiritual Weapon would hit whoever summoned it.
		Entity target = SummonManager.ownerOf(monsterEntity) != null
			? SummonManager.findEnemyTarget(level, monsterEntity, 30)
			: level.getNearestPlayer(monsterEntity, 30);
		if (target == null) return; //Nobody nearby: pass the turn without doing anything, it was already consumed above.

		moveTowardIfNeeded(monsterEntity, target);

		List<MonsterRegistry.MonsterAttack> attacks = new ArrayList<>(block.attacks());
		attacks.addAll(MonsterRegistry.customAttacksOf(monsterEntity));
		if (!attacks.isEmpty()) {
			//Multiattack: an adult dragon makes three attacks per turn in 5e (a bite and two claws), and
			//here it used to make ONE, i.e. a third of its threat. They're chosen at random among its
			//own, one per hit, so a monster with a bite and a claw doesn't repeat the same one three times.
			for (int i = 0; i < block.attacksPerTurn(); i++) {
				//Checked between hits: if the first one kills the target, the rest don't happen. A dead
				//target doesn't take two more attacks, and without this the chat announced hits against a corpse.
				if (!monsterEntity.isAlive() || !target.isAlive()) break;
				resolveAttack(block, monsterEntity, randomOf(attacks), target);
			}
			return;
		}
		//Monster spells still require a player: their resolution reads the target's sheet.
		if (!block.spells().isEmpty() && target instanceof Player playerTarget) {
			resolveSpell(block, monsterEntity, randomOf(block.spells()), playerTarget);
		}
	}

	//Picks among several options (attacks or spells) at random instead of always the first — so a
	//monster with "bite" and "claw" doesn't repeat the same hit every turn. With a single option,
	//nextInt(1) always returns 0: no special case needed for that size.
	private static <T> T randomOf(List<T> options) {
		return options.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(options.size()));
	}

	//Before, the monster attacked from wherever it stood, regardless of the real distance to the target
	//(NoAI fixed since it's summoned, see MonsterRegistry.spawnAt) — it could never chase anyone who moved
	//away, or flank, or even close in to hit: it hit anyone up to 30 blocks away as if it had infinite
	//reach. It closes in a straight line up to melee reach (the same value OpportunityAttackTracker
	//already uses) before resolving its action, with the same per-turn movement budget the compatibility
	//mobs already use (see MovementAnchorTracker.speedBlocksForMob).
	//ponytail: straight line on the horizontal plane, no real pathfinding (doesn't dodge obstacles,
	//doesn't go around walls) — it's still truly NoAI, this only simulates "it closed the distance," not real movement AI.
	private static void moveTowardIfNeeded(Entity monster, Entity target) {
		//Speed 0: grappled, restrained, paralyzed, petrified, or unconscious. This used to be checked only
		//in MovementAnchorTracker, which governs players and the compatibility mobs — the mod's own
		//monsters move THROUGH HERE, via a teleport, so the rule never reached them. A monster inside an
		//Entangle would just walk out of it, which is exactly what that spell exists to prevent.
		Combatant combatant = Combatant.of(monster);
		if (combatant != null && combatant.cannotMove()) return;

		Vec3 from = monster.position();
		Vec3 to = target.position();
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
		if (horizontalDistance <= OpportunityAttackTracker.MELEE_REACH) return;

		double travel = Math.min(MovementAnchorTracker.speedBlocksForMob(monster), horizontalDistance - OpportunityAttackTracker.MELEE_REACH);
		if (travel <= 0) return;

		double scale = travel / horizontalDistance;
		monster.teleportTo(from.x + dx * scale, from.y, from.z + dz * scale);
		MonsterRegistry.faceTarget(monster, target);
	}

	//Opportunity attack: TurnManager triggers this when whoever has the turn leaves a monster's melee
	//reach without having already used their reaction this round (see OpportunityAttackTracker.checkOpportunityAttacks).
	//Uses a real attack chosen at random among the available ones (species or custom), without going
	//through resolveAction: this is the monster's reaction, not its turn action, so it doesn't touch TurnManager.tryAct.
	/**
	 * <p>A legendary action attack: same path as the opportunity one — one of its weapons, resolved with
	 * the usual rules — with its own announcement, because chat needs to be able to distinguish why the
	 * boss just hit outside its turn.</p>
	 */
	public static void resolveLegendaryAttack(Entity monsterEntity, Player target) {
		attackOutsideOwnTurn(monsterEntity, target, "chat.dndsheets.monster.legendary_action");
	}

	public static void resolveOpportunityAttack(Entity monsterEntity, Player mover) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(monsterEntity);
		if (block == null) return;

		List<MonsterRegistry.MonsterAttack> attacks = new ArrayList<>(block.attacks());
		attacks.addAll(MonsterRegistry.customAttacksOf(monsterEntity));
		if (attacks.isEmpty()) return;

		attackOutsideOwnTurn(monsterEntity, mover, "chat.dndsheets.monster.opportunity_attack");
	}

	//Common body for both out-of-turn attacks: the only real difference between an opportunity attack and
	//a legendary one is what chat says, and having it written twice was asking for the two to drift apart.
	private static void attackOutsideOwnTurn(Entity monsterEntity, Player target, String messageKey) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(monsterEntity);
		if (block == null) return;

		List<MonsterRegistry.MonsterAttack> attacks = new ArrayList<>(block.attacks());
		attacks.addAll(MonsterRegistry.customAttacksOf(monsterEntity));
		if (attacks.isEmpty()) return;

		Combatant combatant = Combatant.of(target);
		String targetName = combatant != null ? combatant.name() : target.getName().getString();
		ChatFeedback.broadcast(monsterEntity, Component.translatable(messageKey, ContentNames.of(MonsterRegistry.displayNameOf(monsterEntity, block)), targetName).withStyle(ChatFormatting.DARK_PURPLE));
		resolveAttack(block, monsterEntity, randomOf(attacks), target);
	}

	/**
	 * <p>Target is {@code Entity} and not {@code Player}: a player's summon (Spiritual Weapon, Flaming
	 * Sphere) attacks monsters, not players. Everything target-specific — AC, resistances, temporary HP,
	 * how it takes damage — is already resolved by {@link Combatant}, so generalizing this didn't cost a
	 * second branch, it meant deleting the ones that were left.</p>
	 */
	private static void resolveAttack(MonsterRegistry.MonsterStatBlock block, Entity monsterEntity, MonsterRegistry.MonsterAttack attack, Entity target) {
		Combatant targetCombatant = Combatant.of(target);
		if (targetCombatant == null) return;

		int toHitMod = block.abilityModifier(attack.toHitAbility()) + block.proficiencyBonus();
		//Assumed melee: a stat block doesn't say its attacks' reach, and the monster closes in to
		//MELEE_REACH before hitting (see moveTowardIfNeeded).
		boolean melee = true;
		//A monster doesn't carry its own advantage yet (no sheet, no flags), so only the target's side is
		//passed in. The day it does, it goes in through the same place and combines with everything else at once.
		//The monster's OWN conditions matter too: invisible attacks with advantage, frightened with
		//disadvantage. This used to only be passed from the player's side, so an invisible monster
		//attacked flat — the same old asymmetry, on the source instead of the target.
		Combatant attackerCombatant = Combatant.of(monsterEntity);
		DiceManager.AttackRoll attackRoll = DiceManager.rollAttack(new JsonObject(), "1d20 + " + toHitMod,
			AttackRules.advantageAgainst(monsterEntity, targetCombatant, melee,
				attackerCombatant != null ? attackerCombatant.ownAttackAdvantage() : DiceManager.Advantage.NORMAL));
		if (attackRoll.outcome().result() == null) return;
		CombatFx.diceTick(monsterEntity);

		//The monster plays by the SAME rules as the player, and now literally with the same code: cover,
		//effective AC, hit and critical all come from AttackRules. Having this written twice is what left
		//monsters ignoring condition-based advantage and cover, each for months, and each discovered separately.
		AttackRules.Against result = AttackRules.against(monsterEntity, targetCombatant, attackRoll, melee);
		int targetAc = result.targetAc();
		String targetName = targetCombatant.name();

		if (!result.hit()) {
			ChatFeedback.broadcast(monsterEntity, ChatFeedback.withCover(ChatFeedback.attackResult(MonsterRegistry.displayNameOf(monsterEntity, block), targetName, attack.name(), attackRoll.outcome().formatted(), targetAc, false, null), result.cover()));
			return;
		}

		boolean critical = result.critical();
		int damageMod = block.abilityModifier(attack.damageAbility());
		DiceManager.DamageResult damageRoll = DiceManager.rollDamage(new JsonObject(), attack.dice() + " + " + damageMod, critical);
		if (damageRoll.formatted() == null) return;

		//A monster's natural attack isn't magical unless its block says so, and the schema doesn't say so yet.
		int finalAmount = DamageTypes.applyMultiplier(damageRoll.amount(), targetCombatant.effectiveDamageMultiplier(attack.damageType(), false));
		//Scaled by difficulty (Config.scaleMonsterDamage): AFTER resistances, not before — table difficulty
		//shouldn't make a real immunity useless, or the other way around.
		targetCombatant.takeDamage(Config.scaleMonsterDamage(finalAmount)); //Covers temporary HP, concentration and death in a single place.
		CombatFx.hit(target, critical, attack.damageType());
		ChatFeedback.broadcast(monsterEntity, ChatFeedback.withCover(ChatFeedback.attackResult(MonsterRegistry.displayNameOf(monsterEntity, block), targetName, attack.name(), attackRoll.outcome().formatted(), targetAc, true, damageRoll.formatted()), result.cover()));

		if (attack.appliesEffect()) applyEffectFromHit(target, attack.effectName(), attack.effectDice(), attack.effectTurns(), monsterEntity);
	}

	//Leaves the effect ready for TurnManager to keep applying it at the start of each of the target's turns.
	private static void applyEffectFromHit(Entity target, String name, String dice, int turns, Entity source) {
		TurnManager.applyEffect(target, name, dice, turns, source);
		//Character name, not the Minecraft account's: same standard as the rest of the combat lines, and
		//the wrong one was slipping in here. With no Combatant (a compatibility mob) it stays the usual one.
		Combatant combatant = Combatant.of(target);
		String targetName = combatant != null ? combatant.name() : target.getName().getString();
		ChatFeedback.broadcast(target, net.minecraft.network.chat.Component.translatable("chat.dndsheets.monster.effect_applied", targetName, name, turns).withStyle(ChatFormatting.DARK_PURPLE));
	}

	private static void resolveSpell(MonsterRegistry.MonsterStatBlock block, Entity monsterEntity, MonsterRegistry.MonsterSpell spell, Player target) {
		JsonObject targetSheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (targetSheet == null) return;

		String counterer = CounterspellManager.findCounterer(monsterEntity.level(), monsterEntity.position(), monsterEntity);
		if (counterer != null) {
			ChatFeedback.broadcast(monsterEntity, Component.translatable("chat.dndsheets.spell.counterspelled", ContentNames.of(MonsterRegistry.displayNameOf(monsterEntity, block)), ContentNames.of(spell.name()), counterer).withStyle(ChatFormatting.DARK_PURPLE));
			return;
		}

		Combatant targetCombatant = Combatant.of(target);
		if (targetCombatant == null) return;

		//Cover, real DC, save, and final damage: same rules, and the same code, as when a player is the
		//caster (see SaveRules). The DC does come from different places — the monster carries it written
		//in its block and the player computes it from their sheet — and that difference is real, not duplication.
		SaveRules.Outcome save = SaveRules.resolve(monsterEntity, target, spell.saveAbility(),
			spell.saveDc(), spell.dice(), spell.halfOnSave());
		if (save == null) return;
		boolean saved = save.saved();

		CombatFx.spellCast(monsterEntity);
		CombatFx.spellImpact(target, saved, spell.damageType());
		//targetCombatant.name() and not target.getName(): the rest of the mod announces the CHARACTER's
		//name, and the Minecraft account's was slipping in here.
		ChatFeedback.broadcast(monsterEntity, ChatFeedback.withLegendaryResistance(
			ChatFeedback.withCover(ChatFeedback.saveResult(MonsterRegistry.displayNameOf(monsterEntity, block), targetCombatant.name(), spell.name(),
				save.roll().formatted(), save.dc(), saved, save.label(), save.damageFormatted()), save.cover()),
			save.legendaryResistance(), MonsterRegistry.legendaryResistancesLeft(target)));

		//A single implementation of "apply spell damage": affinities, temporary HP, concentration and
		//death. A spell always counts as magical. Scaled by difficulty just like the physical attack
		//above — SaveRules.resolve is shared with a player casting the same spell, so monster difficulty
		//can't live there; it's applied here, only on the monster's side.
		if (save.finalDamage() > 0) SpellCastManager.applyDamage(target, Config.scaleMonsterDamage(save.finalDamage()), spell.damageType());
		//Same standard as SpellCastManager.castSaveSpell: the save decides the condition, not the damage.
		//A paralyzing breath that deals no damage should still paralyze, and one that does damage
		//shouldn't impose its condition on someone who made the save.
		if (!saved && spell.appliesEffect()) applyEffectFromHit(target, spell.effectName(), spell.effectDice(), spell.effectTurns(), monsterEntity);
	}
}
