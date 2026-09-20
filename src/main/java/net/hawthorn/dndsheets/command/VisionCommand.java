package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.VisionManager;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>{@code /dndvision on|off} (and with no arguments, current status): turns {@link VisionManager}'s
 * vision rules on and off without leaving the game and without editing the toml by hand.</p>
 *
 * <p>The command exists because this is the kind of rule decided <em>at the table</em>, not at install
 * time: turned on to head down into a dungeon, turned off for a building session. Saving the value in
 * the regular config instead of custom state is what makes it survive a restart without inventing new
 * persistence.</p>
 */
@Mod.EventBusSubscriber
public class VisionCommand {
	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndvision")
			.requires(source -> DndsheetsMod.canActAsDm(source))
			.then(Commands.literal("on").executes(ctx -> set(ctx, true)))
			.then(Commands.literal("off").executes(ctx -> set(ctx, false)))
			.executes(VisionCommand::status));
	}

	private static int set(CommandContext<CommandSourceStack> ctx, boolean enabled) {
		Config.setVisionRules(enabled);

		MinecraftServer server = ctx.getSource().getServer();
		//Turning it off has to lift blindness from whoever has it applied RIGHT NOW: the tick that would
		//remove it upon stepping into light is exactly the one that was just turned off, so they'd stay
		//blind forever.
		if (!enabled) VisionManager.liftAll(server);

		server.getPlayerList().broadcastSystemMessage(Component.translatable(enabled
			? "chat.dndsheets.vision.enabled"
			: "chat.dndsheets.vision.disabled"), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		boolean enabled = Config.visionRules();
		ctx.getSource().sendSuccess(() -> Component.translatable(enabled
			? "chat.dndsheets.vision.status_on"
			: "chat.dndsheets.vision.status_off"), false);
		return enabled ? 1 : 0;
	}
}
