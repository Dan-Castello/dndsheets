package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Bardic Inspiration: the bard right-clicking on ANOTHER player (with the Horn of Inspiration,
 * {@code {dndsheets:{bardicInspiration:true}}}) grants them a die (d6 to d12 depending on the bard's
 * level, rolled at the moment it's granted) that's added to their NEXT attack roll, for
 * {@value #DURATION_ROUNDS} rounds (10 minutes in 5e). Same as Rage, the duration counts in rounds if
 * turn mode is active when it's granted, or in real ticks if not — see {@link TurnManager#onRoundsPass}.</p>
 *
 * <p><b>Deliberately narrowed scope</b>: 5e lets you use this die on an attack roll, an ability check OR a
 * saving throw; here it only hooks into the attack roll (the same spot where advantage/disadvantage
 * already lives in {@link CombatManager}/{@link SpellCastManager}) — extending it to checks and saves
 * would also touch {@code RollAnnouncerProcedure}, the sheet screen. There's also no limit on uses per
 * rest (in 5e it's the bard's Charisma modifier); it can be granted again whenever wanted, same as
 * Rage.</p>
 */
@Mod.EventBusSubscriber
public class BardInspirationManager {
	//The die comes from the BARD's level (see CharacterRules.bardicInspirationDieFor), not a constant:
	//it used to be fixed at 1d6, so the resource that defines the class never improved. And from the
	//bard, not the target — whoever inspires is who sets the die's quality, even if someone else rolls it.
	private static final int DURATION_ROUNDS = 100; //10 minutes in 5e = 100 rounds.
	private static final int DURATION_TICKS = 20 * 60 * 10; //10 real-time minutes outside turn mode.

	//Token per target: each grant() carries its own, and its expiry timer only clears
	//"bardicInspiration" if it's still the MOST RECENT grant for that player. Avoids the case where a
	//second grant (before the first expires) gets wrongly cleared by the older timer — and unlike
	//comparing by the rolled value, this doesn't fail even if the two rolls happen to coincide.
	private static final Map<UUID, Integer> latestGrantToken = new ConcurrentHashMap<>();
	private static int nextToken = 0;

	//Unlike rage/second wind/etc., this token has no independent expiry timer of its own tied to the
	//player (only the NBT effect expires on its own; the token itself stays in the map forever if the
	//player never logs back in).
	@SubscribeEvent
	public static void onPlayerLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
		latestGrantToken.remove(event.getEntity().getUUID());
	}

	//Activated from AbilityItemDispatcher instead of subscribing to EntityInteract on its own.
	static void tryUse(PlayerInteractEvent.EntityInteract event) {
		if (!(event.getEntity() instanceof ServerPlayer bard) || !(event.getTarget() instanceof ServerPlayer target)) return;

		InteractionEvents.consume(event);
		grant(bard, target);
	}

	public static void grant(ServerPlayer bard, ServerPlayer target) {
		JsonObject targetSheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (targetSheet == null) return;

		String die = CharacterRules.bardicInspirationDieFor(
			SheetLoader.characterLevelOf(SheetLoader.getServerSheet(bard.getStringUUID()), bard));
		DiceManager.RollOutcome roll = DiceManager.roll(new JsonObject(), die);
		if (roll.result() == null) return;
		int amount = roll.result().getValue();

		targetSheet.addProperty("bardicInspiration", amount);
		//The target needs to be told: the die is added to THEIR next attack roll, and without this they'd
		//have no way to know they were carrying it until it was spent on its own.
		JsonObject patch = new JsonObject();
		patch.addProperty("bardicInspiration", amount);
		DndsheetsMod.sendSheetFieldUpdate(target, patch);
		CombatFx.activate(target);

		String bardName = SheetLoader.characterNameOf(SheetLoader.getServerSheet(bard.getStringUUID()), bard);
		String targetName = SheetLoader.characterNameOf(targetSheet, target);
		ChatFeedback.broadcast(bard, Component.translatable("chat.dndsheets.bard.inspires", bardName, targetName, amount, roll.formatted()).withStyle(ChatFeedback.RESOURCE));

		UUID uuid = target.getUUID();
		MinecraftServer server = target.getServer();
		int myToken = ++nextToken;
		latestGrantToken.put(uuid, myToken);
		Runnable expire = () -> {
			//If another, newer grant arrived for this player in the meantime, THAT one is in charge now —
			//this older timer must not touch anything.
			if (!Integer.valueOf(myToken).equals(latestGrantToken.get(uuid))) return;
			latestGrantToken.remove(uuid);
			ServerPlayer stillHere = server != null ? server.getPlayerList().getPlayer(uuid) : null;
			if (stillHere == null) return;
			JsonObject sheet = SheetLoader.getServerSheet(stillHere.getStringUUID());
			if (sheet != null) {
				sheet.remove("bardicInspiration");
				SheetLoader.saveAndSync(stillHere, sheet);
			}
		};
		TurnManager.scheduleExpiry(DURATION_ROUNDS, DURATION_TICKS, expire);
	}

	//Public: CombatManager/SpellCastManager call it right before rolling an attack, same as
	//CombatManager.consumeAdvantage — it's consumed as soon as it's used, hit or miss.
	public static int consumeAttackBonus(JsonObject sheet) {
		if (sheet == null || !sheet.has("bardicInspiration")) return 0;
		int amount = sheet.get("bardicInspiration").getAsInt();
		sheet.remove("bardicInspiration");
		return amount;
	}

	public static ItemStack buildInspirationStack() {
		return AbilityItem.build(ItemLook.INSPIRATION, "bardicInspiration", Component.translatable("chat.dndsheets.inspiration.item_name"),
			Component.translatable("chat.dndsheets.inspiration.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
