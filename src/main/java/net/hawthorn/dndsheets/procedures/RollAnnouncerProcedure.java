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
				message = ChatFeedback.rollFailed("revisa que la expresión esté bien escrita.");
			} else {
				String characterName = roller != null ? SheetLoader.characterNameOf(sheet, roller) : "Alguien";
				message = ChatFeedback.roll(characterName, null, outcome.formatted());
			}
		} catch (CommandSyntaxException ignored) {
			message = ChatFeedback.rollFailed("expresión inválida.");
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

		announce(world, x, y, z, roller, message, isPrivate);
	}

	//Punto único de entrega, para las dos formas de tirada (sheet y /roll): pública a quien esté cerca de
	//verdad (ver ChatFeedback.broadcast, mismo radio), o privada (ver sendPrivately) — antes esto era
	//broadcastSystemMessage server-wide, así que CUALQUIER tirada suelta de habilidad/salvación de
	//CUALQUIER jugador (los botones de la hoja, /roll) llegaba a todo el servidor sin importar dónde
	//estuviera — con una mesa grande esto era la mayor fuente de saturación del chat, muy por encima de
	//combate/magia (que ya estaban acotados). El sonido de dado se oye igual en ambos casos, es ambiente,
	//no delata el resultado.
	private static void announce(LevelAccessor world, double x, double y, double z, Entity roller, Component message, boolean isPrivate) {
		if (isPrivate) sendPrivately(world, roller, message);
		else if (roller != null) ChatFeedback.broadcast(roller, message);
		else world.getServer().getPlayerList().broadcastSystemMessage(message, false); //Sin entidad de origen (no debería pasar, ver RollCommand), no hay desde dónde medir radio.

		if (world instanceof Level level && !level.isClientSide()) {
			level.playSound(null, BlockPos.containing(x, y, z), DndsheetsModSounds.DICE.get(), SoundSource.NEUTRAL, 1, 1);
		}
	}

	//Tirada privada (Sigilo, Investigación...): solo le llega a quien tiró y a quien esté conectado como
	//operador (mismo criterio hasPermissions(2) que ya usa el resto del mod para "es un DM") — sin canal
	//de susurro nativo que reusar en este mod, así que es sendSystemMessage directo a cada destinatario,
	//sin duplicar si el propio roller ya es op.
	private static void sendPrivately(LevelAccessor world, Entity roller, Component message) {
		Set<ServerPlayer> recipients = new HashSet<>();
		if (roller instanceof ServerPlayer serverRoller) recipients.add(serverRoller);
		for (ServerPlayer player : world.getServer().getPlayerList().getPlayers()) {
			if (player.hasPermissions(2)) recipients.add(player);
		}
		for (ServerPlayer player : recipients) player.sendSystemMessage(message);
	}
}
