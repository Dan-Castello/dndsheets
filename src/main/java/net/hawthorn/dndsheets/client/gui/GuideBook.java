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

//Guide for anyone who doesn't know how to use the mod (players and DM). Uses vanilla's written book
//(BookViewScreen) instead of a custom screen: pagination and rendering for free, without giving the
//item to the player or going through the server — it's built and opened entirely on the client.
//
//With Patchouli installed the Guide opens as a manual, with an index, categories, search and
//bookmarks (see PatchouliCompat). Without Patchouli it used to be a strip of 31 loose pages that could
//only be read by flipping through them one at a time, so the two things the manual really provided are
//rebuilt here by hand: the book is SPLIT into the same categories and entries as the Patchouli one, and
//it starts with an index whose rows are links (ClickEvent.CHANGE_PAGE, which BookViewScreen already
//knows how to resolve). Every page links back to the index.
//
//The two versions teach the same content in the same order: each Entry here is an entry from
//assets/dndsheets/patchouli_books/guide/en_us/entries/<category>/<file>.json, and
//JsonContentSelfTest.checkPatchouliBook fails if the two lists drift apart.
public class GuideBook {
	private record Entry(String titleKey, String... pages) {
	}

	private record Chapter(String titleKey, boolean dmOnly, Entry... entries) {
	}

	private static final Chapter[] CHAPTERS = {
		//First of all, and deliberately so: the rest of the Guide teaches the MOD assuming knowledge of
		//the game itself — it says AC, saving throw and advantage without defining them — and someone
		//joining a world with this installed for the first time has no reason to have ever played D&D.
		//These four entries are the only thing that can be read knowing nothing, so they're read first.
		new Chapter("gui.dndsheets.guide.cat.first_steps", false,
			new Entry("gui.dndsheets.guide.entry.what_is_this",
				"gui.dndsheets.guide.page.intro",
				"gui.dndsheets.guide.page.roles"),
			new Entry("gui.dndsheets.guide.entry.dice",
				"gui.dndsheets.guide.page.d20",
				"gui.dndsheets.guide.page.advantage_basics"),
			new Entry("gui.dndsheets.guide.entry.glossary",
				"gui.dndsheets.guide.page.glossary_1",
				"gui.dndsheets.guide.page.glossary_2"),
			new Entry("gui.dndsheets.guide.entry.getting_started",
				"gui.dndsheets.guide.page.first_ten",
				"gui.dndsheets.guide.page.first_combat")),

		new Chapter("gui.dndsheets.guide.cat.character", false,
			new Entry("gui.dndsheets.guide.entry.sheet",
				"gui.dndsheets.guide.page.sheet",
				"gui.dndsheets.guide.page.sheet_2",
				"gui.dndsheets.guide.page.characters",
				"gui.dndsheets.guide.page.hud_states"),
			new Entry("gui.dndsheets.guide.entry.level_up",
				"gui.dndsheets.guide.page.level_up",
				"gui.dndsheets.guide.page.level_up_2",
				"gui.dndsheets.guide.page.level_up_3",
				"gui.dndsheets.guide.page.subclass"),
			new Entry("gui.dndsheets.guide.entry.rest",
				"gui.dndsheets.guide.page.rest",
				"gui.dndsheets.guide.page.death_saves"),
			new Entry("gui.dndsheets.guide.entry.class_items",
				"gui.dndsheets.guide.page.class_items",
				"gui.dndsheets.guide.page.wildshape",
				"gui.dndsheets.guide.page.wildshape_2"),
			new Entry("gui.dndsheets.guide.entry.compendium",
				"gui.dndsheets.guide.page.compendium"),
			new Entry("gui.dndsheets.guide.entry.magic_items",
				"gui.dndsheets.guide.page.magic_items")),

		new Chapter("gui.dndsheets.guide.cat.combat", false,
			new Entry("gui.dndsheets.guide.entry.rolls",
				"gui.dndsheets.guide.page.rolling",
				"gui.dndsheets.guide.page.skills",
				"gui.dndsheets.guide.page.private_rolls"),
			new Entry("gui.dndsheets.guide.entry.turns",
				"gui.dndsheets.guide.page.turns",
				"gui.dndsheets.guide.page.turn_actions"),
			new Entry("gui.dndsheets.guide.entry.cover",
				"gui.dndsheets.guide.page.cover",
				"gui.dndsheets.guide.page.vision",
				"gui.dndsheets.guide.page.vision_2",
				"gui.dndsheets.guide.page.distance"),
			new Entry("gui.dndsheets.guide.entry.magic",
				"gui.dndsheets.guide.page.spells",
				"gui.dndsheets.guide.page.spell_prepare",
				"gui.dndsheets.guide.page.spell_casting_time",
				"gui.dndsheets.guide.page.spell_schools")),

		//The Patchouli book shows the DM pages to everyone: a manual with an index can't hide half the
		//index without looking odd, and what's in there is how to use the mod, not anyone's secret. The
		//written book does hide them, because doing so costs nothing here — that's this dmOnly.
		new Chapter("gui.dndsheets.guide.cat.dm", true,
			new Entry("gui.dndsheets.guide.entry.first_session",
				"gui.dndsheets.guide.page.dm_first_1",
				"gui.dndsheets.guide.page.dm_first_2",
				"gui.dndsheets.guide.page.dm_first_3"),
			new Entry("gui.dndsheets.guide.entry.panel",
				"gui.dndsheets.guide.page.dm_panel",
				"gui.dndsheets.guide.page.dm_commands",
				"gui.dndsheets.guide.page.dm_commands_2"),
			new Entry("gui.dndsheets.guide.entry.wands",
				"gui.dndsheets.guide.page.dm_wand",
				"gui.dndsheets.guide.page.dm_turns"),
			new Entry("gui.dndsheets.guide.entry.monsters",
				"gui.dndsheets.guide.page.dm_creatures",
				"gui.dndsheets.guide.page.dm_bosses",
				"gui.dndsheets.guide.page.dm_bosses_2",
				"gui.dndsheets.guide.page.dm_ownclock",
				"gui.dndsheets.guide.page.dm_ownclock_2",
				"gui.dndsheets.guide.page.dm_encounters",
				"gui.dndsheets.guide.page.dm_encounters_2"),
			new Entry("gui.dndsheets.guide.entry.dungeons",
				"gui.dndsheets.guide.page.dm_dungeons_1",
				"gui.dndsheets.guide.page.dm_dungeons_2",
				"gui.dndsheets.guide.page.dm_dungeons_3",
				"gui.dndsheets.guide.page.dm_dungeons_4",
				"gui.dndsheets.guide.page.dm_dungeons_5"),
			new Entry("gui.dndsheets.guide.entry.content",
				"gui.dndsheets.guide.page.dm_content_packs",
				"gui.dndsheets.guide.page.dm_content_packs_2",
				"gui.dndsheets.guide.page.dm_more_packs",
				"gui.dndsheets.guide.page.dm_npc_ai",
				"gui.dndsheets.guide.page.dm_npc_ai_2",
				"gui.dndsheets.guide.page.dm_bind",
				"gui.dndsheets.guide.page.dm_bind_2"),
			new Entry("gui.dndsheets.guide.entry.sheets_notes",
				"gui.dndsheets.guide.page.dm_sheet_admin",
				"gui.dndsheets.guide.page.dm_notes"),
			new Entry("gui.dndsheets.guide.entry.journal",
				"gui.dndsheets.guide.page.dm_journal")),
	};

