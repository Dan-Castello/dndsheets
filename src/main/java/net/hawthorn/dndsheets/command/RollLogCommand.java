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
 * <p>{@code /dndrolls [n]}: las últimas tiradas de la partida (ver {@link RollLog}), consultables sin
 * desplazarse por el chat. Sin permiso: es de solo lectura, y un grupo sin DM lo necesita tanto como
 * cualquiera — mismo criterio que {@code /dndchar list}.</p>
 */
@Mod.EventBusSubscriber
public class RollLogCommand {
	private static final int DEFAULT_COUNT = 15;
	private static final int MAX_COUNT = 100;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndrolls")
			.executes(ctx -> show(ctx, DEFAULT_COUNT))
			.then(Commands.argument("cantidad", IntegerArgumentType.integer(1, MAX_COUNT))
				.executes(ctx -> show(ctx, IntegerArgumentType.getInteger(ctx, "cantidad")))));
	}

	private static int show(CommandContext<CommandSourceStack> ctx, int count) {
		List<RollLog.Entry> recent = RollLog.recent();
		if (recent.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("Todavía no se tiró nada esta partida."), false);
			return 0;
		}
		//Solo a quien pregunta, no a todo el radio: es una consulta, no un anuncio de mesa — RollAnnouncerProcedure
		//ya se encarga de anunciar cada tirada en el momento en que pasa.
		int shown = Math.min(count, recent.size());
		for (int i = 0; i < shown; i++) {
			RollLog.Entry entry = recent.get(i);
			ctx.getSource().sendSuccess(() -> Component.literal(entry.actor() + ": " + entry.formatted())
				.withStyle(ChatFormatting.GRAY), false);
		}
		return shown;
	}
}
