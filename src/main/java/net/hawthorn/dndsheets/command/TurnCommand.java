package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.TurnManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;

/**
 * <p>Modo turnos: {@code /dndturns start} tira iniciativa (1d20 + Destreza) para todos los jugadores y
 * monstruos invocados dentro de un radio, ordena y arranca (ver {@link TurnManager#startAt}).
 * {@code /dndturns next} avanza al siguiente combatiente; {@code cancel} hace lo mismo (para saltar a
 * alguien AFK sin que actúe); {@code end} lo apaga. Cada combatiente tiene derecho a una única acción en
 * su turno — ver {@link TurnManager#tryAct}. {@code effect} aplica un efecto de estado (veneno, etc.) a
 * un jugador a mano, que {@link TurnManager} irá tirando solo al empezar cada uno de sus turnos.</p>
 */
@Mod.EventBusSubscriber
public class TurnCommand {
	//Solo sugerencias de tab: cualquier otro texto sigue siendo válido, esto no restringe el argumento.
	//Las 14 condiciones de 5e primero (esas SÍ tienen consecuencias mecánicas reales, ver Condition y
	//TurnManager.applyEffect), y detrás los efectos de daño con nombre libre de siempre. Sigue sin
	//restringir el argumento: cualquier otro texto vale y se comporta como un temporizador de daño.
	private static final String[] EFFECT_NAME_SUGGESTIONS = buildEffectSuggestions();

	private static String[] buildEffectSuggestions() {
		java.util.List<String> names = new java.util.ArrayList<>();
		for (net.hawthorn.dndsheets.Condition condition : net.hawthorn.dndsheets.Condition.values()) names.add(condition.label());
		names.addAll(java.util.List.of("veneno", "fuego", "sangrado"));
		return names.toArray(new String[0]);
	}
	private static final String[] DICE_SUGGESTIONS = {"1d4", "1d6", "1d8", "1d10", "1d12", "2d6", "2d8"};

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndturns")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("start")
				.executes(ctx -> start(ctx, TurnManager.DEFAULT_RADIUS))
				.then(Commands.argument("radio", IntegerArgumentType.integer(1, 200))
					.executes(ctx -> start(ctx, IntegerArgumentType.getInteger(ctx, "radio")))))
			.then(Commands.literal("next").executes(TurnCommand::next))
			.then(Commands.literal("cancel").executes(TurnCommand::cancel))
			.then(Commands.literal("end").executes(TurnCommand::end))
			.then(Commands.literal("effect")
				.then(Commands.argument("jugadores", EntityArgument.players())
					.then(Commands.argument("nombre", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(EFFECT_NAME_SUGGESTIONS, builder))
						.then(Commands.argument("dado", StringArgumentType.word())
							.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DICE_SUGGESTIONS, builder))
							.then(Commands.argument("turnos", IntegerArgumentType.integer(1, 20))
								.executes(TurnCommand::applyEffect)))))));
	}

	private static int start(CommandContext<CommandSourceStack> ctx, double radius) {
		int count = TurnManager.startAt(ctx.getSource().getLevel(), ctx.getSource().getPosition(), radius);
		if (count == 0) ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.turn.nobody_in_radius"));
		return count;
	}

	private static int next(CommandContext<CommandSourceStack> ctx) {
		TurnManager.next(ctx.getSource().getLevel());
		return 1;
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx) {
		TurnManager.cancel(ctx.getSource().getLevel());
		return 1;
	}

	private static int end(CommandContext<CommandSourceStack> ctx) {
		TurnManager.end(ctx.getSource().getLevel());
		return 1;
	}

	private static int applyEffect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String name = StringArgumentType.getString(ctx, "nombre");
		String dice = StringArgumentType.getString(ctx, "dado");
		int turns = IntegerArgumentType.getInteger(ctx, "turnos");

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			TurnManager.applyEffect(target, name, dice, turns);
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Efecto \"" + name + "\" aplicado a " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}
}
