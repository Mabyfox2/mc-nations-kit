# UsefulTrades

"Less grinding" tweaks for a vanilla-feeling server. Every part can be changed or turned off in
`plugins/UsefulTrades/config.yml`, and `/usefultrades reload` applies it without a restart.

## What it adds

- **71 extra villager trades** across 14 professions, added on top of the vanilla ones as villagers level up:
  enchanted books, explorer maps to real nearby structures (stronghold, ancient city, trial chambers...),
  wool, leather, string, gunpowder and more. Write your own in the `trades:` section.
- **Steady villager prices:** trade prices don't climb with demand. This also fixes plugins like
  InfiniteVillagerTrading, whose endless uses made vanilla demand overflow and push prices up to 40x.
- **Optional no grudges** (`villagers.no-grudges`, off by default): villagers forget being hit.
- **x5 mob drops** for mobs a player kills. XP, shearing, crops and mob farms stay normal, and anything a mob
  wore, held or carried drops once, so nothing can be duplicated.
- **Anvil:** combine two books or items of the same level to go past the vanilla max, with a cap per enchantment
  (Fortune, Looting, Luck of the Sea 5; most combat enchants 7; Thorns, Knockback, Fire Aspect 3; others 10).
  The cost never shows "Too Expensive". Gear already above a cap keeps its level.
- **Money sidebar** on Java and Bedrock showing the player's balance. `/moneybar` hides or shows it.

## Commands

| Command | Who | What |
|---|---|---|
| `/usefultrades list`, `reload`, `selftest` | op | See, reload or test the extra trades |
| `/moneybar` | everyone | Toggle the money sidebar |

## Extra: sell prices

[`extras/worth.yml`](extras/worth.yml) (also in [downloads](../downloads)) is an EssentialsX price list for `/sell` that fits this economy,
including cheap prices for raw blocks (dirt $0.01). Copy it to `plugins/Essentials/worth.yml` if you want it.
