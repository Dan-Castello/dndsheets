package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>{@code /dnddistance <objetivo>}: distancia en línea recta entre quien ejecuta el comando y el
 * objetivo, convertida a pies (5 ft/bloque, misma rejilla que {@link net.hawthorn.dndsheets.MovementAnchorTracker}
 * y el resto de la mesa) y redondeada al múltiplo de 5 más cercano, como se mide en 5e.</p>
 */
@Mod.EventBusSubscriber
public class DistanceCommand {
	private static final double FEET_PER_BLOCK = 5.0;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dnddistance")
			.then(Commands.argument("objetivo", EntityArgument.entity())
				.executes(DistanceCommand::report)));
	}

	private static int report(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Entity source = ctx.getSource().getEntityOrException();
		Entity target = EntityArgument.getEntity(ctx, "objetivo");

		double blocks = source.position().distanceTo(target.position());
		long feet = Math.round(blocks * FEET_PER_BLOCK / 5.0) * 5;

		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.distance.report",
			target.getDisplayName(), feet), false);
		return (int) feet;
	}
}
