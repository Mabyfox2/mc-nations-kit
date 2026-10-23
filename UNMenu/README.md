# UNMenu

Pop-up button menus for Bedrock players (phone, tablet, PlayStation, Xbox, Switch), so they don't have to type commands.
Java players can ignore it.

**Needs:** Floodgate. **Works with:** EssentialsX, VaultUnlocked, UNBank, UNBorders (their buttons show up when they're installed).

## How it works

- Bedrock players get a "Server Menu" compass in their hotbar. Using it, or typing `/menu`, opens the menu:
  Nation, Land and borders, Money, Homes, Teleport and Chat.
- Every button runs the normal command as the player, so permissions, costs and teleport warm-ups still apply.
- Pop-ups for incoming `/tpa` requests (Accept or Deny) and nation invites (Join).
- The compass can't be dropped or put in chests, stays out of graves, and comes back on respawn.
  Players can remove it from the menu; `/menu` always works.

## Settings (config.yml)

- `give-compass` (true): hand out the menu compass.
- `map-link`: a web map address (for example your BlueMap) shown in the Land menu. Empty hides it.

Changes take effect on the next restart. Permission: `unmenu.use` (everyone).
