package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.AbilityItem;
import net.hawthorn.dndsheets.ItemLook;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;

/**
 * <p>{@code /dndnotes give <jugadores>}: entrega un Libro y Pluma vanilla renombrado a "Cuaderno del DM".
 * No hace falta ningún manager ni persistencia propia — un libro y pluma normal YA es privado (nadie más
 * lo lee salvo que se lo enseñes) y Minecraft ya guarda su contenido solo, como el resto del inventario.
 * Este comando solo lo hace fácil de conseguir y gatea el "dárselo" a operadores, igual que el resto de
 * herramientas de DM.</p>
 */
@Mod.EventBusSubscriber
public class NotesCommand {
	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndnotes")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("give")
				.then(Commands.argument("jugadores", EntityArgument.players())
					.executes(NotesCommand::give))));
	}

	private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			target.getInventory().add(buildNotebookStack());
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.notebook.given", targets.size()), true);
		return targets.size();
	}

	//Público: también lo usa la pestaña creativa (DndsheetsModCreativeTab).
	public static ItemStack buildNotebookStack() {
		return AbilityItem.build(net.minecraft.world.item.Items.WRITABLE_BOOK, "dmNotebook",
			Component.translatable("chat.dndsheets.notebook.item_name"),
			Component.translatable("chat.dndsheets.notebook.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
