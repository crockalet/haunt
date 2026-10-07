# Haunt

Free, open-source fake GPS / mock location app for Android, built with Kotlin Multiplatform.
It's designed for developers and can be driven by AI agents over ADB through the `haunt` CLI and its built-in MCP server.

> Status: design phase. See [docs/DESIGN.md](docs/DESIGN.md).

## Agent skill

[`skills/haunt/SKILL.md`](skills/haunt/SKILL.md) teaches coding agents to drive Haunt with the `haunt` CLI or
`haunt mcp`: setup, teleporting, routes and GPX/KML playback, checking the result, and fixing common errors.

In Claude Code, install it as a plugin:

```
/plugin marketplace add crockalet/haunt
/plugin install haunt@haunt
```

Other agents that read Agent Skills can use the `skills/haunt/` folder as is (for example, copy it to `~/.claude/skills/haunt`).
The skill still needs the `haunt` CLI and the app; it explains where to get them.

## Credits

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL), tiles by
[OpenFreeMap](https://openfreemap.org) in the [OpenMapTiles](https://openmaptiles.org) schema; routing by
[OSRM](https://project-osrm.org) on servers run by [FOSSGIS e.V.](https://www.fossgis.de) and the OSRM demo;
place search by [Photon](https://photon.komoot.io) (komoot). In the app: Settings → About → Data & licences.

## Contact

Open an issue on [GitHub](https://github.com/crockalet/haunt/issues).

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
