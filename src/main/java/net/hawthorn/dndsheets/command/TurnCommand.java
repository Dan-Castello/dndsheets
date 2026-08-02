package net.hawthorn.dndsheets.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DiceManager;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.TurnManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * <p>Modo turnos: {@code /dndturns start} tira iniciativa (1d20 + Destreza) para todos los jugadores y
 * monstruos invocados dentro de un radio, ordena y arranca. {@code /dndturns next} avanza al siguiente
 * combatiente; {@code cancel} hace lo mismo (para saltar a alguien AFK sin que actúe); {@code end} lo
 * apaga. Cada combatiente tiene derecho a una única acción en su turno — ver {@link
 * TurnManager#tryAct}. {@code effect} aplica un efecto de estado (veneno, etc.) a un jugador a mano,
 * que {@link TurnManager} irá tirando solo al empezar cada uno de sus turnos.</p>
 */
@Mod.EventBusSubscriber
public class TurnCommand {
	public static final double DEFAULT_RADIUS = 30.0;

	//Solo sugerencias de tab: cualquier otro texto sigue siendo válido, esto no restringe el argumento.
	private static final String[] EFFECT_NAME_SUGGESTIONS = {"veneno", "fuego", "sangrado", "aturdido"};
	private static final String[] DICE_SUGGESTIONS = {"1d4", "1d6", "1d8", "1d10", "1d12", "2d6", "2d8"};

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndturns")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("start")
				.executes(ctx -> start(ctx, DEFAULT_RADIUS))
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
		int count = startAt(ctx.getSource().getLevel(), ctx.getSource().getPosition(), radius);
		if (count == 0) ctx.getSource().sendFailure(Component.literal("No hay jugadores ni monstruos invocados en ese radio."));
		return count;
	}

	//Público: también lo usa el Panel de DM (ver network.TurnControlMessage) para arrancar el modo turnos
	//sin pasar por un CommandContext, que no existe fuera de un comando de verdad.
	public static int startAt(ServerLevel level, Vec3 pos, double radius) {
		AABB box = new AABB(pos.x - radius, pos.y - radius, pos.z - radius, pos.x + radius, pos.y + radius, pos.z + radius);

		record Rolled(int entityId, String name, int score, boolean isMonster, String playerUuid) {}
		List<Rolled> rolled = new ArrayList<>();
		for (Entity entity : level.getEntities((Entity) null, box, e -> e instanceof Player || MonsterRegistry.monsterIdOf(e) != null)) {
			String playerUuid = entity instanceof Player player ? player.getStringUUID() : null;
			rolled.add(new Rolled(entity.getId(), nameOf(entity), rollInitiative(entity), MonsterRegistry.monsterIdOf(entity) != null, playerUuid));
		}
		rolled.sort((a, b) -> b.score() - a.score());

		List<TurnManager.Combatant> combatants = new ArrayList<>();
		for (Rolled r : rolled) combatants.add(new TurnManager.Combatant(r.entityId(), r.name() + " (" + r.score() + ")", r.isMonster(), r.playerUuid()));

		if (combatants.isEmpty()) return 0;

		TurnManager.start(level, combatants);
		return combatants.size();
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

	private static int rollInitiative(Entity entity) {
		if (entity instanceof Player player) {
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			DiceManager.RollOutcome outcome = DiceManager.roll(sheet != null ? sheet : new JsonObject(), "1d20 + $dex");
			return outcome.result() != null ? outcome.result().getValue() : 10;
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(entity);
		int mod = block != null ? block.abilityModifier("dex") : 0;
		DiceManager.RollOutcome outcome = DiceManager.roll(new JsonObject(), "1d20 + " + mod);
		return outcome.result() != null ? outcome.result().getValue() : 10;
	}

	private static String nameOf(Entity entity) {
		if (entity instanceof Player player) {
			return SheetLoader.characterNameOf(SheetLoader.getServerSheet(player.getStringUUID()), player);
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(entity);
		return block != null ? block.name() : entity.getName().getString();
	}
}
