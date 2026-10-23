# UNBorders

Nations claim land one chunk at a time. Inside claimed land, outsiders can't build, break, open chests,
start fires or blow anything up. The protection drops only between two nations the UN has put at war.

**Needs:** UNBank. **Optional:** VaultUnlocked + EssentialsX (to charge for claims), BlueMap 5 (adds a "Nations" layer to the web map).

## Commands

| Command | Who | What |
|---|---|---|
| `/claim` | nation leader or treasurer | Claim the chunk you're standing in |
| `/unclaim [all]` | nation leader or treasurer | Give a chunk (or all your land) back to the wild |
| `/border map` | everyone | Text map of the borders around you |
| `/border list`, `/border wars` | everyone | Every nation's land, and current wars |
| `/border war <a> <b>`, `peace <a> <b>` | op | Start or end a war by hand |
| `/border bypass`, `remove`, `reload` | op | Build anywhere, delete a claim, reload config |

Wars started with UNBank's `/un war` and ended with `/un peace` are picked up automatically.
Permissions: `unborders.use` (everyone), `unborders.admin` (op). `/borders` works too.

## Settings (config.yml)

- `claim-cost` (50): price of one chunk.
- `base-chunks` (16) + `chunks-per-member` (8) x members: how much land a nation can hold.
- `must-connect` (true): new land has to touch land the nation already owns.
- `gap-between-nations` (1): chunks of wild land that must stay between two nations.
- `disabled-worlds`: worlds where nobody can claim.

Claims and wars are saved in `plugins/UNBorders/claims.yml`.
