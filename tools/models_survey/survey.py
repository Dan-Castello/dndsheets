"""Survey of ready-made creature models the mod could ship: permissive licence only (CC0, CC BY, MIT, Apache,
Unlicense), ranked by popularity. Read-only: it queries public APIs and writes a report, it downloads no art.

  python tools/models_survey/survey.py        -> reference/creature-model-survey.{json,md}

Sources and their popularity signal
- Modrinth mods (api.modrinth.com): real download counts and an SPDX licence; the repo's LICENSE is then read on GitHub
  to make sure it does not carve the assets out.
- Sketchfab (api.sketchfab.com): views and likes (it publishes no download counts) and a CC licence per model.

Gates, in order: licence (permissive) -> originality (not a copy of a Mojang mob or of another mod's creature: a
licence the uploader had no right to grant is no licence) -> format we can import (Java ModelPart / geo / Blockbench
glTF). Each rejection is kept in the report with its reason."""
import json, math, os, re, sys, time, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
OUT = os.path.join(REPO, 'reference')
UA = {'User-Agent': 'dndsheets-model-survey (github.com/Dan-Castello/dndsheets)'}

PERMISSIVE_SPDX = {'CC0-1.0', 'MIT', 'Apache-2.0', 'Unlicense', 'CC-BY-4.0', 'CC-BY-3.0', 'BSD-3-Clause', 'BSD-2-Clause', 'Zlib'}
PERMISSIVE_SKETCHFAB = {'CC0 Public Domain', 'CC Attribution'}
# Mojang's mobs (and famous mod creatures) re-uploaded as "CC BY": the uploader cannot license what they did not make.
NOT_ORIGINAL = re.compile(r'\b(ender ?dragon|iron golem|snow golem|wither|creeper|enderman|steve|alex|villager|pillager|'
                          r'zombie|skeleton|spider|cave spider|wolf|ghast|blaze|piglin|hoglin|warden|allay|vex|axolotl|'
                          r'phantom|guardian|shulker|ravager|evoker|vindicator|polar bear|mutant|wither storm|cobblemon|'
                          r'pokemon|fnaf|minecraft movie|slime|magma cube|witch|silverfish|endermite|strider|sniffer|'
                          r'drowned|husk|stray|bogged|breeze|camel|frog|bee|parrot|ocelot|panda|fox|goat|llama|horse|'
                          r'squid|dolphin|turtle|copper golem|demon slayer|naruto|genshin|mimicer|hide behind)\b', re.I)
# Mods in the mobs category that tune mobs instead of adding them.
UTILITY = re.compile(r'\b(control|filter|spawners?|music|torch\w*|feeding|breeding|status|config|farm|tweaks?|variants?)\b', re.I)
# Items, props and scenes tagged "minecraft": not creatures.
NOT_CREATURE = re.compile(r'\b(items?|sword|axe|bow|trident|staff|wand|glasses|totem|armou?r set|house|world|map|city|'
                          r'schematic|building|castle|room|chamber|tree|ore|block|weapon|tool|plushie?)\b', re.I)
# A mod in the "mobs" category that actually adds creatures (not a mob filter or a spawner tweak).
ADDS_CREATURES = re.compile(r'\b(adds?|new|introduces?|featuring)\b.{0,80}\b(mobs?|creatures?|monsters?|bosses|beasts?|'
                            r'animals?|undead)\b', re.I | re.S)
# Licence text -> SPDX, read from the repo's file itself (raw.githubusercontent.com has no API rate limit).
LICENCE_TEXT = [('MIT', r'Permission is hereby granted, free of charge'), ('Apache-2.0', r'Apache License'),
                ('CC0-1.0', r'CC0|Creative Commons Zero|No Copyright'), ('CC-BY-4.0', r'Creative Commons Attribution 4\.0'),
                ('Unlicense', r'free and unencumbered software'), ('LGPL', r'GNU LESSER GENERAL'), ('GPL', r'GNU GENERAL PUBLIC'),
                ('MPL-2.0', r'Mozilla Public License'), ('ARR', r'All rights reserved')]

