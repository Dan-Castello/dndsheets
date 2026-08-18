package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.VisionManager;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>{@code /dndvision on|off} (y sin argumentos, cómo está ahora): enciende y apaga las reglas de visión
 * de {@link VisionManager} sin salir de la partida y sin editar el toml a mano.</p>
 *
 * <p>El comando existe porque esta regla es de las que se deciden <em>en la mesa</em> y no al instalar: se
 * enciende para bajar a una mazmorra y se apaga para la sesión de construir. Guardar el valor en la config
 * de siempre en vez de en un estado propio es lo que hace que sobreviva al reinicio sin inventar
 * persistencia nueva.</p>
 */
@Mod.EventBusSubscriber
public class VisionCommand {
	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndvision")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("on").executes(ctx -> set(ctx, true)))
			.then(Commands.literal("off").executes(ctx -> set(ctx, false)))
			.executes(VisionCommand::status));
	}

	private static int set(CommandContext<CommandSourceStack> ctx, boolean enabled) {
		Config.setVisionRules(enabled);

		MinecraftServer server = ctx.getSource().getServer();
		//Apagar tiene que levantar la ceguera de quien la tenga puesta AHORA: el tick que se la quitaría al
		//salir a la luz es justo el que se acaba de apagar, así que se quedaría ciego para siempre.
		if (!enabled) VisionManager.liftAll(server);

		server.getPlayerList().broadcastSystemMessage(Component.literal(enabled
			? "Reglas de visión activadas: a oscuras se está ciego, y llevar una antorcha en la mano cuenta como luz."
			: "Reglas de visión desactivadas."), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		boolean enabled = Config.visionRules();
		ctx.getSource().sendSuccess(() -> Component.literal(enabled
			? "Reglas de visión: activadas. Apágalas con /dndvision off."
			: "Reglas de visión: desactivadas. Enciéndelas con /dndvision on."), false);
		return enabled ? 1 : 0;
	}
}
