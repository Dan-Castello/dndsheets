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
 * <p>{@code /dndsolo on|off}: enciende y apaga el modo solo (sin DM) — ver
 * {@link net.hawthorn.dndsheets.DndsheetsMod#canActAsDm}. Mismo patrón de hot-reload que
 * {@link VisionCommand}, pero a propósito con nivel de permiso 4 en vez de 2: {@code visionRules}
 * cambia una regla de mesa dentro de una partida ya de confianza, mientras que este flag decide QUIÉN
 * tiene poder administrativo total sobre otros jugadores (invocar monstruos, ajustar la hoja ajena,
 * aplicar condiciones). Pedir el mismo acceso que ya hace falta para nombrar operadores —no el nivel
 * 2 que este mismo flag vuelve irrelevante en cuanto se enciende— es lo que evita que cualquiera con
 * permiso de "DM" se auto-escale a "dueño del server".</p>
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
		ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(Component.literal(enabled
			? "Modo solo activado: cualquier jugador conectado puede actuar como DM (invocar monstruos, controlar turnos, aplicar presets y condiciones)."
			: "Modo solo desactivado: las acciones de DM vuelven a exigir operador."), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		boolean enabled = Config.soloMode();
		ctx.getSource().sendSuccess(() -> Component.literal(enabled
			? "Modo solo: activado. Apágalo con /dndsolo off."
			: "Modo solo: desactivado. Enciéndelo con /dndsolo on."), false);
		return enabled ? 1 : 0;
	}
}