# Gaps of monsters.json and the archetypes the survey looks for (search terms).
TERMS = ['dragon', 'goblin', 'orc', 'ogre', 'giant', 'kobold', 'lizardman', 'demon', 'devil', 'ghost', 'wraith', 'specter',
         'lich', 'mummy', 'ghoul', 'golem', 'wolf', 'bear', 'owlbear', 'lion', 'crocodile', 'snake', 'spider', 'scorpion',
         'slime', 'elemental', 'eagle', 'griffin', 'harpy', 'shark', 'kraken', 'witch', 'hag', 'centaur', 'minotaur',
         'beholder', 'mimic', 'treant', 'dinosaur', 'rat', 'bat']
MOD_TERMS = ['', 'necromancy', 'monsters', 'creatures', 'undead', 'dragons', 'dungeon']


def get(url):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
                return json.load(r)
        except Exception:
            time.sleep(2 * (attempt + 1))
    return None


# --- Modrinth ------------------------------------------------------------------------------------------------------

def repo_licence_text(repo):
    for name in ('LICENSE', 'LICENSE.txt', 'LICENSE.md', 'LICENCE', 'license', 'license.txt', 'COPYING'):
        try:
            url = 'https://raw.githubusercontent.com/%s/HEAD/%s' % (repo, name)
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read().decode('utf-8', 'replace')
        except Exception:
            continue
    return None


def modrinth():
    seen, rows = {}, []
    for term in MOD_TERMS:
        for lic in sorted(PERMISSIVE_SPDX):
            facets = json.dumps([['project_type:mod'], ['license:%s' % lic], ['categories:mobs']])
            q = urllib.parse.urlencode({'query': term, 'facets': facets, 'index': 'downloads', 'limit': 20})
            for h in (get('https://api.modrinth.com/v2/search?' + q) or {}).get('hits', []):
                seen.setdefault(h['slug'], h)
            time.sleep(0.2)
    for slug, h in seen.items():
        p = get('https://api.modrinth.com/v2/project/' + slug) or {}
        src = p.get('source_url') or ''
        repo_licence, excludes_assets = None, False
        m = re.match(r'https://github\.com/([^/]+/[^/#?]+)', src)
        if m:
            text = repo_licence_text(m.group(1).removesuffix('.git'))
            if text:
                repo_licence = next((spdx for spdx, pat in LICENCE_TEXT if re.search(pat, text, re.I)), 'unknown')
                # A carve-out says so in words; whole words only (MIT's own text has "PARTICULAR" and "NOT").
                excludes_assets = bool(re.search(r'\b(assets?|textures?|art(work)?|models?|resources?)\b[^.\n]{0,80}'
                                                 r'\b(are not|is not|not (covered|included|licensed|part)|excluded|all rights reserved)\b', text, re.I))
        rows.append(dict(source='modrinth', name=h['title'], slug=slug, url='https://modrinth.com/project/' + slug,
                         downloads=h['downloads'], follows=h['follows'], licence=h['license'], repo=src,
                         adds_creatures=bool(ADDS_CREATURES.search((h.get('description') or '') + ' ' + (p.get('body') or '')[:4000])),
                         repo_licence=repo_licence, excludes_assets=excludes_assets,
                         mc_1_20_1='1.20.1' in (p.get('game_versions') or []), loaders=p.get('loaders', [])))
        time.sleep(0.2)
    return rows


# --- Sketchfab -----------------------------------------------------------------------------------------------------

