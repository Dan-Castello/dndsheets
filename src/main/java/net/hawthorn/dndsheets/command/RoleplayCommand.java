package net.hawthorn.dndsheets.command;

import net.hawthorn.dndsheets.RoleplayManager;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** {@code /dndrp <verb>}: what the Roleplay Wand's verb list runs. Narration only, no permission needed. */
@Mod.EventBusSubscriber
public class RoleplayCommand {
	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		var root = Commands.literal("dndrp");
		for (RoleplayManager.Verb verb : RoleplayManager.Verb.values()) {
			root.then(Commands.literal(verb.name().toLowerCase()).executes(ctx -> {
				RoleplayManager.setVerb(ctx.getSource().getPlayerOrException(), verb);
				return 1;
			}));
		}
		event.getDispatcher().register(root);
	}
}
