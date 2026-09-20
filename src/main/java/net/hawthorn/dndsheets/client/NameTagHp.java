package net.hawthorn.dndsheets.client;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.TurnStateMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>HP on the floating nametag of any combatant in the active encounter: "Goblin 4/7". The data already
 * travels to the client for the HUD tracker (see {@link TurnHudState}); this just repeats it where the
 * player is already looking — above the creature they're aiming at. With no active combat, or for an
 * entity outside the turn order, the nametag stays exactly as it was (invariant 9).</p>
 *
 * <p>It draws nothing of its own: it hooks onto the nametag Minecraft already renders (always for
 * players; for named creatures, when aimed at), so it gets vanilla's billboarding, occlusion, and
 * "only visible when it should be" for free. A hand-drawn floating bar was deliberately ruled out: it
 * would be new 3D rendering to repeat a value already on the HUD tracker.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, value = Dist.CLIENT)
public class NameTagHp {

	@SubscribeEvent
	public static void onRenderNameTag(RenderNameTagEvent event) {
		if (!TurnHudState.active()) return;
		TurnStateMessage.RosterRow row = TurnHudState.myRow(event.getEntity().getId());
		if (row == null || row.maxHp() <= 0 || row.defeated()) return;

		//ALLOW, not just setContent: a mob with no custom name never shows a nametag on its own
		//(shouldShowName is false), so without forcing it the HP only appeared over players and named
		//NPCs — and the ones that matter are exactly the enemies. Only while combat is active and only
		//for whoever's in the turn order: outside that, the event never even reaches here (invariant 9).
		event.setResult(net.minecraftforge.eventbus.api.Event.Result.ALLOW);
		ChatFormatting color = row.currentHp() * 2 >= row.maxHp() ? ChatFormatting.GREEN
			: row.currentHp() * 4 >= row.maxHp() ? ChatFormatting.YELLOW : ChatFormatting.RED;
		event.setContent(event.getContent().copy()
			.append(Component.literal(" " + row.currentHp() + "/" + row.maxHp()).withStyle(color)));
	}
}
