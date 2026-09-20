package net.hawthorn.dndsheets.command;

import net.hawthorn.dndsheets.ContentNames;

import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.SpellRegistry;
import net.hawthorn.dndsheets.SpellSlots;
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Path;
import java.util.Collection;

/**
 * <p>Loads spells from JSON at {@code <world folder>/dndsheets/spells/<file>.json} (see
 * {@link SpellRegistry} for the format) and lets a player "learn" one, adding it to their own sheet's
 * list of known spells so it shows up in their Spellbook. Learning the FIRST spell also gives them a
 * spell slot (if they had 0) and a quick-cast staff in their inventory, so it can be tried out
 * immediately without depending separately on {@code /dndsheet setslots} or {@code /dndspells
 * staff}.</p>
 */
@Mod.EventBusSubscriber
public class SpellCommand {
	private static final Path SPELLS_DIR = DndPaths.SPELLS_DIR;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndspells")
			.requires(source -> DndsheetsMod.canActAsDm(source))
			.then(ContentCommands.loadBranch(SPELLS_DIR, SpellRegistry::loadFile, "spells"))
			.then(ContentCommands.listBranch(SpellRegistry::ids, "Spells"))
			.then(Commands.literal("learn")
				.then(Commands.argument("players", EntityArgument.players())
					.then(Commands.argument("spellId", ResourceLocationArgument.id())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(SpellRegistry.ids(), builder))
						.executes(SpellCommand::learn))))
			.then(Commands.literal("staff")
				.then(Commands.argument("players", EntityArgument.players())
					.then(Commands.argument("spellId", ResourceLocationArgument.id())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(SpellRegistry.ids(), builder))
						.executes(ctx -> staff(ctx, "minecraft:blaze_rod"))
						.then(Commands.argument("itemBase", ResourceLocationArgument.id())
							.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ForgeRegistries.ITEMS.getKeys().stream().map(Object::toString), builder))
							.executes(ctx -> staff(ctx, ResourceLocationArgument.getId(ctx, "itemBase").toString())))))));
	}



	private static int learn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String spellId = ResourceLocationArgument.getId(ctx, "spellId").toString();
		SpellRegistry.Spell spell = SpellRegistry.get(spellId);
		if (spell == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.spell.no_such_load_hint", spellId));
			return 0;
		}

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) learnForPlayer(target, spellId, spell);

		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.spell.learned_players", targets.size(), ContentNames.of(spell.name())), true);
		return targets.size();
	}

	//Public: also used by network.SpellGiveMessage (its GUI equivalent, see client.gui.SpellGiveListScreen)
	//— same per-player body as the loop above, so as not to duplicate the first-spell logic between
	//command and GUI.
	public static void learnForPlayer(ServerPlayer target, String spellId, SpellRegistry.Spell spell) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;

		SheetLoader.validateSheet(sheet);
		boolean alreadyKnown = !SpellRegistry.learn(sheet, spellId);

		//Without this, learning a spell for the first time left the player with 0/0 slots forever
		//(a rest doesn't create slots out of nothing, it only refills ones that already existed) and
		//nothing in the inventory to cast it with: /dndsheet setslots AND /dndspells staff had to be
		//remembered separately. If the DM already configured slots by hand, this doesn't touch them.
		//Learning a spell without having slots left the player at 0/0 forever (a rest refills what's
		//already there, it doesn't create any). With per-class progression, a casting class already
		//brings its own; this only covers whoever does NOT cast and whoever the DM teaches a spell to
		//anyway.
		int slotsMax = sheet.has("spellSlotsMax") ? sheet.get("spellSlotsMax").getAsInt() : 0;
		if (slotsMax <= 0) SpellSlots.setFlat(sheet, 1, 1);
		if (!alreadyKnown) {
			target.getInventory().add(buildStaffStack(spellId, spell, null));
		}

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> target), new SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	//A staff (or any base item) tagged {dndsheets:{quickSpell:"id"}} casts it with a right-click without
	//going through the Spellbook (see QuickSpellManager), always using the wielder's real stats and
	//spell slots, not a "charge" of its own on the staff. With hundreds of spells in the game a separate
	//staff for each one isn't viable, so the one handed out is RECONFIGURABLE: holding it and choosing
	//"Bind to staff" in the Spellbook rewrites its spell (see StaffBindMessage), without creating a new
	//item on every change.
	private static int staff(CommandContext<CommandSourceStack> ctx, String itemId) throws CommandSyntaxException {
		String spellId = ResourceLocationArgument.getId(ctx, "spellId").toString();
		SpellRegistry.Spell spell = SpellRegistry.get(spellId);
		if (spell == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.spell.no_such_load_hint", spellId));
			return 0;
		}

		ItemStack stack = buildStaffStack(spellId, spell, itemId);

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) {
			target.getInventory().add(stack.copy());
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.spell.staff_given", ContentNames.of(spell.name()), targets.size()), true);
		return targets.size();
	}

	//Public: also used by the creative tab (DndsheetsModCreativeTab) to display the staves for each loaded spell.
	public static ItemStack buildStaffStack(String spellId, SpellRegistry.Spell spell, String itemId) {
		//null = "the mod's own staff". Requested by the three call sites that used to pass a blaze rod by hand.
		ResourceLocation itemLoc = itemId == null ? null : ResourceLocation.tryParse(itemId);
		Item baseItem = itemLoc != null ? ForgeRegistries.ITEMS.getValue(itemLoc) : null;
		//With no item configured, the mod's own staff with its texture. With one configured, whatever the
		//DM says: whoever sets "minecraft:trident" wants to see a trident, not our icon on top of it.
		boolean ownStaff = baseItem == null;
		if (ownStaff) baseItem = net.hawthorn.dndsheets.init.DndsheetsModItems.TOKEN.get();

		ItemStack stack = new ItemStack(baseItem);
		if (ownStaff) net.hawthorn.dndsheets.ItemLook.STAFF.applyTo(stack);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putString("quickSpell", spellId);
		dndTag.putBoolean("staffConfigurable", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		//The SAME key StaffBindMessage uses when rebinding the staff in-game: it's the same object through
		//two paths, and this used to have "Staff of " hardcoded by hand. An English client would get a
		//staff whose name was in Spanish and which even stopped matching once it was rebound.
		stack.setHoverName(Component.translatable("chat.dndsheets.staff.item_name", ContentNames.of(spell.name())));
		return stack;
	}
}
