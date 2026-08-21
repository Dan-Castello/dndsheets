package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.JournalManager;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.List;

/**
 * <p>{@code /dndjournal}: diario de campaña y handouts, que son lo mismo con distinta visibilidad (ver
 * {@link JournalManager}).</p>
 *
 * <ul>
 *   <li>{@code publish <título>} — convierte el Libro y Pluma de tu mano en una entrada. Solo DM.</li>
 *   <li>{@code share <id> <jugadores>} — se la entrega a esos jugadores. Es lo que hace un handout.</li>
 *   <li>{@code party <id>} / {@code hide <id>} — visible para todos, o de vuelta a privada.</li>
 *   <li>{@code list} — abre el diario con lo que TÚ puedas leer. Sin permisos: cada uno ve lo suyo.</li>
 * </ul>
 */
@Mod.EventBusSubscriber
public class JournalCommand {

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndjournal")
			//Sin subcomando abre el diario: es lo que se va a querer casi siempre.
			.executes(JournalCommand::open)
			.then(Commands.literal("list").executes(JournalCommand::open))
			.then(Commands.literal("publish")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("titulo", StringArgumentType.greedyString())
					.executes(JournalCommand::publish)))
			.then(Commands.literal("share")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests(JournalCommand::suggestIds)
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(JournalCommand::share))))
			.then(Commands.literal("party")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests(JournalCommand::suggestIds)
					.executes(ctx -> setParty(ctx, true))))
			.then(Commands.literal("hide")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests(JournalCommand::suggestIds)
					.executes(ctx -> setParty(ctx, false))))
			.then(Commands.literal("delete")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests(JournalCommand::suggestIds)
					.executes(JournalCommand::delete))));
	}

	//Solo sugiere lo que quien escribe puede leer: autocompletar el id de una nota privada del DM ya
	//filtraría que existe, que es justo lo que una nota privada no debería revelar.
	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestIds(
			CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		try {
			List<String> ids = JournalManager.readableBy(ctx.getSource().getPlayerOrException())
				.stream().map(JournalManager.Entry::id).toList();
			return SharedSuggestionProvider.suggest(ids, builder);
		} catch (CommandSyntaxException e) {
			return builder.buildFuture();
		}
	}

	private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		//El comando ya corre en el servidor: manda la lista directamente, sin pedirsela a si mismo.
		BrowseActionMessage.sendJournal(ctx.getSource().getPlayerOrException());
		return 1;
	}

	private static int publish(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer dm = ctx.getSource().getPlayerOrException();
		String title = StringArgumentType.getString(ctx, "titulo");

		ItemStack book = dm.getMainHandItem().is(Items.WRITABLE_BOOK) ? dm.getMainHandItem()
			: dm.getOffhandItem().is(Items.WRITABLE_BOOK) ? dm.getOffhandItem() : ItemStack.EMPTY;
		if (book.isEmpty()) {
			//Se dice exactamente qué falta: "no se pudo" obligaría a adivinar entre no llevar libro,
			//llevarlo firmado, o llevarlo en blanco.
			ctx.getSource().sendFailure(Component.literal(
				"Necesitas un Libro y Pluma en la mano. Consigue uno con /dndnotes give."));
			return 0;
		}

		JournalManager.Entry entry = JournalManager.publishFromBook(dm, book, title);
		if (entry == null) {
			ctx.getSource().sendFailure(Component.literal("El libro está en blanco: escribe algo antes de publicarlo."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Publicado \"" + entry.title() + "\" [" + entry.id()
			+ "]. Compártelo con /dndjournal share " + entry.id() + " <jugadores> o /dndjournal party " + entry.id() + ".")
			.withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int share(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String id = StringArgumentType.getString(ctx, "id");
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		if (!JournalManager.share(id, targets)) {
			ctx.getSource().sendFailure(Component.literal("No existe esa entrada."));
			return 0;
		}
		JournalManager.Entry entry = JournalManager.get(id);
		//Al que la recibe se le avisa: un handout que aparece en una lista sin decir nada no lo lee nadie.
		for (ServerPlayer target : targets) {
			target.sendSystemMessage(Component.translatable("chat.dndsheets.journal.received", entry.title()).withStyle(ChatFormatting.GOLD));
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Compartido con " + targets.size() + " jugador(es)."), false);
		return targets.size();
	}

	private static int setParty(CommandContext<CommandSourceStack> ctx, boolean party) {
		String id = StringArgumentType.getString(ctx, "id");
		if (!JournalManager.setParty(id, party)) {
			ctx.getSource().sendFailure(Component.literal("No existe esa entrada."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal(party
			? "Ahora la ve todo el grupo." : "Ya no la ve el grupo."), false);
		return 1;
	}

	private static int delete(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		if (!JournalManager.delete(id)) {
			ctx.getSource().sendFailure(Component.literal("No existe esa entrada."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Entrada borrada."), false);
		return 1;
	}
}
