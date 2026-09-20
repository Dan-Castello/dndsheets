package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.hawthorn.dndsheets.RollLog;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * <p>{@code /dndrolls [n]}: the most recent rolls of the game (see {@link RollLog}), checkable without
 * scrolling through chat. No permission required: it's read-only, and a group without a DM needs it just
 * as much as anyone — same criterion as {@code /dndchar list}.</p>
 */
@Mod.EventBusSubscriber
public class RollLogCommand {
	private static final int DEFAULT_COUNT = 15;
	private static final int MAX_COUNT = 100;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndrolls")
			.executes(ctx -> show(ctx, DEFAULT_COUNT))
			.then(Commands.argument("amount", IntegerArgumentType.integer(1, MAX_COUNT))
				.executes(ctx -> show(ctx, IntegerArgumentType.getInteger(ctx, "amount")))));
	}

	private static int show(CommandContext<CommandSourceStack> ctx, int count) {
		List<RollLog.Entry> recent = RollLog.recent();
		if (recent.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.roll.none_yet"), false);
			return 0;
		}
		//Only to whoever asked, not to the whole radius: it's a query, not a table announcement —
		//RollAnnouncerProcedure already handles announcing each roll the moment it happens.
		int shown = Math.min(count, recent.size());
		for (int i = 0; i < shown; i++) {
			RollLog.Entry entry = recent.get(i);
			ctx.getSource().sendSuccess(() -> Component.literal(entry.actor() + ": " + entry.formatted())
				.withStyle(ChatFormatting.GRAY), false);
		}
		return shown;
	}
}
