package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.compat.PatchouliCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

//Guía para quien no sabe usar el mod (jugadores y DM). Usa el libro escrito vanilla (BookViewScreen)
//en vez de una pantalla propia: paginación y renderizado gratis, sin darle el ítem al jugador ni
//pasar por el servidor — se construye y se abre entero en el cliente.
//
//Con Patchouli instalado la Guía se abre como manual, con índice, categorías, búsqueda y marcapáginas
//(ver PatchouliCompat). Sin Patchouli era una tira de 31 páginas sueltas que solo se podía leer
//pasándolas una a una, así que aquí se reconstruyen a mano las dos cosas que daba el manual y que de
//verdad hacen falta: el libro va PARTIDO en las mismas categorías y entradas que el de Patchouli, y
//empieza por un índice cuyas filas son enlaces (ClickEvent.CHANGE_PAGE, que BookViewScreen ya sabe
//resolver). Cada página lleva de vuelta al índice.
//
//Las dos versiones enseñan lo mismo y en el mismo orden: cada Entry de aquí es una entrada de
//assets/dndsheets/patchouli_books/guide/en_us/entries/<categoría>/<archivo>.json, y
//JsonContentSelfTest.checkPatchouliBook falla si las dos listas se separan.
public class GuideBook {
	private record Entry(String titleKey, String... pages) {
	}

	private record Chapter(String titleKey, boolean dmOnly, Entry... entries) {
	}

