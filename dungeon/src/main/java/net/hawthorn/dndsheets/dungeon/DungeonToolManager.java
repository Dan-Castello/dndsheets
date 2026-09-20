package net.hawthorn.dndsheets.dungeon;

import net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.InteractionEvents;
import net.hawthorn.dndsheets.MonsterRegistry;

import net.hawthorn.dndsheets.dungeon.network.DungeonJigsawConfigureOpenMessage;
import net.hawthorn.dndsheets.dungeon.network.DungeonPieceAddOpenMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Right-clicking with the DM Wand ({@link MonsterRegistry#isDmTool}) on an already-named structure
 * block opens "Add piece" prefilled with that id, instead of forcing the DM to retype by hand the same
 * id they already wrote once when saving the structure (see {@link net.hawthorn.dndsheets.dungeon.client.gui.DungeonPieceAddScreen}).
 * The same on a jigsaw block opens a short form that writes Name/Target/Pool directly (see
 * {@link net.hawthorn.dndsheets.dungeon.client.gui.DungeonJigsawConfigureScreen}), bypassing the jigsaw's
 * vanilla GUI and never hand-typing the 3 exact strings with our namespace. Sneak + right-click copies
 * a jigsaw's configuration to a per-DM clipboard, which prefills (doesn't paste on its own, still asks
 * for confirmation) the form of any other jigsaw touched afterward — to give several exits to the same
 * pool without retyping the name by hand each time.</p>
 */
@Mod.EventBusSubscriber
public class DungeonToolManager {
	private record JigsawClipboard(String pool, boolean isStart) {}

	//Per-DM, not global: two DMs working on the same game shouldn't stomp on each other's clipboard.
	private static final Map<UUID, JigsawClipboard> jigsawClipboard = new HashMap<>();

	/** The clipboard was NEVER cleared: neither put nor get had a remove, so every DM who copied a
	 *  jigsaw left their entry for the rest of the server's lifetime. */
	static void clearFor(ServerPlayer player) {
		jigsawClipboard.remove(player.getUUID());
	}

	//clearFor(player) used to be called directly by SheetLoader (core) in PlayerLoggedOutEvent. The
	//core can no longer know about this addon, so it listens for the same event on its own.
	@SubscribeEvent
	public static void onPlayerLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) clearFor(player);
	}

	@SubscribeEvent
	public static void onCaptureFromStructureBlock(PlayerInteractEvent.RightClickBlock event) {
		if (!MonsterRegistry.isDmTool(event.getItemStack())) return;
		if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof StructureBlockEntity structureBlock)) return;

		cancelBothHandsRetry(event);
		if (event.getLevel().isClientSide()) return;

		Player dm = event.getEntity();
		if (!DndsheetsMod.canActAsDm(dm)) return;
		if (!(dm instanceof ServerPlayer serverDm)) return;

		String structureId = structureBlock.getStructureName();
		if (structureId == null || structureId.isBlank()) {
			serverDm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.block_unnamed").withStyle(ChatFormatting.GRAY));
			return;
		}

		//StructureBlockEntity#saveStructure returns false with no more detail than "no" — by far the most
		//common reason is that the block is no longer in SAVE mode (someone switched it to LOAD/CORNER/
		//DATA with its vanilla GUI at some point, e.g. just poking at it out of curiosity), so this is
		//distinguished BEFORE attempting, to give a message that's actually useful instead of "check that
		//everything is fine".
		if (structureBlock.getMode() != StructureMode.SAVE) {
			serverDm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.block_wrong_mode", structureBlock.getMode()).withStyle(ChatFormatting.GRAY));
			return;
		}

		//Re-scans and re-saves the .nbt RIGHT NOW, using the structure block's position/size
		//(structurePos/structureSize) already set from an earlier Save — without this, a jigsaw
		//configured with the DM Wand AFTER the structure block's last manual "Save" never made it into
		//the captured .nbt (generation looks for the jigsaw Name=dungeon_start INSIDE the already-saved
		//.nbt, not in the live world, see JigsawPlacement.addPieces), so the starting piece would never
		//generate unless the DM remembered to press "Save" by hand again after marking the connections.
		//Capturing with the Wand is already meant to be "the final photo", so it takes the photo at this
		//exact instant instead of trusting an old one.
		if (!structureBlock.saveStructure()) {
			serverDm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.block_resave_failed").withStyle(ChatFormatting.GRAY));
			return;
		}

		ResourceLocation parsed = ResourceLocation.tryParse(structureId);
		String suggestedId = parsed == null ? "" : suggestIdFrom(parsed.getPath());

		DndsheetsDungeonMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> serverDm), new DungeonPieceAddOpenMessage(structureId, suggestedId));
	}

	//"rooms/entrance" -> "entrance": only the last path segment, so "id" doesn't start out already full
	//of slashes that would need editing by hand anyway.
	private static String suggestIdFrom(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? path : path.substring(slash + 1);
	}

	@SubscribeEvent
	public static void onConfigureJigsaw(PlayerInteractEvent.RightClickBlock event) {
		if (!MonsterRegistry.isDmTool(event.getItemStack())) return;
		if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof JigsawBlockEntity jigsaw)) return;

		cancelBothHandsRetry(event);
		if (event.getLevel().isClientSide()) return;

		Player dm = event.getEntity();
		if (!DndsheetsMod.canActAsDm(dm)) return;
		if (!(dm instanceof ServerPlayer serverDm)) return;

		//Only counts as "configured" if it's from OUR namespace — a freshly placed jigsaw defaults to
		//"minecraft:empty" for Name/Pool (see JigsawBlockEntity), which isn't a real chosen pool.
		ResourceLocation currentPoolLocation = jigsaw.getPool().location();
		boolean isConfigured = DungeonManager.POOL_NAMESPACE.equals(currentPoolLocation.getNamespace());
		boolean currentIsStart = jigsaw.getName().equals(new ResourceLocation(DungeonManager.START_JIGSAW_NAME));

		//Sneak: copies this jigsaw to the clipboard instead of opening the form — kept separate from a
		//normal click so copying is always an explicit action, never a side effect of configuring.
		if (dm.isShiftKeyDown()) {
			if (!isConfigured) {
				serverDm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.jigsaw_unset").withStyle(ChatFormatting.GRAY));
				return;
			}
			jigsawClipboard.put(dm.getUUID(), new JigsawClipboard(currentPoolLocation.getPath(), currentIsStart));
			serverDm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.jigsaw_copied", currentPoolLocation.getPath(), (currentIsStart ? Component.translatable("chat.dndsheets.dungeon.start_suffix") : Component.literal("."))).withStyle(ChatFormatting.GRAY));
			return;
		}

		//The clipboard (if there is one) prefills the form instead of whatever THIS jigsaw already had —
		//giving several exits to the same pool means copying once and only confirming on the rest,
		//without retyping. Still asks for confirmation (never writes on its own): a normal click
		//shouldn't silently overwrite anything.
		JigsawClipboard copied = jigsawClipboard.get(dm.getUUID());
		String prefillPool = copied != null ? copied.pool() : (isConfigured ? currentPoolLocation.getPath() : "");
		boolean prefillIsStart = copied != null ? copied.isStart() : currentIsStart;

		DndsheetsDungeonMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> serverDm), new DungeonJigsawConfigureOpenMessage(event.getPos(), prefillPool, prefillIsStart));
	}

	//Cancels on BOTH sides (client AND server), not just the server: the client decides in its own
	//local prediction whether to retry the interaction with the other hand when the hand used "doesn't
	//consume" anything (vanilla main-hand -> off-hand behavior). Without also cancelling on the client
	//with a result that DOES consume, the client would send a second packet with the other hand and the
	//server would end up processing the action twice — it showed up as a duplicated chat message.
	private static void cancelBothHandsRetry(PlayerInteractEvent.RightClickBlock event) {
		InteractionEvents.consume(event);
		event.setCancellationResult(InteractionResult.SUCCESS);
	}
}
