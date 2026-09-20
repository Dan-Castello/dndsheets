package net.hawthorn.dndsheets.procedures;

import net.hawthorn.dndsheets.BardInspirationManager;
import net.hawthorn.dndsheets.ChatFeedback;
import net.hawthorn.dndsheets.CombatManager;
import net.hawthorn.dndsheets.DiceManager;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.VisionManager;
import net.hawthorn.dndsheets.init.DndsheetsModSounds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.level.Level;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.BlockPos;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.context.CommandContext;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RollAnnouncerProcedure {
	public static void execute(LevelAccessor world, double x, double y, double z, JsonObject sheet, CommandContext<CommandSourceStack> arguments, Entity roller) {
		execute(world, x, y, z, sheet, arguments, roller, false);
	}

	public static void execute(LevelAccessor world, double x, double y, double z, JsonObject sheet, CommandContext<CommandSourceStack> arguments, Entity roller, boolean isPrivate) {
		if (world.isClientSide() || world.getServer() == null) return;

		Component message;
		try {
			DiceManager.RollOutcome outcome = DiceManager.roll(sheet, MessageArgument.getMessage(arguments, "expression").getString());
			if (outcome.result() == null) {
				message = ChatFeedback.rollFailed(Component.translatable("chat.dndsheets.roll.bad_expression").getString());
			} else {
				String characterName = roller != null ? SheetLoader.characterNameOf(sheet, roller) : Component.translatable("chat.dndsheets.roll.someone").getString();
				message = ChatFeedback.roll(characterName, null, outcome.formatted());
			}
		} catch (CommandSyntaxException ignored) {
			message = ChatFeedback.rollFailed(Component.translatable("chat.dndsheets.roll.invalid_expression").getString());
		}

		announce(world, x, y, z, roller, message, isPrivate);
	}

	public static void execute(LevelAccessor world, double x, double y, double z, String uuid, int category, int index, int subIndex, Entity roller) {
		execute(world, x, y, z, uuid, category, index, subIndex, roller, false);
	}

	public static void execute(LevelAccessor world, double x, double y, double z, String uuid, int category, int index, int subIndex, Entity roller, boolean isPrivate) {
		Logger logger = LogManager.getLogger(DndsheetsMod.MODID);
		logger.log(org.apache.logging.log4j.Level.getLevel("info"), "Attempting to make a roll announcement.");
		if (world.isClientSide() || world.getServer() == null) return;

		JsonObject sheet = SheetLoader.getServerSheet(uuid);
		RollIndex roll = new RollIndex(category, index, subIndex);
		List<String> expressions = roll.findExpressionsInSheet(sheet);
		List<String> contexts = roll.findContextsInSheet(sheet);

		//Pending advantage/disadvantage and granted Bardic Inspiration used to only get consumed on an
		//actual hit against a target (CombatManager/SpellCastManager) — clicking the button on the
		//Attacks tab ignored them completely even when active (the player saw them "disappear" without
		//ever being applied to anything, per playtesting feedback). They're consumed here only if the
		//group actually carries an attack roll (starts with "1d20"), and only applied to that one, not
		//to the other rolls in the same button (e.g. damage is separate). Outside the Attacks tab
		//(Checks/Saves/Skills) they're left untouched: those resources are "next attack", not any roll.
		boolean isAttackForm = roll.getCategory() == RollIndex.Category.ATTACKS;
		boolean hasAttackRoll = isAttackForm && expressions.stream().anyMatch(e -> e.trim().toLowerCase().startsWith("1d20"));
		DiceManager.Advantage advantage = hasAttackRoll ? CombatManager.consumeAdvantage(sheet) : DiceManager.Advantage.NORMAL;
		int inspiration = hasAttackRoll ? BardInspirationManager.consumeAttackBonus(sheet) : 0;

		//Dim light: disadvantage on Perception, and on NONE of the other 18 skills — that's the exact
		//SRD rule (see Light), not "disadvantage on everything you do in the dark". Only looks at the
		//player who's rolling, so a DM rolling for an NPC (roller not a ServerPlayer, or has no sheet)
		//triggers nothing.
		boolean isPerceptionCheck = roll.getCategory() == RollIndex.Category.SKILLS && index == RollIndex.PERCEPTION_SKILL_INDEX;
		boolean perceptionDisadvantage = isPerceptionCheck && roller instanceof ServerPlayer serverRoller
			&& VisionManager.inDimLight(serverRoller);

		List<String> resultRolls = new ArrayList<>();
		boolean attackBonusApplied = false;
		for (String expression : expressions) {
			boolean isAttackRoll = hasAttackRoll && !attackBonusApplied && expression.trim().toLowerCase().startsWith("1d20");
			DiceManager.RollOutcome outcome;
			if (isAttackRoll) {
				String withInspiration = inspiration > 0 ? expression + " + " + inspiration : expression;
				outcome = DiceManager.rollAttack(sheet, withInspiration, advantage).outcome();
				attackBonusApplied = true; //Only the first "1d20" roll in the group consumes the resource, same as a real physical attack.
			} else if (perceptionDisadvantage && expression.trim().toLowerCase().startsWith("1d20")) {
				outcome = DiceManager.rollWithAdvantage(sheet, expression, DiceManager.Advantage.DISADVANTAGE);
			} else {
				outcome = DiceManager.roll(sheet, expression);
			}
			if (outcome.result() == null) {
				logger.log(org.apache.logging.log4j.Level.getLevel("info"), "Got a null.");
				continue;
			}
			resultRolls.add(outcome.formatted());
		}

		//Previously resent the entire sheet for every attack roll from the Attacks tab — now only the
		//two fields that consumeAdvantage/consumeAttackBonus just touched.
		if (hasAttackRoll && roller instanceof ServerPlayer serverPlayer) {
			JsonObject patch = new JsonObject();
			patch.addProperty("nextAttackAdvantage", "normal");
			patch.add("bardicInspiration", JsonNull.INSTANCE);
			DndsheetsMod.sendSheetFieldUpdate(serverPlayer, patch);
		}

		Component message;
		if (resultRolls.isEmpty()) {
			message = ChatFeedback.rollFailed(Component.translatable("chat.dndsheets.roll.missing_abilities").getString());
		} else {
			String characterName = roller != null ? SheetLoader.characterNameOf(sheet, roller) : Component.translatable("chat.dndsheets.roll.someone").getString();
			message = ChatFeedback.multiRoll(characterName, contexts, resultRolls);
		}

		announce(world, x, y, z, roller, message, isPrivate);
	}

	//Single delivery point, for both roll forms (sheet and /roll): public to whoever is actually
	//nearby (see ChatFeedback.broadcast, same radius), or private (see sendPrivately) — this used to
	//be a server-wide broadcastSystemMessage, so ANY loose skill/save roll from ANY player (the
	//sheet's buttons, /roll) reached the entire server no matter where they were — with a large table
	//this was the biggest source of chat flooding, well above combat/magic (which were already
	//scoped). The dice sound plays the same in both cases; it's ambience, it doesn't give away the
	//result.
	private static void announce(LevelAccessor world, double x, double y, double z, Entity roller, Component message, boolean isPrivate) {
		if (isPrivate) sendPrivately(world, roller, message);
		else if (roller != null) ChatFeedback.broadcast(roller, message);
		else world.getServer().getPlayerList().broadcastSystemMessage(message, false); //No origin entity (shouldn't happen, see RollCommand), there's nowhere to measure a radius from.

		if (world instanceof Level level && !level.isClientSide()) {
			level.playSound(null, BlockPos.containing(x, y, z), DndsheetsModSounds.DICE.get(), SoundSource.NEUTRAL, 1, 1);
		}
	}

	//Private roll (Stealth, Investigation...): only reaches whoever rolled and whoever is connected as
	//an operator (same hasPermissions(2) criterion the rest of the mod already uses for "is a DM") —
	//there's no native whisper channel to reuse in this mod, so it's a direct sendSystemMessage to
	//each recipient, without duplicating if the roller themself is already an op.
	private static void sendPrivately(LevelAccessor world, Entity roller, Component message) {
		Set<ServerPlayer> recipients = new HashSet<>();
		if (roller instanceof ServerPlayer serverRoller) recipients.add(serverRoller);
		for (ServerPlayer player : world.getServer().getPlayerList().getPlayers()) {
			if (DndsheetsMod.canActAsDm(player)) recipients.add(player);
		}
		for (ServerPlayer player : recipients) player.sendSystemMessage(message);
	}
}
