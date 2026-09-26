package net.hawthorn.dndsheets.client;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RoleplayManager;
import net.hawthorn.dndsheets.client.gui.CommandListScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/** Right-click (air or block) with the Roleplay Wand opens the verb list; each row runs {@code /dndrp <verb>}. */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, value = Dist.CLIENT)
public class RoleplayClient {
	@SubscribeEvent
	public static void onUse(PlayerInteractEvent.RightClickItem event) {
		if (!event.getLevel().isClientSide() || !RoleplayManager.isRoleplayWand(event.getItemStack())) return;
		//Aiming at a creature is the actor/target click (handled on the server): the list only opens on air or blocks.
		net.minecraft.world.phys.HitResult hit = net.minecraft.client.Minecraft.getInstance().hitResult;
		if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY) return;
		List<CommandListScreen.Row> rows = new ArrayList<>();
		for (RoleplayManager.Verb verb : RoleplayManager.Verb.values()) {
			String key = verb.name().toLowerCase();
			rows.add(new CommandListScreen.Row(Component.translatable("chat.dndsheets.rp.verb_name." + key), "dndrp " + key));
		}
		CommandListScreen.open(Component.translatable("chat.dndsheets.rp.list_title"), rows);
	}
}
