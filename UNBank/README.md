# UNBank

Nations with a shared treasury, a daily tax and a UN treasury run by the Secretary-General (op).
Player money lives in EssentialsX (through Vault); nation and UN balances are kept in `plugins/UNBank/data.yml`.

**Needs:** EssentialsX + VaultUnlocked (or Vault).

## How it works

- The first time a player joins each day they get a daily reward, and their nation collects its daily tax from them.
  A share of every tax goes to the UN treasury.
- Founding a nation costs money, which goes to the UN.
- When the Secretary-General approves a war with `/un war`, the attacking nation pays the UN a war fee.
  UNBorders and UNGraves watch for this and open raiding between the two nations until `/un peace`.

## Commands

| Command | Who | What |
|---|---|---|
| `/nation create <name>` | everyone | Found a nation |
| `/nation join <name>`, `leave`, `info [name]`, `list` | everyone | Join (needs an invite), leave, look up nations |
| `/nation deposit <amount>` | members | Put money in the treasury |
| `/nation withdraw <amount>`, `pay <player> <amount>` | leader, treasurer | Spend from the treasury |
| `/nation invite`, `kick`, `treasurer`, `setleader <player>` | leader | Manage members |
| `/nation tax <amount>`, `disband` | leader | Set the daily tax, end the nation |
| `/un balance` | everyone | See the UN treasury |
| `/un war <attacker> <defender>`, `/un peace <a> <b>` | op | Approve or end a war |
| `/un fine <nation or player> <amount>` | op | Take money into the UN treasury |
| `/un award <nation or player> <amount>` | op | Pay out of the UN treasury |
| `/un reparations <from> <to> <amount>` | op | Make one nation pay another |
| `/un reload` | op | Reload config.yml |

`/n` is short for `/nation`. Permissions: `unbank.nation` and `unbank.un` (everyone), `unbank.sg` (op).

## Settings (config.yml)

`daily-reward` (25), `nation-create-cost` (500), `max-daily-tax` (20), `un-dues-percent` (10), `war-fee` (1000).
