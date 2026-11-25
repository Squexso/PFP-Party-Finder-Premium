# PFP

Client-side Fabric mod for Minecraft **26.2** (Hypixel SkyBlock). Players on your blacklist are kicked from your party automatically when they join, via Party Finder (Dungeons / Kuudra) or optionally by invite.

When someone on the list joins, the mod writes a party message first and kicks shortly after:

```
[PFP] Name was kicked: reason
```

## Requirements

- Minecraft 26.2, Java 25
- Fabric Loader 0.20.1+
- Fabric API

The mod only runs on the client.

## Commands

| Command | What it does |
| --- | --- |
| `/pfp` | Opens the GUI |
| `/pfp add <name> <reason> [7D\|inf]` | Blacklist for Dungeons and Kuudra |
| `/pfp dungeon <name> <reason> [7D\|inf]` | Dungeons only |
| `/pfp kuudra <name> <reason> [7D\|inf]` | Kuudra only |
| `/pfp remove <name>` | Remove an entry |
| `/pfp list` | Print the list in chat |
| `/pfp msg kick\|add <text>` | Change a message text |

Duration: `7D` = 7 days, `inf` = permanent. Without a duration the global auto-remove setting applies.

Invites carry no mode, so only entries set to "both" (`/pfp add`) are kicked there.

## Messages

- The kick message and the "added" message can be changed in the GUI (tab "Texts") or with `/pfp msg`.
- `{name}` and `{reason}` are replaced. `[PFP]` is always put in front.
- Party chat commands (anyone in the party, any capitalisation, replies only via `/pc`, 3 s cooldown, can be turned off in the GUI):
  - `!pfp` shows where to get the mod
- Adding a player announces it in party chat. This can be turned off too.

## Party protection

- **Party Finder tooltip:** hovering a listing (Dungeons or Kuudra) adds a red `Blacklisted: Name - reason` line for every listed member who is on your blacklist, plus a `Tags:` line for tagged players. The reason can be hidden in Settings.
- **Party check:** when you join a party, blacklisted members and a blacklisted leader are reported privately in chat. Nothing is kicked by this.
- **History:** the last 20 parties (type and floor/tier if you joined through the Party Finder tooltip, members, leader, times, blacklist matches, players PFP kicked), stored in `config/pfp_history.json`.
- **Repeat alerts:** a private note when someone appears in 3 or more of your recorded parties.
- **Tags:** `/pfp tag <name> <Trusted|Friend|Toxic|Carry|Suspicious|none>`, separate from the blacklist. In the GUI, click a player name (Overview, History details, Blacklist) to cycle the tag, right-click removes it.
- **Kuudra supplies:** counted per player from the `recovered one of Elle's supplies!` chat line and stored in the History session (only when the run start was seen; otherwise shown as unknown).
- **Session result:** `Completed` only when the run-end message was seen, otherwise `Unknown`.
- **Secret tracker (optional, off by default):** secrets per player = Hypixel API counter after the run minus before it. Needs your own key: `/pfp apikey <key>` (`clear` removes it, never printed). A difference of 0 is shown as `0 (unconfirmed)` because the API can lag; failures show `Unknown`.
- **Debug log:** `/pfp debug on` writes party/run chat lines and Party Finder tooltips to `config/pfp_debug.log` to verify Hypixel message formats.
- Overview activity is persisted in `config/pfp_activity.json`.

## Extras

- Keybind "Send coordinates to party chat" (unbound by default; set it in the GUI tab "Texts" or under Controls > PFP) or `/pfp coords` sends `x: .. y: .. z: ..` to the party.
- Repeated identical chat lines are merged into one line with a counter, for example `... fainted from pressure. (x2)`. Can be turned off in the GUI (tab "Texts").

## Config

`config/pfp.json` in your Minecraft instance.

## Building

```
gradle build
```

The jar ends up in `build/libs/`.

## License

Code: see `LICENSE` if present, otherwise all rights reserved by the author until one is added.
The bundled font Poppins is licensed under the SIL Open Font License 1.1, see `assets/pfp/font/OFL.txt`.
- **Requirement check (Gear tab):** a list of items every joining player must own. Add them by typing a name (`Term`, `Juju`, or a SkyBlock id), by clicking items in your own inventory (the id is read from the item), or from a preset list. The inventory, armor, equipment and ender chest of the player are read from the Hypixel API through the proxy (`proxy/`). A player who lacks an item is announced in party chat and kicked, if you are the leader. If the inventory API is off or the lookup fails, the result is unknown and nobody is kicked.
- **Party chat commands** (switch `!pfp replies` in Settings, right-click it to see them): `!pfp` (project link), `!pfp list` (how many players are on the list), `!pfp full list` (all names, split over a few messages) and `!pfp <name>` (the reason, or that the player is not on the list). Answers go to party chat, at most once every 3 seconds.
