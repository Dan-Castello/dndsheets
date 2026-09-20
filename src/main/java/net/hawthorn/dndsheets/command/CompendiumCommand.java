package net.hawthorn.dndsheets.command;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.ScreenActionMessage;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * <p>{@code /dndcompendium}: opens the compendium. No permissions required on purpose — it's reference
 * material, and it reveals nothing a player couldn't already see in their Spellbook or in a monster's
 * sheet while fighting it. The DM also has it as a row in the DM Panel.</p>
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
