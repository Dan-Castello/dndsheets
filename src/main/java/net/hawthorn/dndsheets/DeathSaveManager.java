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
 * natural 20) stabilizes them; 3 failures (or a natural 1, which counts double) does kill them for real.</p>
 */
@Mod.EventBusSubscriber
public class DeathSaveManager {
	private static final int INFINITE_DURATION = 1_000_000;
	private static final Set<UUID> allowRealDeath = ConcurrentHashMap.newKeySet();

	static void clearFor(ServerPlayer player) {
		allowRealDeath.remove(player.getUUID());
	}

	//Real death is canceled and the "downed" state is entered instead, unless the player has already
	//been let die for real (3 failed saves) or is already downed (to avoid resetting the count by accident).
	@SubscribeEvent
	public static void onLivingDeath(LivingDeathEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		if (allowRealDeath.remove(player.getUUID())) {
			//Actual real death (3 failed saves): before this the sheet stayed with downed=true forever.
			//The player respawned at full health but onAttackWhileDowned/onLivingHurtWhileDowned kept
			//treating them as downed (unable to attack or take damage ever again), and resendStateOnJoin
			//reopened the death-save screen on every reconnect. It's cleared the same way as stabilize(),
			//because this IS the end of the "downed" state.
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			if (sheet != null) {
				sheet.addProperty("downed", false);
				sheet.addProperty("deathSaveSuccesses", 0);
				sheet.addProperty("deathSaveFailures", 0);
				sendSheetUpdate(player, sheet);
			}
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new ScreenActionMessage(ScreenActionMessage.Action.DEATH_SAVE_CLOSE));
			return;
		}

		if (!Config.auto(Config.Rule.DEATH_SAVES)) return; //Manual: real vanilla death.
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || isDowned(sheet)) return;

		event.setCanceled(true);
		goDown(player, sheet);
	}

	//ponytail: protects the downed player with total invulnerability instead of treating extra damage as
	//an automatic failed save (the real 5e rule). Simpler, and it stops them from being finished off by
	//accident; add that nuance here if it's ever wanted.
	@SubscribeEvent(priority = EventPriority.HIGH)
	public static void onLivingHurtWhileDowned(LivingHurtEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getEntity() instanceof Player victim)) return;
		//The killing blow from 3 failed saves (see handleRollRequest) also passes through here before
		//reaching LivingDeathEvent: if it were canceled like any other damage while downed, HP would
		//never drop below 1 and the player would stay downed forever without ever dying for real.
		//allowRealDeath is the same flag onLivingDeath already uses to avoid canceling THAT particular event.
		if (allowRealDeath.contains(victim.getUUID())) return;
		JsonObject sheet = SheetLoader.getServerSheet(victim.getStringUUID());
		if (sheet != null && isDowned(sheet)) event.setCanceled(true);
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
		CombatFx.diceTick(player);

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
	private static void killForReal(ServerPlayer player, JsonObject sheet) {
		allowRealDeath.add(player.getUUID());
		sendSheetUpdate(player, sheet);
		player.hurt(player.damageSources().generic(), Float.MAX_VALUE);
		//player.hurt() already fired (and cleared) the flag if the player really died. If something
		//intercepted the death before LivingDeathEvent (a totem of undying, total absorption...), the
		//flag would have stayed stuck forever, and this player's NEXT real death, from any unrelated
		//cause, would have silently skipped the death-save system. No-op if it's already cleared.
		allowRealDeath.remove(player.getUUID());

		//If this was the last person alive in an active encounter, end turn mode right now — without
		//this, any compatibility mob stayed frozen (NoAI) forever, with nobody having permission to run
		///dndturns end in a session with no DM live.
		if (player.level() instanceof net.minecraft.server.level.ServerLevel level) TurnManager.onPlayerRealDeath(level, player);
	}

	private static void goDown(ServerPlayer player, JsonObject sheet) {
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
