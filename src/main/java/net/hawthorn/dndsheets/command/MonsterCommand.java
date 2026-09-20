package net.hawthorn.dndsheets.command;

import net.hawthorn.dndsheets.ContentNames;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.Combatant;
import net.hawthorn.dndsheets.CombatFx;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Path;
import java.util.Collection;

/**
 * <p>Loads monster stat blocks from JSON and spawns them as real vanilla mobs with no AI
 * ({@code NoAI:1}), so the DM controls them by hand with the DM wand instead of letting Minecraft
 * move or attack with them on its own. See {@link MonsterRegistry} for the JSON format and
 * {@link net.hawthorn.dndsheets.MonsterActionManager} for how the DM triggers their attacks/spells.</p>
 */
@Mod.EventBusSubscriber
public class MonsterCommand {
	private static final Path MONSTERS_DIR = DndPaths.MONSTERS_DIR;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndmonsters")
			.requires(source -> DndsheetsMod.canActAsDm(source))
			.then(ContentCommands.loadBranch(MONSTERS_DIR, MonsterRegistry::loadFile, "monsters"))
			.then(ContentCommands.listBranch(MonsterRegistry::ids, "Monsters"))
			.then(spawnNode())
			.then(galleryNode())
			.then(attackNode())
			.then(bindNode())
			.then(Commands.literal("dmtool")
				.then(Commands.argument("players", EntityArgument.players()).executes(MonsterCommand::giveDmTool)))
			.then(Commands.literal("movetool")
				.then(Commands.argument("players", EntityArgument.players()).executes(MonsterCommand::giveMoveTool))));
	}

	/**
	 * <p>Attaches a stat block to a creature that <b>already exists</b>, instead of spawning a new one.
	 * It's the missing direction: until now a mod monster could only be born from the mod, so the DM had
	 * to choose between 5e rules and the tools of an NPC mod (EasyNPC and friends), which are far better
	 * for building a character — skin, pose, dialogue, patrol or follow-the-party goals — than anything
	 * this mod is ever going to have.</p>
	 *
	 * <p>With this there's no need to choose: the creature is built wherever it's built best, and then
	 * told "this is a dndsheets:capitan_guardia". From that moment it has AC, HP, resistances, attacks,
	 * conditions and its own turn like any monster from the bestiary, and it stays theirs for its
	 * originating mod. Note what it is NOT: there's no integration with EasyNPC and no dependency on
	 * anyone. The tag is persistent NBT ({@code MonsterRegistry.tagAsMonster}) and works the same on a
	 * vanilla mob or one from any other mod, which is exactly why nothing needs to be integrated.</p>
	 *
	 * <p>Its AI isn't touched: if the creature already knew how to patrol, it still does. Turn mode
	 * freezes it for the duration of combat and gives it back when it ends (see {@code TurnManager.freeze}),
	 * exactly like a mod-native monster spawned with {@code "ai": true}.</p>
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> bindNode() {
		return Commands.literal("bind")
			.then(Commands.argument("target", EntityArgument.entity())
				.then(Commands.argument("monsterId", ResourceLocationArgument.id())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(MonsterRegistry.ids(), builder))
					.executes(MonsterCommand::bind)))
			.then(Commands.literal("clear")
				.then(Commands.argument("target", EntityArgument.entity())
					.executes(MonsterCommand::unbind)));
	}

	private static int bind(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Entity target = EntityArgument.getEntity(ctx, "target");
		String monsterId = ResourceLocationArgument.getId(ctx, "monsterId").toString();
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(monsterId);
		if (block == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.monster.no_such_block", monsterId));
			return 0;
		}
		//A player has their own sheet and their own rules: giving them a monster stat block would override
		//those.
		if (target instanceof net.minecraft.world.entity.player.Player) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.monster.bind_player"));
			return 0;
		}

		//What gets overridden and what doesn't is decided by MonsterRegistry.applyStatBlock, which is also
		//what the DM Wand's selector runs: two ways of doing the same thing have to do the same thing.
		MonsterRegistry.applyStatBlock(target, block);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.monster.bound",
			target.getName().getString(), monsterId), true);
		return 1;
	}

	private static int unbind(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Entity target = EntityArgument.getEntity(ctx, "target");
		if (MonsterRegistry.monsterIdOf(target) == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.monster.not_bound"));
			return 0;
		}
		target.getPersistentData().remove("dndsheets");
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.monster.unbound",
			target.getName().getString()), true);
		return 1;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> spawnNode() {
		return Commands.literal("spawn")
			.then(Commands.argument("monsterId", ResourceLocationArgument.id())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(MonsterRegistry.ids(), builder))
				.executes(ctx -> spawn(ctx, 1))
				.then(Commands.argument("amount", IntegerArgumentType.integer(1, 50))
					.executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "amount")))))
			//Blank NPC, no JSON: name required, base entity/AC/HP optional (villager, AC 10, 10 HP by
			//default) — to be filled in live with "attack add" as needed.
			.then(Commands.literal("generic")
				.then(Commands.argument("name", StringArgumentType.string())
					.executes(ctx -> spawnGeneric(ctx, "minecraft:villager", 10, 10))
					.then(Commands.argument("baseEntity", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ForgeRegistries.ENTITY_TYPES.getKeys().stream().map(Object::toString), builder))
						.executes(ctx -> spawnGeneric(ctx, StringArgumentType.getString(ctx, "baseEntity"), 10, 10))
						.then(Commands.argument("ac", IntegerArgumentType.integer(0, 30))
							.executes(ctx -> spawnGeneric(ctx, StringArgumentType.getString(ctx, "baseEntity"), IntegerArgumentType.getInteger(ctx, "ac"), 10))
							.then(Commands.argument("hp", IntegerArgumentType.integer(1, 9999))
								.executes(ctx -> spawnGeneric(ctx, StringArgumentType.getString(ctx, "baseEntity"), IntegerArgumentType.getInteger(ctx, "ac"), IntegerArgumentType.getInteger(ctx, "hp"))))))));
	}

	//Spawns the ENTIRE bestiary at once in a grid, to see at a glance which model each one comes out as
	//—which is exactly what changes depending on which appearance mods are installed (see MonsterSkins).
	//Without this, checking that an appearance pack really applied required spawning them one by one.
	private static LiteralArgumentBuilder<CommandSourceStack> galleryNode() {
		return Commands.literal("gallery")
			.executes(ctx -> gallery(ctx, ""))
			//"clear" is a literal, so it wins over the argument: it's not possible to filter by the word
			//"clear".
			.then(Commands.literal("clear").executes(MonsterCommand::galleryClear))
			.then(Commands.argument("filter", StringArgumentType.word())
				.executes(ctx -> gallery(ctx, StringArgumentType.getString(ctx, "filter"))));
	}

	//Edits the attacks of an already-spawned monster LIVE (a specific instance, not its whole species —
	//see MonsterRegistry.addCustomAttack): no "appliesEffect", attack+damage only, on purpose (see the
	//note in MonsterRegistry). A status effect still requires the monster's full JSON.
	private static LiteralArgumentBuilder<CommandSourceStack> attackNode() {
		return Commands.literal("attack")
			.then(Commands.literal("add")
				.then(Commands.argument("target", EntityArgument.entity())
					.then(Commands.argument("name", StringArgumentType.string())
						.then(Commands.argument("attackAbility", StringArgumentType.word())
							.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Combatant.ABILITIES, builder))
							.then(Commands.argument("dice", StringArgumentType.word())
								.then(Commands.argument("damageAbility", StringArgumentType.word())
									.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Combatant.ABILITIES, builder))
									.then(Commands.argument("damageType", StringArgumentType.word())
										.executes(MonsterCommand::addAttack))))))))
			.then(Commands.literal("remove")
				.then(Commands.argument("target", EntityArgument.entity())
					.then(Commands.argument("name", StringArgumentType.string())
						.executes(MonsterCommand::removeAttack))))
			.then(Commands.literal("clear")
				.then(Commands.argument("target", EntityArgument.entity())
					.executes(MonsterCommand::clearAttacks)));
	}



	private static int spawn(CommandContext<CommandSourceStack> ctx, int count) {
		String monsterId = ResourceLocationArgument.getId(ctx, "monsterId").toString();
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(monsterId);
		if (block == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.monster.no_such_load_hint", monsterId));
			return 0;
		}

		ServerLevel level = ctx.getSource().getLevel();
		Vec3 pos = ctx.getSource().getPosition();
		int spawned = 0;
		for (int i = 0; i < count; i++) {
			Entity entity = MonsterRegistry.spawnAt(level, pos.x, pos.y, pos.z, monsterId);
			if (entity != null) {
				CombatFx.monsterSpawn(entity);
				spawned++;
			}
		}

		if (spawned == 0) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.monster.no_base_entity", block.baseEntityId(), monsterId));
			return 0;
		}

		int finalSpawned = spawned;
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.monster.spawned_count",
			finalSpawned, ContentNames.of(block.name()), block.ac(), block.maxHp()), true);
		return spawned;
	}

	//4 blocks and not 3: with 3, large models (an Ice and Fire dragon, a giant) overlap with their
	//neighbor and can't be told apart, which is the only thing this grid is for.
	private static final int GALLERY_SPACING = 4;

	private static int gallery(CommandContext<CommandSourceStack> ctx, String filter) {
		String needle = filter.toLowerCase(java.util.Locale.ROOT);
		java.util.List<String> ids = MonsterRegistry.ids().stream()
			.filter(id -> needle.isEmpty() || id.toLowerCase(java.util.Locale.ROOT).contains(needle))
			.sorted()
			.toList();
		if (ids.isEmpty()) {
			ctx.getSource().sendFailure(needle.isEmpty()
				? Component.translatable("chat.dndsheets.monster.gallery_none_loaded")
				: Component.translatable("chat.dndsheets.monster.gallery_none_matching", filter));
			return 0;
		}

		ServerLevel level = ctx.getSource().getLevel();
		Vec3 origin = ctx.getSource().getPosition();
		int columns = (int) Math.ceil(Math.sqrt(ids.size()));
		int spawned = 0;
		for (int i = 0; i < ids.size(); i++) {
			//No CombatFx.monsterSpawn: there are hundreds at once and a per-monster effect would just
			//obscure the view.
			if (MonsterRegistry.spawnAt(level,
					origin.x + (i % columns) * GALLERY_SPACING,
					origin.y,
					origin.z + (i / columns) * GALLERY_SPACING,
					ids.get(i)) != null) {
				spawned++;
			}
		}

		int finalSpawned = spawned;
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.monster.gallery_spawned", finalSpawned, columns), true);
		return spawned;
	}

	//Removes EVERY mod monster nearby, not just the gallery's: 330 stat blocks don't get cleaned up by
	//hand with the DM Wand. The radius comfortably covers the largest grid (18x18 cells of 4 blocks).
	private static int galleryClear(CommandContext<CommandSourceStack> ctx) {
		ServerLevel level = ctx.getSource().getLevel();
		Vec3 pos = ctx.getSource().getPosition();
		java.util.List<Entity> found = level.getEntities((Entity) null,
			new net.minecraft.world.phys.AABB(pos, pos).inflate(200),
			entity -> MonsterRegistry.monsterIdOf(entity) != null);

		for (Entity entity : found) {
			//markDefeated before remove: this removal doesn't go through vanilla death, and without this
			//an ongoing combat would be left waiting on an enemy that no longer exists (see TurnManager).
			net.hawthorn.dndsheets.TurnManager.markDefeated(entity.getId());
			entity.remove(Entity.RemovalReason.DISCARDED);
		}

		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.monster.gallery_cleared", found.size()), true);
		return found.size();
	}

	//Blank NPC (no JSON involved): default AC/HP/ability scores, no attacks. Meant to be filled in live
	//with /dndmonsters attack add according to what that specific encounter needs.
	private static int spawnGeneric(CommandContext<CommandSourceStack> ctx, String baseEntity, int ac, int hp) {
		String name = StringArgumentType.getString(ctx, "name");
		ServerLevel level = ctx.getSource().getLevel();
		Vec3 pos = ctx.getSource().getPosition();

		Entity entity = MonsterRegistry.spawnGeneric(level, pos.x, pos.y, pos.z, name, baseEntity, ac, hp);
		if (entity == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.content.base_item_missing", baseEntity));
			return 0;
		}

		CombatFx.monsterSpawn(entity);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.monster.generic_spawned", name, ac, hp), true);
		return 1;
	}

	//Custom attack on ONE already-spawned monster (doesn't touch its species' shared stat block, see
	//MonsterRegistry.addCustomAttack) — this way a single goblin in the batch can get an extra attack
	//without reloading JSON or affecting the others.
	private static int addAttack(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		Entity target = EntityArgument.getEntity(ctx, "target");
		if (MonsterRegistry.statBlockOf(target) == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.monster.not_summoned"));
			return 0;
		}

		String name = StringArgumentType.getString(ctx, "name");
		String toHitAbility = StringArgumentType.getString(ctx, "attackAbility").toLowerCase(java.util.Locale.ROOT);
		String dice = StringArgumentType.getString(ctx, "dice");
		String damageAbility = StringArgumentType.getString(ctx, "damageAbility").toLowerCase(java.util.Locale.ROOT);
		String damageType = StringArgumentType.getString(ctx, "damageType").toLowerCase(java.util.Locale.ROOT);

		MonsterRegistry.addCustomAttack(target, new MonsterRegistry.MonsterAttack(name, toHitAbility, dice, damageAbility, damageType, null, null, 0));
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.monster.attack_added", name, target.getName().getString()), true);
		return 1;
	}

	private static int removeAttack(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		Entity target = EntityArgument.getEntity(ctx, "target");
		String name = StringArgumentType.getString(ctx, "name");

		if (!MonsterRegistry.removeCustomAttack(target, name)) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.monster.attack_not_found", target.getName().getString(), name));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.monster.attack_removed", name, target.getName().getString()), true);
		return 1;
	}

	private static int clearAttacks(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		Entity target = EntityArgument.getEntity(ctx, "target");
		MonsterRegistry.clearCustomAttacks(target);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.monster.attacks_cleared", target.getName().getString()), true);
		return 1;
	}

	private static int giveDmTool(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ItemStack stack = buildDmToolStack();

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) {
			target.getInventory().add(stack.copy());
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.monster.dm_wand_given", targets.size()), true);
		return targets.size();
	}

	//Public: also used by the creative tab (DndsheetsModCreativeTab).
	public static ItemStack buildDmToolStack() {
		ItemStack stack = net.hawthorn.dndsheets.ItemLook.DM_WAND.applyTo(
			new ItemStack(net.hawthorn.dndsheets.init.DndsheetsModItems.TOKEN.get()));
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("dmtool", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.translatable("chat.dndsheets.monster.wand_item_name"));

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.translatable("chat.dndsheets.monster.wand_item_lore").withStyle(net.minecraft.ChatFormatting.GRAY))));
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.translatable("chat.dndsheets.monster.wand_item_lore2").withStyle(net.minecraft.ChatFormatting.GRAY))));
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.translatable("chat.dndsheets.monster.wand_item_lore3").withStyle(net.minecraft.ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);

		return stack;
	}

	private static int giveMoveTool(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ItemStack stack = buildMoveToolStack();

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) {
			target.getInventory().add(stack.copy());
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.monster.move_wand_given", targets.size()), true);
		return targets.size();
	}

	//Public: also used by the creative tab (DndsheetsModCreativeTab).
	public static ItemStack buildMoveToolStack() {
		ItemStack stack = net.hawthorn.dndsheets.ItemLook.MOVE_WAND.applyTo(
			new ItemStack(net.hawthorn.dndsheets.init.DndsheetsModItems.TOKEN.get()));
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("movetool", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.translatable("chat.dndsheets.monster.move_item_name"));

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.translatable("chat.dndsheets.monster.move_item_lore").withStyle(net.minecraft.ChatFormatting.GRAY))));
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.translatable("chat.dndsheets.monster.move_item_lore2").withStyle(net.minecraft.ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);

		return stack;
	}
}
