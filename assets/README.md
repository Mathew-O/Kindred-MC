# 🎨 Art

| File | What it is |
| --- | --- |
| `banner.png` | The banner at the top of the main README. |
| `faces/` | Pixel portraits of the crew, cut from their skins. |
| `skins/` | The skins of Kindred, Sol and Vesp (standard 64×64 Minecraft skins). |
| `make_banner.py` | The script that draws `banner.png` and `faces/` from the skins. |

Changed a skin? Redraw the art from the repository root (needs Python 3 and Pillow, `pip install pillow`):

```bash
python3 assets/make_banner.py
```

To dress a player in another skin in game, put the PNG in the server's `config/kindred/skins` folder and use `/aiplayer skin <name> [bot]` or the K menu. The [operator guide](../docs/operator-guide.md) has the details.
