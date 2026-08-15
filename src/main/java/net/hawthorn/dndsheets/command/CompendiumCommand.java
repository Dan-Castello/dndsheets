package net.hawthorn.dndsheets.command;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.ScreenActionMessage;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * <p>{@code /dndcompendium}: abre el compendio. Sin permisos a propósito — es material de referencia, y
 * no revela nada que un jugador no pueda ver ya en su Grimorio o en la ficha de un monstruo al pelearlo.
 * El DM lo tiene además como fila del Panel de DM.</p>
 */
@Mod.EventBusSubscriber
public class CompendiumCommand {

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndcompendium")
			.executes(ctx -> {
				DndsheetsMod.PACKET_HANDLER.send(
					PacketDistributor.PLAYER.with(() -> ctx.getSource().getPlayer()),
					new ScreenActionMessage(ScreenActionMessage.Action.COMPENDIUM_OPEN));
				return 1;
			}));
	}
}
