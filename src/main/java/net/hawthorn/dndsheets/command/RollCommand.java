package net.hawthorn.dndsheets.command;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.common.util.FakePlayerFactory;

import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.commands.Commands;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.procedures.RollAnnouncerProcedure;

import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;

/**
 * <p>The four roll aliases: {@code /roll} and its shortcut {@code /r}, and their two private twins
 * {@code /rollprivate} and {@code /rp}.</p>
 *
 * <p>Private means the roll only reaches whoever rolled it and connected operators (see
 * {@code RollAnnouncerProcedure.sendPrivately}). It's a separate command instead of a trailing argument
 * because {@code expression} uses {@code MessageArgument.message()}, which captures the rest of the
 * text: there's no clean way to distinguish a trailing flag from the dice expression itself.</p>
 */
@Mod.EventBusSubscriber
public class RollCommand {
	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		register(event, "roll", false);
		register(event, "r", false);
		register(event, "rollprivate", true);
		register(event, "rp", true);
	}

	private static void register(RegisterCommandsEvent event, String name, boolean isPrivate) {
		event.getDispatcher().register(Commands.literal(name)
			.then(Commands.argument("expression", MessageArgument.message())
				.executes(arguments -> roll(arguments, isPrivate))));
	}

	private static int roll(CommandContext<CommandSourceStack> arguments, boolean isPrivate) {
		CommandSourceStack source = arguments.getSource();
		Level world = source.getUnsidedLevel();

		Entity entity = source.getEntity();
		//With no entity —console or command block— the roll happens on behalf of the server's fake
		//player: RollAnnouncerProcedure needs a uuid to look up the sheet with.
		if (entity == null && world instanceof ServerLevel serverLevel)
			entity = FakePlayerFactory.getMinecraft(serverLevel);
		//getStringUUID() used to be called on this entity without checking it: if the world wasn't a
		//ServerLevel it stayed null and the command blew up with an NPE instead of just doing nothing.
		if (entity == null) return 0;

		Vec3 position = source.getPosition();
		JsonObject sheet = SheetLoader.getServerSheet(entity.getStringUUID());
		RollAnnouncerProcedure.execute(world, position.x(), position.y(), position.z(), sheet, arguments, entity, isPrivate);
		return 0;
	}
}
