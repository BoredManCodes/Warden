# Dashboard: Grim (Anticheat)

`/dash/violations` surfaces recent Grim anticheat violations in a sortable table. Visible to **Mod and above**.

The page only does anything if [GrimAC](https://github.com/GrimAnticheat/Grim) is installed on your Paper server. Warden does not bundle Grim; it is a softdepend.

---

## Without Grim installed

The page renders a "Grim is not installed" notice with a link to the GrimAC SpigotMC page. No data, no errors.

---

## With Grim installed

A live table of recent violations:

- Player name
- Check (the Grim check that triggered, e.g. `KillauraA`, `ScaffoldRotations`)
- Violation level (VL): how many violations of this check the player has accumulated
- Timestamp
- Verbose details: the diagnostic blob Grim emits when verbose mode is on

The table is sortable by every column; click a header to sort.

---

## Top checks

A pill bar at the top of the page shows the top 5 most-tripped checks across all players in the visible window. Useful for spotting check noise: if `KillauraA` is firing constantly for the same player while they are using a legitimate combat mod, you may want to whitelist them in Grim's config.

---

## Result limit

The page loads the last N violations (default 200). A picker at the top lets you raise it to 1000 or lower to 50. Larger windows are slower to load but useful for retrospective analysis.

---

## Mod workflow

Click into a player name to open `/dash/members/{discordId}` if Warden can resolve a Discord account via DiscordSRV. From there you have the full member context to decide whether to mod-action them.

The Grim violations themselves are not actionable from the Warden dashboard; Warden only **reads** the violation log. Take action via Grim's own punishment config or via the in-game `/ban` etc. commands.