def sketchfab():
    seen = {}
    for term in TERMS:
        for style in ('blockbench', 'minecraft'):
            for lic in ('cc0', 'by'):
                q = urllib.parse.urlencode({'type': 'models', 'q': '%s %s' % (style, term), 'downloadable': 'true',
                                            'license': lic, 'count': 24, 'sort_by': '-viewCount'})
                for m in (get('https://api.sketchfab.com/v3/search?' + q) or {}).get('results', []):
                    tags = ' '.join(t['name'] for t in m.get('tags', []))
                    if not re.search(r'blockbench|minecraft|voxel|blocky', tags + ' ' + m['name'], re.I):
                        continue
                    if not re.search(re.escape(term), tags + ' ' + m['name'], re.I):
                        continue  # the search engine's loose matches: not the creature looked for
                    seen.setdefault(m['uid'], dict(source='sketchfab', name=m['name'], term=term, url=m['viewerUrl'],
                                                   views=m['viewCount'], likes=m['likeCount'],
                                                   licence=(m.get('license') or {}).get('label', '?'),
                                                   faces=m['faceCount'], animations=m['animationCount'],
                                                   author=m['user']['username']))
                time.sleep(0.25)
    return list(seen.values())


# --- evaluation ----------------------------------------------------------------------------------------------------

def evaluate(row):
    if row['source'] == 'modrinth':
        if row['licence'] not in PERMISSIVE_SPDX:
            return 'rejected', 'licence %s is not permissive' % row['licence']
        if not row['repo_licence']:
            return 'rejected', 'no source repository with a LICENSE to check what the licence covers'
        if row['repo_licence'] not in PERMISSIVE_SPDX:
            return 'rejected', 'the repo LICENSE is %s' % row['repo_licence']
        if row['excludes_assets']:
            return 'rejected', 'the LICENSE carves the assets out'
        if not row['adds_creatures'] or UTILITY.search(row['name']):
            return 'rejected', 'its description adds no creatures (a utility mod in the mobs category)'
        if not row['mc_1_20_1']:
            return 'review', 'permissive, but no 1.20.1 build: its models may use a newer format'
        return 'candidate', 'permissive licence covering the repo; models still checked one by one for vanilla geometry'
    if row['licence'] not in PERMISSIVE_SKETCHFAB:
        return 'rejected', 'licence %s' % row['licence']
    if NOT_CREATURE.search(row['name']):
        return 'rejected', 'an item, prop or scene, not a creature'
    if NOT_ORIGINAL.search(row['name']):
        return 'rejected', 'a Mojang mob or another mod\'s creature: the uploader cannot license it'
    if row['faces'] > 20000:
        return 'rejected', '%d faces: a scene or a mesh, not a cube model' % row['faces']
    return 'candidate', 'needs a manual download (Sketchfab requires a login) and the glTF cube importer'


def score(row):
    if row['source'] == 'modrinth':
        return round(math.log10(row['downloads'] + 1), 2)
    return round(math.log10(row['views'] + 1) + 2 * math.log10(row['likes'] + 1), 2)


