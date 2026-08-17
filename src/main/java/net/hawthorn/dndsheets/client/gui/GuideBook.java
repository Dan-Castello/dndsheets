package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

//Guía para quien no sabe usar el mod (jugadores y DM). Usa el libro escrito vanilla (BookViewScreen)
//en vez de una pantalla propia: paginación y renderizado gratis, sin darle el ítem al jugador ni
//pasar por el servidor — se construye y se abre entero en el cliente.
public class GuideBook {
	private static final String[] PLAYER_PAGES = {
		"gui.dndsheets.guide.page.sheet",
		"gui.dndsheets.guide.page.rolling",
		"gui.dndsheets.guide.page.private_rolls",
		"gui.dndsheets.guide.page.turns",
		"gui.dndsheets.guide.page.rest",
		"gui.dndsheets.guide.page.death_saves",
		"gui.dndsheets.guide.page.spells",
		"gui.dndsheets.guide.page.distance",
		"gui.dndsheets.guide.page.class_items",
		//Lo añadido en la tanda de fidelidad de reglas. Iba todo sin explicar en ninguna parte, que para
		//quien juega es lo mismo que no existir: nadie va a descubrir que parapetarse le da +2 a la CA
		//mirando los números de sus tiradas.
		"gui.dndsheets.guide.page.characters",
		"gui.dndsheets.guide.page.level_up",
		"gui.dndsheets.guide.page.turn_actions",
		"gui.dndsheets.guide.page.cover",
		"gui.dndsheets.guide.page.hud_states",
	};

	private static final String[] DM_PAGES = {
		"gui.dndsheets.guide.page.dm_panel",
		"gui.dndsheets.guide.page.dm_wand",
		"gui.dndsheets.guide.page.dm_dungeons_1",
		"gui.dndsheets.guide.page.dm_dungeons_2",
		"gui.dndsheets.guide.page.dm_notes",
		"gui.dndsheets.guide.page.dm_turns",
		"gui.dndsheets.guide.page.dm_content_packs",
		"gui.dndsheets.guide.page.dm_sheet_admin",
		"gui.dndsheets.guide.page.dm_more_packs",
		"gui.dndsheets.guide.page.dm_commands",
		"gui.dndsheets.guide.page.dm_bosses",
		"gui.dndsheets.guide.page.dm_creatures",
	};

	private GuideBook() {
	}

	public static void open(boolean includeDmPages) {
		CompoundTag tag = new CompoundTag();
		tag.putString("title", I18n.get("gui.dndsheets.guide.title"));
		tag.putString("author", "DndSheets");

		ListTag pages = new ListTag();
		for (String key : PLAYER_PAGES) {
			pages.add(StringTag.valueOf(Component.Serializer.toJson(Component.translatable(key))));
		}
		if (includeDmPages) {
			for (String key : DM_PAGES) {
				pages.add(StringTag.valueOf(Component.Serializer.toJson(Component.translatable(key))));
			}
		}
		tag.put("pages", pages);

		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.setTag(tag);

		Minecraft.getInstance().setScreen(new BookViewScreen(new BookViewScreen.WrittenBookAccess(book)));
	}
}
