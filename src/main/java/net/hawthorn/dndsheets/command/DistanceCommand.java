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
 * <p>{@code /dnddistance <target>}: straight-line distance between whoever runs the command and the
 * target, converted to feet (Config.feetPerBlock, 5 by default, same grid as {@link net.hawthorn.dndsheets.MovementAnchorTracker}
 * and the rest of the table) and rounded to the nearest multiple of 5, as measured in 5e.</p>
 */
@Mod.EventBusSubscriber
public class DistanceCommand {

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dnddistance")
			.then(Commands.argument("target", EntityArgument.entity())
				.executes(DistanceCommand::report)));
	}

	private static int report(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Entity source = ctx.getSource().getEntityOrException();
		Entity target = EntityArgument.getEntity(ctx, "target");

		double blocks = source.position().distanceTo(target.position());
		long feet = Math.round(blocks * net.hawthorn.dndsheets.Config.feetPerBlock() / 5.0) * 5;

		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.distance.report",
			target.getDisplayName(), feet), false);
		return (int) feet;
	}
}
