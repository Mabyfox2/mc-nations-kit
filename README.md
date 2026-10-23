# MC Nations Kit

Five Paper plugins made for **United Nations of MC With Gang**, a nations-themed Minecraft server.
Players found nations, share a treasury, claim land, and can only raid each other once the UN
(the server owner, called the Secretary-General) approves a war.

Everything runs on the server, so players don't install anything. Java and Bedrock players
(phone, tablet, console, through Geyser + Floodgate) can all join.

## The plugins

| Plugin | What it does | Needs |
|---|---|---|
| [UNBank](UNBank) | Nations, nation treasuries, a daily tax, a daily login reward and the UN treasury. `/nation`, `/un` | EssentialsX + VaultUnlocked |
| [UNBorders](UNBorders) | Nations claim land chunk by chunk. Outsiders can't build, break, open or blow anything up there unless the two nations are at war. `/claim`, `/border` | UNBank (BlueMap optional) |
| [UNGraves](UNGraves) | When you die, your items wait in a grave only you can open, unless your nation is at war with the looter's. `/graves` | Nothing (works with UNBank + UNBorders) |
| [UNMenu](UNMenu) | Button menus for Bedrock players: nation, land, money, homes, teleports. `/menu` | Floodgate |
| [UsefulTrades](UsefulTrades) | Less grinding: 71 extra villager trades, steady villager prices, x5 mob drops, higher anvil enchant levels, a money sidebar | Nothing (money sidebar uses EssentialsX + VaultUnlocked) |

## Install

1. Run a **Paper 26.2** server on **Java 25**.
2. Add [EssentialsX](https://essentialsx.net/) and [VaultUnlocked](https://www.spigotmc.org/resources/vaultunlocked.117277/) for money.
   Optional: [BlueMap](https://bluemap.bluecolored.de/) for a web map, [Geyser and Floodgate](https://geysermc.org/) for Bedrock players.
3. Download the jars you want from the [latest release](../../releases/latest) and put them in your server's `plugins` folder.
4. Restart the server. Each plugin makes its own `plugins/<Name>/config.yml` you can change.

UNBorders needs UNBank. Everything else can be used on its own.

## Build it yourself

You need JDK 25 and a Paper 26.2 server folder that has been started once (so `libraries/` holds the Paper API).
The plugins these hook into (VaultUnlocked, BlueMap, floodgate, EssentialsX) are read from its `plugins` folder.

```sh
sh build.sh /path/to/server            # builds all five into dist/
sh build.sh /path/to/server UNGraves   # or just the ones you name
```

On Windows, run it from Git Bash.

## License

MIT, see [LICENSE](LICENSE). Use it, change it, share it.
