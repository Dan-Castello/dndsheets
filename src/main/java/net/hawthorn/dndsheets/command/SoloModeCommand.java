package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.hawthorn.dndsheets.Config;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>{@code /dndsolo on|off}: turns solo mode (no DM) on and off — see
 * {@link net.hawthorn.dndsheets.DndsheetsMod#canActAsDm}. Same hot-reload pattern as
 * {@link VisionCommand}, but deliberately with permission level 4 instead of 2: {@code visionRules}
 * changes a table rule within a game that's already trusted, while this flag decides WHO has total
 * administrative power over other players (spawning monsters, adjusting someone else's sheet, applying
 * conditions). Requiring the same access already needed to grant operator status —not the level 2 that
 * this very flag makes irrelevant the moment it's turned on— is what stops anyone with "DM" permission
 * from self-escalating to "server owner".</p>
 */
@Mod.EventBusSubscriber
public class SoloModeCommand {
	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndsolo")
			.requires(source -> source.hasPermission(4))
			.then(Commands.literal("on").executes(ctx -> set(ctx, true)))
			.then(Commands.literal("off").executes(ctx -> set(ctx, false)))
			.executes(SoloModeCommand::status));
	}

	private static int set(CommandContext<CommandSourceStack> ctx, boolean enabled) {
		Config.setSoloMode(enabled);
		ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(Component.translatable(enabled
			? "chat.dndsheets.solo.enabled"
			: "chat.dndsheets.solo.disabled"), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		boolean enabled = Config.soloMode();
		ctx.getSource().sendSuccess(() -> Component.translatable(enabled
			? "chat.dndsheets.solo.status_on"
			: "chat.dndsheets.solo.status_off"), false);
		return enabled ? 1 : 0;
	}
}
