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
 * <p>Los cuatro alias de tirada: {@code /roll} y su atajo {@code /r}, y sus dos gemelos privados
 * {@code /rollprivate} y {@code /rp}.</p>
 *
 * <p>Privado quiere decir que la tirada solo le llega a quien tira y a los operadores conectados (ver
 * {@code RollAnnouncerProcedure.sendPrivately}). Es un comando aparte en vez de un argumento final
 * porque {@code expression} usa {@code MessageArgument.message()}, que captura todo el resto del texto:
 * no hay forma limpia de distinguir un flag final de la propia expresión de dados.</p>
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
		//Sin entidad —consola o bloque de comandos— se tira en nombre del jugador falso del servidor:
		//RollAnnouncerProcedure necesita un uuid con el que buscar la hoja.
		if (entity == null && world instanceof ServerLevel serverLevel)
			entity = FakePlayerFactory.getMinecraft(serverLevel);
		//Antes se llamaba a getStringUUID() sobre este entity sin comprobarlo: si el mundo no era un
		//ServerLevel seguía siendo null y el comando reventaba con NPE en vez de no hacer nada.
		if (entity == null) return 0;

		Vec3 position = source.getPosition();
		JsonObject sheet = SheetLoader.getServerSheet(entity.getStringUUID());
		RollAnnouncerProcedure.execute(world, position.x(), position.y(), position.z(), sheet, arguments, entity, isPrivate);
		return 0;
	}
}
