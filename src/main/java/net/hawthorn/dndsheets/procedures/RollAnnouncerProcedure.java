package net.hawthorn.dndsheets.procedures;

import net.hawthorn.dndsheets.BardInspirationManager;
import net.hawthorn.dndsheets.ChatFeedback;
import net.hawthorn.dndsheets.CombatManager;
import net.hawthorn.dndsheets.DiceManager;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
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
import java.util.List;

public class RollAnnouncerProcedure {
	public static void execute(LevelAccessor world, double x, double y, double z, JsonObject sheet, CommandContext<CommandSourceStack> arguments, Entity roller) {
		if (world.isClientSide() || world.getServer() == null) return;

		Component message;
		try {
			DiceManager.RollOutcome outcome = DiceManager.roll(sheet, MessageArgument.getMessage(arguments, "expression").getString());
			if (outcome.result() == null) {
				message = ChatFeedback.rollFailed("revisa que la expresión esté bien escrita.");
			} else {
				String characterName = roller != null ? SheetLoader.characterNameOf(sheet, roller) : "Alguien";
				message = ChatFeedback.roll(characterName, null, outcome.formatted());
			}
		} catch (CommandSyntaxException ignored) {
			message = ChatFeedback.rollFailed("expresión inválida.");
		}

		world.getServer().getPlayerList().broadcastSystemMessage(message, false);
		if (world instanceof Level level && !level.isClientSide()) {
			level.playSound(null, BlockPos.containing(x, y, z), DndsheetsModSounds.DICE.get(), SoundSource.NEUTRAL, 1, 1);
		}
	}

	public static void execute(LevelAccessor world, double x, double y, double z, String uuid, int category, int index, int subIndex, Entity roller) {
		Logger logger = LogManager.getLogger(DndsheetsMod.MODID);
		logger.log(org.apache.logging.log4j.Level.getLevel("info"), "Attempting to make a roll announcement.");
		if (world.isClientSide() || world.getServer() == null) return;

		JsonObject sheet = SheetLoader.getServerSheet(uuid);
		RollIndex roll = new RollIndex(category, index, subIndex);
		List<String> expressions = roll.findExpressionsInSheet(sheet);
		List<String> contexts = roll.findContextsInSheet(sheet);

		//Ventaja/desventaja pendiente e Inspiración Bárdica concedida solo se consumían al golpear de verdad
		//a un objetivo (CombatManager/SpellCastManager) — clicar el botón de la pestaña Ataques las ignoraba
		//por completo aunque estuvieran activas (el jugador las veía "desaparecer" sin aplicarse nunca a
		//nada, ver feedback de playtesting). Se consumen acá solo si el grupo de verdad trae una tirada de
		//ataque (empieza con "1d20") y solo se aplican a esa, no a las demás tiradas del mismo botón (p.ej.
		//el daño va aparte). Fuera de la pestaña Ataques (Pruebas/Salvaciones/Habilidades) no se tocan: esos
		//recursos son "próximo ataque", no cualquier tirada.
		boolean isAttackForm = roll.getCategory() == RollIndex.Category.ATTACKS;
		boolean hasAttackRoll = isAttackForm && expressions.stream().anyMatch(e -> e.trim().toLowerCase().startsWith("1d20"));
		DiceManager.Advantage advantage = hasAttackRoll ? CombatManager.consumeAdvantage(sheet) : DiceManager.Advantage.NORMAL;
		int inspiration = hasAttackRoll ? BardInspirationManager.consumeAttackBonus(sheet) : 0;

		List<String> resultRolls = new ArrayList<>();
		boolean attackBonusApplied = false;
		for (String expression : expressions) {
			boolean isAttackRoll = hasAttackRoll && !attackBonusApplied && expression.trim().toLowerCase().startsWith("1d20");
			DiceManager.RollOutcome outcome;
			if (isAttackRoll) {
				String withInspiration = inspiration > 0 ? expression + " + " + inspiration : expression;
				outcome = DiceManager.rollAttack(sheet, withInspiration, advantage).outcome();
				attackBonusApplied = true; //Solo la primera tirada "1d20" del grupo consume el recurso, igual que un ataque físico real.
			} else {
				outcome = DiceManager.roll(sheet, expression);
			}
			if (outcome.result() == null) {
				logger.log(org.apache.logging.log4j.Level.getLevel("info"), "Got a null.");
				continue;
			}
			resultRolls.add(outcome.formatted());
		}

		//Antes reenviaba la hoja completa por cada tirada de ataque desde la pestaña de Ataques — ahora solo
		//los dos campos que consumeAdvantage/consumeAttackBonus acaban de tocar. Ver AUDIT_TECHNICAL.md M-NET-1.
		if (hasAttackRoll && roller instanceof ServerPlayer serverPlayer) {
			JsonObject patch = new JsonObject();
			patch.addProperty("nextAttackAdvantage", "normal");
			patch.add("bardicInspiration", JsonNull.INSTANCE);
			DndsheetsMod.sendSheetFieldUpdate(serverPlayer, patch);
		}

		Component message;
		if (resultRolls.isEmpty()) {
			message = ChatFeedback.rollFailed("revisa que las características estén puestas y que la expresión sea correcta.");
		} else {
			String characterName = roller != null ? SheetLoader.characterNameOf(sheet, roller) : "Alguien";
			message = ChatFeedback.multiRoll(characterName, contexts, resultRolls);
		}

		world.getServer().getPlayerList().broadcastSystemMessage(message, false);
		if (world instanceof Level level && !level.isClientSide()) {
			level.playSound(null, BlockPos.containing(x, y, z), DndsheetsModSounds.DICE.get(), SoundSource.NEUTRAL, 1, 1);
		}
	}
}
