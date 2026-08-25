package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>{@code /dnddifficulty facil|normal|dificil}: mando de dificultad self-service para un grupo sin
 * DM — ver {@link Config#scaleMonsterMaxHp} y {@link Config#scaleMonsterDamage}. Solo escala PG y
 * daño de MONSTRUO; lo que hace un jugador nunca cambia. Es un dial de partida, no de confianza, así
 * que —a diferencia de {@code /dndsolo}— sí queda accesible en modo solo con nivel de permiso 2.</p>
 */
@Mod.EventBusSubscriber
public class DifficultyCommand {
	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dnddifficulty")
			.requires(source -> DndsheetsMod.canActAsDm(source))
			.then(Commands.literal("facil").executes(ctx -> set(ctx, "facil")))
			.then(Commands.literal("normal").executes(ctx -> set(ctx, "normal")))
			.then(Commands.literal("dificil").executes(ctx -> set(ctx, "dificil")))
			.executes(DifficultyCommand::status));
	}

	private static int set(CommandContext<CommandSourceStack> ctx, String preset) {
		Config.setDifficultyPreset(preset);
		ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
			Component.literal("Dificultad de los monstruos: " + preset + "."), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSuccess(() -> Component.literal(
			"Dificultad de los monstruos: " + Config.difficultyPreset()
				+ ". Cámbiala con /dnddifficulty <facil|normal|dificil>."), false);
		return 1;
	}
}
