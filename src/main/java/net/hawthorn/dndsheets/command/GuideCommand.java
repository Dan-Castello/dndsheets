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
 * <p>{@code /dndguide}: reopens the Guide (see {@link net.hawthorn.dndsheets.client.gui.GuideBook}) on
 * demand, without having to remember the sheet's or the DM Panel's button. Open to any player,
 * like {@code /dnddistance}; the DM pages are included automatically if whoever runs it is an operator.</p>
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
			new TutorialOpenMessage(DndsheetsMod.canActAsDm(player)));
		return 1;
	}
}