def report(rows):
    os.makedirs(OUT, exist_ok=True)
    json.dump(rows, open(os.path.join(OUT, 'creature-model-survey.json'), 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
    md = ['# Creature model survey', '',
          'Generated by `tools/models_survey/survey.py` on %s. Permissive licences only (CC0, CC BY, MIT, Apache, Unlicense).' % time.strftime('%Y-%m-%d'),
          'Popularity: log10(downloads) for Modrinth mods; log10(views) + 2*log10(likes+1) for Sketchfab (it publishes no',
          'download counts). Gates: licence, then originality, then importable format. Decisions on individual models of a',
          'candidate mod are in the "Imported" section.', '']
    for source, cols in (('modrinth', ['score', 'name', 'downloads', 'licence', 'repo_licence', 'verdict', 'reason']),
                         ('sketchfab', ['score', 'name', 'views', 'likes', 'licence', 'animations', 'verdict', 'reason'])):
        sub = sorted((r for r in rows if r['source'] == source), key=lambda r: (r['verdict'] != 'candidate', -r['score']))
        md += ['## %s (%d)' % (source.capitalize(), len(sub)), '', '| ' + ' | '.join(cols) + ' |', '|' + '---|' * len(cols)]
        for r in sub[:60]:
            md.append('| ' + ' | '.join(('[%s](%s)' % (r['name'].replace('|', '/'), r['url'])) if c == 'name' else str(r.get(c, '')) for c in cols) + ' |')
        md.append('')
    md += IMPORTED
    open(os.path.join(OUT, 'creature-model-survey.md'), 'w', encoding='utf-8', newline='\n').write('\n'.join(md) + '\n')


# Decisions taken model by model on the candidates (tools/blockbench/import/javamodel.py IMPORTS).
IMPORTED = [
    '## Imported', '',
    'From **Goety – The Dark Arts** (Modrinth, MIT, repo LICENSE without asset carve-out, Forge 1.20.1). Every model',
    'was evaluated on its own: sharing Mojang\'s humanoid/skeleton/illager box layout is not a verdict by itself; what',
    'counts is what the author added and whose texture it is (Mojang\'s own boxes are never copied: the importer keeps',
    'only the pivots of HumanoidModel and the parts each model defines itself).', '',
    '| Model | Monsters | Decision |', '|---|---|---|',
    '| WraithModel | wraith, shadow (border variant) | imported: original rig, 3 texture variants |',
    '| SpecterModel | specter, ghost | imported |',
    '| BlackBeastModel | – | imported, then replaced by the Whulvk werewolf from Sketchfab (24 clips) |',
    '| BroodMotherModel | giant spider (x0.25) | imported, replaces the spider archetype there |',
    '| WebSpiderModel | giant wolf spider (x0.8) | imported |',
    '| RedstoneGolemModel | stone golem (x0.4) | imported, replaces the golem archetype there |',
    '| WhispererModel | shambling mound (x0.5) | imported |',
    '| IceGolem, GraveGolem, Gnasher, Wight, Reaper | – | left out: no monster of the bestiary fits |',
    '| NecromancerModel | lich (+ frost, mossy, drowned variants) | imported: skeleton proportions, own crown, pauldrons, loincloth, staff and textures |',
    '| CroneModel | green, sea and night hag | imported: illager base, own witch hat, hunch, cuffed arms, nose and texture |',
    '| WarlockModel | mage, archmage, cult fanatic | imported: illager geometry with small additions, own texture |',
    '| CultistModel (plain) | – | left out: its fanatics wear the vanilla pillager texture (Mojang\'s art) |',
    '| HauntedArmorModel, LichModeModel | – | left out: no geometry of their own (vanilla armour, the player\'s skin) |',
    '| SkullLordModel | – | left out: no floating-skull monster in the bestiary |',
    '| GraveGolemModel | – | left out: 4.9 blocks tall at Medium, no size category fits |',
    '| SquallGolemModel | – | left out: the elemental archetype covers it |', '',
    'From **Sketchfab** (CC BY 4.0, downloaded with the API and converted by `tools/blockbench/import/gltf.py`, textures',
    're-baked to box UV, each with a size factor in `creatures/scales.json`):', '',
    '| Model | Author | Monsters | Decision |', '|---|---|---|---|',
    '| [Minecraft] Shark | sedona1029 | hunter, reef and giant shark | imported (`blue_shark`, x0.7) |',
    '| Pixel Kraken | Ycar | kraken, giant octopus | imported (`kraken`, x0.6) |',
    '| Leathern Drake - Blockbench | drageondb | wyvern | imported (`drake`, x0.4), with its own idle and fire-breath clips |',
    '| Minecraft - Orc | samtorres | orc | imported (x1.7), own idle/attack/hurt clips; replaces the brute archetype there |',
    '| Whulvk Werewolf Lycan | drageondb | werewolf (hybrid) | imported, 300 cubes and its own idle |',
    '| Snake | kingillager | constrictor and poisonous snakes, swarm | imported (`rattlesnake`), own attack; replaces the serpent archetype there |',
    '| Crocodile - minecraft | radher | crocodile, giant crocodile | imported (x0.5), replaces the reptile archetype there |',
    '| Scorpion | kingillager | scorpion, giant scorpion | imported (they were vanilla spiders) |',
    '| Minecraft Rat - blockbench | ohdear69420 | rats, swarm of rats, wererat | imported; its "Stand" clip is a stand-up, not used |',
    '| Minecraft Lion | APlant | lion | imported (x0.6) |',
    '| Minotaur alpha | HiimSix. | minotaur | imported (x0.4), own idle |',
    '| Mummy | Nightfoxymc | mummy, mummy lord | imported, replaces the undead archetype there |',
    '| Ogrin giant | margaux_kane | ogre, hill giant | imported (`ogre`, x0.45), own idle; first discarded, recovered (see below) |',
    '| Minecraft Blue Troll | Creative_Orc_ | troll | imported (x0.45); recovered |',
    '| Kodiak Bear | DrPlatipi | black, brown and were- bears | imported (`bear`, x0.8); recovered |',
    '| Aguia negra da Savana | dinei00161 | eagle, giant eagle | imported (`eagle`, x0.3), own fly and attack; recovered |',
    '| Minecraft Stone Golem Mob | purpleanvil | earth elemental | imported (`earth_golem`), own idle and attack; recovered |',
    '| The Lich (Blockbench) | – | – | left out: an FBX-converted triangle mesh, not cubes |',
    '| Minecraft Centaur, Centaur // Satyr | – | – | left out: one merged block / no horse body in the export |',
    '| Infected Bear, Golden Protector, Minecraft Scorpion | – | – | left out: no monster fits / unreadable |',
    '| Ghoul v4 Blox Fruits, Mimikyu, Indominus Rex, I Heard It Too wraiths, AsteronMod Ogre, Lololoshka characters | – | – | left out: characters of other franchises or mods |',
    '| Succubus Boss for Model Engine | SuccubusInSc | – | left out: effect props floating around a T-pose |',
    '| Ghasklle | LironHero | – | left out: loose fragments |',
    '| Golem | bada9889 | – | left out: modelled facing sideways, no monster fits |',
    '| Realistic / Fire dragon minecraft | nayav / DPancito | – | left out: broken scale; the dragon archetype covers them |',
    '| T-rex Minecraft, Minecraft Bat | – | – | left out: merged OBJ/FBX meshes, not cubes |', '',
    '### Why some were first discarded', '',
    'A per-cube comparison of every converted model against its glTF (full transform chain) found four bugs, all fixed:',
    '- the review renderer ignored the rotation of each cube (troll, bear, stone golem, eagle only LOOKED broken);',
    '- Euler angles lost a component in gimbal lock (Y = +-90): tentacles of the kraken came out wrong;',
    '- the Sketchfab display orientation chosen by the author was applied: the orc faced backwards, the giant was turned;',
    '- a uniform scale inside the content (FBX-converted exports) was ignored.',
    'After the fixes all 20 downloaded models match their glTF exactly; the ones still left out are left out for what',
    'they are, not for the conversion.', '']


def main():
    # --from-json re-applies the gates and the scores to the last survey without querying the APIs again.
    cached = os.path.join(OUT, 'creature-model-survey.json')
    rows = json.load(open(cached, encoding='utf-8')) if '--from-json' in sys.argv else modrinth() + sketchfab()
    for r in rows:
        r['verdict'], r['reason'] = evaluate(r)
        r['score'] = score(r)
    report(rows)
    c = sum(r['verdict'] == 'candidate' for r in rows)
    print('%d models/projects surveyed, %d candidates -> reference/creature-model-survey.md' % (len(rows), c))


if __name__ == '__main__':
    sys.exit(main())
