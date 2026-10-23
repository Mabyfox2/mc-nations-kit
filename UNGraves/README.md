# UNGraves

When a player dies, their items and XP go into a grave (a skeleton skull with a floating "name's grave" label)
instead of spilling on the ground. It shows up on Java and Bedrock.

**Only works when the `keepInventory` gamerule is off.** While it's on, the plugin does nothing.

## How it works

- The owner right-clicks or punches the grave to get everything back, armor and hotbar in the same slots.
  Anything that doesn't fit stays in the grave.
- Nobody else can open it, except members of a nation that is at war with the owner's nation
  (read from UNBank and UNBorders). They get a take-only chest view, and the owner is told.
- Graves can't be broken, blown up, pushed by pistons, washed away or changed by mobs. They never expire.

## Commands

| Command | Who | What |
|---|---|---|
| `/graves` | everyone | Where your graves are |
| `/graves <player\|all>` | op | Everyone's graves |
| `/graves test` | op | A practice grave with 3 dirt in front of you |

Ops (`ungraves.admin`) can open any grave. `/grave` works too. Graves are saved in `plugins/UNGraves/graves.yml`.
