package net.hawthorn.dndsheets.client.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>The arithmetic behind splitting the Guide into written-book pages. Deliberately free of anything
 * Minecraft-specific: {@link GuideBook} is what measures the text, since it has the client's
 * {@code Font}, and here we just distribute what's already been measured.</p>
 *
 * <p>This is kept separate because it's the part that can fail silently — how many pages the index takes
 * up determines the page number EVERY one of its rows jumps to, so one extra line sends every link to the
 * wrong page — and {@code JsonContentSelfTest} runs without the game: tangled together with {@code Font}
 * there would be no way to test it. It's the same reason {@code CharacterRules} exists.</p>
 */
public final class GuideLayout {

	private GuideLayout() {
	}

	/**
	 * <p>Joins already-wrapped lines into chunks that fit on a page. The first chunk can hold less than
	 * the rest, which is where the entry's title goes.</p>
	 *
	 * <p>They're rejoined with a space because that's exactly what the splitter strips out when wrapping:
	 * the text gets wrapped the same way again when it's rendered.</p>
	 */
	public static List<String> wrap(List<String> lines, int firstLimit, int restLimit) {
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int used = 0;
		int limit = firstLimit;
		for (String line : lines) {
			if (used == limit) {
				chunks.add(current.toString());
				current.setLength(0);
				used = 0;
				limit = restLimit;
			}
			if (used > 0) current.append(' ');
			current.append(line);
			used++;
		}
		//Without the guard above, a text ending exactly at the limit would leave a blank page behind it.
		//With it, the last chunk always carries something.
		chunks.add(current.toString());
		return chunks;
	}

	/**
	 * <p>Distributes rows of known height into pages of {@code limit} lines, and returns how many rows
	 * each page holds. A row taller than an entire page gets its own page to itself instead of blocking
	 * the layout.</p>
	 */
	public static List<Integer> paginate(List<Integer> heights, int limit) {
		List<Integer> pages = new ArrayList<>();
		int rows = 0;
		int used = 0;
		for (int height : heights) {
			if (used + height > limit && rows > 0) {
				pages.add(rows);
				rows = 0;
				used = 0;
			}
			rows++;
			used += height;
		}
		pages.add(rows);
		return pages;
	}
}
