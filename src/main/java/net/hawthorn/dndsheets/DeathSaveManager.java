package net.hawthorn.dndsheets;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.network.ScreenActionMessage;
import net.minecraft.world.InteractionHand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * <p>When a player would hit 0 HP, their real death is canceled instead: they freeze at 1 HP,
 * incapacitated (blind, weakened and nearly immobile), and a window opens for them to roll their 3
 * death saves. Another player can revive them instantly by interacting with them. 3 successes (or a
 * natural 20) stabilizes them; 3 failures (or a natural 1, which counts double) does kill them for real.
 * Damage taken while downed is a failed save, and massive damage (what is left after 0 HP is at least the
 * max HP) kills outright.</p>
 */
@Mod.EventBusSubscriber(modid = net.hawthorn.dndsheets.DndsheetsMod.MODID)
public class DeathSaveManager {
	private static final int INFINITE_DURATION = 1_000_000;
	private static final Set<UUID> allowRealDeath = ConcurrentHashMap.newKeySet();

	static void clearFor(ServerPlayer player) {
		allowRealDeath.remove(player.getUUID());
		massiveMarked.remove(player.getUUID());
		lastDownedHit.remove(player.getUUID());
		reviveGrace.remove(player.getUUID());
	}

	//Seconds after being revived during which nothing can hurt (or re-down) the character. Without it, a monster
	//whose turn came right after the revive put them back at 0 HP and the saves restarted from nothing.
	private static final long REVIVE_GRACE_TICKS = 100;
	private static final java.util.Map<UUID, Long> reviveGrace = new ConcurrentHashMap<>();
	//Deaths we asked for through the massive-damage rule, so the flag can be taken back if the damage is canceled.
	private static final Set<UUID> massiveMarked = ConcurrentHashMap.newKeySet();

	/** Pure: is a grace window that ends at {@code until} still running at game time {@code now}? */
	static boolean graceActive(long until, long now) {
		return now < until;
	}

	public static boolean hasGrace(net.minecraft.world.entity.player.Player player) {
		Long until = reviveGrace.get(player.getUUID());
		return until != null && graceActive(until, player.level().getGameTime());
	}

	/** Monsters do not pick a character who is down or has just been revived as their target (see MonsterActionManager). */
	public static boolean isDownedOrProtected(net.minecraft.world.entity.player.Player player) {
		if (hasGrace(player)) return true;
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		return sheet != null && isDowned(sheet);
	}

	/** A death we asked for is in progress: other handlers (wild shape) must not cancel it. */
	static boolean realDeathPending(ServerPlayer player) {
		return allowRealDeath.contains(player.getUUID());
	}

