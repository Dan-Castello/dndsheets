package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.TutorialOpenMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * <p>{@code /dndguide}: reabre la Guía (ver {@link net.hawthorn.dndsheets.client.gui.GuideBook}) a
 * demanda, sin tener que recordar el botón de la hoja o del Panel de DM. Abierto a cualquier jugador,
 * como {@code /dnddistance}; las páginas de DM se incluyen solas si quien lo ejecuta es operador.</p>
 */
@Mod.EventBusSubscriber
public class GuideCommand {
	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndguide")
			.executes(GuideCommand::open));
	}

	private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new TutorialOpenMessage(player.hasPermissions(2)));
		return 1;
	}
}
