package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.MonsterSaveTemplateMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Saves the spawned monster (typically a generic NPC already built by hand with "+ Add attack", see
 * {@link MonsterActionScreen}) as a reusable JSON template in {@code monsters/dm_created.json} — this
 * lets a DM create an entire monster (name/AC/HP already set when spawning it, attacks already set
 * live) without writing a single line of JSON, using only "Spawn generic NPC" + this screen. The
 * ability scores (str/dex/con/int/wis/cha) can't currently be adjusted on an already-spawned NPC, so
 * they're asked for here — they default to 10 if left untouched, same as {@code MonsterRegistry.spawnGeneric}
 * defaults them.</p>
 */
public class MonsterTemplateSaveScreen extends SmallFormScreen {
	private final int entityId;
	private EditBox idBox, abilitiesBox;

	private MonsterTemplateSaveScreen(int entityId, Screen parent) {
		super(Component.translatable("gui.dndsheets.monster_action.save_template"), 1, parent);
		this.entityId = entityId;
	}

	public static void open(int entityId) {
		Minecraft.getInstance().setScreen(new MonsterTemplateSaveScreen(entityId, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		idBox = addField(net.minecraft.client.resources.language.I18n.get("gui.dndsheets.form.id_path"), "", 32);
		abilitiesBox = addField(net.minecraft.client.resources.language.I18n.get("gui.dndsheets.form.abilities_csv"), "10, 10, 10, 10, 10, 10", 32);
	}

	@Override
	protected void onConfirm() {
		String id = idBox.getValue().trim();
		if (id.isEmpty()) return;

		DndsheetsMod.PACKET_HANDLER.sendToServer(new MonsterSaveTemplateMessage(entityId, id, abilitiesBox.getValue().trim()));
	}
}