	//Real death is canceled and the "downed" state is entered instead, unless the player has already
	//been let die for real (3 failed saves) or is already downed (to avoid resetting the count by accident).
	@SubscribeEvent
	public static void onLivingDeath(LivingDeathEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		DndsheetsMod.LOGGER.debug("death event for {}: canceled={}, asked-for={}", player.getGameProfile().getName(), event.isCanceled(), allowRealDeath.contains(player.getUUID()));
		//Falling out of the world is not something death saves can undo: the player would stay "downed" falling forever.
		if (event.getSource().is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD)) return;
		if (allowRealDeath.remove(player.getUUID())) {
			//The death we asked for (3 failed saves, giving up, massive damage): it goes through. 'downed' is NOT cleared here:
			//another listener (or a totem) can still abort the death after this point, and a character who is alive but no
			//longer marked as down was sent straight back into the death saves by the next hit. The respawn clears it.
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new ScreenActionMessage(ScreenActionMessage.Action.DEATH_SAVE_CLOSE));
			return;
		}

		if (!Config.auto(Config.Rule.DEATH_SAVES)) return; //Manual: real vanilla death.
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || isDowned(sheet)) return;

		event.setCanceled(true);
		goDown(player, sheet);
	}

	/** The new body after a real death: standing, no saves pending, no leftover protection. */
	@SubscribeEvent
	public static void clearDownedOnRespawn(PlayerEvent.PlayerRespawnEvent event) {
		if (event.getEntity().level().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) return;
		clearFor(player);
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet != null && (isDowned(sheet) || sheet.has("deathSaveFailures"))) {
			sheet.addProperty("downed", false);
			sheet.addProperty("deathSaveSuccesses", 0);
			sheet.addProperty("deathSaveFailures", 0);
			sendSheetUpdate(player, sheet);
		}
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new ScreenActionMessage(ScreenActionMessage.Action.DEATH_SAVE_CLOSE));
	}

	/** Massive damage (SRD): what is left after dropping to 0 HP is at least the maximum HP -- instant death. */
	static boolean isMassiveDamage(float health, float damage, float maxHealth) {
		return damage - health >= maxHealth;
	}

	//Instant death by massive damage: the hit that would drop a standing player to 0 with at least their max
	//HP left over skips the downed state. It marks allowRealDeath, the same flag a failed third save uses, so
	//onLivingDeath lets this death through (it consumes the flag even if a totem then cancels the death).
	@SubscribeEvent(priority = EventPriority.HIGH)
	public static void onMassiveDamage(LivingDamageEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getEntity() instanceof ServerPlayer player) || !Config.auto(Config.Rule.DEATH_SAVES)) return;
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || isDowned(sheet) || hasGrace(player)) return;
		if (!isMassiveDamage(player.getHealth(), event.getAmount(), player.getMaxHealth())) return;
		allowRealDeath.add(player.getUUID());
		massiveMarked.add(player.getUUID());
		ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.death.massive", characterName(sheet, player)).withStyle(net.minecraft.ChatFormatting.DARK_RED));
	}

	//If something cancels that damage, the death we asked for will not happen: take the flag back, or the player's NEXT
	//death, from anything, would skip the death saves.
	@SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
	public static void onMassiveDamageCanceled(LivingDamageEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		if (massiveMarked.remove(player.getUUID()) && event.isCanceled()) allowRealDeath.remove(player.getUUID());
	}

	//SRD: damage taken while at 0 HP is a failed death save (and damage of at least the max HP kills outright).
	//The HP itself stays protected at 1 so the character is not finished by the same hit twice.
	//ponytail: one failure per second at most, so a burn or poison tick is one failure, not three in a row; a
	//critical hit (two failures in 5e) is not distinguished because the hurt event does not carry it.
	private static final java.util.Map<UUID, Long> lastDownedHit = new ConcurrentHashMap<>();
	private static final long DOWNED_HIT_COOLDOWN_TICKS = 20;

	private static void failSaveFromDamage(ServerPlayer player, float amount) {
		//The live sheet, read when this runs (a tick after the hit): in between the character may have been stabilized, have
		//given up or died, and a failure must not be added to someone who is no longer down.
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || !isDowned(sheet) || !player.isAlive() || hasGrace(player)) return;
		long now = player.level().getGameTime();
		Long last = lastDownedHit.get(player.getUUID());
		if (amount <= 0 || (last != null && now - last < DOWNED_HIT_COOLDOWN_TICKS)) return;
		lastDownedHit.put(player.getUUID(), now);

		boolean massive = amount >= player.getMaxHealth();
		int failures = (sheet.has("deathSaveFailures") ? sheet.get("deathSaveFailures").getAsInt() : 0) + 1;
		sheet.addProperty("deathSaveFailures", Math.min(failures, 3));
		String name = characterName(sheet, player);
		if (massive) {
			ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.death.massive", name).withStyle(net.minecraft.ChatFormatting.DARK_RED));
		} else {
			ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.death.hit_while_down", name, Math.min(failures, 3)).withStyle(net.minecraft.ChatFormatting.RED));
		}
		if (massive || failures >= 3) killForReal(player, sheet);
		else sendSheetUpdate(player, sheet);
	}

	@SubscribeEvent(priority = EventPriority.HIGH)
	public static void onLivingHurtWhileDowned(LivingHurtEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getEntity() instanceof Player victim)) return;
		//The killing blow from 3 failed saves (see handleRollRequest) also passes through here before
		//reaching LivingDeathEvent: if it were canceled like any other damage while downed, HP would
		//never drop below 1 and the player would stay downed forever without ever dying for real.
		//allowRealDeath is the same flag onLivingDeath already uses to avoid canceling THAT particular event.
		if (allowRealDeath.contains(victim.getUUID())) return;
		if (event.getSource().is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD)) { //The void kills, downed or not.
			allowRealDeath.add(victim.getUUID());
			return;
		}
		if (hasGrace(victim)) { //Just revived: not even a hit that was already coming can put them back down.
			event.setCanceled(true);
			return;
		}
		JsonObject sheet = SheetLoader.getServerSheet(victim.getStringUUID());
		if (sheet != null && isDowned(sheet)) {
			event.setCanceled(true);
			if (victim instanceof ServerPlayer downed && downed.getServer() != null) {
				float amount = event.getAmount();
				//Deferred to the server thread's next step: killing from inside another hurt event would nest them.
				downed.getServer().execute(() -> failSaveFromDamage(downed, amount));
			}
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGH)
	public static void onAttackWhileDowned(AttackEntityEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		JsonObject sheet = SheetLoader.getServerSheet(event.getEntity().getStringUUID());
		if (sheet != null && isDowned(sheet)) event.setCanceled(true);
	}

	//Revive: interacting with a downed player stabilizes them instantly, no roll needed.
	@SubscribeEvent
	public static void onInteractWithDowned(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity().level().isClientSide()) return;
		//Main hand only: reviving doesn't depend on holding anything, so without this the TWO passes of
		//the click (one per hand, see InteractionEvents) would revive and announce it twice.
		if (event.getHand() != InteractionHand.MAIN_HAND) return;
		if (!(event.getTarget() instanceof ServerPlayer target)) return;
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null || !isDowned(sheet)) return;

		//Without this the handler ran twice (once per hand) and the revive announcement came out
		//duplicated for the whole table. See InteractionEvents.
		InteractionEvents.consume(event);

		String reviverName = event.getEntity().getName().getString();
		String targetName = characterName(sheet, target);
		stabilize(target, sheet, Component.translatable("chat.dndsheets.death.revived_title", reviverName));
		ChatFeedback.broadcast(target, ChatFeedback.revived(reviverName, targetName));
	}

	/**
	 * <p>Called on receiving a {@code DeathSaveRollMessage} from the downed client.</p>
	 */
	public static void handleRollRequest(ServerPlayer player) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || !isDowned(sheet)) return;

		//Same gating as an attack or a spell: in real 5e only ONE death save is rolled per turn. Without
		//this, repeated clicking would resolve all 3 rolls in a fraction of a second, giving no time for
		//an ally to revive. Outside turn mode (tryAct always lets it through) this changes nothing.
		if (!TurnManager.tryAct(player)) { TurnManager.notifyCantAct(player); return; }

		int roll = ThreadLocalRandom.current().nextInt(1, 21);
		String characterName = characterName(sheet, player);
		CombatFx.diceRoll(player, roll, roll, roll == 20 ? 1 : roll == 1 ? 2 : 0, characterName);

		if (roll == 20) {
			stabilize(player, sheet, Component.translatable("chat.dndsheets.death.natural_twenty_title"));
			ChatFeedback.broadcast(player, ChatFeedback.naturalTwenty(characterName));
			return;
		}

		int successes = sheet.has("deathSaveSuccesses") ? sheet.get("deathSaveSuccesses").getAsInt() : 0;
		int failures = sheet.has("deathSaveFailures") ? sheet.get("deathSaveFailures").getAsInt() : 0;

		if (roll == 1) failures += 2;
		else if (roll >= 10) successes += 1;
		else failures += 1;
		successes = Math.min(successes, 3);
		failures = Math.min(failures, 3);

		sheet.addProperty("deathSaveSuccesses", successes);
		sheet.addProperty("deathSaveFailures", failures);

		ChatFeedback.broadcast(player, ChatFeedback.deathSaveRoll(characterName, roll, successes, failures));

		if (successes >= 3) {
			stabilize(player, sheet, Component.translatable("chat.dndsheets.death.stabilized_title"));
		} else if (failures >= 3) {
			killForReal(player, sheet);
		} else {
			sendSheetUpdate(player, sheet);
		}
	}

	/**
	 * <p>Called on receiving a {@code DeathSaveGiveUpMessage} from the downed client: they give up and
	 * die for real right now, with no more rolls — the same real-death path as 3 failed saves (see
	 * killForReal). No turn gating: giving up isn't a 5e action, so it makes no sense to force waiting
	 * for your own turn to do it.</p>
	 */
	public static void handleGiveUpRequest(ServerPlayer player) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || !isDowned(sheet)) return;

		ChatFeedback.broadcast(player, ChatFeedback.givesUp(characterName(sheet, player)));
		killForReal(player, sheet);
	}

	//Actual real death (3 failed saves, or giving up by hand): allowRealDeath is the flag that tells
	//onLivingDeath/onLivingHurtWhileDowned not to cancel THIS death like any other damage while downed.
	//It has to work whatever protects the player: genericKill ignores invulnerability and Resistance (a creative or
	//invulnerable character used to survive "give up"), and if something still stops it (a totem of undying) the death
	//is forced through directly.
	private static void killForReal(ServerPlayer player, JsonObject sheet) {
		//A druid in beast shape would otherwise be turned back by the shape-drop handler instead of dying.
		if (DruidWildShapeManager.isShifted(player)) DruidWildShapeManager.revert(player, false);

		allowRealDeath.add(player.getUUID());
		sendSheetUpdate(player, sheet);
		net.minecraft.world.damagesource.DamageSource source = player.damageSources().genericKill();
		player.hurt(source, Float.MAX_VALUE);
		if (!player.isDeadOrDying()) {
			player.setHealth(0.0F);
			player.die(source);
		}
		boolean dead = player.isDeadOrDying();
		DndsheetsMod.LOGGER.debug("killForReal for {}: dead={}", player.getGameProfile().getName(), dead);
		//The flag is dropped only now: the death event it exists for has already been delivered.
		allowRealDeath.remove(player.getUUID());

		//If this was the last person alive in an active encounter, end turn mode right now: without
		//this, any compatibility mob stayed frozen (NoAI) forever, with nobody having permission to run
		///dndturns end in a session with no DM live. Only when the death really happened.
		if (dead && player.level() instanceof net.minecraft.server.level.ServerLevel level) TurnManager.onPlayerRealDeath(level, player);
	}

	private static void goDown(ServerPlayer player, JsonObject sheet) {
		lastDownedHit.remove(player.getUUID());
		player.setHealth(1.0f);
		sheet.addProperty("downed", true);
		sheet.addProperty("deathSaveSuccesses", 0);
		sheet.addProperty("deathSaveFailures", 0);

		player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, INFINITE_DURATION, 0, false, false));
		player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, INFINITE_DURATION, 4, false, false));
		player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, INFINITE_DURATION, 9, false, false));

		sendSheetUpdate(player, sheet);
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new ScreenActionMessage(ScreenActionMessage.Action.DEATH_SAVE_OPEN));
		CombatFx.downed(player);
		ChatFeedback.broadcast(player, ChatFeedback.downed(characterName(sheet, player)));
	}

	private static void stabilize(ServerPlayer player, JsonObject sheet, Component titleText) {
		lastDownedHit.remove(player.getUUID());
		reviveGrace.put(player.getUUID(), player.level().getGameTime() + REVIVE_GRACE_TICKS);
		CombatFx.actionBar(player, Component.translatable("chat.dndsheets.death.grace").withStyle(net.minecraft.ChatFormatting.GREEN));
		sheet.addProperty("downed", false);
		sheet.addProperty("deathSaveSuccesses", 0);
		sheet.addProperty("deathSaveFailures", 0);

		player.removeEffect(MobEffects.BLINDNESS);
		player.removeEffect(MobEffects.WEAKNESS);
		player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
		if (player.getHealth() < 1.0f) player.setHealth(1.0f);

		sendSheetUpdate(player, sheet);
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new ScreenActionMessage(ScreenActionMessage.Action.DEATH_SAVE_CLOSE));
		CombatFx.saved(player, titleText);
	}

	/**
	 * <p>Sets the death-save screen to match the sheet: opens it if the character is downed and closes it
	 * otherwise. Used by connecting (in case they went down before disconnecting) and by character switching.</p>
	 *
	 * <p>It closes as well as opens because "downed" belongs to the CHARACTER (it lives on their sheet, see
	 * {@code downed}), and switching characters can go either direction: leaving a dying character to
	 * switch to another had to close their screen, and coming back to them has to reopen it. Sending a
	 * close to someone with none open does nothing, so the symmetric version serves both cases.</p>
	 */
	public static void resendState(ServerPlayer player, JsonObject sheet) {
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new ScreenActionMessage(
			isDowned(sheet) ? ScreenActionMessage.Action.DEATH_SAVE_OPEN : ScreenActionMessage.Action.DEATH_SAVE_CLOSE));
	}

	private static boolean isDowned(JsonObject sheet) {
		return sheet.has("downed") && sheet.get("downed").getAsBoolean();
	}

	private static String characterName(JsonObject sheet, ServerPlayer player) {
		return SheetLoader.characterNameOf(sheet, player);
	}

	//All 5 paths that touch a downed character's sheet (giving up, stabilizing, dying, rolling, and the
	//reset on respawn) go out through here, so persisting in this one place covers all of them. It used
	//to just notify the client: a restart mid-saves would bring the character back standing with the
	//counter reset to zero (invariant 4).
	private static void sendSheetUpdate(ServerPlayer player, JsonObject sheet) {
		SheetLoader.saveAndSync(player, sheet);
	}
}