	private static final Chapter[] CHAPTERS = {
		//Delante de todo, y es deliberado: el resto de la Guía enseña el MOD dando por sabido el juego —
		//dice CA, salvación y ventaja sin definirlos— y quien entra por primera vez a un mundo con esto
		//instalado no tiene por qué haber jugado nunca a D&D. Estas cuatro entradas son lo único que se
		//lee sin saber nada, así que se leen primero.
		new Chapter("gui.dndsheets.guide.cat.primeros_pasos", false,
			new Entry("gui.dndsheets.guide.entry.que_es",
				"gui.dndsheets.guide.page.intro",
				"gui.dndsheets.guide.page.roles"),
			new Entry("gui.dndsheets.guide.entry.dado",
				"gui.dndsheets.guide.page.d20",
				"gui.dndsheets.guide.page.advantage_basics"),
			new Entry("gui.dndsheets.guide.entry.vocabulario",
				"gui.dndsheets.guide.page.glossary_1",
				"gui.dndsheets.guide.page.glossary_2"),
			new Entry("gui.dndsheets.guide.entry.empezar",
				"gui.dndsheets.guide.page.first_ten",
				"gui.dndsheets.guide.page.first_combat")),

		new Chapter("gui.dndsheets.guide.cat.personaje", false,
			new Entry("gui.dndsheets.guide.entry.hoja",
				"gui.dndsheets.guide.page.sheet",
				"gui.dndsheets.guide.page.sheet_2",
				"gui.dndsheets.guide.page.characters",
				"gui.dndsheets.guide.page.hud_states"),
			new Entry("gui.dndsheets.guide.entry.subir_nivel",
				"gui.dndsheets.guide.page.level_up",
				"gui.dndsheets.guide.page.level_up_2",
				"gui.dndsheets.guide.page.level_up_3",
				"gui.dndsheets.guide.page.subclass"),
			new Entry("gui.dndsheets.guide.entry.descanso",
				"gui.dndsheets.guide.page.rest",
				"gui.dndsheets.guide.page.death_saves"),
			new Entry("gui.dndsheets.guide.entry.objetos_clase",
				"gui.dndsheets.guide.page.class_items",
				"gui.dndsheets.guide.page.wildshape",
				"gui.dndsheets.guide.page.wildshape_2"),
			new Entry("gui.dndsheets.guide.entry.compendio",
				"gui.dndsheets.guide.page.compendium"),
			new Entry("gui.dndsheets.guide.entry.objetos_magicos",
				"gui.dndsheets.guide.page.magic_items")),

		new Chapter("gui.dndsheets.guide.cat.combate", false,
			new Entry("gui.dndsheets.guide.entry.tiradas",
				"gui.dndsheets.guide.page.rolling",
				"gui.dndsheets.guide.page.skills",
				"gui.dndsheets.guide.page.private_rolls"),
			new Entry("gui.dndsheets.guide.entry.turnos",
				"gui.dndsheets.guide.page.turns",
				"gui.dndsheets.guide.page.turn_actions"),
			new Entry("gui.dndsheets.guide.entry.cobertura",
				"gui.dndsheets.guide.page.cover",
				"gui.dndsheets.guide.page.vision",
				"gui.dndsheets.guide.page.vision_2",
				"gui.dndsheets.guide.page.distance"),
			new Entry("gui.dndsheets.guide.entry.magia",
				"gui.dndsheets.guide.page.spells")),

		//El libro de Patchouli enseña las páginas de DM a todo el mundo: un manual con índice no puede
		//esconder medio índice sin quedar raro, y lo que hay ahí es cómo se usa el mod, no el secreto de
		//nadie. El libro escrito sí las esconde, porque aquí no cuesta nada — es este dmOnly.
		new Chapter("gui.dndsheets.guide.cat.dm", true,
			new Entry("gui.dndsheets.guide.entry.primera_sesion",
				"gui.dndsheets.guide.page.dm_first_1",
				"gui.dndsheets.guide.page.dm_first_2",
				"gui.dndsheets.guide.page.dm_first_3"),
			new Entry("gui.dndsheets.guide.entry.panel",
				"gui.dndsheets.guide.page.dm_panel",
				"gui.dndsheets.guide.page.dm_commands",
				"gui.dndsheets.guide.page.dm_commands_2"),
			new Entry("gui.dndsheets.guide.entry.varas",
				"gui.dndsheets.guide.page.dm_wand",
				"gui.dndsheets.guide.page.dm_turns"),
			new Entry("gui.dndsheets.guide.entry.monstruos",
				"gui.dndsheets.guide.page.dm_creatures",
				"gui.dndsheets.guide.page.dm_bosses",
				"gui.dndsheets.guide.page.dm_bosses_2",
				"gui.dndsheets.guide.page.dm_ownclock",
				"gui.dndsheets.guide.page.dm_ownclock_2",
				"gui.dndsheets.guide.page.dm_encounters",
				"gui.dndsheets.guide.page.dm_encounters_2"),
			new Entry("gui.dndsheets.guide.entry.mazmorras",
				"gui.dndsheets.guide.page.dm_dungeons_1",
				"gui.dndsheets.guide.page.dm_dungeons_2",
				"gui.dndsheets.guide.page.dm_dungeons_3",
				"gui.dndsheets.guide.page.dm_dungeons_4"),
			new Entry("gui.dndsheets.guide.entry.contenido",
				"gui.dndsheets.guide.page.dm_content_packs",
				"gui.dndsheets.guide.page.dm_content_packs_2",
				"gui.dndsheets.guide.page.dm_more_packs",
				"gui.dndsheets.guide.page.dm_npc_ai",
				"gui.dndsheets.guide.page.dm_npc_ai_2",
				"gui.dndsheets.guide.page.dm_bind",
				"gui.dndsheets.guide.page.dm_bind_2"),
			new Entry("gui.dndsheets.guide.entry.hojas",
				"gui.dndsheets.guide.page.dm_sheet_admin",
				"gui.dndsheets.guide.page.dm_notes"),
			new Entry("gui.dndsheets.guide.entry.diario",
				"gui.dndsheets.guide.page.dm_journal")),
	};

	//BookViewScreen.TEXT_WIDTH y TEXT_HEIGHT son protected, así que van copiados: 114 px de ancho y
	//128/9 = 14 líneas por página. Lo que pase de ahí NO se recorta con puntos suspensivos ni añade una
	//página: desaparece sin avisar. La página de subir de nivel medía 704 caracteres, así que llevaba
	//tiempo enseñando poco más de la mitad de lo que decía, y nada en el juego lo delataba.
	private static final int PAGE_WIDTH = 114;
	private static final int PAGE_LINES = 128 / 9;
	//Cada página cierra con una línea en blanco y el enlace de vuelta; la primera de cada entrada gasta
	//otras dos en su título.
	private static final int FOOTER_LINES = 2;
	private static final int HEADER_LINES = 2;

	/** Una fila del índice. {@code target} es la página de contenido a la que salta, o -1 si es un rótulo. */
	private record IndexLine(Component label, int target) {
	}