	//BookViewScreen.TEXT_WIDTH and TEXT_HEIGHT are protected, so they're copied here: 114 px wide and
	//128/9 = 14 lines per page. Anything past that is NOT truncated with an ellipsis or given an extra
	//page: it just disappears without warning. The level-up page was 704 characters long, so it had
	//been showing barely more than half of what it said for a while, with nothing in-game to reveal it.
	private static final int PAGE_WIDTH = 114;
	private static final int PAGE_LINES = 128 / 9;
	//Every page ends with a blank line and the back link; the first page of each entry spends two more
	//lines on its title.
	private static final int FOOTER_LINES = 2;
	private static final int HEADER_LINES = 2;

	/** One index row. {@code target} is the content page it jumps to, or -1 if it's a heading. */
	private record IndexLine(Component label, int target) {
	}

	private GuideBook() {
	}

	public static void open(boolean includeDmPages) {
		//With Patchouli installed, the same Guide opens as a manual. The text is the same — the entries
		//point at these same language keys — so there aren't two guides to maintain, just two ways of
		//reading it. See PatchouliCompat.
		if (PatchouliCompat.openOnClient()) return;

		Font font = Minecraft.getInstance().font;

		List<Component> content = new ArrayList<>();
		List<IndexLine> index = new ArrayList<>();
		index.add(rubric("gui.dndsheets.guide.index"));

		//This used to hide the dmOnly chapters (Panel, Wands, Dungeons...) from anyone without operator
		//permission — the same reasoning that already applied to Patchouli above now applies here too:
		//"it's how to use the mod, not anyone's secret." With Solo mode (see Config.soloMode/DndsheetsMod.
		//canActAsDm) anyone can end up being the one summoning monsters or generating a dungeon, and the
		//client has no cheap way to know whether that mode is on to decide what to show — so the only
		//dungeon guide that exists was staying invisible for exactly the people who need it most.
		//includeDmPages stays as a parameter (the shape of the message that carries it isn't removed) but
		//no longer filters anything; see TutorialOpenMessage if it ever needs to be fully cleaned up.
		for (Chapter chapter : CHAPTERS) {
			index.add(new IndexLine(Component.empty(), -1));
			index.add(rubric(chapter.titleKey()));
			for (Entry entry : chapter.entries()) {
				//Where this entry starts WITHIN the content: the index doesn't yet know how much space it
				//takes up itself, and that offset is added below, once paginated.
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
				//+1 because CHANGE_PAGE counts from 1 (BookViewScreen.handleComponentClicked).
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

	/** How many lines a text takes up on a page, using the same line splitter that will render it. */
	private static int measure(Font font, String text) {
		return Math.max(1, font.getSplitter().splitLines(text, PAGE_WIDTH, Style.EMPTY).size());
	}

	/** One entry: its title atop the first page, and one book page for each chunk that fits. */
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