	private GuideBook() {
	}

	public static void open(boolean includeDmPages) {
		//Con Patchouli instalado, la misma Guía se abre como manual. El texto es el mismo —las entradas
		//apuntan a estas mismas claves de idioma— así que no hay dos guías que mantener, solo dos formas
		//de leerla. Ver PatchouliCompat.
		if (PatchouliCompat.openOnClient()) return;

		Font font = Minecraft.getInstance().font;

		List<Component> content = new ArrayList<>();
		List<IndexLine> index = new ArrayList<>();
		index.add(rubric("gui.dndsheets.guide.index"));

		for (Chapter chapter : CHAPTERS) {
			if (chapter.dmOnly() && !includeDmPages) continue;
			index.add(new IndexLine(Component.empty(), -1));
			index.add(rubric(chapter.titleKey()));
			for (Entry entry : chapter.entries()) {
				//Dónde empieza esta entrada DENTRO del contenido: el índice todavía no sabe cuánto ocupa
				//él mismo, y ese desfase se suma abajo, ya paginado.
				int startsAt = content.size();
				appendEntry(font, content, entry);
				index.add(new IndexLine(
					Component.literal(" ").append(Component.translatable(entry.titleKey())), startsAt));
			}
		}

		List<Integer> heights = new ArrayList<>();
		for (IndexLine line : index) heights.add(measure(font, line.label().getString()));
		List<Integer> indexPages = GuideLayout.paginate(heights, PAGE_LINES);

		ListTag pages = new ListTag();
		int from = 0;
		for (int rows : indexPages) {
			MutableComponent text = Component.empty();
			for (int i = from; i < from + rows; i++) {
				if (i > from) text.append("\n");
				IndexLine line = index.get(i);
				//+1 porque CHANGE_PAGE cuenta desde 1 (BookViewScreen.handleComponentClicked).
				text.append(line.target() < 0 ? line.label()
					: line.label().copy().withStyle(linkToPage(indexPages.size() + line.target() + 1)));
			}
			from += rows;
			pages.add(StringTag.valueOf(Component.Serializer.toJson(text)));
		}
		for (Component page : content) {
			pages.add(StringTag.valueOf(Component.Serializer.toJson(page)));
		}

		CompoundTag tag = new CompoundTag();
		tag.putString("title", I18n.get("gui.dndsheets.guide.title"));
		tag.putString("author", "DndSheets");
		tag.put("pages", pages);

		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.setTag(tag);

		Minecraft.getInstance().setScreen(new BookViewScreen(new BookViewScreen.WrittenBookAccess(book)));
	}

	private static IndexLine rubric(String key) {
		return new IndexLine(Component.translatable(key).withStyle(ChatFormatting.BOLD), -1);
	}

	private static UnaryOperator<Style> linkToPage(int page) {
		return style -> style.withColor(ChatFormatting.DARK_BLUE).withUnderlined(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.CHANGE_PAGE, String.valueOf(page)));
	}

	/** Cuántas líneas ocupa un texto en una página, con el mismo repartidor con el que se va a dibujar. */
	private static int measure(Font font, String text) {
		return Math.max(1, font.getSplitter().splitLines(text, PAGE_WIDTH, Style.EMPTY).size());
	}

	/** Una entrada: su título arriba de la primera página, y una página de libro por cada trozo que quepa. */
	private static void appendEntry(Font font, List<Component> out, Entry entry) {
		Component header = Component.translatable(entry.titleKey()).withStyle(ChatFormatting.BOLD);
		for (String key : entry.pages()) {
			List<String> lines = new ArrayList<>();
			for (FormattedText line : font.getSplitter().splitLines(I18n.get(key), PAGE_WIDTH, Style.EMPTY)) {
				lines.add(line.getString());
			}
			int room = PAGE_LINES - FOOTER_LINES;
			for (String chunk : GuideLayout.wrap(lines, header == null ? room : room - HEADER_LINES, room)) {
				MutableComponent page = Component.empty();
				if (header != null) {
					page.append(header).append("\n\n");
					header = null;
				}
				page.append(chunk).append("\n\n")
					.append(Component.translatable("gui.dndsheets.guide.back").withStyle(linkToPage(1)));
				out.add(page);
			}
		}
	}
}
